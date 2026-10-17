package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.service.preprocessing.FileService;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 固定客户端所见题面；以评阅边界零调用证明过期请求不能产生模型费用或答卷。 */
class ClassroomQuestionVersionTest {
    private static final String COURSE_ID="10000000-0000-4000-8000-000000000001";
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,String> documents=new HashMap<>();
    private final List<QuizService.AnswerSubmission> answers=List.of(new QuizService.AnswerSubmission(1,"A"));
    private ClassroomCourseService service;
    private ClassroomQuizAdapter quizzes;

    @BeforeEach void setup() throws Exception {
        var mapper=mock(ClassroomCourseMapper.class);quizzes=mock(ClassroomQuizAdapter.class);
        when(mapper.find(anyString())).thenAnswer(call->documents.get(call.getArgument(0,String.class)));
        when(mapper.save(anyString(),anyString())).thenAnswer(call->{documents.put(call.getArgument(0),call.getArgument(1));return 1;});
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        service=new ClassroomCourseService(mapper,json,mock(ClassroomEngine.class),mock(ClassroomTutorModel.class),quizzes,
                mock(FileService.class),mock(DocKnowledgeRegistry.class),tx,mock(ExecutorService.class));
        documents.put(COURSE_ID,"""
            {"id":"%s","title":"练习","topic":"加法","grade":2,"subject":"数学","domain":"general","direction":"academic","status":"ready",
             "scenes":[{"id":"quiz","title":"练习","type":"quiz","order":0,"quizId":42,"actions":[],
               "content":{"type":"quiz","questions":[{"id":"question","no":1,"type":"single","question":"1+1=?","hasAnswer":true,
                 "options":[{"label":"2","value":"A"},{"label":"3","value":"B"}],"answer":["A"],"analysis":"加法"}]}}],
             "outlines":[],"submissions":{},"messages":[],"history":[],"progress":{"sceneIndex":0,"actionIndex":0}}
            """.formatted(COURSE_ID));
    }

    @Test void publicVersionIgnoresHiddenAnswersAndEvidenceButChangesWithVisibleQuestionOrOptions() throws Exception {
        ObjectNode scene=scene(document());String version=ClassroomCourseService.publicScene(scene).path("questionVersion").asText();
        assertTrue(version.matches("[a-f0-9]{64}"));
        ObjectNode question=question(scene);
        question.putArray("answer").add("B");question.put("analysis","新的私有解析").put("correctAnswer","B").put("explanation","隐藏说明");
        question.putArray("gradingPoints").add("隐藏评分点");question.putObject("review").put("answer","B");
        ((ObjectNode)question.path("options").get(0)).put("hiddenEvidence","不能进入版本");
        scene.put("questionVersion","forged-client-version");
        assertEquals(version,ClassroomCourseService.publicScene(scene).path("questionVersion").asText(),"公开版本不暴露答案或评分证据的变化");
        question.put("question","1+2=?");
        assertNotEquals(version,ClassroomCourseService.publicScene(scene).path("questionVersion").asText());
        question.put("question","1+1=?");((ObjectNode)question.path("options").get(0)).put("label","4");
        assertNotEquals(version,ClassroomCourseService.publicScene(scene).path("questionVersion").asText());
    }

    @Test void staleRenderedQuestionIsRejectedBeforePreparingAnyEvaluationOrWritingCourseState() throws Exception {
        String oldVersion=version();ObjectNode changed=document();question(scene(changed)).put("question","1+2=?");
        documents.put(COURSE_ID,changed.toString());String before=documents.get(COURSE_ID);
        var error=assertThrows(ClassroomException.class,()->service.answer(COURSE_ID,
                new ClassroomCourseService.AnswerRequest("quiz","stale",answers,oldVersion)));
        assertEquals(409,error.status());assertEquals("QUESTION_VERSION_CHANGED",error.code());
        verifyNoInteractions(quizzes);assertEquals(before,documents.get(COURSE_ID));
    }

    @Test void matchingVersionCommitsOnceAndHistoricalReplaySurvivesLaterQuestionReplacement() throws Exception {
        var result=stubSuccessfulEvaluation();String oldVersion=version();
        var request=new ClassroomCourseService.AnswerRequest("quiz","versioned",answers,oldVersion);
        assertEquals(result,service.answer(COURSE_ID,request));
        ObjectNode changed=document();question(scene(changed)).put("question","1+2=?");documents.put(COURSE_ID,changed.toString());
        assertEquals(result,service.answer(COURSE_ID,request));
        verify(quizzes,times(1)).prepare(any(),anyList(),anyString());verify(quizzes,times(1)).evaluate(any());
        verify(quizzes,times(1)).commit(any(),any(),any());
    }

