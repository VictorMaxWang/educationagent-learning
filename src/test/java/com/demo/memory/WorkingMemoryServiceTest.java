package com.demo.memory;

import com.demo.memory.embedding.MemoryEmbeddingProvider;
import com.demo.memory.store.LuceneMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作记忆测试：缓冲 → 沉淀 → 清空。
 *
 * <p>它兜的是"用户分几次说完整件事"：单看一句「我下个月要搬家」抽不出事实，
 * 但会话安静下来后把缓冲里的几句一起过一遍抽取器就能抽到。
 */
class WorkingMemoryServiceTest {

    private static final int DIM = 32;

    @TempDir
    Path tempDir;

    private LuceneMemoryStore store;
    private MemoryRagService ragService;
    private WorkingMemoryService working;

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
        init(true);
    }

    /**
     * @param captureAll 是否开启埋点全量捕获。关掉时回到"只沉淀规则认得出的事实"的旧行为，
     *                   用来区分"沉淀机制本身"和"捕获策略"这两件事
     */
    private void init(boolean captureAll) {
        MemoryProperties props = new MemoryProperties();
        props.getStore().setIndexPath(tempDir.resolve("idx").toString());
        props.getStore().setDimension(DIM);
        props.getRag().setAsyncReinforce(false);
        props.getExtractor().setCaptureAll(captureAll);
        store = new LuceneMemoryStore(props);
        store.init();
        ragService = new MemoryRagService(store, new StubProvider(), props);
        // LLM 兜底关闭，所以 restTemplate 传 null 是安全的（真去调就会 NPE 暴露出来）
        MemoryExtractor extractor = new MemoryExtractor(props, null);
        working = new WorkingMemoryService(ragService, extractor, props);
    }

    @AfterEach
    void tearDown() {
        if (ragService != null) ragService.shutdown();
        if (store != null) store.close();
    }

    @Test
    void 关闭捕获时只沉淀可抽取的事实() {
        init(false);
        working.onMessage("u1", "我叫张三");
        working.onMessage("u1", "今天天气不错");
        working.onMessage("u1", "我住在杭州");
        assertEquals(1, working.activeSessions());

        int written = working.flush("u1");
        assertEquals(2, written, "两条可抽取的自我陈述应当各写一条记忆，闲聊不写");
        assertEquals(2, ragService.count("u1"));
    }

    /**
     * 开启埋点捕获后（默认），缓冲里的<b>每条</b>消息都会沉淀成一条记忆——
     * 规则认得出的走结构化事实，认不出的存原句。
     * 这正是"说什么都能加进去"在沉淀路径上的表现。
     */
    @Test
    void 开启捕获后缓冲里每条消息都沉淀() {
        init();
        working.onMessage("u1", "我叫张三");
        working.onMessage("u1", "今天天气不错");
        working.onMessage("u1", "我住在杭州");

        int written = working.flush("u1");
        assertEquals(3, written, "三条消息各沉淀一条");
        assertEquals(3, ragService.count("u1"));
    }

    @Test
    void 沉淀后缓冲清空_重复沉淀不再写入() {
        init();
        working.onMessage("u1", "我叫张三");

        assertEquals(1, working.flush("u1"));
        assertEquals(0, working.flush("u1"), "缓冲已清空，再次沉淀不该重复写入");
        assertEquals(1, ragService.count("u1"));
    }

    @Test
    void 丢弃缓冲不写入() {
        init();
        working.onMessage("u1", "我叫张三");
        working.discard("u1");

        assertEquals(0, working.flush("u1"));
        assertEquals(0, ragService.count("u1"));
    }

    @Test
    void 缓冲有上限不会无限增长() {
        init();
        for (int i = 0; i < 50; i++) {
            working.onMessage("u1", "第" + i + "条无关消息");
        }
        // 上限是 20：只保留最后 20 条。开捕获后这 20 条各沉淀一条，
        // 所以"沉淀出 20 而不是 50"正是上限在起作用——比断言 0 更能说明问题
        assertEquals(20, working.flush("u1"));
        assertEquals(20, ragService.count("u1"));
    }

    @Test
    void 用户之间缓冲隔离() {
        init();
        working.onMessage("u1", "我叫张三");
        working.onMessage("u2", "我住在杭州");

        assertEquals(1, working.flush("u1"));
        assertEquals(1, ragService.count("u1"));
        assertEquals(0, ragService.count("u2"), "u2 的缓冲还没沉淀");
        assertEquals(1, working.flush("u2"));
        assertEquals(1, ragService.count("u2"));
    }

    @Test
    void 关闭开关后不缓冲() {
        init();
        MemoryProperties props = new MemoryProperties();
        props.getWorking().setEnabled(false);
        WorkingMemoryService disabled = new WorkingMemoryService(ragService, new MemoryExtractor(props, null), props);

        disabled.onMessage("u1", "我叫张三");
        assertEquals(0, disabled.activeSessions(), "关闭后不该建立会话缓冲");
    }

    @Test
    void 空消息不入缓冲() {
        init();
        working.onMessage("u1", "");
        working.onMessage("u1", null);
        assertTrue(working.flush("u1") == 0);
    }
}
