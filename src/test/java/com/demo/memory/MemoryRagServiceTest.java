package com.demo.memory;

import com.demo.memory.embedding.MemoryEmbeddingProvider;
import com.demo.memory.store.LuceneMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆库端到端测试：真实 Lucene 索引 + 确定性桩向量化，不产生任何云端调用。
 *
 * <p>桩的实现是"同一段文本 → 同一个 one-hot 向量"，于是：
 * 检索同一句话余弦 = 1（必然命中），检索不同的话余弦 = 0（被 minScore 滤掉）。
 * 这样既能验证完整链路，又不依赖 embedding 服务的可用性。
 */
class MemoryRagServiceTest {

    private static final int DIM = 32;

    @TempDir
    Path tempDir;

    private LuceneMemoryStore store;
    private MemoryRagService service;

    /** 确定性桩：文本 → 固定桶位的 one-hot 向量。 */
    private static class StubEmbeddingProvider implements MemoryEmbeddingProvider {
        /** 置为 true 可模拟"云端 embedding 挂了"。 */
        boolean fail = false;

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
            return vec(text);
        }

        @Override
        public float[] embedQuery(String query) {
            return vec(query);
        }

        private float[] vec(String text) {
            if (fail || text == null) return null;
            float[] v = new float[DIM];
            v[Math.floorMod(text.hashCode(), DIM)] = 1f;
            return v;
        }
    }

    private MemoryRagService newService(int maxPerUser, boolean asyncReinforce) {
        MemoryProperties props = new MemoryProperties();
        props.getStore().setIndexPath(tempDir.resolve("idx").toString());
        props.getStore().setDimension(DIM);
        props.getStore().setMaxMemoryPerUser(maxPerUser);
        props.getRag().setAsyncReinforce(asyncReinforce);
        props.getRag().setMinScore(0.5);

        store = new LuceneMemoryStore(props);
        store.init();
        service = new MemoryRagService(store, new StubEmbeddingProvider(), props);
        return service;
    }

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdown();
        if (store != null) store.close();
        service = null;
        store = null;
    }

    @Test
    void 写入后能检索到并强化访问计数() {
        MemoryRagService svc = newService(100, false);
        assertTrue(svc.isUsable());

        MemoryRecord r = svc.addMemory("u1", "用户叫张三", MemoryType.BASIC, 9);
        assertNotNull(r, "写入应当成功");
        assertEquals(1, svc.count("u1"));

        List<MemoryRecord> hits = svc.search("u1", "用户叫张三", 5);
        assertEquals(1, hits.size());
        assertEquals("用户叫张三", hits.get(0).getContent());

        // reinforce 是同步的（asyncReinforce=false），计数应当已经 +1
        assertEquals(1, store.getById("u1", r.getId()).orElseThrow().getAccessCount());
    }

    /** 强化之后向量必须还在，否则这条记忆会静默消失。 */
    @Test
    void 强化后仍能被检索到() {
        MemoryRagService svc = newService(100, false);
        svc.addMemory("u1", "用户叫张三", MemoryType.BASIC, 9);

        svc.search("u1", "用户叫张三", 5);
        svc.search("u1", "用户叫张三", 5);   // 再强化一次

        assertEquals(1, svc.search("u1", "用户叫张三", 5).size(),
                "多次强化后记忆必须仍然可检索（向量没被 update 抹掉）");
    }

    @Test
    void 批量写入自动去重() {
        MemoryRagService svc = newService(100, false);
        List<MemoryRecord> added = svc.addMemories("u1",
                List.of("用户叫张三", "用户叫张三", "用户住在杭州"));
        assertEquals(2, added.size(), "完全重复的一条应当被跳过");
        assertEquals(2, svc.count("u1"));
    }

    /**
     * 单条写入也必须去重 —— 这才是每轮对话真正走的路径。
     *
     * <p>去重原先只写在 {@code addMemories}（批量）里，而那个方法在生产代码中
     * <b>没有任何调用方</b>。于是用户把"我叫张三"说几遍就存几条，脑图上直接刷屏，
     * 逐出还会把真正有用的记忆挤掉。
     */
    @Test
    void 单条写入也去重() {
        MemoryRagService svc = newService(100, false);

        MemoryRecord first = svc.addMemory("u1", "用户叫张三", MemoryType.BASIC, 9);
        MemoryRecord again = svc.addMemory("u1", "用户叫张三", MemoryType.BASIC, 9);

        assertNotNull(first);
        assertNotNull(again, "重复写入不该返回 null —— 调用方会当成写入失败");
        assertEquals(1, svc.count("u1"), "同一句话写两次只该留下一条记忆");
        assertEquals(first.getId(), again.getId(), "应当返回已有那条，而不是新建一条");
    }

    @Test
    void 内容不同的记忆不会被去重误杀() {
        MemoryRagService svc = newService(100, false);

        svc.addMemory("u1", "用户叫张三", MemoryType.BASIC, 9);
        svc.addMemory("u1", "用户住在杭州", MemoryType.BASIC, 7);
        svc.addMemory("u1", "用户喜欢喝咖啡", MemoryType.SEMANTIC, 6);

        assertEquals(3, svc.count("u1"), "语义不同的记忆必须都留下");
    }

    @Test
    void 去重不跨用户() {
        MemoryRagService svc = newService(100, false);

        svc.addMemory("u1", "用户叫张三", MemoryType.BASIC, 9);
        svc.addMemory("u2", "用户叫张三", MemoryType.BASIC, 9);

        assertEquals(1, svc.count("u1"));
        assertEquals(1, svc.count("u2"), "另一个用户说同样的话，不该被前一个用户的记忆挡住");
    }

    // ======================== 去重压缩（清理存量重复） ========================
    // 去重只能防新的；已经写进去的重复要靠这个清。
    // 它和 deleteAll 的区别是"压缩"而非"重置"——不重复的记忆一条都不能少。

    /** 直接写 store，绕开 addMemory 的去重，才能造出"存量的重复"。 */
    private void seed(String userId, String content, int importance) {
        store.add(typed(userId, content, MemoryType.BASIC, System.currentTimeMillis(), importance));
    }

    @Test
    void 去重压缩只删重复的() {
        MemoryRagService svc = newService(100, false);
        seed("u1", "用户叫张三", 9);
        seed("u1", "用户叫张三", 5);
        seed("u1", "用户住在杭州", 7);
        assertEquals(3, svc.count("u1"));

        assertEquals(1, svc.deleteDuplicates("u1"), "一对重复应当删掉一条");

        List<MemoryRecord> left = svc.listAll("u1", false);
        assertEquals(2, left.size());
        assertTrue(left.stream().anyMatch(r -> "用户住在杭州".equals(r.getContent())),
                "不重复的记忆绝不能被误删，实际剩余="
                        + left.stream().map(MemoryRecord::getContent).toList());
    }

    @Test
    void 去重压缩保留评分最高的() {
        MemoryRagService svc = newService(100, false);
        seed("u1", "用户叫张三", 9);
        seed("u1", "用户叫张三", 5);

        svc.deleteDuplicates("u1");

        List<MemoryRecord> left = svc.listAll("u1", false);
        assertEquals(1, left.size());
        assertEquals(9, left.get(0).getImportance(),
                "保留哪条用逐出评分判断——重要度高的活下来");
    }

    @Test
    void 无重复时压缩不动任何数据() {
        MemoryRagService svc = newService(100, false);
        seed("u1", "用户叫张三", 9);
        seed("u1", "用户住在杭州", 7);

        assertEquals(0, svc.deleteDuplicates("u1"));
        assertEquals(2, svc.count("u1"));
    }

    @Test
    void 压缩只影响指定用户() {
        MemoryRagService svc = newService(100, false);
        seed("u1", "用户叫张三", 9);
        seed("u1", "用户叫张三", 5);
        seed("u2", "用户叫张三", 9);
        seed("u2", "用户叫张三", 5);

        svc.deleteDuplicates("u1");

        assertEquals(1, svc.count("u1"));
        assertEquals(2, svc.count("u2"), "另一个用户的记忆不该被动");
    }

    @Test
    void 超过单用户上限时逐出低分记忆() {
        MemoryRagService svc = newService(3, false);
        // 低重要度的记忆应当先被逐出
        for (int i = 0; i < 5; i++) {
            svc.addMemory("u1", "记忆编号" + i, MemoryType.BASIC, i + 1);
        }
        assertEquals(3, svc.count("u1"), "应当被限制在 3 条");

        List<MemoryRecord> left = svc.listAll("u1", false);
        assertTrue(left.stream().noneMatch(r -> r.getContent().equals("记忆编号0")),
                "最低重要度的记忆应当已被逐出，实际剩余=" + left.stream().map(MemoryRecord::getContent).toList());
    }

    @Test
    void 用户之间互不干扰() {
        MemoryRagService svc = newService(100, false);
        svc.addMemory("u1", "u1 的记忆", MemoryType.BASIC, 5);

        assertTrue(svc.search("u2", "u1 的记忆", 5).isEmpty());
        assertEquals(0, svc.count("u2"));
        assertFalse(svc.deleteMemory("u2", "任意ID"), "不能删别人的记忆");
    }

    /** 云端向量化不可用时：不写入（而不是写入一条永远搜不到的坏记忆）。 */
    @Test
    void 向量化失败时不写入() {
        MemoryProperties props = new MemoryProperties();
        props.getStore().setIndexPath(tempDir.resolve("idx2").toString());
        props.getStore().setDimension(DIM);
        store = new LuceneMemoryStore(props);
        store.init();

        StubEmbeddingProvider failing = new StubEmbeddingProvider();
        failing.fail = true;
        MemoryRagService svc = new MemoryRagService(store, failing, props);
        service = svc;

        assertEquals(null, svc.addMemory("u1", "用户叫张三", MemoryType.BASIC, 9));
        assertEquals(0, svc.count("u1"));
    }

    @Test
    void 衰减_过老删除_稍旧降权() {
        MemoryRagService svc = newService(100, false);
        long now = System.currentTimeMillis();

        // 直接通过 store 写入可控制创建时间的记忆
        MemoryRecord ancient = raw("u1", "100天前的事", now - 100L * 86_400_000L, 8);
        MemoryRecord aging = raw("u1", "40天前的事", now - 40L * 86_400_000L, 10);
        MemoryRecord fresh = raw("u1", "今天的事", now, 10);
        store.add(ancient);
        store.add(aging);
        store.add(fresh);
        assertEquals(3, svc.count("u1"));

        svc.decay("u1");

        // 这两条都是 EPISODIC，走的是更短的分代档（60 天删除 / 14 天软衰减）
        assertEquals(2, svc.count("u1"), "超过 60 天的情景记忆应当被删除");
        List<MemoryRecord> left = svc.listAll("u1", false);
        assertTrue(left.stream().noneMatch(r -> "100天前的事".equals(r.getContent())));

        MemoryRecord agingAfter = left.stream()
                .filter(r -> "40天前的事".equals(r.getContent())).findFirst().orElseThrow();
        assertEquals(9, agingAfter.getImportance(), "40 天的记忆重要度应当 10 → 9");

        MemoryRecord freshAfter = left.stream()
                .filter(r -> "今天的事".equals(r.getContent())).findFirst().orElseThrow();
        assertEquals(10, freshAfter.getImportance(), "今天的记忆不该被降权");
    }

    @Test
    void 调整重要度与删除() {
        MemoryRagService svc = newService(100, false);
        MemoryRecord r = svc.addMemory("u1", "用户叫张三", MemoryType.BASIC, 5);

        assertTrue(svc.adjustImportance("u1", r.getId(), 3));
        assertEquals(8, store.getById("u1", r.getId()).orElseThrow().getImportance());

        // 上限夹取
        assertTrue(svc.adjustImportance("u1", r.getId(), 99));
        assertEquals(10, store.getById("u1", r.getId()).orElseThrow().getImportance());

        assertTrue(svc.deleteMemory("u1", r.getId()));
        assertEquals(0, svc.count("u1"));
    }

    private MemoryRecord raw(String userId, String content, long createdAt, int importance) {
        return MemoryRecord.builder()
                .userId(userId).content(content).type(MemoryType.EPISODIC)
                .importance(importance).createdAt(createdAt).lastAccessedAt(createdAt)
                .accessCount(0)
                .embedding(new StubEmbeddingProvider().embed(content))
                .build();
    }

    /** 指定类型的记忆，用来对比分代衰减档位。 */
    private MemoryRecord typed(String userId, String content, MemoryType type,
                               long createdAt, int importance) {
        return MemoryRecord.builder()
                .userId(userId).content(content).type(type)
                .importance(importance).createdAt(createdAt).lastAccessedAt(createdAt)
                .accessCount(0)
                .embedding(new StubEmbeddingProvider().embed(content))
                .build();
    }

    private static final long DAY = 86_400_000L;

    // ======================== 按类型分档衰减（GC 的"年轻人代回收更频繁"） ========================

    /**
     * 同一时间点上，EPISODIC 和 BASIC 的命运必须不同——否则埋点捕获的闲聊原句
     * 会和"用户叫张三"活得一样久，脑图迟早被流水账淹没。
     */
    @Test
    void 衰减_情景记忆先被回收() {
        MemoryRagService svc = newService(100, false);
        long now = System.currentTimeMillis();
        long age = 70 * DAY;   // 70 天：过了 EPISODIC 的 60 天线，还没到其他类型的 90 天线

        store.add(typed("u1", "70天前的情景", MemoryType.EPISODIC, now - age, 8));
        store.add(typed("u1", "70天前的属性", MemoryType.BASIC, now - age, 8));

        svc.decay("u1");

        List<MemoryRecord> left = svc.listAll("u1", false);
        assertTrue(left.stream().noneMatch(r -> "70天前的情景".equals(r.getContent())),
                "EPISODIC 超过 60 天应当被回收");
        assertTrue(left.stream().anyMatch(r -> "70天前的属性".equals(r.getContent())),
                "BASIC 活得更久——70 天还没到它的 90 天线");
    }

    @Test
    void 衰减_情景记忆更早开始降权() {
        MemoryRagService svc = newService(100, false);
        long now = System.currentTimeMillis();
        long age = 20 * DAY;   // 20 天：过了 EPISODIC 的 14 天软衰减线，还没到其他的 30 天

        store.add(typed("u1", "20天前的情景", MemoryType.EPISODIC, now - age, 10));
        store.add(typed("u1", "20天前的属性", MemoryType.BASIC, now - age, 10));

        svc.decay("u1");

        List<MemoryRecord> left = svc.listAll("u1", false);
        MemoryRecord epi = left.stream()
                .filter(r -> "20天前的情景".equals(r.getContent())).findFirst().orElseThrow();
        MemoryRecord bas = left.stream()
                .filter(r -> "20天前的属性".equals(r.getContent())).findFirst().orElseThrow();

        assertEquals(9, epi.getImportance(), "EPISODIC 20 天已经该降权了（10 → 9）");
        assertEquals(10, bas.getImportance(), "BASIC 20 天还没到软衰减线，不该被动");
    }
}
