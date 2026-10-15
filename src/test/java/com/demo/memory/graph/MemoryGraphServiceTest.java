package com.demo.memory.graph;

import com.demo.memory.MemoryProperties;
import com.demo.memory.MemoryRagService;
import com.demo.memory.MemoryRecord;
import com.demo.memory.MemoryType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆脑图的分代与几何计算测试（纯静态方法，不碰 Lucene）。
 *
 * <p>分代不是装饰：它的每一档都对应 {@code DecayScheduler} 的真实行为，
 * 阈值写错了，用户看到的"即将被回收"就是错的。
 */
class MemoryGraphServiceTest {

    private static final long NOW = 1_800_000_000_000L;

    private static MemoryRecord rec(MemoryType type, int importance, int accessCount, long ageDays) {
        return MemoryRecord.builder()
                .id("id")
                .userId("u1")
                .content("内容")
                .type(type)
                .importance(importance)
                .accessCount(accessCount)
                .createdAt(NOW - ageDays * 86_400_000L)
                .lastAccessedAt(NOW)
                .build();
    }

    @Test
    void 低重要度进入待回收() {
        assertEquals(MemoryGraphDTO.Generation.PENDING_GC,
                MemoryGraphService.generation(rec(MemoryType.SEMANTIC, 1, 100, 1), NOW),
                "重要度 1 无论访问多少次都该回收——它本就不重要");
    }

    /** 情景记忆天然短命：60 天后即使重要度不低也该回收。 */
    @Test
    void 过老的情景记忆进入待回收() {
        assertEquals(MemoryGraphDTO.Generation.PENDING_GC,
                MemoryGraphService.generation(rec(MemoryType.EPISODIC, 8, 0, 61), NOW));
        assertEquals(MemoryGraphDTO.Generation.SURVIVOR,
                MemoryGraphService.generation(rec(MemoryType.EPISODIC, 8, 0, 59), NOW),
                "59 天还没到线，应当还是存活的");
    }

    @Test
    void 高频高重要度进入OLD() {
        assertEquals(MemoryGraphDTO.Generation.OLD,
                MemoryGraphService.generation(rec(MemoryType.BASIC, 8, 5, 10), NOW));
        // 访问次数差一点 → 不算 OLD
        assertEquals(MemoryGraphDTO.Generation.SURVIVOR,
                MemoryGraphService.generation(rec(MemoryType.BASIC, 8, 4, 10), NOW));
    }

    @Test
    void 中等重要度进入SURVIVOR() {
        assertEquals(MemoryGraphDTO.Generation.SURVIVOR,
                MemoryGraphService.generation(rec(MemoryType.SEMANTIC, 4, 0, 5), NOW));
        assertEquals(MemoryGraphDTO.Generation.SURVIVOR,
                MemoryGraphService.generation(rec(MemoryType.SEMANTIC, 3, 2, 5), NOW),
                "被访问过两次也算存活");
    }

    @Test
    void 刚生成且未被访问的是NEW() {
        assertEquals(MemoryGraphDTO.Generation.NEW,
                MemoryGraphService.generation(rec(MemoryType.SEMANTIC, 3, 0, 1), NOW));
    }

    /** OLD 的阈值比 SURVIVOR 更严：先判 OLD 再判 SURVIVOR，顺序不能反。 */
    @Test
    void OLD优先于SURVIVOR判定() {
        MemoryRecord r = rec(MemoryType.BASIC, 9, 20, 1);
        assertEquals(MemoryGraphDTO.Generation.OLD, MemoryGraphService.generation(r, NOW));
    }

    @Test
    void 节点半径随访问次数对数增长() {
        double none = MemoryGraphService.nodeSize(rec(MemoryType.BASIC, 5, 0, 1));
        double few = MemoryGraphService.nodeSize(rec(MemoryType.BASIC, 5, 10, 1));
        double many = MemoryGraphService.nodeSize(rec(MemoryType.BASIC, 5, 100, 1));

        assertTrue(none < few, "访问多应当更大");
        assertTrue(few < many);
        // 对数增长：10 倍访问次数带来的增幅应当明显小于 10 倍
        assertTrue((many - few) < (few - none) * 5,
                "应当是对数增长而不是线性（none=" + none + " few=" + few + " many=" + many + "）");
    }

