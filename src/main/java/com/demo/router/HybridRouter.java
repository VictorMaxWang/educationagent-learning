package com.demo.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 前置路由总调度器：{@code L1 规则 → L2 TF-IDF → L3 LLM → FALLBACK}。
 *
 * <h3>接线位置（对方案书的有意改进）</h3>
 * 方案书要求在 {@code WechatMessageService.processSingleMessage()} 和
 * {@code TextMessageHandler} 两处调用。本项目有<b>两个</b>平级的文本入口
 * （微信 {@code CmdService}、桌面 {@code SessionChatStreamer}），
 * 它们最终都汇到 {@code AgentExecutor}。所以本路由只在
 * {@code AgentExecutor} 内部调用一次——<b>一处接线，两个入口同时生效</b>，
 * 不存在"漏接一个入口"的风险（方案书 Prompt 10 记录过同类教训）。
 *
 * <h3>职责边界</h3>
 * 本类只做<b>分类</b>与<b>通用工具并集</b>；求交集（哪些工具当前真的注册了）
 * 必须由 {@code AgentExecutor} 完成，因为它才知道静态工具与动态 MCP 工具的全集。
 */
@Slf4j
@Component
public class HybridRouter implements PreRouter {

    private final RouterProperties properties;
    private final RuleBasedRouter ruleRouter;
    private final TfIdfRouter tfIdfRouter;
    private final LlmRouter llmRouter;
    private final RouterEvalRecorder recorder;

