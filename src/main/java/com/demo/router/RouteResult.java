package com.demo.router;

import java.util.List;
import java.util.Map;

/**
 * 前置路由的统一结果。
 *
 * <p>由 {@code RuleBasedRouter}(L1) → {@code TfIdfRouter}(L2) → {@code LlmRouter}(L3) 逐层产出，
 * {@code HybridRouter} 汇总后交给消费方（{@code AgentExecutor.llmToolSpecifications} 做工具过滤、
 * {@code MemoryIntegrationService} 做法律 RAG 门控、{@code RouterEvalRecorder} 做埋点）。
 *
 * <h3>{@link #getTools()} 的三态语义（核心约定，不要改）</h3>
 * <ul>
 *   <li>{@code null} —— 不做过滤，发全量工具。{@link RouteDecision#FALLBACK} 专用，是安全兜底。</li>
 *   <li>{@code []}  —— 零工具（闲聊 / 法律这类"不需要工具、靠上下文作答"的场景）。</li>
 *   <li>非空 —— 只发这些工具。</li>
 * </ul>
 * 消费方<b>必须</b>按三态处理；把 {@code null} 当成空列表会导致所有未识别消息丢失全部工具能力。
 *
 * <p>保留了旧 {@code SkillRouter} 用的 {@link #handled}/{@link #unhandled} 工厂方法与
 * {@link #isHandled()}，以免破坏那个类。它们已标记 {@link Deprecated}——
 * {@code SkillRouter} 全项目无调用方（CLAUDE.md 已登记为死代码），后续清理时可一并移除。
 */
public class RouteResult {

    private final RouteDecision decision;
    private final IntentLabel intent;
    private final String matchedRule;
    private final double confidence;
    private final long costMs;
    private final Map<String, Object> features;

    /** 三态：null=全量工具，[]=零工具，非空=子集。 */
    private final List<String> tools;

    private final double vectorSim;
    private final String llmIntent;
    private final int tokenUsed;
    private final int toolsCount;
    private final int toolsSaved;
    private final String layer;
    private final String reply;