    @Test void oldThreeFieldSuccessfulReceiptIsReadBeforeVersionOrCurrentSceneValidation() throws Exception {
        ObjectNode stored=document();var result=result();
        ObjectNode oldIdentity=json.createObjectNode().put("sceneId","quiz").put("submissionId","historical");
        oldIdentity.set("answers",json.valueToTree(answers));
        ObjectNode receipt=((ObjectNode)stored.path("submissions")).putObject("historical").put("request",oldIdentity.toString());
        receipt.set("result",json.valueToTree(result));stored.putArray("scenes");documents.put(COURSE_ID,stored.toString());
        assertEquals(result,service.answer(COURSE_ID,new ClassroomCourseService.AnswerRequest("quiz","historical",answers,"old-version")));
        verifyNoInteractions(quizzes);
    }

    @Test void legacyThreeArgumentRequestAndJsonWithoutVersionRemainUsable() throws Exception {
        var result=stubSuccessfulEvaluation();
        var request=new ClassroomCourseService.AnswerRequest("quiz","legacy",answers);
        assertNull(request.expectedQuestionVersion());assertEquals(result,service.answer(COURSE_ID,request));
        var parsed=json.readValue("{\"sceneId\":\"quiz\",\"submissionId\":\"legacy\",\"answers\":[{\"no\":1,\"answer\":\"A\"}]}",ClassroomCourseService.AnswerRequest.class);
        assertNull(parsed.expectedQuestionVersion());assertEquals(result,service.answer(COURSE_ID,parsed));
        verify(quizzes,times(1)).commit(any(),any(),any());
    }

    @Test void importDropsSuppliedVersionAndPublicProjectionRecomputesItForFreshQuestionIdentity() throws Exception {
        ObjectNode source=document();scene(source).put("questionVersion","forged-client-version");
        ObjectNode envelope=json.createObjectNode().put("format","educationagent.classroom").put("version",1);envelope.set("course",source);
        doAnswer(call->{ObjectNode scene=call.getArgument(1);scene.put("quizId",99);question(scene).put("no",1);return null;}).when(quizzes).attach(any(),any());
        ObjectNode imported=service.importPortable(envelope);
        ObjectNode persisted=(ObjectNode)json.readTree(documents.get(imported.path("id").asText()));
        assertFalse(scene(persisted).has("questionVersion"));
        String version=scene(imported).path("questionVersion").asText();assertTrue(version.matches("[a-f0-9]{64}"));
        assertEquals(version,ClassroomCourseService.publicScene(scene(persisted)).path("questionVersion").asText());
        assertNotEquals("question",question(scene(imported)).path("id").asText());
        assertEquals("forged-client-version",scene(source).path("questionVersion").asText(),"导入不改写来源包");
    }

    private QuizService.SubmitResult stubSuccessfulEvaluation() {
        var result=result();var prepared=new QuizService.PreparedSubmission("classroom",42L,"ticket",1,"submit","payload","private-question",List.of(),answers,null);
        var evaluated=new QuizService.EvaluatedSubmission(1,"private-question","evaluation",result);
        when(quizzes.prepare(any(),anyList(),anyString())).thenReturn(prepared);when(quizzes.evaluate(prepared)).thenReturn(evaluated);
        when(quizzes.commit(any(),eq(prepared),eq(evaluated))).thenReturn(result);return result;
    }
    private QuizService.SubmitResult result(){return new QuizService.SubmitResult(42L,1,1,List.of(new QuizService.ResultItem(1,true,"A","A","说明")));}
    private ObjectNode document() throws Exception{return (ObjectNode)json.readTree(documents.get(COURSE_ID));}
    private String version(){return scene(service.get(COURSE_ID)).path("questionVersion").asText();}
    private static ObjectNode scene(ObjectNode course){return (ObjectNode)course.path("scenes").get(0);}
    private static ObjectNode question(ObjectNode scene){return (ObjectNode)scene.path("content").path("questions").get(0);}
}
