package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.service.preprocessing.FileService;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离课堂状态机，不启动 Spring、数据库或外部模型。 */
class ClassroomCourseServiceTest {
    final ObjectMapper json = new ObjectMapper();
    ClassroomCourseMapper mapper;
    ClassroomEngine engine;
    ClassroomTutorModel tutor;
    ClassroomQuizAdapter quizzes;
    ClassroomCourseService service;
    ManualExecutor executor;
    Map<String, String> documents;

    @BeforeEach void setup() {
        mapper=mock(ClassroomCourseMapper.class); engine=mock(ClassroomEngine.class);
        tutor=mock(ClassroomTutorModel.class); quizzes=mock(ClassroomQuizAdapter.class);
        executor=new ManualExecutor(); documents=new HashMap<>();
        when(mapper.find(anyString())).thenAnswer(i -> documents.get(i.getArgument(0)));
        when(mapper.list()).thenAnswer(i -> new ArrayList<>(documents.values()));
        when(mapper.save(anyString(), anyString())).thenAnswer(i -> { documents.put(i.getArgument(0), i.getArgument(1)); return 1; });
        when(tutor.precheck(anyString())).thenReturn(Optional.empty());
        when(tutor.unsafeReplacement(anyString())).thenReturn(Optional.empty());
        when(tutor.unsafeReplacement(anyString(),any())).thenReturn(Optional.empty());
        TransactionTemplate tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>)i.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        service=new ClassroomCourseService(mapper,json,engine,tutor,quizzes,mock(FileService.class),mock(DocKnowledgeRegistry.class),tx,executor);
        when(engine.outline(any(), any())).thenReturn(outlines());
        when(engine.scene(any(), any(), any())).thenAnswer(i -> scene(i.getArgument(1)));
    }

    @Test void createOutlineGeneratePersistAndHideInternalFields() {
        ObjectNode created=create(); String id=created.path("id").asText();
        assertEquals("outlining", created.path("status").asText());
        assertFalse(created.has("sourceText"));
        executor.next();
        assertEquals("outline_ready",service.get(id).path("status").asText());
        service.generate(id); service.generate(id);
        assertEquals(1,executor.queue.size(),"重复生成不能创建并发任务");
        executor.next();
        ObjectNode done=service.get(id);
        assertEquals("ready",done.path("status").asText());
        assertEquals(4,done.path("scenes").size());
        assertFalse(done.has("history")); assertFalse(done.has("submissions"));
        assertEquals(4,service.list().getFirst().path("sceneCount").asInt());
        service.saveProgress(id,2,1);
        assertEquals(2,service.get(id).path("progress").path("sceneIndex").asInt());
        assertThrows(ClassroomException.class,() -> service.saveProgress(id,9,0));
        assertThrows(ClassroomException.class,() -> service.editOutline(id,outlines()));
    }

    @Test void failedSceneIsRetriedWithoutDuplicatingCompletedPages() {
        String id=create().path("id").asText(); executor.next();
        AtomicInteger attempts=new AtomicInteger();
        doAnswer(i -> {
            var outline=(com.fasterxml.jackson.databind.JsonNode)i.getArgument(1);
            if(outline.path("id").asText().equals("step-2") && attempts.getAndIncrement()==0) throw new IllegalStateException("provider down");
            return scene(outline);
        }).when(engine).scene(any(),any(),any());
        service.generate(id); executor.next();
        assertEquals("error",service.get(id).path("status").asText());
        assertEquals(1,service.get(id).path("scenes").size());
        service.generate(id); executor.next();
        assertEquals("ready",service.get(id).path("status").asText());
        assertEquals(4,service.get(id).path("scenes").size());
        verify(engine,times(1)).scene(any(),argThat(n -> n.path("id").asText().equals("step-1")),any());
    }

    @Test void cancelledOldResultCannotOverwriteRetry() {
        String id=create().path("id").asText(); executor.next();
        AtomicInteger attempt=new AtomicInteger();
        doAnswer(i -> {
            if(attempt.getAndIncrement()==0) {
                service.cancel(id);
                service.generate(id); // 新 token 已排队，旧进程即使给出结果也必须丢弃。
            }
            return scene(i.getArgument(1));
        }).when(engine).scene(any(),any(),any());
        service.generate(id); executor.next();
        assertEquals(0,service.get(id).path("scenes").size());
        assertEquals("generating",service.get(id).path("status").asText());
        executor.next();
        assertEquals("ready",service.get(id).path("status").asText());
        assertEquals(4,service.get(id).path("scenes").size());
    }

    @Test void cancelQueuedOutlineAndRetrySameCourse() {
        String id=create().path("id").asText(); service.cancel(id); executor.next();
        assertEquals("cancelled",service.get(id).path("status").asText());
        verify(engine,never()).outline(any(),any());
        service.generate(id); executor.next();
        assertEquals("outline_ready",service.get(id).path("status").asText());
    }

    @Test void malformedOutputDoesNotBecomeReadyOrReachQuizStorage() {
        String id=create().path("id").asText(); executor.next();
        doReturn(json.createObjectNode().put("type","pbl")).when(engine).scene(any(),any(),any());
        service.generate(id); executor.next();
        assertEquals("error",service.get(id).path("status").asText());
        assertEquals(0,service.get(id).path("scenes").size());
        verify(quizzes,never()).attach(any(),any());
    }

    @Test void repeatedSubmissionIsIdempotentAndMismatchedReuseIsRejected() throws Exception {
        String id=create().path("id").asText(); executor.next();
        ObjectNode stored=(ObjectNode)json.readTree(documents.get(id));
        ObjectNode quiz=stored.withArray("scenes").addObject().put("id","quiz").put("type","quiz").put("quizId",42);
        quiz.putObject("content").put("type","quiz").putArray("questions").addObject().put("answer","A").put("analysis","hidden");
        documents.put(id,stored.toString());
        var result=new QuizService.SubmitResult(42L,1,1,List.of(new QuizService.ResultItem(1,true,"A","A","说明")));
        when(quizzes.submit(any(),any())).thenReturn(result);
        var request=new ClassroomCourseService.AnswerRequest("quiz","request-1",List.of(new QuizService.AnswerSubmission(1,"A")));
        assertEquals(result,service.answer(id,request));
        assertEquals(result,service.answer(id,request));
        verify(quizzes,times(1)).submit(any(),any());
        assertThrows(ClassroomException.class,() -> service.answer(id,new ClassroomCourseService.AnswerRequest("quiz","request-1",List.of(new QuizService.AnswerSubmission(1,"B")))));
        assertFalse(service.get(id).path("scenes").get(0).path("content").path("questions").get(0).has("answer"));
    }

    @Test void restartLeavesExistingPagesAndExposesRetryableError() throws Exception {
        String id=create().path("id").asText(); executor.next();
        ObjectNode stored=(ObjectNode)json.readTree(documents.get(id)); stored.put("status","generating");
        documents.put(id,stored.toString());
        assertEquals("error",service.get(id).path("status").asText());
        assertTrue(service.get(id).path("error").asText().contains("中断"));
    }

    @Test void unsafeQuestionUsesExistingSafetyReplyWithoutModel() {
        String id=create().path("id").asText(); executor.next(); service.generate(id); executor.next();
        when(tutor.precheck("需要帮助")).thenReturn(Optional.of("请联系可信任的成年人"));
        ObjectNode reply=service.chat(id,new ClassroomCourseService.ChatRequest("step-1","需要帮助","teacher"));
        assertEquals("请联系可信任的成年人",reply.path("text").asText());
        assertEquals("user",service.get(id).path("messages").get(0).path("speaker").asText());
        assertEquals("需要帮助",service.get(id).path("messages").get(0).path("text").asText(),"刷新后应保留学生原问题");
        verify(tutor,never()).complete(any());
    }

    ObjectNode create() { return service.create(new ClassroomCourseService.CreateRequest("分数",4,"数学","general","资料正文","教材")); }
    ArrayNode outlines() {
        ArrayNode arr=json.createArrayNode();
        for(int i=1;i<=4;i++) arr.addObject().put("id","step-"+i).put("title","步骤"+i).put("type","slide").put("description","教学说明");
        return arr;
    }
    ObjectNode scene(com.fasterxml.jackson.databind.JsonNode outline) {
        ObjectNode s=json.createObjectNode().put("id",outline.path("id").asText()).put("title","分数").put("type","slide");
        s.putObject("content").put("type","slide").putObject("canvas").putArray("elements");
        s.putArray("actions").addObject().put("id","speech-1").put("type","speech").put("text","把一个整体平均分成四份。");
        return s;
    }
    static class ManualExecutor extends AbstractExecutorService {
        final Queue<Runnable> queue=new ArrayDeque<>(); boolean closed;
        void next() { Objects.requireNonNull(queue.poll()).run(); }
        public void execute(Runnable r) { queue.add(r); }
        public void shutdown() { closed=true; }
        public List<Runnable> shutdownNow() { closed=true; return new ArrayList<>(queue); }
        public boolean isShutdown() { return closed; }
        public boolean isTerminated() { return closed; }
        public boolean awaitTermination(long timeout,TimeUnit unit) { return closed; }
    }
}
