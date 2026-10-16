package com.demo.edu.classroom;

import com.demo.edu.classroom.ai.ClassroomTeachingReview;
import com.demo.edu.classroom.recommendation.ClassroomRecommendationService;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 核对服务真的参与页面提交，未知结果重试复用草稿而非仅增加未被调用的工具类。 */
class ClassroomTeachingIntegrationTest {
    ClassroomCourseServiceTest fixture;
    ClassroomTeachingReview review;
    ClassroomStudyStore study;
    Map<String,ObjectNode> states;
    @BeforeEach void setup() {
        fixture=new ClassroomCourseServiceTest();fixture.setup();review=mock(ClassroomTeachingReview.class);study=mock(ClassroomStudyStore.class);states=new HashMap<>();
        fixture.service.setTeachingReview(review);fixture.service.setStudyStore(study);
        when(study.find(anyString(),anyString())).thenAnswer(i->Optional.ofNullable(states.get(i.getArgument(0)+":"+i.getArgument(1))).map(ObjectNode::deepCopy));
        doAnswer(i->{states.put(i.getArgument(0)+":"+i.getArgument(1),((ObjectNode)i.getArgument(3)).deepCopy());return null;}).when(study).save(anyString(),anyString(),anyString(),any());
        when(study.create(anyString(),anyString(),anyString(),any())).thenReturn(true);
    }
    private ObjectNode verdict(ObjectNode scene,String status) {
        ObjectNode result=scene.deepCopy();
        result.putObject("_teachingReview").put("status",status).put("reason","内容核对结果").putObject("evidence").put("requestId","internal-only");
        return result;
    }
    @Test void uncertainReviewKeepsDraftAndRetryDoesNotRegenerateSamePage() {
        AtomicInteger calls=new AtomicInteger();
        when(review.review(any(),any(),any())).thenAnswer(i->verdict(i.getArgument(1),calls.getAndIncrement()==0?"unknown":"passed"));
        String id=fixture.create().path("id").asText();fixture.executor.next();fixture.service.generate(id);fixture.executor.next();
        assertEquals("error",fixture.service.get(id).path("status").asText());assertEquals(0,fixture.service.get(id).path("scenes").size());
        assertTrue(states.values().stream().anyMatch(s->s.path("reuseAllowed").asBoolean()));
        fixture.service.generate(id);fixture.executor.next();
        assertEquals("ready",fixture.service.get(id).path("status").asText());
        verify(fixture.engine,times(1)).scene(any(),argThat(n->n.path("id").asText().equals("step-1")),any());
        ObjectNode first=(ObjectNode)fixture.service.get(id).path("scenes").get(0);
        assertFalse(first.has("_teachingReview"));assertEquals("passed",first.path("teachingReview").path("status").asText());
        assertFalse(first.toString().contains("internal-only"));
        assertFalse(fixture.service.portable(id).toString().contains("internal-only"));
    }
    @Test void knownRevisionFailureRegeneratesCandidateAndKeepsPreviousPages() {
        AtomicInteger calls=new AtomicInteger();
        when(review.review(any(),any(),any())).thenAnswer(i->{ObjectNode scene=i.getArgument(1);return verdict(scene,scene.path("id").asText().equals("step-2")&&calls.getAndIncrement()==0?"needs_revision":"passed");});
        String id=fixture.create().path("id").asText();fixture.executor.next();fixture.service.generate(id);fixture.executor.next();
        assertEquals(1,fixture.service.get(id).path("scenes").size());
        fixture.service.generate(id);fixture.executor.next();
        assertEquals("ready",fixture.service.get(id).path("status").asText());
        verify(fixture.engine,times(1)).scene(any(),argThat(n->n.path("id").asText().equals("step-1")),any());
        verify(fixture.engine,times(2)).scene(any(),argThat(n->n.path("id").asText().equals("step-2")),any());
    }
    @Test void supplementaryQueueFailureCannotUndoReadyCourse() {
        when(review.review(any(),any(),any())).thenAnswer(i->verdict(i.getArgument(1),"passed"));
        ClassroomRecommendationService recommendation=mock(ClassroomRecommendationService.class);
        @SuppressWarnings("unchecked") ObjectProvider<ClassroomRecommendationService> provider=mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(recommendation);doThrow(new IllegalStateException("queue unavailable")).when(recommendation).prepareIfAbsent(anyString());
        fixture.service.setRecommendationProvider(provider);
        String id=fixture.readyCourse();
        assertEquals("ready",fixture.service.get(id).path("status").asText());verify(recommendation).prepareIfAbsent(id);
    }
    @Test void importedOrEditedContentCannotInheritSemanticPass() {
        ObjectNode scene=fixture.json.createObjectNode().put("title","测试").put("type","slide");
        scene.putObject("content").put("type","slide");scene.putArray("actions");
        scene.putObject("_teachingReview").put("status","passed");scene.putObject("teachingReview").put("status","passed");
        ClassroomValidationEvidence.record(scene,1,"edit");
        assertFalse(scene.has("_teachingReview"));assertFalse(scene.has("teachingReview"));
        assertEquals("NOT_RUN",scene.path("validation").path("checks").path("browserRuntime").asText());
    }
    @Test void publicSceneProjectionHidesFullReviewAndUnansweredQuizReference() {
        ObjectNode scene=fixture.json.createObjectNode().put("title","自检").put("type","quiz");
        scene.putObject("content").put("type","quiz").putArray("questions").addObject().put("question","问题").put("answer","A").put("analysis","标答解释");
        scene.putObject("_teachingReview").put("status","passed").putArray("modelCalls").addObject().put("requestId","internal-call");
        ObjectNode response=ClassroomCourseService.publicScene(scene);
        assertFalse(response.toString().contains("internal-call"));assertFalse(response.has("_teachingReview"));
        assertFalse(response.path("content").path("questions").get(0).has("answer"));
        assertEquals("passed",response.path("teachingReview").path("status").asText());
        assertTrue(scene.has("_teachingReview"),"公开投影不能擦掉服务端审计");
    }
}
