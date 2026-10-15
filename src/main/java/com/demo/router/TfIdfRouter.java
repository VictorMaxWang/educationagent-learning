package com.demo.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * L2 层：基于 {@link IntentTfIdfStore} 质心相似度的意图路由。纯本地计算，零 API 调用。
 *
 * <h3>三区间判定</h3>
 * <ul>
 *   <li>{@code sim >= threshold.high} —— 采纳该意图：{@code CHAT} 走零工具，其余走
 *       {@link RouteDecision#TOOL} + 该意图的工具子集。</li>
 *   <li>{@code threshold.low <= sim < threshold.high} —— <b>不确定</b>，返回 FALLBACK
 *       交给 L3（LLM 兜底）再判一次。原始方案书这里叫 UNCERTAIN，本项目用
 *       「FALLBACK + 带候选意图」表达，L3 拿到 {@link RouteResult#getIntent()} 可以据此收敛候选。</li>
 *   <li>{@code sim < threshold.low} —— 落不进任何已知意图。默认判 FALLBACK（全量工具兜底）；
 *       只有 {@code router.tfidf.chat-on-low-similarity=true} 时才判 CHAT。
 *       原因见 {@link RouterProperties.Tfidf#isChatOnLowSimilarity()}。</li>
 * </ul>
 *
 * <p>带附件的消息直接跳过本层——附件消息由 {@code MessagePreprocessor} 转成文字描述后
 * 语义已经偏移，用质心判定不可靠。这也是 {@code MULTIMODAL} 被判为死代码的原因。
 */
@Slf4j
@Component
public class TfIdfRouter implements PreRouter {

    private final RouterProperties properties;
    private final IntentTfIdfStore store;

    public TfIdfRouter(RouterProperties properties, IntentTfIdfStore store) {
        this.properties = properties;
        this.store = store;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled() && properties.getTfidf().isEnabled() && store.isReady();
    }

    @Override
    public String layerName() {
        return "L2";
    }

    @Override
    public RouteResult route(String userId, String message, MessageContext ctx) {
        long start = System.nanoTime();

        if (ctx != null && ctx.hasAttachment()) {
            return RouteResult.fallback("L2:attachment-skip", elapsedMs(start));
        }

        List<IntentTfIdfStore.IntentScore> ranked = store.rank(message);
        if (ranked.isEmpty()) {
            return RouteResult.fallback("L2:no-token", elapsedMs(start));
        }

        IntentTfIdfStore.IntentScore top = ranked.get(0);
        double sim = top.similarity();
        double margin = ranked.size() >= 2 ? sim - ranked.get(1).similarity() : sim;
        double high = properties.getTfidf().getThresholdHigh();
        double low = properties.getTfidf().getThresholdLow();
        double minMargin = properties.getTfidf().getMinMargin();
        IntentLabel label = top.label();

        Map<String, Object> featureMap = new java.util.LinkedHashMap<>();
        featureMap.put("runnerUp", runnerUp(ranked));
        featureMap.put("margin", round(margin));

        // ---- 风险分支：优先级高于意图判定 ----
        // 模糊风险与意图分类共用同一个质心模型，但语义完全不同：
        //   意图判错的代价 = 省不到 token；风险判错的代价 = 拦掉一个正常提问。
        // 所以它有自己的（更严的）阈值、独立开关与独立模式，且**只在 RISK 是 top1 时才考虑**——
        // 一条天气消息不该因为跟风险语料有点像就被拦。
        RouterProperties.Tfidf.Risk risk = properties.getTfidf().getRisk();
        if (risk != null && risk.isEnabled() && label == IntentLabel.RISK) {
            boolean riskHit = sim >= risk.getThreshold() && margin >= risk.getMinMargin();
            String rule = (riskHit ? "L2:risk@" : "L2:risk-below-threshold@")
                    + fmt(sim) + "/m" + fmt(margin);
            featureMap.put("risk", riskHit);
            featureMap.put("riskMode", risk.getMode());

            if (riskHit && risk.isEnforce()) {
                return RouteResult.builder()
                        .decision(RouteDecision.BLOCKED)
                        .intent(IntentLabel.RISK)
                        .matchedRule(rule)
                        .confidence(sim)
                        .vectorSim(sim)
                        .costMs(elapsedMs(start))
                        .tools(java.util.List.of())
                        .layer(layerName())
                        .features(featureMap)
                        .build();
            }
            if (riskHit) {
                // log 模式：只记录、不拦截。这几行日志就是"误报率"的原始数据来源
                // （配合 logs/router-eval.jsonl 一起看：命中量 / 其中多少其实是正常提问）。
                log.warn("⚠️ L2 风险层命中但 mode=log（只记录不拦截）| sim={} margin={} uid={}",
                        fmt(sim), fmt(margin), userId);
            }
            // RISK 永不产出意图结论：以 FALLBACK 交回上层走全量工具兜底。
            // 若放任它走下面的区间 1，会得到 TOOL + 空工具集 —— 那条消息会静默失去全部能力。
            return RouteResult.builder()
                    .decision(RouteDecision.FALLBACK)
                    .intent(IntentLabel.RISK)
                    .matchedRule(rule)
                    .confidence(sim)
                    .vectorSim(sim)
                    .costMs(elapsedMs(start))
                    .layer(layerName())
                    .features(featureMap)
                    .build();
        }

        // ---- 区间 1：高置信，采纳 ----
        // 必须同时满足「绝对相似度够高」和「比第二名高出一截」。
        // 只卡前者会把「这个项目怎么部署」这类类别外需求误判进 LAW（实测 0.2871），
        // 而 LAW/CHAT 的工具集是空的——误判等于让这条消息失去全部工具能力。
        if (sim >= high && margin >= minMargin) {
            RouteDecision decision = label == IntentLabel.CHAT ? RouteDecision.CHAT : RouteDecision.TOOL;
            return RouteResult.builder()
                    .decision(decision)
                    .intent(label)
                    .matchedRule("L2:tfidf:" + label + "@" + fmt(sim) + "/m" + fmt(margin))
                    .confidence(sim)
                    .vectorSim(sim)
                    .costMs(elapsedMs(start))
                    .tools(label.tools())
                    .layer(layerName())
                    .features(featureMap)
                    .build();
        }

        // ---- 区间 2：不确定 → 交 L3 ----
        // 包含两种情形：相似度落在中间带，以及「相似度够高但跟第二名咬得太紧」。
        if (sim >= low) {
            featureMap.put("uncertain", true);
            if (sim >= high) {
                featureMap.put("uncertainReason", "low-margin");
            }
            return RouteResult.builder()
                    .decision(RouteDecision.FALLBACK)
                    .intent(label)
                    .matchedRule("L2:uncertain:" + label + "@" + fmt(sim) + "/m" + fmt(margin))
                    .confidence(sim)
                    .vectorSim(sim)
                    .costMs(elapsedMs(start))
                    .layer(layerName())
                    .features(featureMap)
                    .build();
        }

        // ---- 区间 3：低置信 ----
        if (properties.getTfidf().isChatOnLowSimilarity()) {
            return RouteResult.builder()
                    .decision(RouteDecision.CHAT)
                    .intent(IntentLabel.CHAT)
                    .matchedRule("L2:low-as-chat@" + fmt(sim))
                    .confidence(sim)
                    .vectorSim(sim)
                    .costMs(elapsedMs(start))
                    .tools(IntentLabel.CHAT.tools())
                    .layer(layerName())
                    .features(featureMap)
                    .build();
        }
        return RouteResult.builder()
                .decision(RouteDecision.FALLBACK)
                .intent(label)
                .matchedRule("L2:low@" + fmt(sim) + "/m" + fmt(margin))
                .confidence(sim)
                .vectorSim(sim)
                .costMs(elapsedMs(start))
                .layer(layerName())
                .features(featureMap)
                .build();
    }

    private String runnerUp(List<IntentTfIdfStore.IntentScore> ranked) {
        if (ranked.size() < 2) return "-";
        IntentTfIdfStore.IntentScore second = ranked.get(1);
        return second.label() + "@" + fmt(second.similarity());
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
