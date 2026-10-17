package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.entity.QuizPaperEntity;
import com.demo.mapper.QuizPaperMapper;
import com.demo.service.preprocessing.FileService;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 以真实增量存储验证公开入口调整；原课程、原题和答卷不参与重写。 */
class ClassroomPublicationPolicyTest {
    private static final String OLD="11111111-1111-1111-1111-111111111111", CURRENT="22222222-2222-2222-2222-222222222222";
    private static final String QA="33333333-3333-3333-3333-333333333333";
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String,String> documents = new LinkedHashMap<>();
    private final Map<Long,QuizPaperEntity> paperRows = new HashMap<>();
    private ClassroomCourseMapper courses;
    private QuizPaperMapper papers;
    private ClassroomStudyStore store;
    private ClassroomPublicationPolicy policy;
    private DataSourceTransactionManager manager;

    @BeforeEach void setup() throws Exception {
        JdbcDataSource data = new JdbcDataSource(); data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE edu_classroom_study_state(kind VARCHAR(40),entry_id VARCHAR(160),course_id VARCHAR(64),scene_id VARCHAR(80),document CLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(kind,entry_id))");
        manager=new DataSourceTransactionManager(data); store=new ClassroomStudyStore(jdbc,json);
        courses=mock(ClassroomCourseMapper.class); papers=mock(QuizPaperMapper.class);
        when(courses.find(anyString())).thenAnswer(call -> documents.get(call.getArgument(0)));
        when(courses.list()).thenAnswer(call -> new ArrayList<>(documents.values()));
        when(courses.listSummaries()).thenAnswer(call -> new ArrayList<>(documents.values()));
        when(courses.save(anyString(),anyString())).thenAnswer(call -> {documents.put(call.getArgument(0),call.getArgument(1));return 1;});
        when(papers.selectById(anyLong())).thenAnswer(call -> paperRows.get(call.getArgument(0)));
        addCourse(OLD,"体积",5, "240"); addCourse(CURRENT,"体积（题目修订版）",14,"480"); addCourse(QA,"功能验收",3,"480");
        policy=new ClassroomPublicationPolicy(courses,papers,store,json,manager);
    }

    private void addCourse(String id,String title,long paperId,String correctOption) throws Exception {
        ObjectNode c=json.createObjectNode().put("id",id).put("title",title).put("topic","体积").put("status","ready")
                .put("grade",7).put("subject","数学").put("domain","general").put("direction","academic");
        c.putArray("history");c.putArray("messages");c.putArray("sources");c.putObject("submissions");c.putObject("runtime");c.putObject("runtimeOperations");
        c.putObject("progress").put("sceneIndex",0).put("actionIndex",0);c.put("revision",0);
        String sid=id+"-scene";
        ObjectNode scene=c.putArray("scenes").addObject().put("id",sid).put("type","quiz").put("title","练习").put("quizId",paperId);
        scene.putArray("actions");ObjectNode content=scene.putObject("content").put("type","quiz");
        ObjectNode question=content.putArray("questions").addObject().put("id",id+"-question").put("no",1).put("type","single")
                .put("question","长20厘米，宽8厘米，高3厘米的长方体体积是多少？").put("analysis",correctOption.equals("480")?"20×8×3=480，选B。":"错误的旧解析").put("points",1);
        question.putArray("options").addObject().put("value","A").put("label","48立方厘米");
        ((com.fasterxml.jackson.databind.node.ArrayNode)question.path("options")).addObject().put("value","B").put("label",correctOption+"立方厘米");question.putArray("answer").add("B");
        c.putArray("outlines").addObject().put("id",sid).put("type","quiz").put("title","练习").put("description","练习").put("order",0);
        documents.put(id,c.toString());
        QuizPaperEntity paper=new QuizPaperEntity();paper.setId(paperId);paper.setFileType("classroom");paper.setSourceFileName("classroom:"+id+":"+sid);paper.setTitle(title+" · 练习");paperRows.put(paperId,paper);
    }
    private ClassroomPublicationPolicy.Batch batch(String requestId) {
        return new ClassroomPublicationPolicy.Batch(requestId,List.of(
                new ClassroomPublicationPolicy.Change(OLD,"体积","alias",CURRENT,null),
                new ClassroomPublicationPolicy.Change(CURRENT,"体积（题目修订版）","publish",null,"体积"),
                new ClassroomPublicationPolicy.Change(QA,"功能验收","archive",null,null)),
                List.of(new ClassroomPublicationPolicy.Correction(OLD,OLD+"-scene",OLD+"-question",CURRENT,CURRENT+"-scene",CURRENT+"-question")));
    }