    @Test
    void 余弦相似度() {
        assertEquals(1.0, MemoryGraphService.cosine(new float[]{1, 0}, new float[]{1, 0}), 1e-9);
        assertEquals(0.0, MemoryGraphService.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-9);
        assertEquals(-1.0, MemoryGraphService.cosine(new float[]{1, 0}, new float[]{-1, 0}), 1e-9);
        // 不假设上游做过归一化
        assertEquals(1.0, MemoryGraphService.cosine(new float[]{3, 0}, new float[]{7, 0}), 1e-9);
    }

    @Test
    void 余弦相似度的边界() {
        assertEquals(0.0, MemoryGraphService.cosine(null, new float[]{1}));
        assertEquals(0.0, MemoryGraphService.cosine(new float[]{1}, new float[]{1, 2}),
                "维度不同不能算出相似度");
        assertEquals(0.0, MemoryGraphService.cosine(new float[0], new float[0]));
        assertEquals(0.0, MemoryGraphService.cosine(new float[]{0, 0}, new float[]{1, 0}),
                "零向量没有方向，应当返回 0 而不是 NaN");
    }

    // ======================== 建图：根节点、边类型、统计 ========================

    private static MemoryRecord withVector(MemoryRecord r, float[] v) {
        r.setEmbedding(v);
        return r;
    }

    /**
     * 造一个服务实例。{@code ObjectProvider} 返回 null → 走同步构建路径，
     * 测试里不需要真的起线程池。
     */
    @SuppressWarnings("unchecked")
    private static MemoryGraphService serviceWith(List<MemoryRecord> records, int cacheTtlSeconds) {
        MemoryRagService rag = mock(MemoryRagService.class);
        when(rag.isUsable()).thenReturn(true);
        when(rag.listAll(anyString(), anyBoolean())).thenReturn(new ArrayList<>(records));

        MemoryProperties props = new MemoryProperties();
        props.getGraph().setEnabled(true);
        props.getGraph().setCacheTtlSeconds(cacheTtlSeconds);

        ObjectProvider<GraphExecutorConfig.GraphExecutor> noExecutor = mock(ObjectProvider.class);
        when(noExecutor.getIfAvailable()).thenReturn(null);
        return new MemoryGraphService(rag, props, noExecutor);
    }

    private static List<MemoryGraphDTO.Link> linksOfType(MemoryGraphDTO.Graph g, String type) {
        return g.links().stream().filter(l -> type.equals(l.type())).toList();
    }

    @Test
    void 根节点代表用户画像() {
        MemoryGraphDTO.Graph g = serviceWith(List.of(), 0).build("u1");
        MemoryGraphDTO.Node root = g.nodes().get(0);

        assertTrue(root.root());
        assertEquals("__root__", root.id());
        assertEquals("用户画像", root.label(),
                "根节点代表'这个用户是谁'，方案书口径是「用户画像」而不是「用户」");
    }

    @Test
    void 画像类记忆用HIERARCHY边挂到根节点() {
        MemoryRecord profile = rec(MemoryType.BASIC, 9, 1, 3);
        MemoryGraphDTO.Graph g = serviceWith(List.of(profile), 0).build("u1");

        List<MemoryGraphDTO.Link> hier = linksOfType(g, MemoryGraphDTO.EDGE_HIERARCHY);
        assertEquals(1, hier.size(), "BASIC 属于'用户是谁'，应当直接挂根节点");
        assertEquals("__root__", hier.get(0).source());
        assertEquals(profile.getId(), hier.get(0).target());
    }

    @Test
    void 相似记忆之间用SIMILARITY边相连() {
        MemoryRecord a = withVector(rec(MemoryType.SEMANTIC, 6, 1, 3), new float[]{1, 0});
        MemoryRecord b = withVector(rec(MemoryType.SEMANTIC, 6, 1, 3), new float[]{1, 0});
        b.setId("id-b");
        MemoryGraphDTO.Graph g = serviceWith(List.of(a, b), 0).build("u1");

        List<MemoryGraphDTO.Link> sim = linksOfType(g, MemoryGraphDTO.EDGE_SIMILARITY);
        assertEquals(1, sim.size(), "余弦 1.0 远超阈值 0.7，应当连一条语义边");
        assertEquals(1.0, sim.get(0).weight(), 1e-9);
    }

