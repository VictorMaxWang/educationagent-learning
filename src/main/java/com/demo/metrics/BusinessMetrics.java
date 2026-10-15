package com.demo.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标埋点：LLM 消耗、工具调用、路由命中、记忆检索。
 *
 * <h3>为什么不用 AOP 切面</h3>
 * 方案书建议用 AOP / 拦截器统一埋点。本项目<b>故意不用</b>：
 * 需要埋的地方只有 4 处（LLM 调用、工具执行、路由判定、记忆检索），
 * 而这几处的上下文（token 用量、工具名、路由层级）都是 AOP 拿不到或拿起来很别扭的
 * ——比如 token 数在 {@code ChatResponse} 里、工具名在 {@code ToolExecutionRequest} 里。
 * 用切面就得写一堆 {@code ThreadLocal} 搬运，反而更容易出错。
 * 直接调用四个方法，一目了然且零反射开销。
 *
 * <h3>指标命名</h3>
 * 与方案书一致：{@code llm.tokens} / {@code llm.latency} / {@code tool.invoke} /
 * {@code router.hit} / {@code memory.search}。
 * Prometheus 抓取时会被自动加上 {@code _total} / {@code _seconds} 后缀，属正常。
 *
 * <h3>为什么同时维护自增计数器</h3>
 * Micrometer 的取值要遍历 meter 注册表，成本不低；而快照接口是给前端 Dashboard 轮询的。
 * 高频指标（LLM 调用数、token 数）另用 {@link AtomicLong} 记一份，
 * 让"取快照"变成几次原子读，而不是一次注册表全扫描。
 */
@Slf4j
@Component
public class BusinessMetrics {

    private static final String M_LLM_TOKENS = "llm.tokens";
    private static final String M_LLM_LATENCY = "llm.latency";
    private static final String M_TOOL_INVOKE = "tool.invoke";
    private static final String M_ROUTER_HIT = "router.hit";
    private static final String M_MEMORY_SEARCH = "memory.search";

    // ---- 教育域（教师助教工作台 / 班级预警）----
    private static final String M_TEACHER_FEEDBACK = "teacher.feedback.submit.count";
    private static final String M_TEACHER_UPLOAD = "teacher.knowledge.upload.count";
    private static final String M_CLASS_WARN = "class.warn.total.count";

    private final MeterRegistry registry;

