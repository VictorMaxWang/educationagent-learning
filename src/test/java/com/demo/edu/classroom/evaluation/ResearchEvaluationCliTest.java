package com.demo.edu.classroom.evaluation;

import com.demo.edu.classroom.recommendation.RecommendationOptimizer.*;
import com.demo.edu.classroom.recommendation.RecommendationVerifier;
import com.demo.edu.classroom.recommendation.BilibiliVideoCatalog;
import com.demo.edu.classroom.recommendation.ClassroomRecommendationService;
import com.demo.edu.classroom.recommendation.ResourceSemanticFeatures;
import com.demo.edu.classroom.recommendation.VideoTeachingClassifier;
import com.demo.edu.classroom.ClassroomCourseService;
import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 评测器本身也必须保留同池约束、失败分母和未知标签，不能为了漂亮结果改变比较。 */
class ResearchEvaluationCliTest {
    static Candidate candidate(String id,int duration,String goal,double relevance,String duplicate,boolean eligible) {
        return new Candidate(id,duration,Set.of(goal),"concept",relevance,.8,0,eligible,duplicate);
    }
    @Test void rankBaselinesRespectBudgetDuplicatesAndEligibility() {
        List<Candidate> pool=List.of(candidate("too-long",900,"a",1,"",true),candidate("first",120,"a",.8,"same",true),
                candidate("copy",100,"a",.9,"same",true),candidate("blocked",1,"b",1,"",false),candidate("second",180,"b",.7,"",true));
        Input input=new Input(pool,Set.of("a","b"),300,false,Map.of());
        assertEquals(List.of("first","second"),ResearchEvaluationCli.feasibleTop(input,pool));
        assertTrue(RecommendationVerifier.recompute(input,ResearchEvaluationCli.greedy(input)).isPresent());
    }
    @Test void reportsObjectiveProxyAndUnknownFirstHitLabelsHonestly() {
        Input input=new Input(List.of(candidate("a",80,"a",.4,"",true),candidate("b",90,"b",.9,"",true)),Set.of("a","b"),300,false,Map.of());
        var result=ResearchEvaluationCli.compare(input);
        assertTrue(result.path("verification").path("optimal").asBoolean());
        assertEquals(2,result.path("firstHitAudit").path("unknownCount").asInt());
        assertEquals(4,result.path("firstHitAudit").path("evaluatedAssignments").asInt());
        assertEquals("EVALUATED_SEPARATELY_ON_72_CLASSIFICATION_CASES",result.path("ablations").path("without_gate").path("status").asText());
        assertTrue(result.path("qualityMeaning").asText().contains("not human"));
    }
    @Test void feedbackAblationIsExplicitControlledInputAndNeverClaimsStudentBenefit() {
        Input input=new Input(List.of(candidate("a",80,"a",.4,"",true),candidate("b",90,"b",.9,"",true)),Set.of("a","b"),300,false,Map.of());
        var result=ResearchEvaluationCli.feedbackAblation(input);
        assertEquals("controlled-feedback-fixture",result.path("mode").asText());
        assertFalse(result.path("humanStudentFeedback").asBoolean());
        assertEquals("a",result.path("priorityGoal").asText());
        assertTrue(result.path("focusedVerification").path("optimal").asBoolean());
    }
    @Test void semanticAblationCanChangeSelectionButIsScoredOnOriginalObjective() {
        Input input=new Input(List.of(candidate("short",40,"a",.1,"",true),candidate("semantic",60,"a",.9,"",true)),Set.of("a"),100,false,Map.of());
        var result=ResearchEvaluationCli.compare(input);
        assertEquals("semantic",result.path("methods").path("optimum").path("ids").get(0).asText());
        assertEquals("short",result.path("ablations").path("without_semantic_relevance").path("ids").get(0).asText());
        assertTrue(result.path("methods").path("optimum").path("score").path("total").asDouble()
                >result.path("ablations").path("without_semantic_relevance").path("originalObjectiveScore").path("total").asDouble());
    }
    @Test void noFeasibleCandidateProducesEmptyResultsInsteadOfInvalidTopThree() {
        Input input=new Input(List.of(candidate("long",800,"a",1,"",true)),Set.of("a"),300,false,Map.of());
        var comparison=ResearchEvaluationCli.compare(input);
        comparison.path("methods").forEach(method->assertTrue(method.path("ids").isEmpty()));
        assertTrue(comparison.path("verification").path("optimal").asBoolean());
    }
    @Test void fixedCatalogInterceptsBothPublicSignaturesWithoutNetwork() {
        ObjectMapper json=new ObjectMapper();ObjectNode source=json.createObjectNode().put("query","静夜思 朗读");
        var fixed=new ResearchEvaluationCli.FixedSnapshotCatalog(source,List.of());
        BilibiliVideoCatalog catalog=fixed;
        assertTrue(catalog.search("静夜思 朗读",600).videos().isEmpty());
        assertTrue(catalog.search("静夜思 朗读",600,Duration.ofSeconds(5)).videos().isEmpty());
        assertEquals(1,fixed.legacyCalls.get());assertEquals(1,fixed.budgetCalls.get());
    }
    @Test void productionOrchestratorActuallyDispatchesToFixedBudgetCatalog() throws Exception {
        ObjectMapper json=new ObjectMapper();ObjectNode source=json.createObjectNode().put("id","BILI-TEST").put("query","静夜思 朗读");
        ObjectNode course=ResearchEvaluationCli.queryCourse(source);
        ClassroomCourseService courses=mock(ClassroomCourseService.class);
        when(courses.studySnapshot("eval-BILI-TEST")).thenReturn(course);when(courses.hasActiveGeneration()).thenReturn(false);
        ResourceSemanticFeatures semantic=mock(ResourceSemanticFeatures.class);when(semantic.identity()).thenReturn(json.createObjectNode().put("provider","test-only"));
        VideoTeachingClassifier classifier=mock(VideoTeachingClassifier.class);
        when(classifier.classify(any(ClassroomStudyContext.Snapshot.class),any(Duration.class))).thenReturn(
                new VideoTeachingClassifier.Classification("eligible","recitation","观察停顿",List.of("朗读停顿"),"静夜思",json.createObjectNode(),json.createObjectNode()));
        var fixed=new ResearchEvaluationCli.FixedSnapshotCatalog(source,List.of());var store=new ResearchEvaluationCli.MemoryStore();
        ClassroomAiGateway gateway=mock(ClassroomAiGateway.class);
        try(var service=new ClassroomRecommendationService(courses,store,json,classifier,fixed,semantic,gateway)) {
            var queued=service.refresh("eval-BILI-TEST",new ClassroomRecommendationService.RefreshRequest("scene-1",10,""));
            long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
            while(store.find("recommendation_decision",queued.path("decisionId").asText()).isEmpty()&&System.nanoTime()<deadline)Thread.sleep(25);
            var result=store.find("recommendation_decision",queued.path("decisionId").asText()).orElseThrow();
            assertEquals("hidden",result.path("status").asText());
            assertEquals(1,fixed.budgetCalls.get());assertEquals(0,fixed.legacyCalls.get());
            verify(classifier,times(1)).classify(any(ClassroomStudyContext.Snapshot.class),any(Duration.class));
            verify(semantic,never()).compute(anyString(),anyString(),anyList(),any(Duration.class));
            verifyNoInteractions(gateway);
        }
    }
    @Test void optimizedFirstAuditKeepsFullSamePoolAndUnknownNoHitAssignments() {
        Input input=new Input(List.of(candidate("a",80,"a",.2,"",true),candidate("b",90,"a",.5,"",true),candidate("c",70,"a",.9,"",true)),Set.of("a"),300,false,Map.of());
        var result=ResearchEvaluationCli.compare(input).path("firstHitOptimizedAudit");
        List<String> left=new ArrayList<>(),right=new ArrayList<>();result.path("retrievalOrder").forEach(x->left.add(x.asText()));result.path("optimizedFirstOrder").forEach(x->right.add(x.asText()));
        assertEquals(3,right.size());assertEquals(new HashSet<>(left),new HashSet<>(right));assertEquals("c",right.getFirst());
        assertEquals(3,result.path("audit").path("unknownCount").asInt());assertEquals(8,result.path("audit").path("evaluatedAssignments").asInt());
        assertEquals(1,result.path("audit").path("noHitLeftAssignments").asInt());assertEquals(1,result.path("audit").path("noHitRightAssignments").asInt());
    }
    @Test void evidenceIdentifiesActualLoadedBytecodeAndHarnessRevision() {
        var evidence=ResearchEvaluationCli.buildEvidence();
        assertEquals(ResearchEvaluationCli.HARNESS_REVISION,evidence.path("harnessRevision").asText());
        assertTrue(evidence.path("loadedClassSha256").asText().matches("[0-9a-f]{64}"));
        assertTrue(evidence.path("workspaceSourceSha256").asText().matches("[0-9a-f]{64}"));
    }
}