    /** 一条孤立的 EPISODIC 谁也不像、又没连根，必须补边——否则它飘在图上是个孤立点。 */
    @Test
    void 孤立的情景记忆补一条归属边() {
        MemoryRecord episodic = rec(MemoryType.EPISODIC, 3, 1, 2);
        MemoryGraphDTO.Graph g = serviceWith(List.of(episodic), 0).build("u1");

        assertEquals(1, linksOfType(g, MemoryGraphDTO.EDGE_HIERARCHY).size(),
                "EPISODIC 不在方案书的'画像类'里，但孤立时仍要补边，否则图上看不见它");
    }

    /** 但有近邻的 EPISODIC 不该多补——否则"哪些是画像"这件事就被兜底规则冲淡了。 */
    @Test
    void 情景记忆有近邻时不补归属边() {
        MemoryRecord a = withVector(rec(MemoryType.EPISODIC, 5, 1, 2), new float[]{1, 0});
        MemoryRecord b = withVector(rec(MemoryType.EPISODIC, 5, 1, 2), new float[]{1, 0});
        b.setId("id-b");
        MemoryGraphDTO.Graph g = serviceWith(List.of(a, b), 0).build("u1");

        assertEquals(0, linksOfType(g, MemoryGraphDTO.EDGE_HIERARCHY).size());
        assertEquals(1, linksOfType(g, MemoryGraphDTO.EDGE_SIMILARITY).size());
    }

    @Test
    void 统计含汇总字段() {
        MemoryRecord profile = withVector(rec(MemoryType.BASIC, 9, 1, 3), new float[]{1, 0});
        MemoryRecord episodic = withVector(rec(MemoryType.EPISODIC, 5, 1, 3), new float[]{1, 0});
        episodic.setId("id-b");
        MemoryGraphDTO.Graph g = serviceWith(List.of(profile, episodic), 0).build("u1");

        assertEquals(2, g.stats().get("totalCount"));
        assertEquals(2, g.stats().get("nodeCount"), "nodeCount 不含根节点");
        assertEquals(g.links().size(), g.stats().get("linkCount"));
        assertTrue((Integer) g.stats().get("rootDegree") >= 1, "根节点至少有 1 条出边");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Integer> dist = (java.util.Map<String, Integer>) g.stats().get("typeDistribution");
        assertEquals(1, dist.get("BASIC"));
        assertEquals(1, dist.get("EPISODIC"));

        // 分代计数与汇总字段是两个维度，必须同时存在（前端图例读前者）
        assertNotNull(g.stats().get("NEW"));
        assertNotNull(g.stats().get("PENDING_GC"));
    }

    @Test
    void 缓存命中时不重复构图() {
        MemoryRagService rag = mock(MemoryRagService.class);
        when(rag.isUsable()).thenReturn(true);
        when(rag.listAll(anyString(), anyBoolean())).thenReturn(new ArrayList<>());

        MemoryProperties props = new MemoryProperties();
        props.getGraph().setEnabled(true);
        props.getGraph().setCacheTtlSeconds(10);

        @SuppressWarnings("unchecked")
        ObjectProvider<GraphExecutorConfig.GraphExecutor> noExecutor = mock(ObjectProvider.class);
        when(noExecutor.getIfAvailable()).thenReturn(null);

        MemoryGraphService svc = new MemoryGraphService(rag, props, noExecutor);
        MemoryGraphDTO.Graph first = svc.build("u1");
        MemoryGraphDTO.Graph second = svc.build("u1");

        assertSame(first, second, "TTL 内应当直接返回缓存对象");
        verify(rag, times(1)).listAll(anyString(), anyBoolean());

        // 清空记忆后必须显式失效，否则最长 10 秒内还会看到旧图
        svc.evict("u1");
        svc.build("u1");
        verify(rag, times(2)).listAll(anyString(), anyBoolean());
    }