    // ---- 高频指标的原子副本（供快照快速读取） ----
    private final AtomicLong llmCallCount = new AtomicLong();
    private final AtomicLong llmFailureCount = new AtomicLong();
    private final AtomicLong llmTokenTotal = new AtomicLong();
    private final AtomicLong toolCallCount = new AtomicLong();
    private final AtomicLong toolFailureCount = new AtomicLong();
    private final AtomicLong routerHitCount = new AtomicLong();
    private final AtomicLong routerFallbackCount = new AtomicLong();
    private final AtomicLong memorySearchCount = new AtomicLong();
    private final AtomicLong memoryHitCount = new AtomicLong();
    private final AtomicLong teacherFeedbackCount = new AtomicLong();
    private final AtomicLong teacherKnowledgeUploadCount = new AtomicLong();
    private final AtomicLong classWarnCount = new AtomicLong();

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记录一次 LLM 调用。 */
    public void recordLlmCall(long latencyMs, Integer inputTokens, Integer outputTokens, boolean success) {
        try {
            Timer timer = Timer.builder(M_LLM_LATENCY)
                    .description("LLM 调用耗时")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry);
            timer.record(Math.max(0, latencyMs), TimeUnit.MILLISECONDS);

            if (success) {
                llmCallCount.incrementAndGet();
            } else {
                llmFailureCount.incrementAndGet();
                Counter.builder(M_LLM_LATENCY).tag("outcome", "failure").register(registry).increment();
            }

            int in = inputTokens == null ? 0 : inputTokens;
            int out = outputTokens == null ? 0 : outputTokens;
            if (in > 0) {
                Counter.builder(M_LLM_TOKENS).tag("type", "input").register(registry).increment(in);
            }
            if (out > 0) {
                Counter.builder(M_LLM_TOKENS).tag("type", "output").register(registry).increment(out);
            }
            llmTokenTotal.addAndGet(in + out);
        } catch (Exception e) {
            // 埋点永远不能影响主流程
            log.debug("记录 LLM 指标失败：{}", e.toString());
        }
    }

    /** 记录一次工具调用。 */
    public void recordToolInvoke(String toolName, boolean success) {
        try {
            String name = toolName == null || toolName.isBlank() ? "unknown" : toolName;
            Counter.builder(M_TOOL_INVOKE)
                    .tag("tool", name)
                    .tag("outcome", success ? "success" : "failure")
                    .register(registry)
                    .increment();
            toolCallCount.incrementAndGet();
            if (!success) toolFailureCount.incrementAndGet();
        } catch (Exception e) {
            log.debug("记录工具指标失败：{}", e.toString());
        }
    }

    /** 记录一次路由判定。 */
    public void recordRouter(String layer, String decision) {
        try {
            String d = decision == null ? "UNKNOWN" : decision;
            Counter.builder(M_ROUTER_HIT)
                    .tag("layer", layer == null ? "NONE" : layer)
                    .tag("decision", d)
                    .register(registry)
                    .increment();
            if ("FALLBACK".equals(d)) {
                routerFallbackCount.incrementAndGet();
            } else {
                routerHitCount.incrementAndGet();
            }
        } catch (Exception e) {
            log.debug("记录路由指标失败：{}", e.toString());
        }
    }

    /** 记录一次记忆检索。 */
    public void recordMemorySearch(int hits, long latencyMs) {
        try {
            Timer.builder(M_MEMORY_SEARCH)
                    .description("记忆检索耗时")
                    .tag("outcome", hits > 0 ? "hit" : "miss")
                    .register(registry)
                    .record(Math.max(0, latencyMs), TimeUnit.MILLISECONDS);
            memorySearchCount.incrementAndGet();
            if (hits > 0) memoryHitCount.incrementAndGet();
        } catch (Exception e) {
            log.debug("记录记忆指标失败：{}", e.toString());
        }
    }

    /** 记录一次教师错误反馈。 */
    public void recordTeacherFeedbackSubmitted() {
        try {
            Counter.builder(M_TEACHER_FEEDBACK)
                    .description("教师提交 AI 错误反馈次数")
                    .register(registry).increment();
            teacherFeedbackCount.incrementAndGet();
        } catch (Exception e) {
            log.debug("记录教师反馈指标失败：{}", e.toString());
        }
    }

    /** 记录一次教师上传校本知识库。 */
    public void recordTeacherKnowledgeUploaded() {
        try {
            Counter.builder(M_TEACHER_UPLOAD)
                    .description("教师上传校本知识库次数")
                    .register(registry).increment();
            teacherKnowledgeUploadCount.incrementAndGet();
        } catch (Exception e) {
            log.debug("记录校本上传指标失败：{}", e.toString());
        }
    }

    /**
     * 记录班级情绪预警。
     *
     * @param count 本次新增的预警条数（通常为 1；被当日去重拦掉时为 0）
     */
    public void recordClassWarn(int count) {
        if (count <= 0) return;
        try {
            Counter.builder(M_CLASS_WARN)
                    .description("班级情绪预警总次数（按班级按日聚合，无个人标识）")
                    .register(registry).increment(count);
            classWarnCount.addAndGet(count);
        } catch (Exception e) {
            log.debug("记录班级预警指标失败：{}", e.toString());
        }
    }

    /**
     * 业务指标快照，供 {@code GET /api/metrics/snapshot} 使用。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("calls", llmCallCount.get());
        llm.put("failures", llmFailureCount.get());
        llm.put("tokensTotal", llmTokenTotal.get());
        llm.put("avgLatencyMs", avgLatencyMs(M_LLM_LATENCY));
        llm.put("p95LatencyMs", percentileMs(M_LLM_LATENCY, 0.95));
        out.put("llm", llm);

        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("calls", toolCallCount.get());
        tools.put("failures", toolFailureCount.get());
        tools.put("byTool", counterByTag(M_TOOL_INVOKE, "tool"));
        out.put("tools", tools);

        Map<String, Object> router = new LinkedHashMap<>();
        router.put("hits", routerHitCount.get());
        router.put("fallbacks", routerFallbackCount.get());
        long total = routerHitCount.get() + routerFallbackCount.get();
        router.put("hitRate", total == 0 ? 0.0 : round((double) routerHitCount.get() / total));
        router.put("byDecision", counterByTag(M_ROUTER_HIT, "decision"));
        router.put("byLayer", counterByTag(M_ROUTER_HIT, "layer"));
        out.put("router", router);

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("searches", memorySearchCount.get());
        memory.put("hits", memoryHitCount.get());
        memory.put("avgLatencyMs", avgLatencyMs(M_MEMORY_SEARCH));
        out.put("memory", memory);

        // 教育域（教师助教工作台）：三项都是"动作发生次数"，不含任何学生个体信息
        Map<String, Object> edu = new LinkedHashMap<>();
        edu.put("teacherFeedbackSubmits", teacherFeedbackCount.get());
        edu.put("teacherKnowledgeUploads", teacherKnowledgeUploadCount.get());
        edu.put("classWarnTotal", classWarnCount.get());
        out.put("edu", edu);

        return out;
    }

    // ======================== Micrometer 读取辅助 ========================

    private double avgLatencyMs(String meterName) {
        double totalMs = 0;
        long count = 0;
        for (Timer t : registry.find(meterName).timers()) {
            totalMs += t.totalTime(TimeUnit.MILLISECONDS);
            count += t.count();
        }
        return count == 0 ? 0.0 : round(totalMs / count);
    }

    private double percentileMs(String meterName, double percentile) {
        double best = 0;
        for (Timer t : registry.find(meterName).timers()) {
            double v = t.percentile(percentile, TimeUnit.MILLISECONDS);
            best = Math.max(best, v);
        }
        return round(best);
    }

    /** 按某个 tag 维度汇总计数：{tagValue: count}。 */
    private Map<String, Long> counterByTag(String meterName, String tagKey) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Counter c : registry.find(meterName).counters()) {
            String key = c.getId().getTag(tagKey);
            if (key == null) continue;
            out.merge(key, Math.round(c.count()), Long::sum);
        }
        return out;
    }

    /** 已注册的指标名（排查"指标到底有没有注册上"时很有用）。 */
    public List<String> registeredMeterNames() {
        return registry.getMeters().stream()
                .map(m -> m.getId().getName())
                .distinct()
                .sorted()
                .toList();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