    @Test void publicationLeavesOriginalRecordsUnchangedAndOldLinksReachTheCurrentPage() throws Exception {
        Map<String,String> before=Map.copyOf(documents);ObjectNode first=policy.apply(batch("publish-1"));
        assertEquals(json.readTree(first.toString()),json.readTree(policy.apply(batch("publish-1")).toString()));
        assertEquals(before,documents);verify(courses,never()).save(any(),any());
        assertEquals(CURRENT,policy.resolve(OLD).courseId());
        assertEquals(CURRENT+"-scene",policy.resolve(OLD).sceneMap().path(OLD+"-scene").asText());
        assertEquals(404,assertThrows(ClassroomException.class,()->policy.resolve(QA)).status());
        assertEquals(409,assertThrows(ClassroomException.class,()->policy.requireActive(OLD)).status());
        assertFalse(policy.isPaperPublic(3L));assertFalse(policy.isPaperPublic(5L));assertTrue(policy.isPaperPublic(14L));
        assertEquals("体积 · 练习",policy.publicPaperTitle(14L,"wrong title"));
        List<ObjectNode> summaries=new ArrayList<>();for(String raw:documents.values())summaries.add((ObjectNode)json.readTree(raw));
        List<ObjectNode> visible=policy.publicSummaries(summaries);
        assertEquals(1,visible.size());assertEquals(CURRENT,visible.getFirst().path("id").asText());assertEquals("体积",visible.getFirst().path("title").asText());
    }

    @Test void deletingSourceMetadataOrReorderingOptionsDoesNotReactivateTheKnownBadQuestion() throws Exception {
        policy.apply(batch("publish-2"));ObjectNode stale=(ObjectNode)json.readTree(documents.get(OLD));
        ObjectNode q=(ObjectNode)stale.path("scenes").get(0).path("content").path("questions").get(0);
        q.put("id","new-file-id");q.remove(List.of("analysis","review","answer"));
        var options=(com.fasterxml.jackson.databind.node.ArrayNode)q.path("options");JsonNode a=options.get(0),b=options.get(1);options.removeAll();options.add(b);options.add(a);
        ObjectNode untouched=q.deepCopy();untouched.put("question","完全不同的问题");
        ((com.fasterxml.jackson.databind.node.ArrayNode)stale.path("scenes").get(0).path("content").path("questions")).add(untouched);
        ObjectNode replaced=policy.applyCorrections(stale);JsonNode actual=replaced.path("scenes").get(0).path("content").path("questions");
        assertEquals("480立方厘米",actual.get(0).path("options").get(1).path("label").asText());
        assertEquals("B",actual.get(0).path("answer").get(0).asText());assertEquals("new-file-id",actual.get(0).path("id").asText());
        assertEquals(untouched,actual.get(1));assertFalse(q.has("analysis"),"导入处理不应改写调用方的原始对象");
    }

    @Test void invalidBatchRollsBackAllPublicationWritesAndRequestIdsCannotBeReused() {
        paperRows.get(3L).setSourceFileName("wrong-course");
        assertThrows(ClassroomException.class,()->policy.apply(batch("failed-batch")));
        assertEquals(OLD,policy.resolve(OLD).courseId());assertEquals(QA,policy.resolve(QA).courseId());assertTrue(policy.isPaperPublic(5L));
        assertTrue(store.find(ClassroomPublicationPolicy.RECEIPT,"failed-batch").isEmpty());
        paperRows.get(3L).setSourceFileName("classroom:"+QA+":"+QA+"-scene");
        policy.apply(batch("saved-batch"));
        var conflicting=new ClassroomPublicationPolicy.Batch("saved-batch",List.of(new ClassroomPublicationPolicy.Change(QA,"功能验收","publish",null,"其他标题")),List.of());
        assertEquals(409,assertThrows(ClassroomException.class,()->policy.apply(conflicting)).status());
    }

    @Test void cyclicAliasesAndWrongTitlesAreRejectedWithoutChangingThePublicCatalog() {
        var cycle=new ClassroomPublicationPolicy.Batch("cycle",List.of(
                new ClassroomPublicationPolicy.Change(OLD,"体积","alias",CURRENT,null),
                new ClassroomPublicationPolicy.Change(CURRENT,"体积（题目修订版）","alias",OLD,null)),List.of());
        assertEquals(409,assertThrows(ClassroomException.class,()->policy.apply(cycle)).status());
        var wrongTitle=new ClassroomPublicationPolicy.Batch("wrong-title",List.of(new ClassroomPublicationPolicy.Change(QA,"另一个课程","archive",null,null)),List.of());
        assertEquals(409,assertThrows(ClassroomException.class,()->policy.apply(wrongTitle)).status());assertEquals(QA,policy.resolve(QA).courseId());
    }

