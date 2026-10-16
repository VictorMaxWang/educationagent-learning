package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
}
