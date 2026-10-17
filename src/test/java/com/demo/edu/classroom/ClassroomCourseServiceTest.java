package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.service.preprocessing.FileService;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
        when(mapper.listSummaries()).thenAnswer(i -> new ArrayList<>(documents.values()));
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
        var request=new ClassroomCourseService.AnswerRequest("quiz","request-1",List.of(new QuizService.AnswerSubmission(1,"A")));
        var prepared=new QuizService.PreparedSubmission("classroom",42L,"request-1",1,"submit","payload","question",List.of(),request.answers(),null);
        var evaluated=new QuizService.EvaluatedSubmission(1,"question","evaluation",result);
        when(quizzes.prepare(any(),anyList(),anyString())).thenReturn(prepared);when(quizzes.evaluate(prepared)).thenReturn(evaluated);
        when(quizzes.commit(any(),eq(prepared),eq(evaluated))).thenReturn(result);
        assertEquals(result,service.answer(id,request));
        assertEquals(result,service.answer(id,request));
        verify(quizzes,times(1)).commit(any(),eq(prepared),eq(evaluated));verify(quizzes,times(1)).evaluate(prepared);
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

    @Test void sceneEditsUseRevisionAndProgressDoesNotConsumeIt() {
        String id=readyCourse(); ObjectNode before=service.get(id); long revision=before.path("revision").asLong();
        service.saveProgress(id,0,1); assertEquals(revision,service.get(id).path("revision").asLong());
        ObjectNode content=textContent("original");
        ObjectNode after=service.editScene(id,"step-1",new ClassroomCourseService.SceneEditRequest(revision,content,null));
        assertEquals(revision+1,after.path("revision").asLong());
        assertEquals("original",after.path("scenes").get(0).path("content").path("canvas").path("elements").get(0).path("content").asText());
        assertThrows(ClassroomException.class,()->service.editScene(id,"step-1",new ClassroomCourseService.SceneEditRequest(revision,textContent("stale"),null)));
        assertEquals(0,service.get(id).path("progress").path("actionIndex").asInt());
    }

    @Test void runtimeReplayAndAcknowledgementsAreValidated() {
        String id=readyCourse(); long revision=service.get(id).path("revision").asLong();
        ObjectNode board=json.createObjectNode().put("id","board-1").put("isOpen",true);
        board.set("canvas",textContent("board").path("canvas"));
        ArrayNode ack=json.createArrayNode().add("speech-1");
        ArrayNode results=json.createArrayNode(); results.addObject().put("actionId","speech-1").put("success",true).put("at",1000);
        var request=new ClassroomCourseService.RuntimeRequest(revision,"op-1","step-1",board,null,ack,results);
        ObjectNode saved=service.saveRuntime(id,request); assertEquals(revision+1,saved.path("revision").asLong());
        assertEquals(saved.path("revision").asLong(),service.saveRuntime(id,request).path("revision").asLong(),"同一次操作重发不重复递增版本");
        assertFalse(saved.has("runtimeOperations"));
        assertThrows(ClassroomException.class,()->service.saveRuntime(id,new ClassroomCourseService.RuntimeRequest(null,"op-1","step-1",null,null,null,null)));
        assertThrows(ClassroomException.class,()->service.saveRuntime(id,new ClassroomCourseService.RuntimeRequest(null,"op-2","step-1",null,null,json.createArrayNode().add("never-issued"),null)));
    }

    @Test void teacherEditsActualSelectedElementAndPeerCannotMutate() {
        String id=readyCourse(); long initial=service.get(id).path("revision").asLong();
        service.editScene(id,"step-1",new ClassroomCourseService.SceneEditRequest(initial,textContent("实际保存的标题"),null));
        ObjectNode reference=json.createObjectNode().put("kind","slide_element").put("sceneId","step-1").put("elementId","text-1");
        when(tutor.complete(any())).thenReturn("{\"reply\":\"已修改标题\",\"actions\":[{\"type\":\"spotlight\",\"elementId\":\"text-1\"}],\"edits\":[{\"op\":\"update\",\"elementId\":\"text-1\",\"changes\":{\"content\":\"新的标题\"}}]}");
        ObjectNode reply=service.chat(id,new ClassroomCourseService.ChatRequest("step-1","修改标题","teacher",reference,null,"edit"));
        assertEquals("新的标题",reply.path("updatedScene").path("content").path("canvas").path("elements").get(0).path("content").asText());
        assertEquals(reply.path("revision").asLong(),service.get(id).path("revision").asLong());
        assertFalse(service.get(id).path("messages").get(1).has("updatedScene"),"消息历史不重复保存完整画布");
        verify(tutor).complete(argThat(messages -> messages.toString().contains("实际保存的标题")));
        long revision=service.get(id).path("revision").asLong();
        ObjectNode peer=service.chat(id,new ClassroomCourseService.ChatRequest("step-1","谈谈这个标题","peer",reference,null,"question"));
        assertTrue(peer.path("actions").isEmpty()); assertFalse(peer.has("updatedScene"));
        assertEquals(revision,service.get(id).path("revision").asLong());
        ObjectNode forged=reference.deepCopy().put("content","伪造正文");
        assertThrows(ClassroomException.class,()->service.chat(id,new ClassroomCourseService.ChatRequest("step-1","看看","teacher",forged,null,"question")));
    }

    @Test void aiEditCannotOverwriteAnInterveningSave() {
        String id=readyCourse(); service.editScene(id,"step-1",new ClassroomCourseService.SceneEditRequest(service.get(id).path("revision").asLong(),textContent("原文"),null));
        when(tutor.complete(any())).thenAnswer(i -> {
            service.editScene(id,"step-1",new ClassroomCourseService.SceneEditRequest(service.get(id).path("revision").asLong(),textContent("较新的人工编辑"),null));
            return "{\"reply\":\"修改\",\"edits\":[{\"op\":\"update\",\"elementId\":\"text-1\",\"changes\":{\"content\":\"旧模型结果\"}}]}";
        });
        assertThrows(ClassroomException.class,()->service.chat(id,new ClassroomCourseService.ChatRequest("step-1","修改","teacher",null,null,"edit")));
        assertEquals("较新的人工编辑",service.get(id).path("scenes").get(0).path("content").path("canvas").path("elements").get(0).path("content").asText());
    }

    @Test void editingSlideTextKeepsAcknowledgementsForUnchangedWhiteboardActions() {
        String id=readyCourse(); long revision=service.get(id).path("revision").asLong();
        ArrayNode actions=json.createArrayNode();
        actions.addObject().put("id","wb-action").put("type","wb_draw_text").put("elementId","wb-text")
                .put("content","演示").put("x",10).put("y",10);
        ObjectNode first=service.editScene(id,"step-1",new ClassroomCourseService.SceneEditRequest(revision,textContent("原文"),actions));
        ObjectNode board=json.createObjectNode().put("id","board").put("isOpen",true); board.set("canvas",textContent("白板").path("canvas"));
        ObjectNode runtime=service.saveRuntime(id,new ClassroomCourseService.RuntimeRequest(first.path("revision").asLong(),"draw-op","step-1",board,null,json.createArrayNode().add("wb-action"),null));
        ObjectNode edited=service.editScene(id,"step-1",new ClassroomCourseService.SceneEditRequest(runtime.path("revision").asLong(),textContent("新文字"),null));
        assertEquals("wb-action",edited.path("runtime").path("step-1").path("acknowledgedActionIds").get(0).asText());
        assertTrue(edited.path("runtime").path("step-1").has("whiteboard"));
        ((ObjectNode)actions.get(0)).put("content","新的演示");
        ObjectNode changed=service.editScene(id,"step-1",new ClassroomCourseService.SceneEditRequest(edited.path("revision").asLong(),textContent("新文字"),actions));
        assertTrue(changed.path("runtime").path("step-1").path("acknowledgedActionIds").isEmpty());
    }

    @Test void portableKeepsAnswersAndBoardsButImportRebuildsIdentityAndHistory() throws Exception {
        String id=readyCourse(); ObjectNode internal=(ObjectNode)json.readTree(documents.get(id));
        ObjectNode quiz=(ObjectNode)json.readTree("""
            {"id":"step-4","type":"quiz","title":"练习","order":3,"quizId":123,"content":{"type":"quiz","questions":[{"id":"old-question","no":1,"type":"single","question":"1+1=?","options":[{"label":"2","value":"A"},{"label":"3","value":"B"}],"answer":["A"],"analysis":"加法"}]},"actions":[]}
            """);
        ((ArrayNode)internal.path("scenes")).set(3,quiz); internal.put("sourceText","private source");
        internal.withArray("history").addObject().put("content","private chat"); internal.with("submissions").put("old","private grade");
        ObjectNode board=internal.with("runtime").putObject("step-1").putObject("whiteboard").put("id","old-board").put("isOpen",true);
        board.set("canvas",textContent("白板教材").path("canvas"));
        internal.with("runtime").with("step-1").putObject("interactiveState").put("private","state");
        documents.put(id,internal.toString());
        ObjectNode portable=service.portable(id); JsonNode exported=portable.path("course");
        assertFalse(exported.has("history")); assertFalse(exported.has("submissions")); assertFalse(exported.has("sourceText"));
        assertEquals("A",exported.path("scenes").get(3).path("content").path("questions").get(0).path("answer").get(0).asText());
        assertTrue(exported.path("runtime").path("step-1").has("whiteboard")); assertFalse(exported.path("runtime").path("step-1").has("interactiveState"));
        doAnswer(i -> { ObjectNode scene=i.getArgument(1); if("quiz".equals(scene.path("type").asText())) scene.put("quizId",999); return null; }).when(quizzes).attach(any(),any());
        ObjectNode imported=service.importPortable(portable);
        assertNotEquals(id,imported.path("id").asText()); assertEquals("ready",imported.path("status").asText());
        assertNotEquals("step-1",imported.path("scenes").get(0).path("id").asText());
        assertEquals(999,imported.path("scenes").get(3).path("quizId").asInt());
        assertTrue(imported.path("messages").isEmpty());
        String newSceneId=imported.path("scenes").get(0).path("id").asText();
        assertNotEquals("old-board",imported.path("runtime").path(newSceneId).path("whiteboard").path("id").asText());
        assertFalse(imported.path("scenes").get(3).path("content").path("questions").get(0).has("answer"));
        assertEquals(123,service.get(id).path("scenes").get(3).path("quizId").asInt(),"导入不能修改原课程题库");
        verify(tutor,never()).complete(any());
        assertThrows(ClassroomException.class,()->service.editScene(id,"step-4",new ClassroomCourseService.SceneEditRequest(internal.path("revision").asLong(),quiz.path("content"),null)));
    }

    @Test void importedManualBoardIsBaselineAndOnlyWhiteboardActionsAreAcknowledged() throws Exception {
        String id=readyCourse(); ObjectNode source=(ObjectNode)json.readTree(documents.get(id));
        ObjectNode first=(ObjectNode)source.path("scenes").get(0); first.put("type","interactive");
        first.putObject("content").put("type","interactive").put("html","<html><body><input id='x' value='1'></body></html>");
        ArrayNode actions=first.putArray("actions");
        actions.addObject().put("id","draw-before-edit").put("type","wb_draw_text").put("elementId","text-1").put("content","原讲稿文字").put("x",10).put("y",10);
        actions.addObject().put("id","clear-after-draw").put("type","wb_clear");
        actions.addObject().put("id","change-experiment").put("type","widget_setState").putObject("state").put("x",2);
        ObjectNode board=source.with("runtime").putObject("step-1").putObject("whiteboard").put("id","old-board").put("isOpen",true);
        board.set("canvas",textContent("人工编辑后的教材文字").path("canvas"));
        documents.put(id,source.toString());
        ObjectNode imported=service.importPortable(service.portable(id));
        ObjectNode importedScene=(ObjectNode)imported.path("scenes").get(0); String sceneId=importedScene.path("id").asText();
        var runtime=imported.path("runtime").path(sceneId); var acknowledgements=runtime.path("acknowledgedActionIds");
        assertEquals(2,acknowledgements.size());
        assertEquals("draw-before-edit",acknowledgements.get(0).asText());
        assertEquals("clear-after-draw",acknowledgements.get(1).asText());
        assertFalse(acknowledgements.toString().contains("change-experiment"));
        assertFalse(runtime.has("interactiveState"));
        ObjectNode reference=json.createObjectNode().put("kind","whiteboard_element").put("sceneId",sceneId)
                .put("whiteboardId",runtime.path("whiteboard").path("id").asText()).put("elementId","text-1");
        assertTrue(ClassroomElementResolver.resolve(imported,importedScene,reference).path("text").asText().contains("人工编辑后的教材文字"));
    }

    @Test void prefixedRealWorldTeacherJsonKeepsExecutableWidgetActions() throws Exception {
        String id=readyInteractiveCourse();
        String body="{\"reply\":\"接下来把份数调整为5，再观察图形。\",\"actions\":[{\"type\":\"widget_setState\",\"state\":{\"part-count\":5}},{\"type\":\"speech\",\"text\":\"现在观察五等份。\"}],\"edits\":[]}";
        for(String raw:List.of("好的，我来帮你把份数调为5。调整完成后观察一下。 "+body,
                "下面给出操作。\n```json\n"+body+"\n```\n请观察变化。")) {
            when(tutor.complete(any())).thenReturn(raw);
            ObjectNode result=service.chat(id,new ClassroomCourseService.ChatRequest("step-1","把份数调成5","teacher"));
            assertEquals("接下来把份数调整为5，再观察图形。",result.path("text").asText());
            assertEquals(2,result.path("actions").size());
            assertEquals("widget_setState",result.path("actions").get(0).path("type").asText());
            assertEquals(5,result.path("actions").get(0).path("state").path("part-count").asInt());
            assertFalse(result.path("text").asText().contains("\"actions\""));
        }
        verify(tutor,atLeastOnce()).complete(argThat(messages -> messages.toString().contains("controls")&&messages.toString().contains("#part-count")));
    }

    @Test void prefixedJsonWithNestedHtmlJsAndEscapedQuotesAppliesTheEdit() throws Exception {
        String id=readyInteractiveCourse();
        String html="<html><body><div id=\"note\">新的内容</div><script>const state = {text: '}', nested: [{a: 1}]}; const escaped = '\\\\';</script></body></html>";
        ObjectNode body=json.createObjectNode().put("reply","实验说明已更新。");
        body.putArray("actions").addObject().put("type","widget_highlight").put("target","#note");
        body.putArray("edits").addObject().put("op","replace_html").put("html",html);
        when(tutor.complete(any())).thenReturn("可以，下面是修改。\n```json\n"+body+"\n```");
        ObjectNode result=service.chat(id,new ClassroomCourseService.ChatRequest("step-1","更新说明","teacher",null,null,"edit"));
        assertEquals("实验说明已更新。",result.path("text").asText());
        assertEquals("#note",result.path("actions").get(0).path("target").asText());
        assertTrue(result.path("updatedScene").path("content").path("html").asText().contains("const state = {text: '}', nested: [{a: 1}]};"));
    }

    @Test void malformedAmbiguousAndNestedOnlyStructuredRepliesNeverBecomeStudentText() {
        String id=readyCourse();
        for(String malformed:List.of(
                "下面是回复：{\"reply\":\"内容没有结束\",\"actions\":[",
                "下面是回复：{\"reply\":\"第一版\"} {\"reply\":\"第二版\"}",
                "下面是回复：{\"reply\":\"第一版\",\"reply\":\"第二版\",\"actions\":[]}",
                "下面是回复：{\"reply\":\"正文\",\"actions\":{\"type\":\"widget_setState\"}}",
                "下面是回复：{\"wrapper\":{\"reply\":\"不能把内部对象当成完整回复\"}}",
                "下面是回复：{\"wrapper\":{\"reply\":\"外层对象缺少闭括号\"}",
                "下面是回复：[{\"reply\":\"不能把数组成员当成根回复\"}]")) {
            when(tutor.complete(any())).thenReturn(malformed);
            ClassroomException error=assertThrows(ClassroomException.class,()->service.chat(id,new ClassroomCourseService.ChatRequest("step-1","解释一下","teacher")),malformed);
            assertEquals(502,error.status());
        }
        assertTrue(service.get(id).path("messages").isEmpty(),"非法协议内容不应被持久化为学生可见消息");
    }

    @Test void ordinaryNaturalLanguageStillWorksIncludingMathematicalBraces() {
        String id=readyCourse(); String answer="同分母时比较分子。集合 {1,2} 是自然语言举例。";
        when(tutor.complete(any())).thenReturn(answer);
        ObjectNode result=service.chat(id,new ClassroomCourseService.ChatRequest("step-1","解释一下","teacher"));
        assertEquals(answer,result.path("text").asText()); assertTrue(result.path("actions").isEmpty());
    }

    String readyInteractiveCourse() throws Exception {
        String id=readyCourse(); ObjectNode document=(ObjectNode)json.readTree(documents.get(id));
        ObjectNode first=(ObjectNode)document.path("scenes").get(0); first.put("type","interactive");
        first.putObject("content").put("type","interactive").put("html","<html><body><input id='part-count' type='number' value='2'><input id='show-grid' type='checkbox'></body></html>");
        first.putArray("actions"); documents.put(id,document.toString()); return id;
    }

    String readyCourse() { String id=create().path("id").asText(); executor.next(); service.generate(id); executor.next(); return id; }
    ObjectNode textContent(String text) {
        ObjectNode content=json.createObjectNode().put("type","slide");
        content.putObject("canvas").put("viewportSize",1000).put("viewportRatio",0.5625).putArray("elements").addObject()
                .put("id","text-1").put("type","text").put("left",10).put("top",10).put("width",300).put("height",80).put("content",text);
        return content;
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
