package com.demo.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务指标埋点测试。用 {@code SimpleMeterRegistry} 而不是 Spring 上下文——
 * 指标逻辑是纯计算，没必要为它起一个应用。
 */
class BusinessMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new BusinessMetrics(registry);

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String key) {
        return (Map<String, Object>) metrics.snapshot().get(key);
    }

    @Test
    void LLM调用计数与token累加() {
        metrics.recordLlmCall(120, 100, 50, true);
        metrics.recordLlmCall(80, 200, 30, true);

        Map<String, Object> llm = section("llm");
        assertEquals(2L, llm.get("calls"));
        assertEquals(0L, llm.get("failures"));
        assertEquals(380L, llm.get("tokensTotal"), "输入 100+200、输出 50+30 应当累加");
        assertEquals(100.0, llm.get("avgLatencyMs"), "平均耗时应当是 (120+80)/2");
    }

    @Test
    void LLM失败单独计数() {
        metrics.recordLlmCall(50, null, null, true);
        metrics.recordLlmCall(50, null, null, false);

        Map<String, Object> llm = section("llm");
        assertEquals(1L, llm.get("calls"));
        assertEquals(1L, llm.get("failures"));
    }

    /** token 用量拿不到时不能编造 0 之外的数字，也不能让总数被污染成负数。 */
    @Test
    void token为null时不污染统计() {
        metrics.recordLlmCall(10, null, null, true);
        assertEquals(0L, section("llm").get("tokensTotal"));
    }

    /** 流式响应只报输出 token 是常见情况，输入侧为 null 也要能正确累加。 */
    @Test
    void 只有输出token时也能累加() {
        metrics.recordLlmCall(10, null, 42, true);
        assertEquals(42L, section("llm").get("tokensTotal"));
    }

    @Test
    void 工具调用按工具名分组() {
        metrics.recordToolInvoke("getWeather", true);
        metrics.recordToolInvoke("getWeather", true);
        metrics.recordToolInvoke("searchWeb", false);

        Map<String, Object> tools = section("tools");
        assertEquals(3L, tools.get("calls"));
        assertEquals(1L, tools.get("failures"));

        @SuppressWarnings("unchecked")
        Map<String, Long> byTool = (Map<String, Long>) tools.get("byTool");
        assertEquals(2L, byTool.get("getWeather"));
        assertEquals(1L, byTool.get("searchWeb"));
    }

    @Test
    void 工具名为空时归到unknown() {
        metrics.recordToolInvoke(null, true);
        @SuppressWarnings("unchecked")
        Map<String, Long> byTool = (Map<String, Long>) section("tools").get("byTool");
        assertEquals(1L, byTool.get("unknown"));
    }

    @Test
    void 路由命中率计算() {
        metrics.recordRouter("L1", "DRAW");
        metrics.recordRouter("L2", "TOOL");
        metrics.recordRouter("L2", "CHAT");
        metrics.recordRouter("NONE", "FALLBACK");

        Map<String, Object> router = section("router");
        assertEquals(3L, router.get("hits"));
        assertEquals(1L, router.get("fallbacks"));
        assertEquals(0.75, router.get("hitRate"), "3/4 命中率");

        @SuppressWarnings("unchecked")
        Map<String, Long> byLayer = (Map<String, Long>) router.get("byLayer");
        assertEquals(2L, byLayer.get("L2"));
        assertEquals(1L, byLayer.get("L1"));
        assertEquals(1L, byLayer.get("NONE"));
    }

    @Test
    void 没有任何路由时的命中率是0而不是NaN() {
        assertEquals(0.0, section("router").get("hitRate"), "分母为 0 时应当返回 0.0，不能是 NaN");
    }

    @Test
    void 记忆检索命中和未命中都记() {
        metrics.recordMemorySearch(3, 12);
        metrics.recordMemorySearch(0, 8);

        Map<String, Object> memory = section("memory");
        assertEquals(2L, memory.get("searches"));
        assertEquals(1L, memory.get("hits"));
    }

    @Test
    void 指标名清单可用于排查埋点是否生效() {
        metrics.recordLlmCall(1, 1, 1, true);
        metrics.recordToolInvoke("x", true);
        metrics.recordRouter("L1", "CHAT");
        metrics.recordMemorySearch(1, 1);

        var names = metrics.registeredMeterNames();
        assertTrue(names.contains("llm.tokens"));
        assertTrue(names.contains("llm.latency"));
        assertTrue(names.contains("tool.invoke"));
        assertTrue(names.contains("router.hit"));
        assertTrue(names.contains("memory.search"));
    }

    /**
     * 埋点绝不能因为指标系统不可用而影响调用方。
     *
     * <p>所有 record 方法都用 try-catch 包住并只记 debug 日志。这里用一个 null registry
     * 让内部必然抛 NPE，验证异常被吞掉——埋点是旁路能力，不该有能力把对话打挂。
     */
    @Test
    void 埋点异常被吞掉不影响主流程() {
        BusinessMetrics broken = new BusinessMetrics(null);
        broken.recordLlmCall(1, 1, 1, true);
        broken.recordToolInvoke("x", true);
        broken.recordRouter("L1", "CHAT");
        broken.recordMemorySearch(1, 1);
        assertTrue(true, "上述调用全部不抛异常即为通过");
    }
}