    @Test
    void 关闭缓存时每次重建() {
        MemoryRagService rag = mock(MemoryRagService.class);
        when(rag.isUsable()).thenReturn(true);
        when(rag.listAll(anyString(), anyBoolean())).thenReturn(new ArrayList<>());

        MemoryProperties props = new MemoryProperties();
        props.getGraph().setEnabled(true);
        props.getGraph().setCacheTtlSeconds(0);

        @SuppressWarnings("unchecked")
        ObjectProvider<GraphExecutorConfig.GraphExecutor> noExecutor = mock(ObjectProvider.class);
        when(noExecutor.getIfAvailable()).thenReturn(null);

        MemoryGraphService svc = new MemoryGraphService(rag, props, noExecutor);
        svc.build("u1");
        svc.build("u1");
        verify(rag, times(2)).listAll(anyString(), anyBoolean());
    }

    @Test
    void 关闭开关时返回仅含根节点的空图() {
        MemoryProperties props = new MemoryProperties();
        props.getGraph().setEnabled(false);
        props.getGraph().setCacheTtlSeconds(0);

        @SuppressWarnings("unchecked")
        ObjectProvider<GraphExecutorConfig.GraphExecutor> noExecutor = mock(ObjectProvider.class);
        MemoryGraphService svc = new MemoryGraphService(mock(MemoryRagService.class), props, noExecutor);

        MemoryGraphDTO.Graph g = svc.build("u1");
        assertEquals(1, g.nodes().size(), "开关关掉时也要有根节点，前端才能区分'没数据'和'接口挂了'");
        assertTrue(g.links().isEmpty());
        assertFalse(g.truncated());
        assertEquals(0, g.stats().get("totalCount"));
        assertNull(g.stats().get("traceId"), "没有上游 span 时 traceId 就该是 null");
    }

    /** 空 userId 不该抛异常——接口层会拦，但服务层也得自己站得住。 */
    @Test
    void 空userId返回空图() {
        MemoryGraphDTO.Graph g = serviceWith(List.of(), 0).build("  ");
        assertEquals(1, g.nodes().size());
    }

    @Test
    void 边类型常量不重复() {
        assertFalse(MemoryGraphDTO.EDGE_HIERARCHY.equals(MemoryGraphDTO.EDGE_SIMILARITY));
    }

    // ======================== 节点标签 ========================

    /** 埋点捕获的原句可能几十个字，截断到 24 字在同心环上读不过来——有关键词就用关键词。 */
    @Test
    void 有关键词时节点标签用关键词() {
        MemoryRecord r = rec(MemoryType.EPISODIC, 5, 1, 1);
        r.setKeywords("最近压力,搬家");
        MemoryGraphDTO.Graph g = serviceWith(List.of(r), 0).build("u1");

        MemoryGraphDTO.Node n = g.nodes().get(1);
        assertEquals("最近压力 搬家", n.label(), "关键词比整句更适合当同心环上的标签");
        assertEquals("最近压力,搬家", n.keywords());
        assertEquals("内容", n.fullContent(), "完整正文仍要保留，供点击查看");
    }

    @Test
    void 无关键词时回退到正文截断() {
        MemoryRecord r = rec(MemoryType.BASIC, 5, 1, 1);
        MemoryGraphDTO.Graph g = serviceWith(List.of(r), 0).build("u1");

        assertEquals("内容", g.nodes().get(1).label(),
                "规则抽出的干净事实本身就很短，没有关键词也够读");
    }

    @Test
    void 类型分布是独立维度() {
        // typeDistribution 按 MemoryType 分，stats 里的 NEW/OLD/... 按 Generation 分，
        // 两者可以同时落在同一条记忆上而互不冲突
        MemoryRecord r = rec(MemoryType.SEMANTIC, 8, 6, 1);
        MemoryGraphDTO.Graph g = serviceWith(Arrays.asList(r), 0).build("u1");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Integer> dist = (java.util.Map<String, Integer>) g.stats().get("typeDistribution");
        assertEquals(1, dist.get("SEMANTIC"));
        assertEquals(1, g.stats().get("OLD"), "同一条记忆在分代维度上是 OLD");
    }
}
