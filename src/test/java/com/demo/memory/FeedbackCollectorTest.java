package com.demo.memory;

import com.demo.memory.embedding.MemoryEmbeddingProvider;
import com.demo.memory.store.LuceneMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反馈采集测试。
 *
 * <p>重点守两件事：
 * ① 匹配顺序（清除 → 负向 → 正向）—— 顺序错了会把"不对"当成"对"，从而<b>加强</b>错误记忆；
 * ② 单字关键词「对」不能命中「对比一下」这种非反馈语境。
 */
class FeedbackCollectorTest {

    private static final int DIM = 32;

    @TempDir
    Path tempDir;

    private LuceneMemoryStore store;
    private MemoryRagService ragService;
    private FeedbackCollector collector;
    private MemoryProperties props;

    private static class StubProvider implements MemoryEmbeddingProvider {
        @Override
        public String name() {
            return "stub";
        }

        @Override
        public int dimension() {
            return DIM;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public float[] embed(String text) {
            return embedQuery(text);
        }

        @Override
        public float[] embedQuery(String query) {
            if (query == null) return null;
            float[] v = new float[DIM];
            v[Math.floorMod(query.hashCode(), DIM)] = 1f;
            return v;
        }
    }

    private void init() {
        props = new MemoryProperties();
        props.getStore().setIndexPath(tempDir.resolve("idx").toString());
        props.getStore().setDimension(DIM);
        props.getRag().setAsyncReinforce(false);
        store = new LuceneMemoryStore(props);
        store.init();
        ragService = new MemoryRagService(store, new StubProvider(), props);
        collector = new FeedbackCollector(ragService, props);
    }

    @AfterEach
    void tearDown() {
        if (ragService != null) ragService.shutdown();
        if (store != null) store.close();
    }

    @Test
    void 清除关键词删除全部记忆() {
        init();
        ragService.addMemory("u1", "用户叫张三", MemoryType.BASIC, 9);
        ragService.addMemory("u1", "用户住在杭州", MemoryType.BASIC, 7);
        assertEquals(2, ragService.count("u1"));

        FeedbackCollector.Outcome o = collector.handle("u1", "忘掉这些", List.of());
        assertEquals(FeedbackCollector.Signal.CLEAR, o.signal());
        assertEquals(2, o.affected());
        assertEquals(0, ragService.count("u1"));
    }

    /** 负向反馈削弱上一轮注入的记忆。 */
    @Test
    void 负向反馈削弱记忆() {
        init();
        MemoryRecord r = ragService.addMemory("u1", "用户叫张三", MemoryType.BASIC, 6);

        MemoryRecord copy = new MemoryRecord();
        copy.setId(r.getId());
        FeedbackCollector.Outcome o = collector.handle("u1", "不对，你记错了", List.of(copy));

        assertEquals(FeedbackCollector.Signal.NEGATIVE, o.signal());
        assertEquals(4, store.getById("u1", r.getId()).orElseThrow().getImportance());
    }

    @Test
    void 正向反馈加强记忆() {
        init();
        MemoryRecord r = ragService.addMemory("u1", "用户叫张三", MemoryType.BASIC, 6);

        MemoryRecord copy = new MemoryRecord();
        copy.setId(r.getId());
        FeedbackCollector.Outcome o = collector.handle("u1", "对，记住了", List.of(copy));

        assertEquals(FeedbackCollector.Signal.POSITIVE, o.signal());
        assertEquals(8, store.getById("u1", r.getId()).orElseThrow().getImportance());
    }

    /**
     * 最关键的用例：<b>「不对」必须判成负向，不能因为含有「对」而被判成正向。</b>
     * <p>若顺序写反，用户每次纠错都会让错误记忆被加强——这是反馈系统里最坏的 bug。
     */
    @Test
    void 不对不能因为含对而被判成正向() {
        init();
        MemoryRecord r = ragService.addMemory("u1", "用户叫张三", MemoryType.BASIC, 6);
        MemoryRecord copy = new MemoryRecord();
        copy.setId(r.getId());

        FeedbackCollector.Outcome o = collector.handle("u1", "不对", List.of(copy));

        assertEquals(FeedbackCollector.Signal.NEGATIVE, o.signal(),
                "「不对」必须判负向，顺序写反会变成正向");
        assertEquals(4, store.getById("u1", r.getId()).orElseThrow().getImportance(),
                "重要度必须下降而不是上升");
    }

    /**
     * 单字关键词「对」不能命中长句里的"对"。
     * <p>「对比一下这两个方案」里的「对」不是确认，不该当成反馈。
     */
    @Test
    void 长句里的对不算正向反馈() {
        init();
        MemoryRecord r = ragService.addMemory("u1", "用户叫张三", MemoryType.BASIC, 6);
        MemoryRecord copy = new MemoryRecord();
        copy.setId(r.getId());

        collector.handle("u1", "对比一下这两个方案的优缺点", List.of(copy));

        assertEquals(6, store.getById("u1", r.getId()).orElseThrow().getImportance(),
                "长句里的「对」不该触发正向反馈");
    }

    /** 短句「对」确实是确认，应当生效。 */
    @Test
    void 短句的对算正向反馈() {
        init();
        MemoryRecord r = ragService.addMemory("u1", "用户叫张三", MemoryType.BASIC, 6);
        MemoryRecord copy = new MemoryRecord();
        copy.setId(r.getId());

        FeedbackCollector.Outcome o = collector.handle("u1", "对", List.of(copy));
        assertEquals(FeedbackCollector.Signal.POSITIVE, o.signal());
    }

    @Test
    void 无反馈时返回NONE() {
        init();
        ragService.addMemory("u1", "用户叫张三", MemoryType.BASIC, 6);
        assertEquals(FeedbackCollector.Signal.NONE,
                collector.handle("u1", "今天天气怎么样", List.of()).signal());
    }

    /** 隐式反馈：用户又提到同一件事 → 强化那条记忆。 */
    @Test
    void 隐式反馈_重复提及则强化() {
        init();
        MemoryRecord r = ragService.addMemory("u1", "用户叫张三", MemoryType.BASIC, 6);

        FeedbackCollector.Outcome o = collector.handle("u1", "用户叫张三", List.of());

        assertEquals(FeedbackCollector.Signal.POSITIVE, o.signal());
        assertEquals(1, store.getById("u1", r.getId()).orElseThrow().getAccessCount());
    }

    @Test
    void 关闭反馈后不生效() {
        init();
        props.getFeedback().setEnabled(false);
        ragService.addMemory("u1", "用户叫张三", MemoryType.BASIC, 6);
        assertEquals(FeedbackCollector.Signal.NONE,
                collector.handle("u1", "忘掉", List.of()).signal());
        assertEquals(1, ragService.count("u1"), "关闭反馈后不该删除记忆");
    }

    /** 下限夹取：负向反馈不能把重要度压到 0 以下。 */
    @Test
    void 负向反馈的下限夹取() {
        init();
        MemoryRecord r = ragService.addMemory("u1", "用户叫张三", MemoryType.BASIC, 1);
        MemoryRecord copy = new MemoryRecord();
        copy.setId(r.getId());

        collector.handle("u1", "不对", List.of(copy));
        assertTrue(store.getById("u1", r.getId()).orElseThrow().getImportance() >= 1);
    }
}
