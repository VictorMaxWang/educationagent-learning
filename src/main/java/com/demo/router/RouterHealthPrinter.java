package com.demo.router;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import com.demo.config.AiChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动期打印【前置路由】配置面板，并提供一份可被 REST 复用的运行时快照。
 *
 * <p>方案书 Prompt 6/8 都要求"启动面板"——把生效的开关、阈值、模型状态一次性打出来。
 * 这个项目现在的痛点是配置项散在 properties 里、默认值藏在 Java 里，
 * 不打印面板就无法在启动日志里确认"到底是哪套参数在跑"。
 */
@Slf4j
@Component
public class RouterHealthPrinter {

    @Resource
    private AiChatProperties aiChat;

    private final RouterProperties properties;
    private final IntentTfIdfStore tfIdfStore;
    private final RuleBasedRouter ruleRouter;
    private final TfIdfRouter tfIdfRouter;
    private final LlmRouter llmRouter;
    private final CircuitBreaker circuitBreaker;

    public RouterHealthPrinter(RouterProperties properties,
                               IntentTfIdfStore tfIdfStore,
                               RuleBasedRouter ruleRouter,
                               TfIdfRouter tfIdfRouter,
                               LlmRouter llmRouter,
                               @Qualifier("llmRouterCircuitBreaker") CircuitBreaker circuitBreaker) {
        this.properties = properties;
        this.tfIdfStore = tfIdfStore;
        this.ruleRouter = ruleRouter;
        this.tfIdfRouter = tfIdfRouter;
        this.llmRouter = llmRouter;
        this.circuitBreaker = circuitBreaker;
    }

    @PostConstruct
    public void print() {
        if (!properties.isEnabled()) {
            log.info("【前置路由】router.enabled=false —— 路由未启用，行为与改造前完全一致");
            return;
        }
        log.info("""

                ═══════════════ 【前置路由】配置 ═══════════════
                 总开关        : enabled={}  mode={}
                 L1 规则层     : enabled={}
                 L2 TF-IDF 层  : enabled={}  high={}  low={}  low→CHAT={}
                                 意图 {} 类 / 种子 {} 条 / 词表 {} 维 / 来源={}
                 L2 风险层     : enabled={}  mode={}{}
                                 阈值={}  支配度={}（比意图判定更严，误报代价是拦掉正常提问）
                 L3 LLM 兜底   : enabled={}  model={}  timeout={}ms  熔断器={}
                 工具过滤      : enabled={}  通用工具={}  fallbackOnEmpty={}
                 评测埋点      : logEnabled={}  → {}
                ══════════════════════════════════════════════""",
                properties.isEnabled(), properties.getMode(),
                ruleRouter.isEnabled(),
                tfIdfRouter.isEnabled(),
                properties.getTfidf().getThresholdHigh(),
                properties.getTfidf().getThresholdLow(),
                properties.getTfidf().isChatOnLowSimilarity(),
                tfIdfStore.labelCount(), tfIdfStore.seedCount(), tfIdfStore.vocabSize(),
                tfIdfStore.getSource() == null ? "unavailable" : tfIdfStore.getSource(),
                properties.getTfidf().getRisk().isEnabled(),
                properties.getTfidf().getRisk().getMode(),
                properties.getTfidf().getRisk().isEnforce()
                        ? "（命中即拦截，不进 L3/LLM）"
                        : "（⚠️ 只记录不拦截 —— 用于量化误报，改 router.tfidf.risk.mode=enforce 即生效）",
                properties.getTfidf().getRisk().getThreshold(),
                properties.getTfidf().getRisk().getMinMargin(),
                llmRouter.isEnabled(),
                aiChat == null ? "unconfigured" : aiChat.getModel(),
                properties.getLlm().getTimeoutMs(),
                circuitBreaker.state(),
                properties.getToolFiltering().isEnabled(),
                properties.getToolFiltering().getCommonTools(),
                properties.getToolFiltering().isFallbackOnEmpty(),
                properties.getEval().isLogEnabled(),
                properties.getEval().getLogPath());
    }

    /** 运行时快照，供 {@code GET /api/router/health} 使用。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", properties.isEnabled());
        m.put("mode", properties.getMode());
        m.put("l1Enabled", ruleRouter.isEnabled());
        m.put("l2Enabled", tfIdfRouter.isEnabled());
        m.put("l3Enabled", llmRouter.isEnabled());
        m.put("thresholdHigh", properties.getTfidf().getThresholdHigh());
        m.put("thresholdLow", properties.getTfidf().getThresholdLow());
        m.put("chatOnLowSimilarity", properties.getTfidf().isChatOnLowSimilarity());
        m.put("intentLabels", tfIdfStore.labelCount());
        m.put("seedCount", tfIdfStore.seedCount());
        m.put("vocabSize", tfIdfStore.vocabSize());
        m.put("modelSource", tfIdfStore.getSource());
        m.put("circuitState", circuitBreaker.state().name());
        m.put("circuitFailures", circuitBreaker.consecutiveFailures());
        m.put("toolFilteringEnabled", properties.getToolFiltering().isEnabled());
        m.put("commonTools", properties.getToolFiltering().getCommonTools());
        m.put("evalLogPath", properties.getEval().getLogPath());
        m.put("evalLogEnabled", properties.getEval().isLogEnabled());
        return m;
    }
}
