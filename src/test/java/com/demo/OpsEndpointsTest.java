package com.demo;

import com.demo.memory.graph.MemoryGraphController;
import com.demo.memory.graph.MemoryGraphDTO;
import com.demo.metrics.MetricsController;
import com.demo.router.RouterEvalController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.demo.testsupport.IsolatedSpringBootTest;
import com.demo.testsupport.IsolatedRedisStubContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运维观测三个新接口的集成测试（真实 Spring 上下文，直接调控制器方法）。
 *
 * <p>不启 Web 容器也不打 HTTP——只验证"装配是否完整 + 返回结构是否符合前端约定"。
 * 前端三个页面都按 {@code json.code === 200} 判成功、按 {@code json.data} 取数据，
 * 所以这两个字段是硬约定，必须钉住。
 */
/**
 * ⚠️ 必须把记忆索引指到测试目录。
 *
 * <p>默认配置里 {@code memory.store.index-path=data/memory-index} 是**运行时**的索引。
 * 如果测试也打开它，正在跑的开发后端会检测到"索引被外部力量改动"而自我关闭 IndexWriter，
 * 之后每一次记忆写入都静默失败——脑图永远是空的，日志里却只有一行 WARN。
 * 实测踩过一次：跑测试把运行中的后端写坏了。
 */
@IsolatedSpringBootTest
class OpsEndpointsTest extends IsolatedRedisStubContext {

    @Autowired
    private RouterEvalController routerEvalController;

    @Autowired
    private MetricsController metricsController;

    @Autowired
    private MemoryGraphController memoryGraphController;

    // ======================== 路由评测 ========================

