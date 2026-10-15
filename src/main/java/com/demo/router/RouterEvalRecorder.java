package com.demo.router;

import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 路由评测埋点：把每次路由判定记到 {@code logs/router-eval.jsonl}，并在内存里留一份ring buffer 供 REST 查询。
 *
 * <p>本项目此前<b>没有任何追加写文件的先例</b>——所有落盘都是覆盖式
 * （{@code TraceContext} 一 trace 一文件、各 Indexer 的指纹文件都是 {@code Files.writeString} 全量写）。
 * 所以这里显式引入 {@link StandardOpenOption#APPEND} + {@link StandardOpenOption#CREATE}。
 *
 * <p>为什么 JSONL 而不是数据库表：路由判定是每消息一次的<b>高频</b>写入，
 * 走 MySQL 会给主链路加一次同步写；JSONL 追加是 O(1) 且可被外部工具（jq / pandas）直接消费。
 * 需要统计时读内存 ring buffer，不重复读文件。这与 RAG 评测（{@code rag_eval_*} 表）的分工不同——
 * RAG 评测是低频的、需要 SQL 聚合的，所以落库；路由是高频流式的，所以落文件。
 */
@Slf4j
@Component
public class RouterEvalRecorder {

    /** 单条消息在日志里保留的最大字符数。 */
    private static final int MSG_PREVIEW_CHARS = 80;

    private final RouterProperties properties;

    /** 业务指标（可选；放在 router 包下但用 @Resource 注入，避免与 metrics 包形成构造依赖）。 */
    @jakarta.annotation.Resource
    private com.demo.metrics.BusinessMetrics businessMetrics;

    /** ring buffer：只保留最近 N 条，供 REST 快速查询。 */
    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong fallbackCalls = new AtomicLong();
    private final AtomicLong failedWrites = new AtomicLong();

    public RouterEvalRecorder(RouterProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("【前置路由】router.enabled=false，路由埋点未启用");
            return;
        }
        log.info("【前置路由】埋点输出：{}（switch: router.eval.log-enabled={}）",
                properties.getEval().getLogPath(), properties.getEval().isLogEnabled());
    }

    /**
     * 记录一次路由判定。<b>绝不抛异常</b>——埋点失败不能影响主链路。
     */
    public void record(String userId, String message, RouteResult result, MessageContext ctx) {
        try {
            totalCalls.incrementAndGet();
            if (result == null || result.getDecision() == RouteDecision.FALLBACK) {
                fallbackCalls.incrementAndGet();
            }
            // 同步推到 Micrometer：jsonl 给人看，Micrometer 给 Prometheus/面板看
            if (businessMetrics != null && result != null) {
                businessMetrics.recordRouter(result.getLayer(),
                        result.getDecision() == null ? null : result.getDecision().name());
            }
            JSONObject line = toJson(userId, message, result, ctx);
            appendToFile(line);
            pushRecent(line);
        } catch (Exception e) {
            // 只记一次警告，避免刷日志
            if (failedWrites.incrementAndGet() % 100 == 1) {
                log.warn("【前置路由】埋点记录失败（已忽略 {} 次）：{}", failedWrites.get(), e.toString());
            }
        }
    }

    private JSONObject toJson(String userId, String message, RouteResult r, MessageContext ctx) {
        JSONObject o = new JSONObject();
        o.put("ts", System.currentTimeMillis());
        o.put("userId", userId);
        o.put("channel", ctx == null ? "UNKNOWN" : ctx.channel().name());
        o.put("attachment", ctx != null && ctx.hasAttachment());
        if (r != null) {
            o.put("layer", r.getLayer());
            o.put("decision", r.getDecision() == null ? null : r.getDecision().name());
            o.put("intent", r.getIntent() == null ? null : r.getIntent().name());
            o.put("matchedRule", r.getMatchedRule());
            o.put("confidence", round(r.getConfidence()));
            o.put("vectorSim", round(r.getVectorSim()));
            o.put("costMs", r.getCostMs());
            o.put("tools", r.getTools() == null ? "ALL" : String.valueOf(r.getTools().size()));
            o.put("toolsCount", r.getToolsCount());
            o.put("toolsSaved", r.getToolsSaved());
            o.put("tokenUsed", r.getTokenUsed());
        }
        o.put("msg", preview(message));
        return o;
    }

    private void appendToFile(JSONObject line) throws IOException {
        if (!properties.getEval().isLogEnabled()) return;
        Path path = Paths.get(properties.getEval().getLogPath());
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path,
                line.toJSONString() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void pushRecent(JSONObject line) {
        // ts/firstLine 之外的字段都是不可变值，直接复用同一份 JSON 对象（只读）
        Map<String, Object> asMap = new LinkedHashMap<>(line);
        synchronized (recent) {
            recent.addFirst(asMap);
            int cap = Math.max(10, properties.getEval().getRecentBufferSize());
            while (recent.size() > cap) {
                recent.removeLast();
            }
        }
    }

    // ======================== 查询 ========================

    /** 最近 N 条记录（时间倒序）。 */
    public List<Map<String, Object>> recent(int limit) {
        int n = Math.max(1, Math.min(limit, 1000));
        synchronized (recent) {
            List<Map<String, Object>> out = new ArrayList<>(Math.min(n, recent.size()));
            int i = 0;
            for (Map<String, Object> m : recent) {
                if (i++ >= n) break;
                out.add(m);
            }
            return out;
        }
    }

    /**
     * 汇总统计：命中率、各决策分布、各层分布、平均耗时与平均省下的工具数。
     * <p>基于内存 ring buffer 计算，样本量上限即 {@code router.eval.recent-buffer-size}。
     */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> snapshot;
        synchronized (recent) {
            snapshot = new ArrayList<>(recent);
        }

        long total = snapshot.size();
        long fallback = 0;
        long costSum = 0;
        long savedSum = 0;
        long savedSamples = 0;
        Map<String, Integer> byDecision = new LinkedHashMap<>();
        Map<String, Integer> byLayer = new LinkedHashMap<>();
        Map<String, Integer> byIntent = new LinkedHashMap<>();

        for (Map<String, Object> m : snapshot) {
            String decision = str(m.get("decision"));
            String layer = str(m.get("layer"));
            String intent = str(m.get("intent"));
            byDecision.merge(decision, 1, Integer::sum);
            byLayer.merge(layer, 1, Integer::sum);
            if (!"-".equals(intent)) {
                byIntent.merge(intent, 1, Integer::sum);
            }
            if ("FALLBACK".equals(decision)) fallback++;
            Object cost = m.get("costMs");
            if (cost instanceof Number n) costSum += n.longValue();
            Object saved = m.get("toolsSaved");
            if (saved instanceof Number n && n.longValue() >= 0) {
                savedSum += n.longValue();
                savedSamples++;
            }
        }

        out.put("sampleSize", total);
        out.put("totalCallsLifetime", totalCalls.get());
        out.put("fallbackCallsLifetime", fallbackCalls.get());
        out.put("fallbackRate", total == 0 ? 0.0 : round((double) fallback / total));
        out.put("hitRate", total == 0 ? 0.0 : round(1.0 - (double) fallback / total));
        out.put("avgCostMs", total == 0 ? 0.0 : round((double) costSum / total));
        out.put("avgToolsSaved", savedSamples == 0 ? 0.0 : round((double) savedSum / savedSamples));
        out.put("byDecision", byDecision);
        out.put("byLayer", byLayer);
        out.put("byIntent", byIntent);
        return out;
    }

    /** 清空内存缓冲（不影响已落盘的 jsonl）。 */
    public void clearBuffer() {
        synchronized (recent) {
            recent.clear();
        }
    }

    // ======================== 小工具 ========================

    private static String preview(String message) {
        if (message == null) return "";
        String s = message.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > MSG_PREVIEW_CHARS ? s.substring(0, MSG_PREVIEW_CHARS) + "…" : s;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String str(Object o) {
        return o == null ? "-" : String.valueOf(o);
    }
}
