package com.demo.edu.classroom.study.feedback;

import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.classroom.ClassroomCourseService;
import com.demo.edu.classroom.ClassroomEngine;
import com.demo.edu.classroom.ClassroomQuizAdapter;
import com.demo.edu.classroom.ClassroomTutorModel;
import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.service.preprocessing.FileService;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用真实CourseService课程锁、真实SQL存储和REPEATABLE_READ事务复现提交/编辑并发。
 * mapper mock仅将接口桥接到JdbcTemplate，课程没有放进内存Map；测试不把mock变量变化当数据库隔离结论。
 */
class LearningFeedbackContentConcurrencyTest {
    final ObjectMapper json = new ObjectMapper();
    final String courseId = UUID.randomUUID().toString();
    final ExecutorService workers = Executors.newFixedThreadPool(2);
    final List<String> events = new CopyOnWriteArrayList<>();
    final ClassroomQuizAdapter quizzes = mock(ClassroomQuizAdapter.class);
    final ClassroomAiGateway gateway = mock(ClassroomAiGateway.class);
    JdbcTemplate jdbc;
    TransactionTemplate gradingTransaction;
    ClassroomCourseService courses;
    LearningFeedbackService feedback;

    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:study_concurrent_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE edu_learning_course(id VARCHAR(64) PRIMARY KEY,document CLOB)");
        jdbc.execute("CREATE TABLE edu_classroom_study_state(kind VARCHAR(40),entry_id VARCHAR(160),course_id VARCHAR(64),scene_id VARCHAR(80) DEFAULT '',document CLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(kind,entry_id))");
        jdbc.execute("CREATE TABLE quiz_audit(kind VARCHAR(40))");
        var manager = new DataSourceTransactionManager(ds);
        gradingTransaction = new TransactionTemplate(manager);
        gradingTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var mapper = mock(ClassroomCourseMapper.class);
        when(mapper.find(anyString())).thenAnswer(call -> jdbc.queryForObject("SELECT document FROM edu_learning_course WHERE id=?", String.class,
                new Object[]{call.getArgument(0, String.class)}));
        when(mapper.save(anyString(), anyString())).thenAnswer(call -> {
            // 明确Object[]避免Mockito泛型参数被推断到jdbc.update(String,Object[],int[])重载。
            int changed = jdbc.update("UPDATE edu_learning_course SET document=? WHERE id=?",
                    new Object[]{call.getArgument(1, String.class), call.getArgument(0, String.class)});
            events.add("course-written"); return changed;
        });
        courses = new ClassroomCourseService(mapper, json, mock(ClassroomEngine.class), mock(ClassroomTutorModel.class), quizzes,
                mock(FileService.class), mock(DocKnowledgeRegistry.class), manager);
        feedback = new LearningFeedbackService(courses, new ClassroomStudyStore(jdbc, json), gateway, quizzes, json, gradingTransaction);
        ObjectNode course = (ObjectNode) json.readTree("""
            {"title":"面积","topic":"面积","grade":5,"subject":"数学","direction":"academic","status":"ready","revision":0,
              "scenes":[{"id":"s","type":"interactive","title":"面积观察","order":0,"actions":[],
                "content":{"type":"interactive","html":"<p>长方形面积等于长乘宽。</p>"}}],
              "outlines":[],"progress":{"sceneIndex":0,"actionIndex":0}}
            """);
        course.put("id", courseId);
        jdbc.update("INSERT INTO edu_learning_course VALUES(?,?)", courseId, course.toString());
        ObjectNode prepared = json.createObjectNode().put("anchorId", "page-1").put("quote", "长方形面积等于长乘宽。");
        prepared.putObject("sourceAssessment").put("status", "consistent").put("reason", "资料给出的计算关系一致")
                .putArray("evidence").addObject().put("anchorId", "page-1").put("quote", "长方形面积等于长乘宽。");
        prepared.putObject("question").put("type", "judge").put("question", "长方形的面积可以用长乘宽计算，对吗？")
                .put("answer", "对").put("analysis", "原资料给出长乘宽关系。");
        when(gateway.structured(anyString(), anyString(), anyString(), any(Duration.class), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "模型调用必须在事务之外");
            ((Consumer<JsonNode>) call.getArgument(4)).accept(prepared);
            return new ClassroomAiGateway.Result(prepared.deepCopy(), json.createObjectNode());
        });
        doAnswer(call -> {
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            ((ObjectNode) call.getArgument(1)).put("quizId", 7L);
            jdbc.update("INSERT INTO quiz_audit VALUES('paper')"); return null;
        }).when(quizzes).attach(any(), any());
    }

    @AfterEach void close() {
        workers.shutdownNow();
        if (courses != null) courses.close();
    }

    @Test void editingCannotCommitBetweenFingerprintCheckAndObjectiveGradeCommit() throws Exception {
        String id = prepare();
        CountDownLatch grading = new CountDownLatch(1), finishGrade = new CountDownLatch(1), editorStarted = new CountDownLatch(1);
        when(quizzes.submit(any(), anyList())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("INSERT INTO quiz_audit VALUES('answer')"); events.add("grade-entered"); grading.countDown();
            assertTrue(finishGrade.await(5, TimeUnit.SECONDS));
            return new QuizService.SubmitResult(7L, 1, 1, List.of(new QuizService.ResultItem(1, true, "对", "对", "关系一致")));
        });
        var submitted = workers.submit(() -> feedback.selfCheck(id, new LearningFeedbackService.SelfCheckRequest("对", "concurrent-submit")));
        assertTrue(grading.await(5, TimeUnit.SECONDS));
        var edited = workers.submit(() -> {
            editorStarted.countDown();
            ObjectNode changed = json.createObjectNode().put("type", "interactive").put("html", "<p>新课件：比较分数须先统一整体。</p>");
            return courses.editScene(courseId, "s", new ClassroomCourseService.SceneEditRequest(0L, changed, json.createArrayNode()));
        });
        try {
            assertTrue(editorStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> edited.get(200, TimeUnit.MILLISECONDS), "编辑必须等待短判分事务提交并释放同一课程锁");
            String during = jdbc.queryForObject("SELECT document FROM edu_learning_course WHERE id=?", String.class, courseId);
            assertFalse(during.contains("新课件"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_audit", Integer.class), "其他连接不能看到未提交成绩");
        } finally { finishGrade.countDown(); }
        assertTrue(submitted.get(5, TimeUnit.SECONDS).path("selfCheckResult").path("correct").asBoolean());
        assertEquals(1, edited.get(5, TimeUnit.SECONDS).path("revision").asLong());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_audit", Integer.class));
        assertEquals(List.of("grade-entered", "course-written"), events);
        assertEquals("stale", feedback.history(courseId, "s").path("attempts").get(0).path("status").asText());
    }

    @Test void courseGuardIsAcquiredOutsideTransactionAndRejectsChangedPersistentContent() {
        String initial = ClassroomStudyContext.from(courses.studySnapshot(courseId), "s").fingerprint();
        courses.withCurrentStudyContent(courseId, "s", initial, () -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "课程锁必须先于事务");
            return gradingTransaction.execute(tx -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                jdbc.update("INSERT INTO quiz_audit VALUES('proof')"); return true;
            });
        });
        ObjectNode changed = json.createObjectNode().put("type", "interactive").put("html", "<p>真实数据库中的更新资料。</p>");
        courses.editScene(courseId, "s", new ClassroomCourseService.SceneEditRequest(0L, changed, json.createArrayNode()));
        assertEquals(409, assertThrows(com.demo.edu.classroom.ClassroomException.class, () ->
                courses.withCurrentStudyContent(courseId, "s", initial, () -> {
                    fail("旧指纹不得进入事务操作"); return null;
                })).status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_audit", Integer.class));
    }

    private String prepare() {
        String fingerprint = ClassroomStudyContext.from(courses.studySnapshot(courseId), "s").fingerprint();
        var saved = feedback.create(courseId, new LearningFeedbackService.CreateRequest("s", "面积等于长度乘宽度", "", null, fingerprint, "save"));
        String id = saved.path("id").asText();
        assertTrue(feedback.selfCheck(id, null).has("selfCheck"));
        return id;
    }
}
