package com.demo.memory.store;

import com.demo.memory.MemoryProperties;
import com.demo.memory.MemoryRecord;
import com.demo.memory.MemoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lucene 记忆库测试。用 4 维 one-hot 向量，让余弦相似度可以手算：
 * 同一个向量 → 1.0；不同 one-hot → 0.0。
 */
class LuceneMemoryStoreTest {

    private static final int DIM = 4;

    @TempDir
    Path tempDir;

    private LuceneMemoryStore store;

    private LuceneMemoryStore newStore(int dimension) {
        MemoryProperties props = new MemoryProperties();
        props.getStore().setIndexPath(tempDir.resolve("idx").toString());
        props.getStore().setDimension(dimension);
        props.getStore().setAnalyzer("cjk");
        LuceneMemoryStore s = new LuceneMemoryStore(props);
        s.init();
        return s;
    }

    private static float[] oneHot(int idx) {
        float[] v = new float[DIM];
        v[idx] = 1f;
        return v;
    }

    private static MemoryRecord rec(String userId, String content, int hot) {
        return MemoryRecord.builder()
                .userId(userId)
                .content(content)
                .type(MemoryType.BASIC)
                .importance(5)
                .createdAt(System.currentTimeMillis())
                .lastAccessedAt(System.currentTimeMillis())
                .accessCount(0)
                .embedding(oneHot(hot))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    @Test
    void 写入后能检索到() {
        store = newStore(DIM);
        assertTrue(store.isAvailable());

        String id = store.add(rec("u1", "用户叫张三", 0));
        assertNotNull(id, "写入应当返回记忆 ID");

        List<MemoryRecord> hits = store.search("u1", oneHot(0), 5, 0.5);
        assertEquals(1, hits.size());
        assertEquals("用户叫张三", hits.get(0).getContent());
        assertEquals(id, hits.get(0).getId());
    }

    /** 用户隔离是硬要求：A 的记忆绝不能被 B 检索到。 */
    @Test
    void 用户隔离() {
        store = newStore(DIM);
        store.add(rec("u1", "u1 的秘密", 0));
        store.add(rec("u2", "u2 的秘密", 0));

        List<MemoryRecord> u1Hits = store.search("u1", oneHot(0), 5, 0.5);
        assertEquals(1, u1Hits.size());
        assertEquals("u1 的秘密", u1Hits.get(0).getContent());

        assertEquals(1, store.search("u2", oneHot(0), 5, 0.5).size());
        assertTrue(store.search("u3", oneHot(0), 5, 0.5).isEmpty(), "没有记忆的用户应当搜不到");
    }

    @Test
    void topK限制生效() {
        store = newStore(DIM);
        for (int i = 0; i < 5; i++) {
            store.add(rec("u1", "记忆" + i, i % DIM));
        }
        assertEquals(2, store.search("u1", oneHot(0), 2, -1.0).size());
    }

    /** minScore 过滤：one-hot 之间的余弦是 0，阈值 0.5 应当把它们全滤掉。 */
    @Test
    void minScore过滤生效() {
        store = newStore(DIM);
        store.add(rec("u1", "同向量", 0));
        store.add(rec("u1", "正交向量", 1));

        // 阈值 -1 → 两条都要
        assertEquals(2, store.search("u1", oneHot(0), 10, -1.0).size());
        // 阈值 0.5 → 只剩余弦=1 的那条
        List<MemoryRecord> strict = store.search("u1", oneHot(0), 10, 0.5);
        assertEquals(1, strict.size());
        assertEquals("同向量", strict.get(0).getContent());
    }

    @Test
    void 计数与列举() {
        store = newStore(DIM);
        store.add(rec("u1", "a", 0));
        store.add(rec("u1", "b", 1));
        store.add(rec("u2", "c", 2));

        assertEquals(2, store.countByUserId("u1"));
        assertEquals(1, store.countByUserId("u2"));
        assertEquals(3, store.totalCount(), "totalCount 不应把 meta 文档算进去");
        assertEquals(2, store.countUsers());
        assertEquals(2, store.listByUserId("u1", false).size());
        assertTrue(store.listAllUserIds().containsAll(List.of("u1", "u2")));
    }

    @Test
    void 按用户删除() {
        store = newStore(DIM);
        store.add(rec("u1", "a", 0));
        store.add(rec("u1", "b", 1));
        store.add(rec("u2", "c", 2));

        assertEquals(2, store.deleteByUserId("u1"));
        assertEquals(0, store.countByUserId("u1"));
        assertEquals(1, store.totalCount(), "只该删掉 u1 的，u2 不受影响");
    }

    @Test
    void 按id删除() {
        store = newStore(DIM);
        String id = store.add(rec("u1", "a", 0));
        assertTrue(store.deleteById(id));
        assertEquals(0, store.totalCount());
    }

    /**
     * 向量回读：Lucene 的 KnnFloatVectorField 只建索引不存原向量，
     * 必须靠额外的 StoredField("embeddingRaw") 才能拿回来。这条用例就是在守这个坑。
     */
    @Test
    void 向量可回读且与写入一致() {
        store = newStore(DIM);
        store.add(rec("u1", "a", 2));

        List<MemoryRecord> withVec = store.listByUserId("u1", true);
        assertEquals(1, withVec.size());
        assertNotNull(withVec.get(0).getEmbedding(), "必须能回读向量（KnnFloatVectorField 本身不存向量）");
        assertTrue(withVec.get(0).getEmbedding()[2] == 1f);

        // 显式不索取时不回填，省内存
        assertTrue(store.listByUserId("u1", false).get(0).getEmbedding() == null);
    }

    /**
     * 更新时不带向量，必须保留索引里原有的向量。
     *
     * <p>Lucene 的 {@code updateDocument} 是<b>整文档替换</b>：如果 reinforce（访问计数+1）
     * 拿着一个 embedding=null 的记录去更新，向量字段会被整个抹掉，
     * 那条记忆从此再也搜不到——而且是静默的，只有检索时才发现"记忆丢了"。
     */
    @Test
    void 更新不带向量时保留原向量() {
        store = newStore(DIM);
        String id = store.add(rec("u1", "a", 3));

        MemoryRecord loaded = store.getById("u1", id).orElseThrow();
        loaded.setEmbedding(null);          // 模拟 reinforce：只改计数，不碰向量
        loaded.setAccessCount(7);
        assertTrue(store.update(loaded));

        assertEquals(1, store.countByUserId("u1"), "更新后记忆不应消失");
        assertEquals(7, store.getById("u1", id).orElseThrow().getAccessCount());
        assertEquals(1, store.search("u1", oneHot(3), 5, 0.5).size(), "向量必须还在，否则搜不到");
    }

    @Test
    void getById做用户校验() {
        store = newStore(DIM);
        String id = store.add(rec("u1", "a", 0));
        assertTrue(store.getById("u1", id).isPresent());
        Optional<MemoryRecord> wrong = store.getById("u2", id);
        assertFalse(wrong.isPresent(), "不能通过别人的 userId 读到这条记忆");
    }

    /** 维度变更必须重建：混维度的向量索引不报错但检索完全不可用。 */
    @Test
    void 维度变更触发重建() {
        store = newStore(DIM);
        store.add(rec("u1", "旧维度记忆", 0));
        assertEquals(1, store.totalCount());
        store.close();

        // 换一个维度重开
        LuceneMemoryStore rebuilt = newStore(3);
        try {
            assertTrue(rebuilt.isAvailable());
            assertEquals(3, rebuilt.dimension());
            assertEquals(0, rebuilt.totalCount(), "维度变更应当清空重建，而不是留下混维度的脏数据");

            float[] v3 = {1f, 0f, 0f};
            MemoryRecord r = MemoryRecord.builder()
                    .userId("u1").content("新维度记忆").type(MemoryType.BASIC)
                    .importance(5).createdAt(System.currentTimeMillis())
                    .lastAccessedAt(System.currentTimeMillis()).embedding(v3).build();
            assertNotNull(rebuilt.add(r));
            assertEquals(1, rebuilt.search("u1", v3, 5, 0.5).size());
        } finally {
            rebuilt.close();
        }
        store = null;
    }

    /** 维度不符的向量必须拒绝写入，不能静默塞进索引。 */
    @Test
    void 维度不符的向量被拒绝() {
        store = newStore(DIM);
        MemoryRecord bad = rec("u1", "维度不对", 0);
        bad.setEmbedding(new float[]{1f, 2f}); // 2 维，索引是 4 维
        assertTrue(store.add(bad) == null, "维度不符应当被拒绝");
        assertEquals(0, store.totalCount());
    }

    @Test
    void 重开索引后数据仍在() {
        store = newStore(DIM);
        store.add(rec("u1", "持久化记忆", 1));
        store.close();
        store = null;

        LuceneMemoryStore reopened = newStore(DIM);
        try {
            assertEquals(1, reopened.totalCount());
            assertEquals(1, reopened.search("u1", oneHot(1), 5, 0.5).size());
        } finally {
            reopened.close();
        }
    }

    // ======================== 关键词字段 ========================

    @Test
    void 关键词往返() {
        store = newStore(DIM);
        MemoryRecord r = rec("u1", "我最近压力有点大", 0);
        r.setKeywords("最近压力");
        String id = store.add(r);
        assertNotNull(id);

        Optional<MemoryRecord> back = store.getById("u1", id);
        assertTrue(back.isPresent());
        assertEquals("最近压力", back.get().getKeywords());
    }

    /**
     * Lucene 的 {@code updateDocument} 是<b>整文档替换</b>：一次只改部分字段的更新
     * （reinforce 只改访问次数、decay 只改重要度）如果不回读，就会把没碰过的字段清空。
     *
     * <p>这正是当年"访问次数+1 把向量抹掉、该条记忆再也搜不到"的同一个坑，
     * 关键词字段是后来加的，必须一起钉住。
     */
    @Test
    void 更新时不带关键词则保留原值() {
        store = newStore(DIM);
        MemoryRecord r = rec("u1", "我最近压力有点大", 0);
        r.setKeywords("最近压力");
        String id = store.add(r);

        // 模拟 reinforce：只改访问次数，关键词不传
        MemoryRecord partial = new MemoryRecord();
        partial.setId(id);
        partial.setUserId("u1");
        partial.setContent("我最近压力有点大");
        partial.setType(MemoryType.BASIC);
        partial.setImportance(5);
        partial.setCreatedAt(System.currentTimeMillis());
        partial.setLastAccessedAt(System.currentTimeMillis());
        partial.setAccessCount(1);
        // 关键词与向量都故意留空

        assertTrue(store.update(partial));

        MemoryRecord back = store.getById("u1", id).orElseThrow();
        assertEquals("最近压力", back.getKeywords(), "关键词不该被部分更新抹掉");
        assertNotNull(back.getEmbedding(), "向量同样不该被抹掉");
        assertEquals(1, back.getAccessCount());
    }

    @Test
    void 更新时带新关键词则覆盖() {
        store = newStore(DIM);
        MemoryRecord r = rec("u1", "原内容", 0);
        r.setKeywords("旧词");
        String id = store.add(r);

        MemoryRecord updated = rec("u1", "原内容", 0);
        updated.setId(id);
        updated.setKeywords("新词");
        assertTrue(store.update(updated));

        assertEquals("新词", store.getById("u1", id).orElseThrow().getKeywords());
    }
}