    @Test
    void 路由健康接口返回各层开关() {
        Map<String, Object> res = routerEvalController.health();
        assertEquals(200, res.get("code"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.get("data");
        assertNotNull(data);
        // 前端要用这些字段渲染"哪几层开着"
        assertTrue(data.containsKey("enabled"));
        assertTrue(data.containsKey("mode"));
        assertTrue(data.containsKey("l1Enabled"));
        assertTrue(data.containsKey("l2Enabled"));
        assertTrue(data.containsKey("l3Enabled"));
        // L2 意图库应当已经就绪（种子随 jar 分发）
        assertEquals(16, data.get("intentLabels"), "15 类意图 + 1 个特殊的 RISK 类");
        assertEquals(240, data.get("seedCount"), "16 类 × 15 条");
    }

    @Test
    void 路由统计接口返回命中率字段() {
        Map<String, Object> res = routerEvalController.stats();
        assertEquals(200, res.get("code"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.get("data");
        assertTrue(data.containsKey("sampleSize"));
        assertTrue(data.containsKey("hitRate"));
        assertTrue(data.containsKey("fallbackRate"));
        assertTrue(data.containsKey("byDecision"));
        assertTrue(data.containsKey("byIntent"));
    }

    @Test
    void 路由近期记录返回列表() {
        Map<String, Object> res = routerEvalController.recent(10);
        assertEquals(200, res.get("code"));
        assertTrue(res.get("data") instanceof List);
    }

    @Test
    void 意图标签表覆盖全部意图且工具名非空() {
        Map<String, Object> res = routerEvalController.labels();
        assertEquals(200, res.get("code"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) res.get("data");
        assertEquals(16, list.size(), "应当与 IntentLabel 枚举数量一致（15 类意图 + RISK）");
        // CHAT / LAW / RISK 是"不需要工具"的意图，工具数为 0 属于正常，其余必须非空
        for (Map<String, Object> item : list) {
            String label = String.valueOf(item.get("label"));
            int count = (Integer) item.get("toolCount");
            if ("CHAT".equals(label) || "LAW".equals(label) || "RISK".equals(label)) {
                assertEquals(0, count, label + " 本就不需要工具");
            } else {
                assertTrue(count > 0, label + " 应当声明工具");
            }
        }
    }

    @Test
    void 干跑接口不写埋点且返回三态工具语义() {
        int before = sampleSize();

        Map<String, Object> res = routerEvalController.dryRun(Map.of("message", "今天北京天气怎么样"));
        assertEquals(200, res.get("code"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.get("data");
        assertEquals("WEATHER", data.get("intent"));
        assertTrue(data.containsKey("toolsOmitted"), "三态语义必须透出（null 表示不过滤）");
        assertFalse(Boolean.TRUE.equals(data.get("toolsOmitted")), "天气意图应当被收窄而不是全量");

        assertEquals(before, sampleSize(), "干跑不能污染线上评测统计");
    }

    private int sampleSize() {
        Map<?, ?> data = (Map<?, ?>) routerEvalController.stats().get("data");
        return ((Number) data.get("sampleSize")).intValue();
    }

    @Test
    void 干跑缺参数返回400() {
        assertEquals(400, routerEvalController.dryRun(null).get("code"));
        assertEquals(400, routerEvalController.dryRun(Map.of()).get("code"));
    }

    // ======================== 指标 ========================

    @Test
    void 指标快照包含四大业务板块() {
        Map<String, Object> res = metricsController.snapshot();
        assertEquals(200, res.get("code"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.get("data");
        assertTrue(data.containsKey("llm"), "缺少 llm 板块");
        assertTrue(data.containsKey("tools"), "缺少 tools 板块");
        assertTrue(data.containsKey("router"), "缺少 router 板块");
        assertTrue(data.containsKey("memory"), "缺少 memory 板块");
        assertTrue(data.containsKey("memoryStore"));
        assertTrue(data.containsKey("jvm"));

        @SuppressWarnings("unchecked")
        Map<String, Object> jvm = (Map<String, Object>) data.get("jvm");
        assertTrue(((Number) jvm.get("heapMaxMb")).longValue() > 0, "堆上限应当可读");
    }

    @Test
    void 指标名清单可用() {
        Map<String, Object> res = metricsController.meters();
        assertEquals(200, res.get("code"));
        assertTrue(res.get("data") instanceof Map);
    }

    // ======================== 记忆脑图 ========================

    @Test
    void 记忆脑图始终有根节点() {
        Map<String, Object> res = memoryGraphController.graph("test-user-no-memory");
        // 记忆脑图接口用的是 {success, data, timestamp}，不是项目主流的 {code, msg, data}
        assertEquals(Boolean.TRUE, res.get("success"));
        assertNotNull(res.get("timestamp"), "方案书要求响应体带 timestamp");

        // data 是 MemoryGraphDTO.Graph 这个 record（Jackson 会按字段名序列化成 JSON 对象），
        // 不是 Map —— 直接取成员访问器而不是当 Map 用
        MemoryGraphDTO.Graph graph = (MemoryGraphDTO.Graph) res.get("data");
        assertFalse(graph.nodes().isEmpty(), "即使该用户没有记忆，也必须有根节点——"
                + "否则前端无法区分'没有数据'和'接口挂了'");

        MemoryGraphDTO.Node root = graph.nodes().get(0);
        assertTrue(root.root());
        assertEquals("__root__", root.id());
        assertEquals("用户画像", root.label(), "根节点代表'这个用户是谁'，方案书口径是「用户画像」");

        assertNotNull(graph.stats(), "统计应当返回，供图例显示计数");
        assertTrue(graph.stats().containsKey("NEW"));
        assertTrue(graph.stats().containsKey("PENDING_GC"));
        assertTrue(graph.stats().containsKey("traceId"));
        assertTrue(graph.stats().containsKey("typeDistribution"));
    }

    @Test
    void 记忆脑图缺userId返回失败() {
        assertEquals(Boolean.FALSE, memoryGraphController.graph(null).get("success"));
        assertEquals(400, memoryGraphController.graph(null).get("code"));
        assertEquals(Boolean.FALSE, memoryGraphController.graph("  ").get("success"));
        assertEquals(400, memoryGraphController.graph("  ").get("code"));
    }

    @Test
    void 记忆健康接口可用() {
        Map<String, Object> res = memoryGraphController.health("test-user");
        assertEquals(Boolean.TRUE, res.get("success"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.get("data");
        assertTrue(data.containsKey("usable"));
        assertTrue(data.containsKey("totalMemories"));
        assertTrue(data.containsKey("indexPath"));
        assertTrue(data.containsKey("dimension"));
    }
}