    @Test void courseApiUsesCurrentContentAndExportWhileArchivedContentCannotBeOpened() throws Exception {
        policy.apply(batch("api-batch"));
        var executor=Executors.newSingleThreadExecutor();
        var service=new ClassroomCourseService(courses,json,mock(ClassroomEngine.class),mock(ClassroomTutorModel.class),mock(ClassroomQuizAdapter.class),
                mock(FileService.class),mock(DocKnowledgeRegistry.class),new TransactionTemplate(manager),executor);
        service.setPublicationPolicy(policy);
        try {
            JsonNode current=service.get(OLD);assertEquals(CURRENT,current.path("id").asText());assertEquals("体积",current.path("title").asText());
            assertEquals(CURRENT+"-scene",current.path("entrySceneMap").path(OLD+"-scene").asText());
            assertEquals(404,assertThrows(ClassroomException.class,()->service.get(QA)).status());
            assertEquals(404,assertThrows(ClassroomException.class,()->service.portable(QA)).status());
            JsonNode exported=service.portable(OLD).path("course");assertEquals("体积",exported.path("title").asText());
            assertEquals("480立方厘米",exported.path("scenes").get(0).path("content").path("questions").get(0).path("options").get(1).path("label").asText());
            String originalCurrent=documents.get(CURRENT);
            service.saveProgress(CURRENT,0,0);
            assertEquals(json.readTree(originalCurrent).path("title"),json.readTree(documents.get(CURRENT)).path("title"),"公开名称不能随进度保存写回原记录");
            clearInvocations(courses);assertEquals(1,service.list().size());verify(courses,never()).find(anyString());verify(courses,times(1)).listSummaries();
        } finally { service.close(); }
    }

    @Test void importingAnOldPackageBuildsANewPaperWithTheApprovedQuestion() throws Exception {
        policy.apply(batch("import-batch"));Map<String,String> before=Map.copyOf(documents);
        var quizService=mock(QuizService.class);var adapter=new ClassroomQuizAdapter(courses,quizService,json);
        when(courses.insertPaper(any())).thenAnswer(call->{((QuizPaperEntity)call.getArgument(0)).setId(99L);return 1;});
        when(courses.insertQuestion(any())).thenReturn(1);
        var executor=Executors.newSingleThreadExecutor();var engine=mock(ClassroomEngine.class);
        var service=new ClassroomCourseService(courses,json,engine,mock(ClassroomTutorModel.class),adapter,mock(FileService.class),mock(DocKnowledgeRegistry.class),new TransactionTemplate(manager),executor);
        service.setPublicationPolicy(policy);
        try {
            ObjectNode envelope=json.createObjectNode().put("format","educationagent.classroom").put("version",1).put("containsAnswers",true);
            ObjectNode old=(ObjectNode)json.readTree(documents.get(OLD));old.put("id","client-removed-provenance");
            ObjectNode oldQuestion=(ObjectNode)old.path("scenes").get(0).path("content").path("questions").get(0);oldQuestion.remove("analysis");oldQuestion.put("explanation","旧内容");
            envelope.set("course",old);ObjectNode imported=service.importPortable(envelope);
            assertNotEquals(OLD,imported.path("id").asText());
            JsonNode actual=service.internalDocument(imported.path("id").asText()).path("scenes").get(0).path("content").path("questions").get(0);
            assertEquals("480立方厘米",actual.path("options").get(1).path("label").asText());assertEquals("B",actual.path("answer").get(0).asText());
            assertEquals("20×8×3=480，选B。",actual.path("analysis").asText());assertNotEquals(OLD+"-question",actual.path("id").asText());
            before.forEach((key,value)->assertEquals(value,documents.get(key)));verifyNoInteractions(engine,quizService);
        } finally {service.close();}
    }

    @Test void publicationCannotCommitBetweenImportValidationAndPaperCommit() throws Exception {
        var attached=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var registrationStarted=new java.util.concurrent.CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();
        var adapter=mock(ClassroomQuizAdapter.class);
        doAnswer(call->{
            ((ObjectNode)call.getArgument(1)).put("quizId",99L);
            if(calls.getAndIncrement()==0){attached.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));}
            return null;
        }).when(adapter).attach(any(),any());
        var executor=Executors.newSingleThreadExecutor();var concurrent=Executors.newFixedThreadPool(2);
        var service=new ClassroomCourseService(courses,json,mock(ClassroomEngine.class),mock(ClassroomTutorModel.class),adapter,mock(FileService.class),mock(DocKnowledgeRegistry.class),new TransactionTemplate(manager),executor);
        service.setPublicationPolicy(policy);
        ObjectNode envelope=json.createObjectNode().put("format","educationagent.classroom").put("version",1);
        envelope.set("course",json.readTree(documents.get(OLD)));
        try {
            var importing=concurrent.submit(()->service.importPortable(envelope));assertTrue(attached.await(3,java.util.concurrent.TimeUnit.SECONDS));
            var registering=concurrent.submit(()->{registrationStarted.countDown();return policy.apply(batch("concurrent-publication"));});
            assertTrue(registrationStarted.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,()->registering.get(100,java.util.concurrent.TimeUnit.MILLISECONDS),"导入未提交前，发布登记不能越过同一规则边界");
            release.countDown();importing.get(3,java.util.concurrent.TimeUnit.SECONDS);registering.get(3,java.util.concurrent.TimeUnit.SECONDS);
            ObjectNode after=service.importPortable(envelope);
            JsonNode question=service.internalDocument(after.path("id").asText()).path("scenes").get(0).path("content").path("questions").get(0);
            assertEquals("480立方厘米",question.path("options").get(1).path("label").asText());
        } finally {release.countDown();concurrent.shutdownNow();service.close();}
    }
}
