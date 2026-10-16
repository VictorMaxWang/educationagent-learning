package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只读补审：只报告，不生成、不写库；判不了与判出错必须分开。 */
class ClassroomQuizReviewControllerTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode document() throws Exception {
        return (ObjectNode) json.readTree("""
                {"id":"c1","title":"分数课","scenes":[
                 {"id":"s1","title":"练一练","type":"quiz","quizId":7,"content":{"type":"quiz","questions":[
                   {"id":"q1","no":1,"type":"single","question":"哪一个是二分之一",
                    "options":[{"label":"2/4","value":"A"},{"label":"1/4","value":"B"}],
                    "answer":["A"],"analysis":"四份取两份","hasAnswer":true}]}},
                 {"id":"s2","title":"讲解","type":"slide","content":{"type":"slide"},"actions":[]}]}
                """);
    }

    private JsonNode reviewResult() throws Exception {
        return json.readTree("""
                {"findings":[
                  {"no":1,"id":"c1-q1","type":"single","stage":"model","outcome":"disagree",
                   "detail":"独立求解得到 B，标答为 A","currentAnswer":["A"],"suggestedAnswer":"B"},
                  {"no":2,"id":"c1-q2","type":"short_answer","stage":"model","outcome":"unjudged",
                   "detail":"题型 short_answer 不支持自动比对","currentAnswer":"基准一致","suggestedAnswer":""}],
                 "ruleDropped":1,"reviewed":2,"callFailed":false}
                """);
    }

    @Test void reportsRuleHitsAndDisagreementsButNotUnjudgedAsProblems() throws Exception {
        ClassroomCourseService courses = mock(ClassroomCourseService.class);
        ClassroomEngine engine = mock(ClassroomEngine.class);
        when(courses.internalDocuments()).thenReturn(List.of(document()));
        when(engine.review(any(), any())).thenReturn(reviewResult());

        ObjectNode report = new ClassroomQuizReviewController(courses, engine, json).audit(null);

        assertEquals("read-only", report.path("mode").asText(), "补审必须是只读的");
        assertEquals(1, report.path("problemCount").asInt(), "unjudged 是查不了，不是查出错，不能算问题");
        assertEquals(2, report.path("reviewedQuestionCount").asInt());
        JsonNode scene = report.path("courses").get(0).path("scenes").get(0);
        assertTrue(scene.path("attachedToQuizPaper").asBoolean(), "已建卷要标出来：改动它会牵动成绩分母");
        assertEquals(1, scene.path("ruleDropped").asInt());
        assertEquals(1, scene.path("problems").size());
        assertEquals("disagree", scene.path("problems").get(0).path("outcome").asText());
        assertEquals("B", scene.path("problems").get(0).path("suggestedAnswer").asText());
        // 图文页不参与习题审核。
        assertEquals(1, report.path("courses").get(0).path("scenes").size());
        // 只读：除了读文档，不得调用课程服务的任何写路径。
        verify(courses).internalDocuments();
        verify(courses, never()).internalDocument(any());
        verifyNoMoreInteractions(courses);
    }

    @Test void targetedCourseReadsOnlyThatDocumentAndReportsFailureInsteadOfLookingClean() throws Exception {
        ClassroomCourseService courses = mock(ClassroomCourseService.class);
        ClassroomEngine engine = mock(ClassroomEngine.class);
        when(courses.internalDocument("c1")).thenReturn(document());
        when(engine.review(any(), any())).thenThrow(new ClassroomException(503, "课堂生成器尚未构建"));

        ObjectNode report = new ClassroomQuizReviewController(courses, engine, json).audit("c1");

        JsonNode scene = report.path("courses").get(0).path("scenes").get(0);
        assertEquals("课堂生成器尚未构建", scene.path("error").asText());
        assertFalse(scene.has("problems"), "失败不能被当成没有问题");
        assertEquals(0, report.path("problemCount").asInt());
        verify(courses).internalDocument("c1");
        verify(courses, never()).internalDocuments();
    }

    @Test void applyRequiresTheReviewedPlanAndNeverGeneratesANewPlan()throws Exception {
        var courses=mock(ClassroomCourseService.class);var engine=mock(ClassroomEngine.class);
        when(courses.internalDocument("c1")).thenReturn(document());when(engine.repair(any(),any())).thenReturn(repairPlan());
        when(courses.applyQuizRepairs(eq("c1"),anyString(),anyMap())).thenReturn(json.createObjectNode().put("courseId","c1").put("applied",1));
        var controller=new ClassroomQuizReviewController(courses,engine,json);
        assertEquals(400,assertThrows(ClassroomException.class,()->controller.repair("c1",true)).status());verifyNoInteractions(engine);
        ObjectNode preview=controller.repair("c1",false);String id=preview.path("courses").get(0).path("planId").asText();assertFalse(id.isBlank());
        assertEquals("dry-run",preview.path("mode").asText());verify(courses,never()).applyQuizRepairs(anyString(),anyString(),anyMap());
        ObjectNode applied=controller.repair("c1",true,id);assertEquals("applied",applied.path("mode").asText());
        assertEquals(applied,controller.repair("c1",true,id));
        verify(engine,times(1)).repair(any(),any());verify(courses,times(1)).applyQuizRepairs(eq("c1"),anyString(),anyMap());
    }

    @Test void wrongCourseAndMutatedPreviewCannotAlterBoundRepair()throws Exception {
        var courses=mock(ClassroomCourseService.class);var engine=mock(ClassroomEngine.class);
        when(courses.internalDocument("c1")).thenReturn(document());when(engine.repair(any(),any())).thenReturn(repairPlan());
        when(courses.applyQuizRepairs(eq("c1"),anyString(),anyMap())).thenAnswer(call->{
            java.util.Map<String,JsonNode> plans=call.getArgument(2);
            assertEquals("A",plans.get("s1").get(0).path("after").path("answer").get(0).asText());
            assertEquals("哪一个是二分之一",plans.get("s1").get(0).path("before").path("question").asText());
            return json.createObjectNode().put("applied",1);
        });
        var controller=new ClassroomQuizReviewController(courses,engine,json);ObjectNode preview=controller.repair("c1",false);
        JsonNode course=preview.path("courses").get(0);String id=course.path("planId").asText();
        ((ObjectNode)course.path("scenes").get(0).path("changes").get(0).path("after")).putArray("answer").add("B");
        assertEquals(409,assertThrows(ClassroomException.class,()->controller.repair("other",true,id)).status());
        assertEquals("applied",controller.repair("c1",true,id).path("mode").asText());
    }

    @Test void failedApplyConsumesPlanAndDoesNotRepeatWritesOrModelRequests()throws Exception {
        var courses=mock(ClassroomCourseService.class);var engine=mock(ClassroomEngine.class);
        when(courses.internalDocument("c1")).thenReturn(document());when(engine.repair(any(),any())).thenReturn(repairPlan());
        when(courses.applyQuizRepairs(anyString(),anyString(),anyMap())).thenThrow(new ClassroomException(409,"该题已有作答，必须另建版本"));
        var controller=new ClassroomQuizReviewController(courses,engine,json);String id=controller.repair("c1",false).path("courses").get(0).path("planId").asText();
        assertEquals(409,assertThrows(ClassroomException.class,()->controller.repair("c1",true,id)).status());
        assertEquals(409,assertThrows(ClassroomException.class,()->controller.repair("c1",true,id)).status());
        verify(courses,times(1)).applyQuizRepairs(anyString(),anyString(),anyMap());verify(engine,times(1)).repair(any(),any());
    }

    private ObjectNode repairPlan()throws Exception {
        return (ObjectNode)json.readTree("""
            {"changes":[{"no":1,"id":"q1","action":"regenerate","reason":"完整重出","before":{},
             "after":{"type":"single","question":"四份中的两份等于多少？","options":[{"value":"A","label":"1/2"},{"value":"B","label":"1/4"}],
             "answer":["A"],"analysis":"四份中的两份是二分之一。"},"verification":{"blindSolve":"agree","explanation":"consistent"}}]}
            """);
    }
}
