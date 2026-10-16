package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeAssistant;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.entity.QuizPaperEntity;
import com.demo.service.preprocessing.FileService;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 覆盖请求方向、跨库真实证据与五题型的可携带往返，不调用网络或供应商。 */
class ClassroomDirectionPortableTest {
    final ObjectMapper json=new ObjectMapper();
    final Map<String,String> documents=new HashMap<>();
    final ClassroomCourseMapper mapper=mock(ClassroomCourseMapper.class);
    final ClassroomEngine engine=mock(ClassroomEngine.class);
    final ClassroomTutorModel tutor=mock(ClassroomTutorModel.class);
    final DocKnowledgeRegistry knowledge=mock(DocKnowledgeRegistry.class);
    final ClassroomCourseServiceTest.ManualExecutor jobs=new ClassroomCourseServiceTest.ManualExecutor();
    ClassroomCourseService service;
    @BeforeEach void setup() {
        when(mapper.find(anyString())).thenAnswer(call->documents.get(call.getArgument(0)));
        when(mapper.save(anyString(),anyString())).thenAnswer(call->{documents.put(call.getArgument(0),call.getArgument(1));return 1;});
        when(tutor.precheck(anyString())).thenReturn(Optional.empty());
        var tx=mock(TransactionTemplate.class);when(tx.execute(any())).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        var papers=new AtomicLong(100);when(mapper.insertPaper(any())).thenAnswer(call->{((QuizPaperEntity)call.getArgument(0)).setId(papers.incrementAndGet());return 1;});
        service=new ClassroomCourseService(mapper,json,engine,tutor,new ClassroomQuizAdapter(mapper,mock(QuizService.class),json),mock(FileService.class),knowledge,tx,jobs);
        var outlines=json.createArrayNode();for(int i=0;i<4;i++) outlines.addObject().put("id","step-"+i).put("title","步骤"+i).put("type","slide");
        when(engine.outline(any(),any())).thenReturn(outlines);
    }
    ClassroomCourseService.CreateRequest request(String domain,String direction) {
        return new ClassroomCourseService.CreateRequest("菜园探索",5,"隐藏旧学科",domain,"","",direction);
    }
    @Test void explicitDirectionIgnoresHiddenSubjectAndLegacyDomainKeepsMeaning() {
        var exploration=service.create(request("general","exploration"));assertEquals("实践探索",exploration.path("subject").asText());
        var wellbeing=service.create(request("general","wellbeing"));assertEquals("身心成长",wellbeing.path("subject").asText());
        var legacy=service.create(new ClassroomCourseService.CreateRequest("学习压力",5,"心理","psychology","",""));
        assertEquals("wellbeing",legacy.path("direction").asText());
        assertThrows(ClassroomException.class,()->service.create(request("general","unknown")));
        assertThrows(ClassroomException.class,()->service.create(new ClassroomCourseService.CreateRequest("数学",5," ","general","","","academic")));
    }
    @Test void wellbeingUsesAllThreeRealLibrariesAndKeepsSuccessfulEvidenceWhenOneFails() {
        var psychology=mock(DocKnowledgeAssistant.class);var safety=mock(DocKnowledgeAssistant.class);var sexedu=mock(DocKnowledgeAssistant.class);
        when(knowledge.getAssistant("psychology")).thenReturn(psychology);when(knowledge.getAssistant("lifesafety")).thenReturn(safety);when(knowledge.getAssistant("sexedu")).thenReturn(sexedu);
        when(psychology.searchArticles(anyString(),isNull(),anyInt())).thenReturn(List.of(Map.of("file","C:/private/情绪.txt","content","遇到受挫先停下来呼吸，再向信任的人表达需要。","source","心理资料","chunkIndex",3)));
        when(safety.searchArticles(anyString(),isNull(),anyInt())).thenThrow(new IllegalStateException("unavailable"));
        when(sexedu.searchArticles(anyString(),isNull(),anyInt())).thenReturn(List.of(Map.of("file","边界.txt","content","身体边界受到尊重。不舒服时可以明确拒绝并向可信任的成年人求助。")));
        String id=service.create(request("general","wellbeing")).path("id").asText();jobs.next();
        var visible=service.get(id);assertEquals(2,visible.path("sources").size());
        assertTrue(visible.path("retrievalNote").asText().contains("部分知识库暂不可用"));
        assertFalse(visible.path("sources").toString().contains("C:/private"));
        assertEquals(Set.of("情绪.txt","边界.txt"),new HashSet<>(List.of(visible.path("sources").get(0).path("file").asText(),visible.path("sources").get(1).path("file").asText())));
        var captured=ArgumentCaptor.forClass(ObjectNode.class);verify(engine).outline(captured.capture(),any());
        assertTrue(captured.getValue().path("generationContext").asText().contains("身体边界受到尊重"));
        verify(safety).searchArticles(anyString(),isNull(),anyInt());
    }
    @Test void explorationUsesTongshiWhileOldGeneralAcademicDoesNotRetrieve() {
        var tongshi=mock(DocKnowledgeAssistant.class);when(knowledge.getAssistant("tongshi")).thenReturn(tongshi);
        when(tongshi.searchArticles(anyString(),isNull(),anyInt())).thenReturn(List.of(Map.of("file","菜园劳动.txt","content","记录每块菜地的长和宽，用长乘宽求面积。")));
        String id=service.create(request("general","exploration")).path("id").asText();jobs.next();
        assertEquals("菜园劳动.txt",service.get(id).path("sources").get(0).path("file").asText());
        clearInvocations(knowledge,tongshi);
        String oldId=service.create(new ClassroomCourseService.CreateRequest("分数",5,"数学","general","","")).path("id").asText();jobs.next();
        assertTrue(service.get(oldId).path("sources").isEmpty());verifyNoInteractions(knowledge,tongshi);
    }
    @Test void unavailableLibrariesNeverCreateCitations() {
        String id=service.create(request("general","wellbeing")).path("id").asText();jobs.next();
        assertTrue(service.get(id).path("sources").isEmpty());
        assertTrue(service.get(id).path("retrievalNote").asText().contains("未检索到可引用资料"));
    }
    @Test void fiveTypesRoundTripScalarCorrectAnswersAndKeepGradingPointsPrivate() throws Exception {
        JsonNode envelope=json.readTree("""
          {"format":"educationagent.classroom","version":1,"course":{"title":"混合题","topic":"面积","grade":5,"subject":"数学","domain":"general","direction":"academic","scenes":[
          {"id":"qscene","title":"练习","type":"quiz","content":{"type":"quiz","questions":[
          {"id":"q1","type":"single","question":"单选","options":[{"label":"甲","value":"A"},{"label":"乙","value":"B"}],"correctAnswer":"B"},
          {"id":"q2","type":"multiple","question":"多选","options":[{"label":"甲","value":"A"},{"label":"乙","value":"B"},{"label":"丙","value":"C"}],"correctAnswer":"CA"},
          {"id":"q3","type":"judge","question":"判断","correctAnswer":false},
          {"id":"q4","type":"fill","question":"差为多少","correctAnswer":0},
          {"id":"q5","type":"short_answer","question":"为何统一整体","correctAnswer":"基准相同","gradingPoints":["整体一样大"],"explanation":"比较基准必须一致"}]},"actions":[]}]}}
          """);
        var imported=service.importPortable(envelope);String id=imported.path("id").asText();
        for(JsonNode question:imported.path("scenes").get(0).path("content").path("questions")) {
            assertFalse(question.has("answer"));assertFalse(question.has("gradingPoints"));assertFalse(question.has("analysis"));
        }
        var portable=service.portable(id);var questions=portable.path("course").path("scenes").get(0).path("content").path("questions");
        assertEquals(List.of("B"),json.convertValue(questions.get(0).path("answer"),List.class));
        assertEquals(List.of("A","C"),json.convertValue(questions.get(1).path("answer"),List.class));
        assertEquals("错",questions.get(2).path("answer").asText());assertTrue(questions.get(3).path("answer").isNumber());assertEquals(0,questions.get(3).path("answer").asInt());
        assertEquals("基准相同",questions.get(4).path("answer").asText());assertEquals("整体一样大",questions.get(4).path("gradingPoints").get(0).asText());
        String secondId=service.importPortable(portable).path("id").asText();assertNotEquals(id,secondId);
        assertEquals(questions.get(3).path("answer"),service.portable(secondId).path("course").path("scenes").get(0).path("content").path("questions").get(3).path("answer"));
        verifyNoInteractions(engine);verify(tutor,never()).complete(any());
    }
    @Test void oldPortableInfersDirectionWithoutMutatingStoredCourse() throws Exception {
        var source=(ObjectNode)json.readTree("""
          {"id":"00000000-0000-0000-0000-000000000001","title":"旧课","grade":5,"subject":"数学","domain":"general","status":"ready","scenes":[],"outlines":[],"progress":{},"messages":[]}
          """);
        documents.put(source.path("id").asText(),source.toString());
        assertEquals("academic",service.get(source.path("id").asText()).path("direction").asText());
        assertEquals("academic",service.portable(source.path("id").asText()).path("course").path("direction").asText());
        assertFalse(json.readTree(documents.get(source.path("id").asText())).has("direction"));
        source.put("domain","sexedu");assertEquals("wellbeing",ClassroomCourseService.publicCourse(source).path("direction").asText());
    }
}
