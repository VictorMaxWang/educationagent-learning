package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.service.preprocessing.FileService;
import com.demo.study.quiz.QuizPersistenceFixture;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实课程服务+真实判题服务+SQL持久化，在实际answer/get/progress接缝验证锁外评阅。 */
class ClassroomAnswerOutOfLockTest {
    @Test void slowShortAnswerDoesNotBlockGetOrProgressAndCommitPreservesNewerProgress() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"short","比较基准相同","面积");
        var json=new ObjectMapper();String id=UUID.randomUUID().toString();
        var service=courseService(f,json,id,"short_answer","比较基准相同");
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var response=mock(ChatResponse.class,RETURNS_DEEP_STUBS);
        when(response.aiMessage().text()).thenReturn("[{\"no\":1,\"correct\":true,\"correctAnswer\":\"比较基准相同\",\"explanation\":\"同义表述正确\"}]");
        when(f.model.chat(any(ChatRequest.class))).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));return response;
        });
        try(var workers=Executors.newFixedThreadPool(2)) {
            var grading=workers.submit(()->service.answer(id,new ClassroomCourseService.AnswerRequest("s","slow",List.of(new QuizService.AnswerSubmission(1,"两块地一样大")))));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try {
                var reading=workers.submit(()->{assertEquals(id,service.get(id).path("id").asText());return service.saveProgress(id,0,1);});
                assertEquals(1,reading.get(1,TimeUnit.SECONDS).path("progress").path("actionIndex").asInt());
                assertTrue(f.history().isEmpty());
            } finally { finish.countDown(); }
            assertEquals(1,grading.get(5,TimeUnit.SECONDS).score());
            assertEquals(1,service.get(id).path("progress").path("actionIndex").asInt());
            assertEquals(1,f.history().size());
        } finally {finish.countDown();service.close();}
    }

    @Test void supersessionDuringModelEvaluationRejectsCommitWithoutWritingAnyOldGrade() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"short","比较基准相同","面积");
        var json=new ObjectMapper();String id=UUID.randomUUID().toString();
        var service=courseService(f,json,id,"short_answer","比较基准相同");
        var registry=mock(ClassroomQuizRevisionRegistry.class);ReflectionTestUtils.setField(service,"quizRevisions",registry);
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var response=mock(ChatResponse.class,RETURNS_DEEP_STUBS);
        when(response.aiMessage().text()).thenReturn("[{\"no\":1,\"correct\":true,\"correctAnswer\":\"比较基准相同\",\"explanation\":\"同义表述正确\"}]");
        when(f.model.chat(any(ChatRequest.class))).thenAnswer(call->{entered.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));return response;});
        try(var workers=Executors.newSingleThreadExecutor()) {
            var grading=workers.submit(()->service.answer(id,new ClassroomCourseService.AnswerRequest("s","superseded",List.of(new QuizService.AnswerSubmission(1,"一样大")))));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            doThrow(new ClassroomException(409,"已有修订" )).when(registry).assertScorable(7L);finish.countDown();
            var failed=assertThrows(java.util.concurrent.ExecutionException.class,()->grading.get(5,TimeUnit.SECONDS));
            assertInstanceOf(ClassroomException.class,failed.getCause());assertEquals(409,((ClassroomException)failed.getCause()).status());
            assertTrue(f.history().isEmpty());
            assertTrue(json.readTree(f.jdbc.queryForObject("SELECT document FROM course_document WHERE id=?",String.class,id)).path("submissions").isEmpty());
        } finally {finish.countDown();service.close();}
    }

    @Test void storedSuccessfulCourseReceiptReplaysBeforeRevisionAndPublicationChecks() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"fill","24","面积");
        var json=new ObjectMapper();String id=UUID.randomUUID().toString();var service=courseService(f,json,id,"fill","24");
        try {
            var request=new ClassroomCourseService.AnswerRequest("s","original",List.of(new QuizService.AnswerSubmission(1,"24")));
            var result=service.answer(id,request);
            var registry=mock(ClassroomQuizRevisionRegistry.class);ReflectionTestUtils.setField(service,"quizRevisions",registry);
            doThrow(new ClassroomException(409,"已有修订")).when(registry).assertScorable(7L);
            var publication=mock(ClassroomPublicationPolicy.class);service.setPublicationPolicy(publication);
            doThrow(new ClassroomException(409,"旧课程已别名映射")).when(publication).requireActive(id);
            ObjectNode interrupted=(ObjectNode)json.readTree(f.jdbc.queryForObject("SELECT document FROM course_document WHERE id=?",String.class,id));
            interrupted.put("status","generating");String unchanged=interrupted.toString();
            f.jdbc.update("UPDATE course_document SET document=? WHERE id=?",unchanged,id);
            assertEquals(result,service.answer(id,request));assertEquals(1,f.history().size());
            assertEquals(unchanged,f.jdbc.queryForObject("SELECT document FROM course_document WHERE id=?",String.class,id),
                    "读取已成功回执不能顺带把重启前的生成状态改写成error");
            assertEquals(409,assertThrows(ClassroomException.class,()->service.answer(id,
                    new ClassroomCourseService.AnswerRequest("s","new",request.answers()))).status());
            assertEquals(1,f.history().size());
        } finally {service.close();}
    }

    private ClassroomCourseService courseService(QuizPersistenceFixture f,ObjectMapper json,String id,String type,String answer) {
        f.jdbc.execute("CREATE TABLE course_document(id VARCHAR(64) PRIMARY KEY,document CLOB)");
        ObjectNode c=json.createObjectNode().put("id",id).put("title","原课程").put("topic","面积").put("grade",5).put("subject","数学")
                .put("direction","academic").put("domain","general").put("status","ready").put("revision",0);
        c.putObject("submissions");c.putArray("outlines");c.putArray("messages");c.putObject("progress").put("sceneIndex",0).put("actionIndex",0);
        ObjectNode scene=c.putArray("scenes").addObject().put("id","s").put("type","quiz").put("title","练习").put("quizId",7);
        scene.putArray("actions").addObject().put("id","speech").put("type","speech").put("text","先说说思路");
        scene.putObject("content").put("type","quiz").putArray("questions").addObject().put("id","q").put("no",1)
                .put("type",type).put("question","第1题").put("answer",answer).put("analysis","依据参考要点评阅");
        f.jdbc.update("INSERT INTO course_document VALUES(?,?)",id,c.toString());
        var mapper=mock(ClassroomCourseMapper.class);
        when(mapper.find(id)).thenAnswer(call->f.jdbc.queryForObject("SELECT document FROM course_document WHERE id=?",String.class,id));
        when(mapper.save(eq(id),anyString())).thenAnswer(call->f.jdbc.update("UPDATE course_document SET document=? WHERE id=?",new Object[]{call.getArgument(1,String.class),id}));
        var adapter=new ClassroomQuizAdapter(mapper,f.service,json);
        return new ClassroomCourseService(mapper,json,mock(ClassroomEngine.class),mock(ClassroomTutorModel.class),adapter,mock(FileService.class),mock(DocKnowledgeRegistry.class),f.manager);
    }
}