    public HybridRouter(RouterProperties properties,
                        RuleBasedRouter ruleRouter,
                        TfIdfRouter tfIdfRouter,
                        LlmRouter llmRouter,
                        RouterEvalRecorder recorder) {
        this.properties = properties;
        this.ruleRouter = ruleRouter;
        this.tfIdfRouter = tfIdfRouter;
        this.llmRouter = llmRouter;
        this.recorder = recorder;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public String layerName() {
        return "HYBRID";
    }

    /**
     * 三层漏斗。任何一层给出非 FALLBACK 的结论即采纳；全部落空则 FALLBACK（全量工具兜底）。
     */
    @Override
    public RouteResult route(String userId, String message, MessageContext ctx) {
        long start = System.nanoTime();
        return finish(userId, message, ctx, funnel(userId, message, ctx), start);
    }

    /**
     * 三层漏斗本体：任何一层给出非 FALLBACK 的结论即采纳；全部落空则 FALLBACK（全量工具兜底）。
     */
    private RouteResult funnel(String userId, String message, MessageContext ctx) {
        if (!properties.isEnabled()) {
            return RouteResult.fallback("router-disabled", 0L);
        }

        RouteResult result = null;

        // ---- L1 规则层 ----
        if (ruleRouter.isEnabled()) {
            result = ruleRouter.route(userId, message, ctx);
            if (result.getDecision() != RouteDecision.FALLBACK) {
                return result;
            }
        }

        // ---- L2 TF-IDF 层（router.mode=rule 时整层不装）----
        boolean hybrid = !"rule".equalsIgnoreCase(properties.getMode());
        if (hybrid && tfIdfRouter.isEnabled()) {
            RouteResult l2 = tfIdfRouter.route(userId, message, ctx);
            if (l2.getDecision() != RouteDecision.FALLBACK) {
                return l2;
            }
            result = l2; // 保留候选意图，供 L3 参考
        }

        // ---- L3 LLM 兜底层 ----
        if (hybrid && llmRouter.isEnabled()) {
            RouteResult l3 = llmRouter.route(userId, message, ctx);
            if (l3.getDecision() != RouteDecision.FALLBACK) {
                return l3;
            }
            if (result == null) result = l3;
        }

        // ---- 全员落空 ----
        return result != null ? result : RouteResult.fallback("no-layer-hit", 0L);
    }

    /**
     * 干跑：只做判定、不写埋点，供 {@code POST /api/router/route} 做评测/调参。
     * <p>必须与 {@link #route} 共用同一套漏斗逻辑，否则"评测结果"会与线上行为不一致。
     */
    public RouteResult dryRun(String userId, String message, MessageContext ctx) {
        long start = System.nanoTime();
        if (!properties.isEnabled()) {
            return RouteResult.fallback("router-disabled", elapsedMs(start));
        }
        RouteResult r = funnel(userId, message, ctx);
        return applyCommonTools(r);
    }

    // ======================== 收尾：并集通用工具 + 埋点 ========================

    private RouteResult finish(String userId, String message, MessageContext ctx,
                               RouteResult r, long startNanos) {
        RouteResult finalResult = applyCommonTools(r);

        // 记录成本（各层自己算的 costMs 只含本层耗时，这里补齐总耗时）
        long total = elapsedMs(startNanos);
        if (finalResult.getCostMs() <= 0) {
            finalResult = copyWithCost(finalResult, total);
        }
        logResult(userId, finalResult);
        return finalResult;
    }

    // ======================== 供消费方（AgentExecutor）调用的钩子 ========================

    /**
     * 写入评测埋点。
     *
     * <p><b>为什么不在本类里直接记</b>：埋点要记的是"最终生效的工具数"，
     * 而那要等 {@code AgentExecutor} 把意图工具与当前真实注册的工具求完交集才知道。
     * 若在这里记，记录的会是路由层的"期望值"，与线上实际不符——
     * 方案书把"工具过滤没生效"列为头号 bug，正是这种"以为过滤了其实没有"的偏差。
     */
    public void recordEval(String userId, String message, RouteResult result, MessageContext ctx) {
        recorder.record(userId, message, result, ctx);
    }

    /** 工具过滤总开关。 */
    public boolean isToolFilteringEnabled() {
        return properties.getToolFiltering().isEnabled();
    }

    /** 安全阀：意图工具在当前工具集里一个都不存在时是否回退全量。 */
    public boolean isFallbackOnEmpty() {
        return properties.getToolFiltering().isFallbackOnEmpty();
    }

    /** 单个工具 schema 的估算 token 数（埋点用）。 */
    public int tokensPerTool() {
        return properties.getToolFiltering().getTokensPerTool();
    }

    /**
     * 把 {@code router.tool-filtering.common-tools} 并进意图工具集。
     * <p>三态语义：{@code tools == null} 表示"不过滤"，绝不能被并集变成"只发这几个"。
     */
    private RouteResult applyCommonTools(RouteResult r) {
        if (!properties.getToolFiltering().isEnabled()) {
            // 关闭过滤 → 一律全量，行为与改造前一致
            return r.getTools() == null ? r : withTools(r, null);
        }
        if (r.getTools() == null) {
            return r;
        }
        // 闲聊不并通用工具：CHAT 的价值就在于"0 工具"，
        // 并进来一个 searchWeb 会让"你好"也背上工具 schema，省 token 的意义就没了。
        if (r.getDecision() == RouteDecision.CHAT) {
            return r;
        }
        Set<String> union = new LinkedHashSet<>(r.getTools());
        union.addAll(properties.getToolFiltering().getCommonTools());
        return withTools(r, new ArrayList<>(union));
    }

    private RouteResult withTools(RouteResult r, List<String> tools) {
        return RouteResult.builder()
                .decision(r.getDecision()).intent(r.getIntent()).matchedRule(r.getMatchedRule())
                .confidence(r.getConfidence()).costMs(r.getCostMs()).features(r.getFeatures())
                .tools(tools)
                .vectorSim(r.getVectorSim()).llmIntent(r.getLlmIntent()).tokenUsed(r.getTokenUsed())
                .toolsCount(r.getToolsCount()).toolsSaved(r.getToolsSaved())
                .layer(r.getLayer()).reply(r.getReply())
                .build();
    }

    private RouteResult copyWithCost(RouteResult r, long costMs) {
        return RouteResult.builder()
                .decision(r.getDecision()).intent(r.getIntent()).matchedRule(r.getMatchedRule())
                .confidence(r.getConfidence()).costMs(costMs).features(r.getFeatures())
                .tools(r.getTools())
                .vectorSim(r.getVectorSim()).llmIntent(r.getLlmIntent()).tokenUsed(r.getTokenUsed())
                .toolsCount(r.getToolsCount()).toolsSaved(r.getToolsSaved())
                .layer(r.getLayer()).reply(r.getReply())
                .build();
    }

    private void logResult(String userId, RouteResult r) {
        log.info("ROUTE | uid={} | layer={} | decision={} | intent={} | conf={} | sim={} | cost={}ms | tools={}",
                userId, r.getLayer(), r.getDecision(), r.getIntent(),
                String.format("%.3f", r.getConfidence()), String.format("%.3f", r.getVectorSim()),
                r.getCostMs(), r.getTools() == null ? "ALL" : r.getTools().size());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
