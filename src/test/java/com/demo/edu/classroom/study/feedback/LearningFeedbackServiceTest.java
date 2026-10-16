package com.demo.edu.classroom.study.feedback;

import com.demo.edu.classroom.ClassroomCourseService;
import com.demo.edu.classroom.ClassroomException;
import com.demo.edu.classroom.ClassroomQuizAdapter;
import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.demo.edu.classroom.study.ClassroomStudyStore;
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

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实H2事务验证独立自检：失败不能留下空卷、错题或半成品去重回执。 */
class LearningFeedbackServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final ClassroomCourseService courses = mock(ClassroomCourseService.class);
    final ClassroomAiGateway gateway = mock(ClassroomAiGateway.class);
    final ClassroomQuizAdapter quiz = mock(ClassroomQuizAdapter.class);
    JdbcTemplate jdbc;
    ClassroomStudyStore store;
    LearningFeedbackService service;
    ObjectNode course;
    ObjectNode modelAnswer;
    AtomicInteger modelCalls;

    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:feedback_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE edu_classroom_study_state(kind VARCHAR(40),entry_id VARCHAR(160),course_id VARCHAR(64),scene_id VARCHAR(80) DEFAULT '',document CLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(kind,entry_id))");
        jdbc.execute("CREATE TABLE quiz_audit(kind VARCHAR(40),payload VARCHAR(200))");
        store = new ClassroomStudyStore(jdbc, json);
        service = new LearningFeedbackService(courses, store, gateway, quiz, json, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        course = (ObjectNode) json.readTree("""
            {"id":"c","title":"菜园面积","topic":"长方形面积","grade":5,"subject":"math","direction":"academic",
              "sourceText":"长方形的面积等于长乘宽。长为6米，宽为4米时，面积为24平方米。",
              "scenes":[{"id":"s","type":"slide","title":"面积","content":{"type":"slide","canvas":{"elements":[{"text":"长乘宽"}]}}}],
              "progress":{"sceneIndex":0,"actionIndex":0}}
            """);
        when(courses.studySnapshot(anyString())).thenAnswer(call -> {
            ObjectNode snapshot = course.deepCopy(); snapshot.put("id", (String) call.getArgument(0)); return snapshot;
        });
        when(courses.withCurrentStudyContent(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                    "课程锁守卫必须在事务之前进入");
            return ((Supplier<?>) call.getArgument(3)).get();
        });
        modelCalls = new AtomicInteger(); modelAnswer = analysis();
        when(gateway.structured(anyString(), anyString(), anyString(), any(Duration.class), any())).thenAnswer(call -> {
            assertEquals(Duration.ofSeconds(45), call.getArgument(3)); modelCalls.incrementAndGet();
            Consumer<JsonNode> validator = call.getArgument(4); validator.accept(modelAnswer);
            return new ClassroomAiGateway.Result(modelAnswer.deepCopy(), json.createObjectNode().put("actualModel", "test-only").put("requestId", "test-request"));
        });
        doAnswer(call -> {
            ObjectNode scene = call.getArgument(1); scene.put("quizId", 42L);
            jdbc.update("INSERT INTO quiz_audit VALUES('paper','standalone')"); return null;
        }).when(quiz).attach(any(), any());
        when(quiz.submit(any(), anyList())).thenAnswer(call -> {
            jdbc.update("INSERT INTO quiz_audit VALUES('answer','确定作答')");
            return new QuizService.SubmitResult(42L, 1, 1, List.of(new QuizService.ResultItem(1, true, "对", "对", "长乘宽的关系正确")));
        });
    }

    @Test void firstAttemptIsImmutableAndSaveRequestIsIdempotent() {
        var first = create("request-1", "长乘宽", null);
        var duplicate = create("request-1", "长乘宽", null);
        assertEquals(first.path("id"), duplicate.path("id"));
        assertEquals(409, assertThrows(ClassroomException.class, () -> create("request-1", "长加宽", null)).status());
        var next = create("request-2", "长度和宽度相乘", first.path("id").asText());
        assertNotEquals(first.path("id"), next.path("id"));
        assertEquals("长乘宽", store.find(LearningFeedbackService.KIND, first.path("id").asText()).orElseThrow().path("understanding").asText());
        assertEquals(2, service.history("c", "s").path("attempts").size());
        verifyNoInteractions(quiz, gateway);
    }

    @Test void limitsAreCodePointBasedAndBlankOrOversizeInputDoesNotCreateRecord() {
        assertThrows(ClassroomException.class, () -> create("empty", "  ", null));
        assertThrows(ClassroomException.class, () -> create("long", "字".repeat(1201), null));
        assertThrows(ClassroomException.class, () -> service.create("c", new LearningFeedbackService.CreateRequest("s", "理解", "问".repeat(801), null, null, "check-point")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM edu_classroom_study_state", Integer.class));
        assertEquals("🙂".repeat(1200), create("emoji", "🙂".repeat(1200), null).path("understanding").asText());
    }

    @Test void parentMustMatchCourseSceneAndCurrentContent() {
        var first = create("one", "面积等于长乘宽", null);
        String parentId = first.path("id").asText();
        assertEquals(409, assertThrows(ClassroomException.class, () -> service.create("other-course",
                new LearningFeedbackService.CreateRequest("s", "理解", "", parentId, null, "two"))).status());
        course.put("sourceText", "资料已更新");
        assertEquals(409, assertThrows(ClassroomException.class, () -> create("updated", "新理解", parentId)).status());
    }

    @Test void expectedFingerprintAndCurrentSceneAreValidated() {
        assertEquals(409, assertThrows(ClassroomException.class, () -> service.create("c",
                new LearningFeedbackService.CreateRequest("s", "理解", "", null, "old-fingerprint", "one"))).status());
        assertEquals(404, assertThrows(ClassroomException.class, () -> service.history("c", "unknown-scene")).status());
    }

    @Test void checkPersistsVerifiedFeedbackAndReplaysWithoutSecondModelCall() {
        var first = create("save", "长度和宽度相乘", null);
        var checked = service.check(first.path("id").asText(), new LearningFeedbackService.CheckRequest("check"));
        assertEquals("checked", checked.path("status").asText());
        assertEquals("covered", checked.path("analysis").get(0).path("status").asText());
        assertFalse(checked.has("_modelEvidence")); assertFalse(checked.toString().contains("test-only"));
        assertEquals(checked, service.check(first.path("id").asText(), null));
        assertEquals(1, modelCalls.get());
        verifyNoInteractions(quiz);
    }

    @Test void unsupportedCitationCannotProduceMissingGoalOrMistake() {
        var first = create("save", "我还不会", null);
        ((ObjectNode) modelAnswer.path("analysis").get(0)).put("status", "missing").put("quote", "三角形面积公式不在资料里");
        var result = service.check(first.path("id").asText(), null);
        assertEquals("unconfirmed", result.path("status").asText());
        assertFalse(result.has("analysis")); assertFalse(result.has("suggestedGoals")); verifyNoInteractions(quiz);
    }

    @Test void unresolvedMeaningRemainsUncertainAndDoesNotCreateScores() {
        var first = create("save", "这两种说法可能不同", null);
        modelAnswer = json.createObjectNode().put("summary", "现有资料还无法判断");
        modelAnswer.set("sourceAssessment", consistent());
        modelAnswer.putArray("analysis").addObject().put("goal", "未提供的条件").put("status", "uncertain").put("feedback", "请补充资料");
        var result = service.check(first.path("id").asText(), null);
        assertEquals("uncertain", result.path("analysis").get(0).path("status").asText());
        assertTrue(result.path("suggestedGoals").isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_audit", Integer.class));
    }

    @Test void sourceChangeBeforeOrDuringModelCallMarksAttemptStale() {
        var first = create("save", "面积24", null);
        course.put("sourceText", "新资料：长方形面积是长乘宽");
        assertEquals("stale", service.check(first.path("id").asText(), null).path("status").asText());
        assertEquals(0, modelCalls.get());
        var next = create("save-new", "新资料的理解", null);
        doAnswer(call -> {
            course.put("sourceText", "又一次修改资料");
            return new ClassroomAiGateway.Result(analysis(), json.createObjectNode());
        }).when(gateway).structured(anyString(), anyString(), anyString(), any(Duration.class), any());
        var result = service.check(next.path("id").asText(), null);
        assertEquals("stale", result.path("status").asText()); assertFalse(result.has("analysis"));
    }

    @Test void progressUpdateDoesNotInvalidateFeedback() {
        var first = create("save", "面积24", null);
        ((ObjectNode) course.path("progress")).put("actionIndex", 99); course.put("revision", 102);
        assertEquals(first.path("contentFingerprint"), service.history("c", "s").path("contentFingerprint"));
        assertEquals("checked", service.check(first.path("id").asText(), null).path("status").asText());
    }

    @Test void interruptedCheckingStateAllowsManualRetryWithoutAutomaticModelRequest() {
        var first = create("save", "面积24", null); String id = first.path("id").asText();
        var interrupted = store.find(LearningFeedbackService.KIND, id).orElseThrow();
        interrupted.put("status", "checking").put("_checkStartedAt", java.time.Instant.now().minusSeconds(120).toString());
        store.save(LearningFeedbackService.KIND, id, "c", interrupted);
        assertEquals("unconfirmed", service.history("c", "s").path("attempts").get(0).path("status").asText());
        assertEquals(0, modelCalls.get());
        assertEquals("checked", service.check(id, null).path("status").asText());
        assertEquals(1, modelCalls.get());
    }

    @Test void hintsAreSequentialAnchoredAndEarlyLevelsDoNotRevealEntireSource() {
        var first = create("save", "面积怎么算", null); String id = first.path("id").asText();
        assertEquals(400, assertThrows(ClassroomException.class, () -> service.hint(id, new LearningFeedbackService.HintRequest(4))).status());
        for (int level = 1; level <= 3; level++) {
            var result = service.hint(id, new LearningFeedbackService.HintRequest(level));
            assertEquals(level, result.path("hintLevel").asInt());
            assertFalse(result.path("hints").toString().contains(course.path("sourceText").asText()));
            assertFalse(result.path("hints").get(level - 1).has("quote"));
        }
        verifyNoInteractions(gateway, quiz);
        modelAnswer = json.createObjectNode().put("text", "一种参考说法：长方形面积可以用长乘宽计算。")
                .put("anchorId", "source-1").put("quote", "长方形的面积等于长乘宽。");
        modelAnswer.set("sourceAssessment", consistent());
        var explained = service.hint(id, new LearningFeedbackService.HintRequest(4));
        assertEquals(4, explained.path("hintLevel").asInt()); assertTrue(explained.path("hints").get(3).has("quote"));
        service.hint(id, new LearningFeedbackService.HintRequest(4)); assertEquals(1, modelCalls.get());
    }

    @Test void validUncertainQuestionGetsHintsBeforeEarlierCoveredAnswer() {
        String questionQuote = "比较两个分数表示的实际面积时，必须先确认整体大小相同。";
        course.put("sourceText", "长方形的面积等于长乘宽。\n" + questionQuote);
        String id = create("hint-question", "面积用长乘宽，但我还不明白为什么要先看整体。", null).path("id").asText();
        modelAnswer = analysis();
        modelAnswer.withArray("analysis").addObject().put("goal", "比较时的整体条件").put("status", "uncertain")
                .put("feedback", "你明确提出了这个疑问，可以先回到资料核对。")
                .put("anchorId", "source-2").put("quote", questionQuote);
        var checked = service.check(id, null);
        assertEquals("covered", checked.path("analysis").get(0).path("status").asText());
        assertEquals("uncertain", checked.path("analysis").get(1).path("status").asText());
        for (int level = 1; level <= 3; level++) {
            var hinted = service.hint(id, new LearningFeedbackService.HintRequest(level));
            assertEquals("source-2", hinted.path("hints").get(level - 1).path("anchorId").asText());
            assertEquals("uncertain", hinted.path("analysis").get(1).path("status").asText());
            assertTrue(hinted.path("suggestedGoals").isEmpty(), "提示疑问不等于把疑问计成错误或薄弱点");
        }
        assertEquals(1, modelCalls.get(), "前三层提示不追加模型调用");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_audit", Integer.class)); verifyNoInteractions(quiz);
    }

    @Test void uncertainWithoutCitationDoesNotInventHintAnchor() {
        String id = create("hint-no-source", "面积用长乘宽，其他问题还不能确定。", null).path("id").asText();
        modelAnswer = analysis();
        modelAnswer.withArray("analysis").addObject().put("goal", "资料未涉及的疑问").put("status", "uncertain")
                .put("feedback", "缺少相关资料，不能作出判断。");
        service.check(id, null);
        var hinted = service.hint(id, new LearningFeedbackService.HintRequest(1));
        assertEquals("source-1", hinted.path("hints").get(0).path("anchorId").asText());
        assertFalse(hinted.path("analysis").get(1).has("anchorId"));
        assertFalse(hinted.path("analysis").get(1).has("quote"));
        assertEquals("uncertain", hinted.path("analysis").get(1).path("status").asText());
        verifyNoInteractions(quiz);
    }

    @Test void confirmedMissingPointKeepsPriorityOverUncertainAndCoveredPoints() {
        String comparison = "比较两个分数表示的实际面积时，必须先确认整体大小相同。";
        String units = "长和宽都以米为单位时，长方形面积应以平方米为单位。";
        course.put("sourceText", "长方形的面积等于长乘宽。\n" + comparison + "\n" + units);
        String id = create("hint-priority", "面积用长乘宽，整体条件还不确定。", null).path("id").asText();
        modelAnswer = analysis();
        modelAnswer.withArray("analysis").addObject().put("goal", "整体条件").put("status", "uncertain").put("feedback", "请回看条件")
                .put("anchorId", "source-2").put("quote", comparison);
        modelAnswer.withArray("analysis").addObject().put("goal", "面积单位").put("status", "missing").put("feedback", "还可以补上面积单位")
                .put("anchorId", "source-3").put("quote", units);
        service.check(id, null);
        var hinted = service.hint(id, new LearningFeedbackService.HintRequest(1));
        assertEquals("source-3", hinted.path("hints").get(0).path("anchorId").asText());
        assertEquals("uncertain", hinted.path("analysis").get(1).path("status").asText());
        assertEquals(1, hinted.path("suggestedGoals").size());
        assertEquals("面积单位", hinted.path("suggestedGoals").get(0).asText()); verifyNoInteractions(quiz);
    }

    @Test void noSourceSkipsModelAndDoesNotAssignWrongState() {
        course.remove("sourceText"); ((ObjectNode) course.path("scenes").get(0).path("content")).remove("canvas");
        var first = create("save", "我想核对", null);
        assertEquals("unconfirmed", service.check(first.path("id").asText(), null).path("status").asText());
        assertEquals(0, modelCalls.get()); verifyNoInteractions(quiz);
    }

    @Test void selfCheckPreparationDoesNotCreatePaperOrExposeAnswer() {
        var first = create("save", "面积长乘宽", null); modelAnswer = question();
        var prepared = service.selfCheck(first.path("id").asText(), null);
        assertEquals("judge", prepared.path("selfCheck").path("type").asText());
        assertFalse(prepared.path("selfCheck").has("answer")); assertFalse(prepared.path("selfCheck").has("analysis"));
        service.selfCheck(first.path("id").asText(), null); assertEquals(1, modelCalls.get());
        verifyNoInteractions(quiz);
    }

    @Test void invalidAnswerDoesNotPoisonIdempotencySlot() {
        var first = create("save", "理解", null); modelAnswer = question(); String id = first.path("id").asText();
        service.selfCheck(id, null);
        assertEquals(400, assertThrows(ClassroomException.class, () -> service.selfCheck(id, new LearningFeedbackService.SelfCheckRequest("随意文字", "bad"))).status());
        assertFalse(store.find(LearningFeedbackService.KIND, id).orElseThrow().has("_pendingSubmission"));
        assertTrue(service.selfCheck(id, new LearningFeedbackService.SelfCheckRequest("对", "good")).path("selfCheckResult").path("correct").asBoolean());
    }

    @Test void realSubmissionScoresOnceAndCannotMutateCourseOrInitialUnderstanding() {
        var first = create("save", "首次表述", null); modelAnswer = question(); String id = first.path("id").asText();
        ObjectNode courseBefore = course.deepCopy(); service.selfCheck(id, null);
        var request = new LearningFeedbackService.SelfCheckRequest("对", "submit-once");
        var scored = service.selfCheck(id, request);
        assertTrue(scored.path("selfCheckResult").path("correct").asBoolean());
        assertEquals("对", scored.path("selfCheckResult").path("correctAnswer").asText());
        assertEquals("submit-once", scored.path("selfCheckSubmissionId").asText());
        assertEquals(scored, service.selfCheck(id, request));
        verify(quiz, times(1)).submit(any(), anyList());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_audit", Integer.class));
        assertEquals(409, assertThrows(ClassroomException.class, () -> service.selfCheck(id, new LearningFeedbackService.SelfCheckRequest("错", "submit-once"))).status());
        assertEquals(courseBefore, course); assertEquals("首次表述", scored.path("understanding").asText());
    }

    @Test void uncertainGradeRollsBackPaperAndAnswerButKeepsSubmissionForSamePayloadRetry() {
        var first = create("save", "理解", null); modelAnswer = question(); String id = first.path("id").asText(); service.selfCheck(id, null);
        doAnswer(call -> {
            jdbc.update("INSERT INTO quiz_audit VALUES('answer','should-rollback')");
            throw new ClassroomException(502, "未确定");
        }).when(quiz).submit(any(), anyList());
        var request = new LearningFeedbackService.SelfCheckRequest("对", "retry-same");
        var failed = service.selfCheck(id, request);
        assertTrue(failed.path("selfCheckResult").path("correct").isNull());
        assertFalse(failed.path("selfCheckResult").has("correctAnswer"));
        assertEquals("retry-same", failed.path("selfCheckSubmissionId").asText());
        assertEquals("对", failed.path("selfCheckAnswer").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_audit", Integer.class));
        assertFalse(store.find(LearningFeedbackService.KIND, id).orElseThrow().path("_selfCheckScene").has("quizId"));
        assertEquals(409, assertThrows(ClassroomException.class, () -> service.selfCheck(id, new LearningFeedbackService.SelfCheckRequest("错", "replacement"))).status());
        doAnswer(call -> {
            jdbc.update("INSERT INTO quiz_audit VALUES('answer','confirmed')");
            return new QuizService.SubmitResult(42L, 1, 1, List.of(new QuizService.ResultItem(1, true, "对", "对", "确认")));
        }).when(quiz).submit(any(), anyList());
        assertTrue(service.selfCheck(id, request).path("selfCheckResult").path("correct").asBoolean());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_audit", Integer.class));
    }

    /** 此用例只校验防御性二次读取分支；真正的数据库并发由LearningFeedbackContentConcurrencyTest覆盖。 */
    @Test void defensiveFingerprintReadRejectsChangedTestSnapshot() {
        var first = create("save", "理解", null); modelAnswer = question(); String id = first.path("id").asText(); service.selfCheck(id, null);
        doAnswer(call -> {
            jdbc.update("INSERT INTO quiz_audit VALUES('answer','should-rollback')"); course.put("sourceText", "已修改资料");
            return new QuizService.SubmitResult(42L, 1, 1, List.of(new QuizService.ResultItem(1, true, "对", "对", "确认")));
        }).when(quiz).submit(any(), anyList());
        assertEquals("stale", service.selfCheck(id, new LearningFeedbackService.SelfCheckRequest("对", "edit-race")).path("status").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_audit", Integer.class));
    }

    @Test void historyIsSceneBoundAndReturnsAtMostTenNewestAttempts() throws Exception {
        for (int i = 0; i < 12; i++) create("save-" + i, "理解" + i, null);
        var other = course.withArray("scenes").addObject().put("id", "s2").put("type", "slide").put("title", "第二课");
        other.putObject("content").put("type", "slide");
        service.create("c", new LearningFeedbackService.CreateRequest("s2", "第二课理解", "", null, null, "another-scene"));
        assertEquals(10, service.history("c", "s").path("attempts").size());
        assertEquals(1, service.history("c", "s2").path("attempts").size());
        course.put("sourceText", "更新后资料");
        assertEquals("stale", service.history("c", "s2").path("attempts").get(0).path("status").asText());
    }

    @Test void conflictingSourcesMakeEveryJudgmentUncertainAndBlockSelfCheckAndFinalHint() {
        course.put("sourceText", "长方形的面积等于长乘宽。资料乙却说长方形的面积等于长加宽。");
        var first = create("conflicting", "面积长乘宽", null); String id = first.path("id").asText();
        modelAnswer = analysis(); modelAnswer.set("sourceAssessment", conflicting());
        modelAnswer.withArray("analysis").addObject().put("goal", "另一资料的说法").put("status", "uncertain").put("feedback", "资料存在矛盾");
        var checked = service.check(id, null);
        checked.path("analysis").forEach(item -> assertEquals("uncertain", item.path("status").asText()));
        assertTrue(checked.path("suggestedGoals").isEmpty());
        assertEquals("conflicting", checked.path("sourceAssessment").path("status").asText());
        assertFalse(service.selfCheck(id, null).has("selfCheck"));
        for (int level = 1; level <= 3; level++) service.hint(id, new LearningFeedbackService.HintRequest(level));
        var hint = service.hint(id, new LearningFeedbackService.HintRequest(4));
        assertEquals(3, hint.path("hintLevel").asInt()); assertTrue(hint.path("error").asText().contains("先核对资料"));
        assertEquals(1, modelCalls.get(), "已经知道来源冲突时不追加模型请求"); verifyNoInteractions(quiz);
    }

    @Test void sourceConflictDetectedDuringDirectQuestionOrExamplePreparationNeverCreatesAnswer() {
        course.put("sourceText", "长方形的面积等于长乘宽。资料乙却说长方形的面积等于长加宽。");
        modelAnswer = json.createObjectNode().set("sourceAssessment", conflicting());
        String firstId = create("question-conflict", "面积关系", null).path("id").asText();
        var prepared = service.selfCheck(firstId, null);
        assertFalse(prepared.has("selfCheck")); assertTrue(prepared.path("error").asText().contains("先核对资料"));
        String secondId = create("example-conflict", "面积关系", null).path("id").asText();
        for (int level = 1; level <= 3; level++) service.hint(secondId, new LearningFeedbackService.HintRequest(level));
        var example = service.hint(secondId, new LearningFeedbackService.HintRequest(4));
        assertEquals(3, example.path("hints").size());
        assertEquals("conflicting", example.path("sourceAssessment").path("status").asText());
        assertEquals(2, modelCalls.get()); verifyNoInteractions(quiz);
    }

    @Test void newlyRecognizedSourceConflictInvalidatesUnsubmittedCachedQuestion() {
        course.put("sourceText", "长方形的面积等于长乘宽。资料乙却说长方形的面积等于长加宽。");
        String id = create("late-conflict", "面积关系", null).path("id").asText();
        modelAnswer = question(); assertTrue(service.selfCheck(id, null).has("selfCheck"));
        modelAnswer = analysis(); modelAnswer.set("sourceAssessment", conflicting());
        assertFalse(service.check(id, null).has("selfCheck"));
        var refused = service.selfCheck(id, new LearningFeedbackService.SelfCheckRequest("对", "should-not-grade"));
        assertFalse(refused.has("selfCheckResult")); assertTrue(refused.path("error").asText().contains("先核对资料"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_audit", Integer.class)); verifyNoInteractions(quiz);
    }

    ObjectNode create(String requestId, String understanding, String parent) {
        String fingerprint = ClassroomStudyContext.from(course, "s").fingerprint();
        return service.create("c", new LearningFeedbackService.CreateRequest("s", understanding, "面积关系", parent, fingerprint, requestId));
    }
    ObjectNode analysis() {
        ObjectNode answer = json.createObjectNode().put("summary", "你表达了相乘关系");
        answer.set("sourceAssessment", consistent());
        answer.putArray("analysis").addObject().put("goal", "长方形面积").put("status", "covered").put("feedback", "自己的说法与资料一致")
                .put("anchorId", "source-1").put("quote", "长方形的面积等于长乘宽。");
        return answer;
    }
    ObjectNode question() {
        ObjectNode answer = json.createObjectNode().put("anchorId", "source-1").put("quote", "长方形的面积等于长乘宽。");
        answer.set("sourceAssessment", consistent());
        answer.putObject("question").put("type", "judge").put("question", "一块长方形菜地，可以用长和宽相乘求面积，对吗？")
                .put("answer", "对").put("analysis", "资料中的面积公式是长乘宽。");
        return answer;
    }
    ObjectNode consistent() {
        ObjectNode assessment = json.createObjectNode().put("status", "consistent").put("reason", "当前资料对面积关系的说明一致");
        assessment.putArray("evidence").addObject().put("anchorId", "source-1").put("quote", "长方形的面积等于长乘宽。");
        return assessment;
    }
    ObjectNode conflicting() {
        ObjectNode assessment = json.createObjectNode().put("status", "conflicting").put("reason", "相同条件下分别要求相乘和相加，资料不能同时成立");
        assessment.putArray("evidence").addObject().put("anchorId", "source-1").put("quote", "长方形的面积等于长乘宽。");
        assessment.withArray("evidence").addObject().put("anchorId", "source-1").put("quote", "资料乙却说长方形的面积等于长加宽。");
        return assessment;
    }
}
