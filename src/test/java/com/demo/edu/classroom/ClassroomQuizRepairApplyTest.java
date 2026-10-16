package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.entity.QuizQuestionEntity;
import com.demo.mapper.QuizQuestionMapper;
import com.demo.service.preprocessing.FileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 修题的落库路径。这是**会写已判分数据**的操作，边界必须钉死：
 * 只改被标记的题、题号题数不变、课件与题库同事务一致、缺同步器宁可整体失败。
 */
class ClassroomQuizRepairApplyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> documents = new HashMap<>();
    private QuizQuestionMapper questions;
    private ClassroomCourseService service;

    @BeforeEach void setup() {
        ClassroomCourseMapper mapper = mock(ClassroomCourseMapper.class);
        questions = mock(QuizQuestionMapper.class);
        when(mapper.find(anyString())).thenAnswer(i -> documents.get(i.getArgument(0)));
        when(mapper.list()).thenAnswer(i -> new ArrayList<>(documents.values()));
        when(mapper.save(anyString(), anyString())).thenAnswer(i -> { documents.put(i.getArgument(0), i.getArgument(1)); return 1; });
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        service = new ClassroomCourseService(mapper, json, mock(ClassroomEngine.class), mock(ClassroomTutorModel.class),
                mock(ClassroomQuizAdapter.class), mock(FileService.class), mock(DocKnowledgeRegistry.class), tx, new NoopExecutor());
        service.setQuizQuestionMapper(questions);
    }

    @Test void appliesOnlyTheMarkedQuestionAndKeepsQuestionNumbersStable() throws Exception {
        store();
        QuizQuestionEntity row = new QuizQuestionEntity();
        row.setId(11L); row.setPaperId(7L); row.setQuestionNo(1); row.setQType("single");
        row.setStem("旧题干"); row.setOptions("[\"A. 48\",\"B. 240\"]"); row.setAnswer("B");
        row.setExplanation("旧解析"); row.setHasAnswer(1);
        when(questions.selectOne(any())).thenReturn(row);

        ObjectNode result = service.applyQuizRepairs("c1", Map.of("s1", plan().path("changes")));
        assertEquals(1, result.path("applied").asInt(), "action=none 是交人工，不得落地");

        ArrayNode list = (ArrayNode) json.readTree(documents.get("c1")).path("scenes").get(0).path("content").path("questions");
        // 题数不得改变：分数分母取自题库，改题数会让学生的成绩分母错位。
        assertEquals(2, list.size());
        assertEquals(1, list.get(0).path("no").asInt());
        assertEquals(2, list.get(1).path("no").asInt());
        assertEquals("长方体的长是10厘米、宽是4厘米、高是2厘米，体积是多少？", list.get(0).path("question").asText());
        assertEquals(List.of("A"), json.convertValue(list.get(0).path("answer"), List.class));
        assertTrue(list.get(0).path("hasAnswer").asBoolean());
        // 未被标记的题必须一字未动。
        assertEquals("未受影响的题", list.get(1).path("question").asText());
        assertEquals(24, list.get(1).path("answer").asInt());
        assertEquals("正常解析", list.get(1).path("analysis").asText());
        // 课件与题库同事务一致 —— 判分读题库，学生看课件，两边不能分叉。
        var captor = org.mockito.ArgumentCaptor.forClass(QuizQuestionEntity.class);
        verify(questions).updateById(captor.capture());
        QuizQuestionEntity updated = captor.getValue();
        assertEquals(7L, updated.getPaperId());
        assertEquals(1, updated.getQuestionNo(), "题号必须稳定：作答记录按它归属");
        assertTrue(updated.getStem().startsWith("长方体的长是10厘米"));
        assertEquals("A", updated.getAnswer());
        assertTrue(updated.getExplanation().contains("80"));
        assertTrue(updated.getOptions().contains("A. 80"));
    }

    @Test void refusesToWriteWhenTheQuestionSynchronizerIsMissing() {
        store();
        String before = documents.get("c1");
        service.setQuizQuestionMapper(null);
        // 宁可整体失败，也不能只改课件不改题库 —— 那会让判分与显示永久不一致。
        assertThrows(IllegalStateException.class,
                () -> service.applyQuizRepairs("c1", Map.of("s1", plan().path("changes"))));
        assertEquals(before, documents.get("c1"), "失败时不得留下半个改动");
        verify(questions, never()).updateById(any(QuizQuestionEntity.class));
    }

    @Test void aSceneWithoutChangesLeavesTheDocumentUntouched() throws Exception {
        String before = store();
        ObjectNode result = service.applyQuizRepairs("c1", Map.of());
        assertEquals(0, result.path("applied").asInt());
        // 没有可落地的改动时不该保存、更不该改修订号。
        assertEquals(before, documents.get("c1"));
        verify(questions, never()).updateById(any(QuizQuestionEntity.class));
    }

    /** 放进一份课程文档并返回它，便于断言"失败时一字未动"。 */
    private String store() {
        String document = """
                {"id":"c1","title":"长方体体积","topic":"体积","direction":"academic","domain":"general","revision":3,
                 "scenes":[{"id":"s1","title":"检查是否学会","type":"quiz","quizId":7,"content":{"type":"quiz","questions":[
                   {"id":"q1","no":1,"type":"single","question":"旧题干",
                    "options":[{"label":"48","value":"A"},{"label":"240","value":"B"}],
                    "answer":["B"],"analysis":"旧解析（含等等、让我重新计算）","hasAnswer":true},
                   {"id":"q2","no":2,"type":"fill","question":"未受影响的题","answer":24,"analysis":"正常解析","hasAnswer":true}]},
                  "actions":[]}]}
                """;
        documents.put("c1", document);
        return document;
    }

    private ObjectNode plan() throws Exception {
        return (ObjectNode) json.readTree("""
                {"changes":[
                  {"no":1,"id":"c1-q1","action":"regenerate","reason":"规则命中 analysis-meta",
                   "before":{"question":"旧题干","options":[],"answer":["B"],"analysis":"旧解析"},
                   "after":{"question":"长方体的长是10厘米、宽是4厘米、高是2厘米，体积是多少？",
                            "options":[{"label":"80","value":"A"},{"label":"16","value":"B"}],
                            "answer":["A"],"analysis":"10 × 4 × 2 = 80。"}},
                  {"no":2,"id":"c1-q2","action":"none","reason":"交人工",
                   "before":{"question":"未受影响的题","options":[],"answer":24,"analysis":"正常解析"}}]}
                """);
    }

    private static final class NoopExecutor extends AbstractExecutorService {
        public void shutdown() {}
        public List<Runnable> shutdownNow() { return List.of(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        public void execute(Runnable command) {}
    }
}
