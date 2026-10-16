package com.demo.edu.classroom;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.entity.QuizPaperEntity;
import com.demo.entity.QuizQuestionEntity;
import com.demo.mapper.QuizPaperMapper;
import com.demo.mapper.QuizQuestionMapper;
import com.demo.study.quiz.QuizMutationGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实增量存储和事务，验证原记录只读、原子登记与提交前题库锁的持续持有。 */
class ClassroomQuizRevisionRegistryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ClassroomCourseMapper courses = mock(ClassroomCourseMapper.class);
    private final QuizPaperMapper papers = mock(QuizPaperMapper.class);
    private final QuizQuestionMapper questions = mock(QuizQuestionMapper.class);
    private final Map<String, String> documents = new HashMap<>();
    private final Map<Long, QuizPaperEntity> paperRows = new HashMap<>();
    private final Map<Long, List<QuizQuestionEntity>> questionRows = new HashMap<>();
    private JdbcDataSource data;
    private JdbcTemplate jdbc;
    private ClassroomStudyStore store;
    private ClassroomQuizRevisionRegistry registry;

    @BeforeEach void setUp() {
        data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE edu_classroom_study_state(kind VARCHAR(40),entry_id VARCHAR(160),course_id VARCHAR(64),scene_id VARCHAR(80),document CLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(kind,entry_id))");
        store = spy(new ClassroomStudyStore(jdbc, json));
        registry = new ClassroomQuizRevisionRegistry(courses, papers, questions, store, json, new DataSourceTransactionManager(data));
        when(courses.find(anyString())).thenAnswer(call -> documents.get(call.getArgument(0)));
        when(papers.selectById(anyLong())).thenAnswer(call -> paperRows.get(call.getArgument(0)));
        when(questions.selectList(any())).thenAnswer(call -> {
            QueryWrapper<QuizQuestionEntity> wrapper = call.getArgument(0);
            wrapper.getSqlSegment();
            return questionRows.get((Long) wrapper.getParamNameValuePairs().get("MPGENVAL1"));
        });
        addCourse("old", "old-scene", 101L, 2);
        addCourse("new", "new-scene", 102L, 2);
        addCourse("final", "final-scene", 103L, 3);
    }

    @Test void registrationCreatesOnlyFixedPointersAndReplayReturnsTheOriginalRecord() throws Exception {
        Map<String, String> before = Map.copyOf(documents);
        var request = request("r-1", "old", "old-scene", "new", "new-scene");
        ObjectNode first = registry.register(request);
        assertEquals(101, first.path("oldPaperId").asLong());
        assertEquals(102, first.path("newPaperId").asLong());
        assertFalse(first.path("createdAt").asText().isBlank());
        ObjectNode replay=registry.register(request);
        // 首次put(long)产生LongNode，JSON持久化读回小整数会产生IntNode；HTTP契约比较JSON值，不比较内存数值节点类型。
        assertEquals(json.readTree(first.toString()),json.readTree(replay.toString()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM edu_classroom_study_state", Integer.class));
        assertEquals(before, documents);
        verify(courses, never()).save(any(), any());
        verify(papers, never()).insert(any(QuizPaperEntity.class));
        verify(questions, never()).insert(any(QuizQuestionEntity.class));
        // 返回副本，调用方修改 HTTP 响应对象不能改写持久化指针。
        first.put("reason", "响应被调用者改写");
        assertEquals("纠正已有练习", registry.register(request).path("reason").asText());
    }

    @Test void rejectsAnOverwrittenPointerOrARequestIdReusedForAnotherPayload() {
        registry.register(request("r-1", "old", "old-scene", "new", "new-scene"));
        assertStatus(409, () -> registry.register(request("r-2", "old", "old-scene", "final", "final-scene")));
        assertStatus(409, () -> registry.register(request("r-1", "new", "new-scene", "final", "final-scene")));
        var changedReason = new ClassroomQuizRevisionRegistry.RegisterRequest("old", "old-scene", "new", "new-scene", "不同原因", "r-1");
        assertStatus(409, () -> registry.register(changedReason));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM edu_classroom_study_state", Integer.class));
    }

    @Test void rejectsSelfReferenceAndCyclesAndResolvesTheLatestTargetForPublicReadsAndGrading() {
        assertStatus(409, () -> registry.register(request("self", "old", "old-scene", "old", "old-scene")));
        registry.register(request("r-1", "old", "old-scene", "new", "new-scene"));
        registry.register(request("r-2", "new", "new-scene", "final", "final-scene"));
        assertStatus(409, () -> registry.register(request("cycle", "final", "final-scene", "old", "old-scene")));
        ObjectNode response = registry.publicRevisions("old");
        assertEquals(Set.of("courseId", "revisions"), fields(response));
        assertEquals(1, response.path("revisions").size());
        JsonNode revision = response.path("revisions").get(0);
        assertEquals(Set.of("sceneId", "newCourseId", "newSceneId", "reason", "createdAt"), fields(revision));
        assertEquals("old-scene", revision.path("sceneId").asText());
        assertEquals("final", revision.path("newCourseId").asText());
        assertEquals("final-scene", revision.path("newSceneId").asText());
        ClassroomException blocked = assertThrows(ClassroomException.class, () -> registry.assertScorable(101L));
        assertEquals(409, blocked.status());
        assertTrue(blocked.getMessage().contains("修订"));
        assertTrue(blocked.getMessage().contains("final-scene"));
        assertDoesNotThrow(() -> registry.assertScorable(103L));
        assertEquals(0, registry.publicRevisions("final").path("revisions").size());
    }

    @Test void mistakeProjectionReturnsNullOrOnlyTheFinalTargetWithoutInternalIdentifiers() {
        assertNull(registry.revisionForPaper(101L));
        ObjectNode original = registry.register(request("r-1", "old", "old-scene", "new", "new-scene"));
        registry.register(request("r-2", "new", "new-scene", "final", "final-scene"));
        ObjectNode projection = registry.revisionForPaper(101L);
        assertEquals(Set.of("newCourseId", "newSceneId", "reason", "createdAt"), fields(projection));
        assertEquals("final", projection.path("newCourseId").asText());
        assertEquals("final-scene", projection.path("newSceneId").asText());
        assertEquals(original.path("reason"), projection.path("reason"));
        assertEquals(original.path("createdAt"), projection.path("createdAt"));
        assertNull(registry.revisionForPaper(103L));
        projection.put("reason", "仅修改返回副本");
        assertEquals(original.path("reason"), registry.revisionForPaper(101L).path("reason"));
        assertStatus(400, () -> registry.revisionForPaper(0L));
        assertStatus(400, () -> registry.revisionForPaper(null));
    }

    @Test void mistakeProjectionRejectsCorruptSourceOwnershipInsteadOfPublishingAnUntrustedJump() {
        ObjectNode corrupt = pointer("old", "old-scene", 999L, "new", "new-scene", 102L);
        store.create(ClassroomQuizRevisionRegistry.KIND, "101", "old", corrupt);
        assertStatus(500, () -> registry.revisionForPaper(101L));
        assertStatus(500, () -> registry.publicRevisions("old"));
    }

    @Test void targetAndSourceMustBeExistingQuizPagesWithMatchingOwnedPapersAndQuestionCounts() {
        assertStatus(404, () -> registry.register(request("missing", "old", "old-scene", "missing", "new-scene")));
        assertStatus(404, () -> registry.register(request("wrong-scene", "old", "new-scene", "new", "new-scene")));
        QuizPaperEntity target = paperRows.get(102L);
        target.setUserId("someone-else");
        assertStatus(409, () -> registry.register(request("owner", "old", "old-scene", "new", "new-scene")));
        target.setUserId(ClassroomCourseService.LEARNER_ID);
        target.setSourceFileName("classroom:other:new-scene");
        assertStatus(409, () -> registry.register(request("source", "old", "old-scene", "new", "new-scene")));
        target.setSourceFileName("classroom:new:new-scene");
        target.setQuestionCount(3);
        assertStatus(409, () -> registry.register(request("count", "old", "old-scene", "new", "new-scene")));
        target.setQuestionCount(2);
        target.setDeleted(1);
        assertStatus(409, () -> registry.register(request("deleted", "old", "old-scene", "new", "new-scene")));
        target.setDeleted(0);
        questionRows.get(101L).getFirst().setPaperId(102L);
        assertStatus(409, () -> registry.register(request("row-owner", "old", "old-scene", "new", "new-scene")));
        questionRows.get(101L).getFirst().setPaperId(101L);
        questionRows.get(102L).removeLast();
        assertStatus(409, () -> registry.register(request("row-count", "old", "old-scene", "new", "new-scene")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM edu_classroom_study_state", Integer.class));
    }

    @Test void rejectsNonQuizUnattachedAndDuplicateSceneRecordsWithoutWriting() throws Exception {
        String original = documents.get("new");
        ObjectNode doc = (ObjectNode) json.readTree(original);
        ((ObjectNode) doc.path("scenes").get(0)).put("type", "slide");
        documents.put("new", doc.toString());
        assertStatus(422, () -> registry.register(request("slide", "old", "old-scene", "new", "new-scene")));
        doc = (ObjectNode) json.readTree(original);
        ((ObjectNode) doc.path("scenes").get(0)).remove("quizId");
        documents.put("new", doc.toString());
        assertStatus(422, () -> registry.register(request("unattached", "old", "old-scene", "new", "new-scene")));
        doc = (ObjectNode) json.readTree(original);
        doc.withArray("scenes").add(doc.path("scenes").get(0).deepCopy());
        documents.put("new", doc.toString());
        assertStatus(409, () -> registry.register(request("duplicate", "old", "old-scene", "new", "new-scene")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM edu_classroom_study_state", Integer.class));
    }

    @Test void indexInsertConflictRollsBackTheMainPointerInsteadOfLeavingAHalfRegistration() {
        doReturn(false).when(store).create(eq(ClassroomQuizRevisionRegistry.REQUEST_KIND), eq("collision"), anyString(), any());
        assertStatus(409, () -> registry.register(request("collision", "old", "old-scene", "new", "new-scene")));
        assertTrue(store.find(ClassroomQuizRevisionRegistry.KIND, "101").isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM edu_classroom_study_state", Integer.class));
        assertDoesNotThrow(() -> registry.assertScorable(101L));
    }

    @Test void readAndScoringRejectCyclesAndChainsLongerThanTheBound() {
        ObjectNode first = pointer("old", "old-scene", 101L, "new", "new-scene", 102L);
        store.create(ClassroomQuizRevisionRegistry.KIND, "101", "old", first);
        store.create(ClassroomQuizRevisionRegistry.KIND, "102", "new", pointer("new", "new-scene", 102L, "old", "old-scene", 101L));
        assertStatus(409, () -> registry.publicRevisions("old"));
        assertStatus(409, () -> registry.assertScorable(101L));
        assertStatus(409, () -> registry.revisionForPaper(101L));
        for (int i = 0; i < 65; i++) store.create(ClassroomQuizRevisionRegistry.KIND, Integer.toString(1000 + i), "chain-" + i,
                pointer("chain-" + i, "s-" + i, 1000 + i, "chain-" + (i + 1), "s-" + (i + 1), 1001 + i));
        ClassroomException longChain = assertThrows(ClassroomException.class, () -> registry.assertScorable(1000L));
        assertTrue(longChain.getMessage().contains("64"));
    }

    @Test void paperLockRemainsHeldWhileTheRealDatabaseTransactionCommits() throws Exception {
        CountDownLatch committing = new CountDownLatch(1), releaseCommit = new CountDownLatch(1), gotPaper = new CountDownLatch(1);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(data) {
            @Override protected void doCommit(DefaultTransactionStatus status) {
                committing.countDown();
                try {
                    if (!releaseCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放提交");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                super.doCommit(status);
            }
        };
        var guarded = new ClassroomQuizRevisionRegistry(courses, papers, questions, store, json, manager);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var registration = workers.submit(() -> guarded.register(request("commit", "old", "old-scene", "new", "new-scene")));
            assertTrue(committing.await(5, TimeUnit.SECONDS));
            try {
                var scoring = workers.submit(() -> QuizMutationGuard.withPaper(101L, () -> {
                    gotPaper.countDown();
                    return assertThrows(ClassroomException.class, () -> guarded.assertScorable(101L)).status();
                }));
                assertFalse(gotPaper.await(100, TimeUnit.MILLISECONDS), "提交完成前不可进入原卷判分");
                releaseCommit.countDown();
                assertEquals(101L, registration.get(5, TimeUnit.SECONDS).path("oldPaperId").asLong());
                assertEquals(409, scoring.get(5, TimeUnit.SECONDS));
            } finally { releaseCommit.countDown(); }
        }
    }

    private void addCourse(String courseId, String sceneId, long paperId, int count) {
        ObjectNode document = json.createObjectNode().put("id", courseId);
        ObjectNode scene = document.putArray("scenes").addObject().put("id", sceneId).put("type", "quiz").put("quizId", paperId);
        var questionJson = scene.putObject("content").put("type", "quiz").putArray("questions");
        List<QuizQuestionEntity> rows = new ArrayList<>();
        for (int no = 1; no <= count; no++) {
            questionJson.addObject().put("id", sceneId + "-q" + no).put("no", no).put("question", "题目" + no);
            QuizQuestionEntity row = new QuizQuestionEntity();
            row.setId(paperId * 100 + no); row.setPaperId(paperId); row.setQuestionNo(no); row.setAnswer("A");
            rows.add(row);
        }
        documents.put(courseId, document.toString());
        QuizPaperEntity paper = new QuizPaperEntity();
        paper.setId(paperId); paper.setUserId(ClassroomCourseService.LEARNER_ID); paper.setFileType("classroom");
        paper.setSourceFileName("classroom:" + courseId + ":" + sceneId); paper.setDeleted(0); paper.setQuestionCount(count);
        paperRows.put(paperId, paper); questionRows.put(paperId, rows);
    }

    private static ClassroomQuizRevisionRegistry.RegisterRequest request(String id, String oldCourse, String oldScene, String newCourse, String newScene) {
        return new ClassroomQuizRevisionRegistry.RegisterRequest(oldCourse, oldScene, newCourse, newScene, "纠正已有练习", id);
    }

    private ObjectNode pointer(String oldCourse, String oldScene, long oldPaper, String newCourse, String newScene, long newPaper) {
        return json.createObjectNode().put("oldCourseId", oldCourse).put("oldSceneId", oldScene).put("oldPaperId", oldPaper)
                .put("newCourseId", newCourse).put("newSceneId", newScene).put("newPaperId", newPaper)
                .put("reason", "纠正已有练习").put("createdAt", "2026-10-16T00:00:00Z").put("requestId", "r-" + oldPaper);
    }

    private static Set<String> fields(JsonNode value) {
        Set<String> fields = new HashSet<>(); value.fieldNames().forEachRemaining(fields::add); return fields;
    }

    private static void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ClassroomException.class, action).status());
    }
}
