package com.demo.edu.classroom.ai;

import com.demo.edu.classroom.ClassroomCancellation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClassroomTeachingReviewTest {
    final ObjectMapper json = new ObjectMapper();
    final ClassroomAiGateway gateway = mock(ClassroomAiGateway.class);
    final ClassroomTeachingReview review = new ClassroomTeachingReview(gateway, json);

    @Test void semanticPassIsContentOnlyAndNeverMutatesInput() {
        respond(verdict("pass"));
        ObjectNode original = scene();
        ObjectNode result = review.review(course(), original, new ClassroomCancellation());
        assertEquals("passed", result.path("_teachingReview").path("status").asText());
        assertFalse(result.path("_teachingReview").path("runtimeVerified").asBoolean());
        assertFalse(original.has("_teachingReview"));
        assertEquals("content-only", result.path("_teachingReview").path("scope").asText());
        assertFalse(review.publicMetadata(result).has("modelCalls"));
        verify(gateway, times(1)).structuredOnce(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test void exactlyOneTextRevisionIsAdoptedOnlyAfterRecheck() {
        ObjectNode change = verdict("needs_revision");
        change.withArray("patches").addObject().put("path", "/actions/0/text").put("before", "面积是长度相加")
                .put("value", "长方形面积等于长乘宽");
        respond(change, verdict("pass"));
        ObjectNode original = scene();
        ObjectNode result = review.review(course(), original, new ClassroomCancellation());
        assertEquals("revised", result.path("_teachingReview").path("status").asText());
        assertEquals("长方形面积等于长乘宽", result.path("actions").get(0).path("text").asText());
        assertEquals("面积是长度相加", original.path("actions").get(0).path("text").asText());
        assertEquals(2, result.path("_teachingReview").path("modelCalls").size());
        assertEquals(original.path("content"), result.path("content"));
    }

    @Test void failedRecheckRestoresOriginalCandidateAndDoesNotLoop() {
        ObjectNode change = verdict("needs_revision");
        change.withArray("patches").addObject().put("path", "/title").put("before", "面积计算").put("value", "长方形面积");
        respond(change, verdict("needs_revision"));
        ObjectNode original = scene();
        ObjectNode result = review.review(course(), original, new ClassroomCancellation());
        assertEquals("needs_revision", result.path("_teachingReview").path("status").asText());
        assertEquals("面积计算", result.path("title").asText());
        assertFalse(result.path("_teachingReview").path("revisionApplied").asBoolean());
        verify(gateway, times(2)).structuredOnce(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test void unknownIsNeverPromotedToPassOrChangedContent() {
        respond(verdict("unknown"));
        ObjectNode result = review.review(course(), scene(), new ClassroomCancellation());
        assertEquals("unknown", result.path("_teachingReview").path("status").asText());
        assertEquals("面积是长度相加", result.path("actions").get(0).path("text").asText());
    }

    @Test void disallowsChangingHtmlIdentifiersOrAnswers() {
        for (String path : new String[]{"/id", "/content/html", "/content/questions/0/answer", "/actions/0/type"}) {
            reset(gateway);
            ObjectNode change = verdict("needs_revision");
            change.withArray("patches").addObject().put("path", path).put("before", "old").put("value", "new");
            respond(change);
            ObjectNode result = review.review(course(), scene(), new ClassroomCancellation());
            assertEquals("needs_revision", result.path("_teachingReview").path("status").asText());
            verify(gateway, times(1)).structuredOnce(anyString(), anyString(), anyString(), any(), any(), any());
        }
    }

    @Test void rejectsMismatchDuplicateAndMarkupPatches() {
        for (String scenario : new String[]{"before", "duplicate", "markup"}) {
            reset(gateway);
            ObjectNode change = verdict("needs_revision");
            change.withArray("patches").addObject().put("path", "/title")
                    .put("before", scenario.equals("before") ? "not current" : "面积计算")
                    .put("value", scenario.equals("markup") ? "<script>alert(1)</script>" : "长方形面积");
            if (scenario.equals("duplicate")) change.withArray("patches").add(change.path("patches").get(0).deepCopy());
            respond(change);
            ObjectNode result = review.review(course(), scene(), new ClassroomCancellation());
            assertEquals("面积计算", result.path("title").asText());
            assertEquals("needs_revision", result.path("_teachingReview").path("status").asText());
        }
    }

    @Test void rejectedPatchKeepsSpecificTeachingReasonWithoutReflectingPatchOrException() {
        String issue = "本页缺少将长与宽对应到菜园网格的示范，尚未覆盖面积计算目标";
        ObjectNode change = verdict("needs_revision").put("reason", "<b>" + issue + "</b>");
        change.withArray("patches").addObject().put("path", "/content/html")
                .put("before", "PRIVATE_INVALID_PATCH_BEFORE").put("value", "PRIVATE_INVALID_PATCH_VALUE");
        respond(change);
        ObjectNode original = scene(), result = review.review(course(), original, new ClassroomCancellation());
        JsonNode evidence = result.path("_teachingReview");
        assertEquals("needs_revision", evidence.path("status").asText());
        assertTrue(evidence.path("reason").asText().startsWith(issue));
        assertEquals(issue, evidence.path("assessmentReason").asText());
        assertEquals(original.path("content"), result.path("content"));
        assertFalse(evidence.toString().contains("PRIVATE_INVALID_PATCH"));
        assertFalse(evidence.toString().contains("patch not allowed"));
        assertFalse(evidence.has("patches"));
        assertFalse(review.publicMetadata(result).has("assessmentReason"));
        verify(gateway, times(1)).structuredOnce(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test void rejectedPatchReasonRemainsBoundedWhilePrivateAssessmentKeepsTheFullValidReason() {
        String issue = "没有覆盖本节的面积目标。" + "需要补充网格说明。".repeat(40);
        ObjectNode change = verdict("needs_revision").put("reason", issue);
        change.withArray("patches").addObject().put("path", "/title").put("before", "不匹配的原文").put("value", "面积");
        respond(change);
        JsonNode evidence = review.review(course(), scene(), new ClassroomCancellation()).path("_teachingReview");
        assertEquals(issue, evidence.path("assessmentReason").asText());
        assertTrue(evidence.path("reason").asText().startsWith("没有覆盖本节的面积目标。"));
        assertTrue(evidence.path("reason").asText().length() <= 240);
        assertTrue(evidence.path("assessmentReason").asText().length() <= 600);
    }

    @Test void htmlBeforeIsPassedVerbatimAndOnlyExactEditableTextCanBePatched() throws Exception {
        ObjectNode original = scene();
        original.withObject("/content/canvas").withArray("elements").addObject()
                .put("type", "text").put("content", "<p>面积是长度相加</p>");
        ObjectNode change = verdict("needs_revision");
        change.withArray("patches").addObject().put("path", "/content/canvas/elements/0/content")
                .put("before", "<p>面积是长度相加</p>").put("value", "面积等于长乘宽");
        respond(change, verdict("pass"));
        ObjectNode result = review.review(course(), original, new ClassroomCancellation());
        assertEquals("revised", result.path("_teachingReview").path("status").asText());
        assertEquals("面积等于长乘宽", result.at("/content/canvas/elements/0/content").asText());
        var first = mockingDetails(gateway).getInvocations().iterator().next();
        String system = first.getArgument(1);
        assertTrue(system.contains("逐字复制同一条editableTexts"));
        assertTrue(system.contains("before包括原有HTML标签"));
        JsonNode editable = json.readTree((String) first.getArgument(2)).path("editableTexts");
        assertTrue(java.util.stream.StreamSupport.stream(editable.spliterator(), false).anyMatch(item ->
                item.path("path").asText().equals("/content/canvas/elements/0/content")
                        && item.path("before").asText().equals("<p>面积是长度相加</p>")));
        verify(gateway, times(2)).structuredOnce(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test void providerFailurePreservesOriginalAndRecordsUnknown() {
        when(gateway.structuredOnce(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new ClassroomAiGateway.Failure(504, "PRIVATE_PROVIDER_EXCEPTION", "request-timeout", json.createObjectNode().put("attemptCount", 1)));
        ObjectNode result = review.review(course(), scene(), new ClassroomCancellation());
        assertEquals("unknown", result.path("_teachingReview").path("status").asText());
        assertEquals(1, result.path("_teachingReview").path("modelCalls").size());
        assertEquals("面积计算", result.path("title").asText());
        assertFalse(result.path("_teachingReview").toString().contains("PRIVATE_PROVIDER_EXCEPTION"));
    }

    @Test void cancellationIsPropagatedRatherThanUnknownOrPass() {
        ClassroomCancellation cancelled = new ClassroomCancellation(); cancelled.cancel();
        assertThrows(CancellationException.class, () -> review.review(course(), scene(), cancelled));
        verifyNoInteractions(gateway);
    }

    @Test void ruleFailureUsesNoPaidModelRequest() {
        ObjectNode invalid = course().put("grade", 20);
        ObjectNode result = review.review(invalid, scene(), new ClassroomCancellation());
        assertEquals("needs_revision", result.path("_teachingReview").path("status").asText());
        verifyNoInteractions(gateway);
    }

    @Test void secondCallUsesRemainingPageBudgetRatherThanNewBudget() throws Exception {
        ClassroomTeachingReview bounded = new ClassroomTeachingReview(gateway, json, Duration.ofMillis(600));
        AtomicInteger calls = new AtomicInteger();
        ObjectNode change = verdict("needs_revision");
        change.withArray("patches").addObject().put("path", "/title").put("before", "面积计算").put("value", "长方形面积");
        when(gateway.structuredOnce(anyString(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            Duration budget = invocation.getArgument(3);
            if (call == 1) Thread.sleep(180);
            else assertTrue(budget.toMillis() < 500, "second call must share original deadline");
            return new ClassroomAiGateway.Result(call == 1 ? change : verdict("pass"), json.createObjectNode());
        });
        assertEquals("revised", bounded.review(course(), scene(), new ClassroomCancellation()).path("_teachingReview").path("status").asText());
        assertEquals(2, calls.get());
    }

    @Test void inconsistentModelVerdictIsRejectedByContract() {
        ObjectNode bad = verdict("pass"); bad.withObject("checks").put("goals", "unknown");
        respond(bad);
        assertThrows(IllegalArgumentException.class, () -> review.review(course(), scene(), new ClassroomCancellation()));
    }

    @SafeVarargs final void respond(ObjectNode... responses) {
        AtomicInteger index = new AtomicInteger();
        when(gateway.structuredOnce(anyString(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            ObjectNode response = responses[Math.min(index.getAndIncrement(), responses.length - 1)].deepCopy();
            Consumer<JsonNode> validator = invocation.getArgument(4); validator.accept(response);
            return new ClassroomAiGateway.Result(response, json.createObjectNode().put("attemptCount", 1).put("model", "fixture-model"));
        });
    }

    ObjectNode verdict(String verdict) {
        ObjectNode response = json.createObjectNode().put("verdict", verdict).put("reason", "按当前课程目标核对");
        response.putObject("checks").put("grade", "pass").put("goals", verdict.equals("pass") ? "pass" : verdict)
                .put("sources", "not_applicable").put("interaction", "not_applicable");
        response.putArray("patches"); return response;
    }
    ObjectNode course() {
        return json.createObjectNode().put("id", "c1").put("grade", 5).put("title", "菜园里的面积").put("direction", "academic");
    }
    ObjectNode scene() {
        ObjectNode scene = json.createObjectNode().put("id", "s1").put("title", "面积计算").put("type", "slide");
        scene.putObject("content").put("type", "slide").putObject("canvas").putArray("elements");
        scene.putArray("actions").addObject().put("id", "a1").put("type", "speech").put("text", "面积是长度相加");
        return scene;
    }
}
