package com.demo.edu.classroom.study.feedback;

import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LearningFeedbackPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ClassroomStudyContext.Snapshot context = new ClassroomStudyContext.Snapshot("course", "scene", "fp", "面积", "面积", 5,
            "math", "academic", 1, List.of(
            new ClassroomStudyContext.Anchor("source-1", "资料", "长方形的面积等于长乘宽。 比较分数时，要使用同样大小的整体。", "reference"),
            new ClassroomStudyContext.Anchor("goal-1", "目标", "理解长方形的面积公式", "objective")), "面积", List.of());

    @Test void acceptsWhitespaceNormalizedRealQuoteAndAllFourStates() throws Exception {
        JsonNode response = json.readTree("""
            {"summary":"逐点核对","analysis":[
              {"goal":"面积","status":"covered","feedback":"说法一致","anchorId":"source-1","quote":"长方形的面积等于长乘宽。"},
              {"goal":"整体","status":"missing","feedback":"补充比较条件","anchorId":"source-1","quote":"比较分数时，要使用同样大小的整体。"},
              {"goal":"关系","status":"conflict","feedback":"需要回看","anchorId":"source-1","quote":"长方形的面积等于长乘宽。\n比较分数时，要使用同样大小的整体。"},
              {"goal":"圆面积","status":"uncertain","feedback":"没有提供相关资料"}]}
            """.replace("。\n比较", "。\\n比较"));
        ((ObjectNode) response).set("sourceAssessment", consistent());
        var checked = LearningFeedbackPolicy.checkedAnalysis(response, context, json);
        assertEquals(4, checked.path("analysis").size());
        assertEquals(List.of("整体", "关系"), json.convertValue(checked.path("suggestedGoals"), List.class));
    }

    @Test void unsupportedMissingConflictAndCoveredNeverPassSourceValidation() throws Exception {
        for (String state : List.of("missing", "conflict", "covered")) {
            ObjectNode response = response(state, "source-1", "三角形面积等于底乘高除以二");
            assertThrows(IllegalArgumentException.class, () -> LearningFeedbackPolicy.checkedAnalysis(response, context, json));
            ((ObjectNode) response.path("analysis").get(0)).remove("quote");
            assertThrows(IllegalArgumentException.class, () -> LearningFeedbackPolicy.checkedAnalysis(response, context, json));
        }
    }

    @Test void objectiveAndOtherCourseAnchorsCannotBeKnowledgeEvidence() {
        assertThrows(IllegalArgumentException.class, () -> LearningFeedbackPolicy.checkedAnalysis(response("missing", "goal-1", "理解长方形的面积公式"), context, json));
        assertThrows(IllegalArgumentException.class, () -> LearningFeedbackPolicy.checkedAnalysis(response("conflict", "other-course", "长乘宽"), context, json));
    }

    @Test void uncertainWithFakeOptionalCitationStillRejected() {
        assertThrows(IllegalArgumentException.class, () -> LearningFeedbackPolicy.checkedAnalysis(response("uncertain", "source-1", "不存在的句子"), context, json));
    }

    @Test void feedbackSchemaDoesNotAllowScoreOrModelEvidenceToLeak() {
        var response = response("covered", "source-1", "长方形的面积等于长乘宽。");
        response.put("score", 100); response.put("prompt", "secret");
        ((ObjectNode) response.path("analysis").get(0)).put("model", "secret");
        var checked = LearningFeedbackPolicy.checkedAnalysis(response, context, json);
        assertFalse(checked.has("score")); assertFalse(checked.has("prompt")); assertFalse(checked.path("analysis").get(0).has("model"));
    }

    @Test void validatesFourObjectiveSelfCheckTypesWithoutChangingExistingClassroomFiveTypes() throws Exception {
        for (String type : List.of("single", "multiple", "judge", "fill")) {
            ObjectNode data = json.createObjectNode().put("anchorId", "source-1").put("quote", "长方形的面积等于长乘宽。");
            data.set("sourceAssessment", consistent());
            ObjectNode q = data.putObject("question").put("type", type).put("question", "怎样计算？").put("analysis", "资料给出了计算关系。");
            if (type.equals("single") || type.equals("multiple")) {
                q.putArray("options").addObject().put("value", "A").put("label", "长乘宽");
                q.withArray("options").addObject().put("value", "B").put("label", "长加宽");
                q.putArray("answer").add("A");
            } else if (type.equals("judge")) q.put("answer", true);
            else q.put("answer", "长乘宽");
            assertEquals(type, LearningFeedbackPolicy.checkedQuestion(data, context, json).path("type").asText());
            q.put("type", "short_answer");
            assertThrows(IllegalArgumentException.class, () -> LearningFeedbackPolicy.checkedQuestion(data, context, json));
        }
    }

    @Test void publicProjectionHidesAnswerAndInternalTraceUntilDeterminateSubmission() throws Exception {
        ObjectNode attempt = json.createObjectNode().put("id", "a").put("understanding", "我的理解");
        attempt.putObject("_modelEvidence").put("model", "private");
        attempt.putObject("_selfCheckScene").putObject("content").putArray("questions").addObject()
                .put("id", "q").put("type", "fill").put("question", "怎样算").put("answer", "长乘宽").put("analysis", "标答解析");
        assertFalse(LearningFeedbackPolicy.project(attempt, json).toString().contains("长乘宽"));
        attempt.putObject("selfCheckResult").putNull("correct").put("correctAnswer", "不该公开").put("explanation", "未确定");
        assertFalse(LearningFeedbackPolicy.project(attempt, json).path("selfCheckResult").has("correctAnswer"));
        attempt.withObject("selfCheckResult").put("correct", true);
        assertFalse(LearningFeedbackPolicy.project(attempt, json).path("selfCheckResult").has("correctAnswer"));
        attempt.putObject("_submissionResult");
        assertTrue(LearningFeedbackPolicy.project(attempt, json).path("selfCheckResult").has("correctAnswer"));
        assertFalse(LearningFeedbackPolicy.project(attempt, json).has("_modelEvidence"));
    }

    private ObjectNode response(String state, String anchorId, String quote) {
        ObjectNode result = json.createObjectNode().put("summary", "核对总结");
        result.set("sourceAssessment", consistent());
        result.putArray("analysis").addObject().put("goal", "面积公式").put("status", state).put("feedback", "请回看资料")
                .put("anchorId", anchorId).put("quote", quote);
        return result;
    }

    @Test void conflictingSourcesOverrideMixedCoveredAndUncertainAndClearGoals() {
        var contradictory = contradictoryContext();
        ObjectNode response = response("covered", "source-1", "长方形的面积等于长乘宽。");
        ObjectNode assessment = response.putObject("sourceAssessment").put("status", "conflicting").put("reason", "一份资料说相乘，另一份说相加。");
        assessment.putArray("evidence").addObject().put("anchorId", "source-1").put("quote", "长方形的面积等于长乘宽。");
        assessment.withArray("evidence").addObject().put("anchorId", "source-2").put("quote", "长方形面积等于长加宽。");
        response.withArray("analysis").addObject().put("goal", "第二个结论").put("status", "conflict").put("feedback", "不应继续判错")
                .put("anchorId", "source-2").put("quote", "长方形面积等于长加宽。");
        var checked = LearningFeedbackPolicy.checkedAnalysis(response, contradictory, json);
        checked.path("analysis").forEach(item -> assertEquals("uncertain", item.path("status").asText()));
        assertTrue(checked.path("suggestedGoals").isEmpty()); assertTrue(checked.path("summary").asText().contains("暂不判断"));
        assertFalse(checked.toString().contains("不应继续判错"));
    }

    @Test void conflictingEvidenceMustBeDistinctRealQuotesButMayShareAnAnchor() {
        var contextual = new ClassroomStudyContext.Snapshot("c", "s", "fp", "面积", "面积", 5, "math", "academic", 1,
                List.of(new ClassroomStudyContext.Anchor("same", "资料", "甲说面积等于长乘宽。乙说面积等于长加宽。", "reference")), "", List.of());
        ObjectNode assessment = json.createObjectNode().put("status", "conflicting").put("reason", "资料互相矛盾");
        assessment.putArray("evidence").addObject().put("anchorId", "same").put("quote", "甲说面积等于长乘宽。");
        assertThrows(IllegalArgumentException.class, () -> LearningFeedbackPolicy.checkedSourceAssessment(assessment, contextual, json));
        assessment.withArray("evidence").addObject().put("anchorId", "same").put("quote", "乙说面积等于长加宽。");
        assertEquals(2, LearningFeedbackPolicy.checkedSourceAssessment(assessment, contextual, json).path("evidence").size());
        ((ObjectNode) assessment.path("evidence").get(1)).put("quote", "甲说面积等于长乘宽。");
        assertThrows(IllegalArgumentException.class, () -> LearningFeedbackPolicy.checkedSourceAssessment(assessment, contextual, json));
        ((ObjectNode) assessment.path("evidence").get(1)).put("quote", "伪造的冲突来源");
        assertThrows(IllegalArgumentException.class, () -> LearningFeedbackPolicy.checkedSourceAssessment(assessment, contextual, json));
    }

    @Test void insufficientSourcesCannotProduceMissingGoalsOrDefiniteExamplesAndQuestions() {
        ObjectNode response = response("missing", "source-1", "长方形的面积等于长乘宽。");
        response.putObject("sourceAssessment").put("status", "insufficient").put("reason", "缺少本题必要条件").putArray("evidence");
        var checked = LearningFeedbackPolicy.checkedAnalysis(response, context, json);
        assertEquals("uncertain", checked.path("analysis").get(0).path("status").asText());
        assertTrue(checked.path("suggestedGoals").isEmpty());
        assertTrue(LearningFeedbackPolicy.checkedExplanation(response, context, json).path("blocked").asBoolean());
        assertTrue(LearningFeedbackPolicy.checkedQuestion(response, context, json).path("blocked").asBoolean());
        response.remove("sourceAssessment");
        assertThrows(IllegalArgumentException.class, () -> LearningFeedbackPolicy.checkedAnalysis(response, context, json));
    }

    private ObjectNode consistent() {
        ObjectNode assessment = json.createObjectNode().put("status", "consistent").put("reason", "所提供的关系没有相互冲突");
        assessment.putArray("evidence").addObject().put("anchorId", "source-1").put("quote", "长方形的面积等于长乘宽。");
        return assessment;
    }
    private ClassroomStudyContext.Snapshot contradictoryContext() {
        return new ClassroomStudyContext.Snapshot("c", "s", "fp", "面积", "面积", 5, "math", "academic", 1,
                List.of(new ClassroomStudyContext.Anchor("source-1", "资料甲", "长方形的面积等于长乘宽。", "reference"),
                        new ClassroomStudyContext.Anchor("source-2", "资料乙", "长方形面积等于长加宽。", "reference")), "", List.of());
    }
}