    private RouteResult(Builder b) {
        this.decision = b.decision;
        this.intent = b.intent;
        this.matchedRule = b.matchedRule;
        this.confidence = b.confidence;
        this.costMs = b.costMs;
        this.features = b.features == null ? Map.of() : Map.copyOf(b.features);
        this.tools = b.tools;
        this.vectorSim = b.vectorSim;
        this.llmIntent = b.llmIntent;
        this.tokenUsed = b.tokenUsed;
        this.toolsCount = b.toolsCount;
        this.toolsSaved = b.toolsSaved;
        this.layer = b.layer;
        this.reply = b.reply;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 未识别 → FALLBACK，且 {@code tools=null}（发全量工具）——最安全的兜底。 */
    public static RouteResult fallback(String reason, long costMs) {
        return builder()
                .decision(RouteDecision.FALLBACK)
                .matchedRule(reason)
                .costMs(costMs)
                .layer("NONE")
                .build();
    }

    // ======================== 访问器 ========================

    public RouteDecision getDecision() { return decision; }
    public IntentLabel getIntent() { return intent; }
    public String getMatchedRule() { return matchedRule; }
    public double getConfidence() { return confidence; }
    public long getCostMs() { return costMs; }
    public Map<String, Object> getFeatures() { return features; }
    public List<String> getTools() { return tools; }
    public double getVectorSim() { return vectorSim; }
    public String getLlmIntent() { return llmIntent; }
    public int getTokenUsed() { return tokenUsed; }
    public int getToolsCount() { return toolsCount; }
    public int getToolsSaved() { return toolsSaved; }
    public String getLayer() { return layer; }
    public String getReply() { return reply; }

    /** 是否允许据此过滤工具。FALLBACK 与三态 {@code null} 一律不过滤。 */
    public boolean isToolFilterable() {
        return tools != null && decision.isFilterable();
    }

    /**
     * 返回一份「已应用工具选择」的副本，用于埋点回填真实生效的工具数量。
     *
     * <p>路由层只知道<b>想要</b>哪些工具，不知道当前真实注册了哪些（静态工具随
     * {@code AgentConfig} 装配、MCP 工具随懒加载动态增删），所以求交集由
     * {@code AgentExecutor} 完成；完成之后用它回填，埋点记的才是真实值。
     *
     * <p>{@code finalTools} 传 {@code null} 表示<b>最终没有过滤</b>
     * （FALLBACK，或安全阀判定要回退全量）——此时 {@link #getTools()} 会被置回
     * {@code null}，让下游的"三态"判断重新变成"发全量"，这是安全阀能生效的关键。
     *
     * @param finalTools 交集后的最终工具名；{@code null} = 不过滤（全量）
     * @param totalTools 当前注册的工具总数
     */
    public RouteResult withToolSelection(List<String> finalTools, int totalTools) {
        return builder()
                .decision(decision).intent(intent).matchedRule(matchedRule)
                .confidence(confidence).costMs(costMs).features(features)
                .tools(finalTools)
                .vectorSim(vectorSim).llmIntent(llmIntent).tokenUsed(tokenUsed)
                .toolsCount(finalTools == null ? 0 : finalTools.size())
                .toolsSaved(finalTools == null ? 0 : Math.max(0, totalTools - finalTools.size()))
                .layer(layer).reply(reply)
                .build();
    }

    // ======================== 旧 API 兼容（SkillRouter 专用，已废弃） ========================

    /** @deprecated 旧 SkillRouter 语义，新代码请用 {@link #builder()}。 */
    @Deprecated
    public static RouteResult handled(String reply, String toolName, double score) {
        return builder()
                .decision(RouteDecision.TOOL)
                .matchedRule(toolName)
                .confidence(score)
                .reply(reply)
                .layer("LEGACY")
                .build();
    }

    /** @deprecated 旧 SkillRouter 语义，请用 {@link #fallback(String, long)}。 */
    @Deprecated
    public static RouteResult unhandled() {
        return fallback("legacy-unhandled", 0L);
    }

    /** @deprecated 旧 SkillRouter 语义。 */
    @Deprecated
    public boolean isHandled() {
        return reply != null;
    }

    @Override
    public String toString() {
        return "RouteResult{layer=" + layer + ", decision=" + decision + ", intent=" + intent
                + ", rule=" + matchedRule + ", conf=" + String.format("%.3f", confidence)
                + ", costMs=" + costMs + ", tools=" + (tools == null ? "ALL" : tools.size())
                + ", saved=" + toolsSaved + '}';
    }

    // ======================== Builder ========================

    public static class Builder {
        private RouteDecision decision = RouteDecision.FALLBACK;
        private IntentLabel intent;
        private String matchedRule;
        private double confidence;
        private long costMs;
        private Map<String, Object> features;
        private List<String> tools;
        private double vectorSim;
        private String llmIntent;
        private int tokenUsed;
        private int toolsCount;
        private int toolsSaved;
        private String layer;
        private String reply;

        public Builder decision(RouteDecision v) { this.decision = v; return this; }
        public Builder intent(IntentLabel v) { this.intent = v; return this; }
        public Builder matchedRule(String v) { this.matchedRule = v; return this; }
        public Builder confidence(double v) { this.confidence = v; return this; }
        public Builder costMs(long v) { this.costMs = v; return this; }
        public Builder features(Map<String, Object> v) { this.features = v; return this; }
        public Builder tools(List<String> v) { this.tools = v; return this; }
        public Builder vectorSim(double v) { this.vectorSim = v; return this; }
        public Builder llmIntent(String v) { this.llmIntent = v; return this; }
        public Builder tokenUsed(int v) { this.tokenUsed = v; return this; }
        public Builder toolsCount(int v) { this.toolsCount = v; return this; }
        public Builder toolsSaved(int v) { this.toolsSaved = v; return this; }
        public Builder layer(String v) { this.layer = v; return this; }
        public Builder reply(String v) { this.reply = v; return this; }

        public RouteResult build() {
            return new RouteResult(this);
        }
    }
}
