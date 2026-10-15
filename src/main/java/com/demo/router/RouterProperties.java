package com.demo.router;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 前置路由配置。所有键以 {@code router.} 开头，全部带默认值，不配也能跑。
 *
 * <p>默认值策略：{@code enabled=true}（路由本身只观测+收窄工具，不改变执行语义），
 * 但 {@code llm.enabled=false}——L3 会真实调用 DeepSeek 花 token，
 * 且本项目的 L2 TF-IDF 已能覆盖绝大多数消息，L3 留给需要时再开。
 *
 * <p>注意：本项目的 properties 按 ISO-8859-1 读取，<b>中文值必须写成 Unicode 转义序列</b>
 * （即反斜杠 + u + 四位十六进制，形如 U+4F60 U+597D），
 * 所以中文关键词一律硬编码在 Java 源码里，不放进配置。
 * <p>（本段刻意不写出该转义的字面形式——Java 会在词法分析之前先处理转义序列，
 * 注释里写一个不完整的转义会直接导致编译失败。）
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "router")
public class RouterProperties {

    /** 前置路由总开关。false → HybridRouter 直接返回 FALLBACK，行为与改造前完全一致。 */
    private boolean enabled = true;

    /** 路由模式：{@code rule} 只装 L1；{@code hybrid} 装 L1+L2+L3。 */
    private String mode = "hybrid";

    private final Rule rule = new Rule();
    private final Tfidf tfidf = new Tfidf();
    private final Llm llm = new Llm();
    private final ToolFiltering toolFiltering = new ToolFiltering();
    private final Eval eval = new Eval();

    @Getter
    @Setter
    public static class Rule {
        /** L1 规则层开关。 */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Tfidf {
        /** L2 TF-IDF 层开关。 */
        private boolean enabled = true;

        /** 高阈值：相似度 ≥ high 才可能采纳该意图（还需同时满足 {@link #minMargin}）。 */
        private double thresholdHigh = 0.25;

        /** 低阈值：相似度 < low 视为「无法识别」→ FALLBACK（走全量工具兜底）。 */
        private double thresholdLow = 0.12;

        /**
         * 支配度阈值：{@code top1 - top2} 必须 ≥ 该值，否则视为「跟谁都像」→ 不采纳。
         *
         * <p><b>这是实测出来的，不是拍脑袋。</b>本机用 165 条种子跑出的分布（见
         * {@code IntentTfIdfStoreTest.打印相似度分布}）：
         * <pre>
         *   正确路由的消息        top1 相似度 0.2609 ~ 0.6723，top1-top2 差值 ≥ 0.133
         *   类别外的普通需求      top1 相似度 0.1948 ~ 0.3910，top1-top2 差值 ≤ 0.025
         *                        （「这个项目怎么部署」LAW 0.2871 / SEARCH 0.2808，差值仅 0.006）
         * </pre>
         * 只卡绝对相似度<b>分不开</b>——错误项的 0.2871 正好夹在正确项中间。
         * 但差值分得很干净，所以必须同时卡两者。
         *
         * <p>为什么不用「把 high 提到 0.35」解决：那会连带砍掉
         * 「明天会下雨吗」「我家狗能吃什么」「从北京到上海怎么走」这些真实需求的路由。
         *
         * <p>思路与方案书给法律 RAG 的 {@code LAW_TOP1_DOMINANCE_THRESHOLD} 一致。
         */
        private double minMargin = 0.10;

        /**
         * 低相似度时是否判为 CHAT（零工具）。
         *
         * <p><b>默认 false，这是对方案书的有意偏离。</b>
         * 方案书写的是「&lt; threshold.low → CHAT」，但本项目意图类别只覆盖 11 个域，
         * 任何落在类别之外的真实需求（例如"帮我写个周报"）都会被判 CHAT → 0 工具 → 能力丢失。
         * 方案书自己的验收标准同时也写着「无法识别 → FALLBACK（全量工具，兜底保留）」，
         * 二者只有取 FALLBACK 才自洽且安全。想要方案书原行为可置 true。
         */
        private boolean chatOnLowSimilarity = false;

        /** 模糊风险层（L2 的"模糊语义风险识别"）。 */
        private final Risk risk = new Risk();

        public Risk getRisk() {
            return risk;
        }

        /**
         * L2 风险层配置。
         *
         * <h3>为什么它有独立阈值与模式，不复用意图那套</h3>
         * 意图分类判错的代价是"省不到 token"；风险层判错的代价是"**拦掉一个正常提问**"。
         * 前者的容错空间远大于后者，所以风险层更严（默认 0.35 / 0.12，意图是 0.25 / 0.10），
         * 并且独立开关、独立模式。
         *
         * <h3>为什么默认 mode=log</h3>
         * 与本项目另一处判断保持一致：<b>精确关键词层默认 enforce，模糊语义层默认 log</b>。
         * 精确关键词命中了就是命中了，误报可控；模糊质心会因措辞相近而误判，
         * 所以先只记录、把误报量出来，再决定是否真的拦。
         * 想直接生效：{@code router.tfidf.risk.mode=enforce}。
         */
        @Getter
        @Setter
        public static class Risk {
            /** 是否启用风险识别。false → L2 只做意图分类（行为与改造前一致）。 */
            private boolean enabled = true;

            /**
             * 风险阈值。<b>这是实测扫出来的，不是拍的</b>——
             * 在 {@code RiskLayerEvalTest} 的 15 条正例 / 14 条反例标注集上扫描得到：
             * <pre>
             *   阈值   召回          误报
             *   0.40   0.0% (0/15)   0.0% (0/14)
             *   0.35  26.7% (4/15)   0.0%
             *   0.30  60.0% (9/15)   0.0%
             *   0.25  86.7% (13/15)  7.1% (1/14)  ← 拐点：再降只多 1 条召回、误报翻倍
             *   0.20  93.3% (14/15)  7.1%
             *   0.15  93.3%          14.3%
             * </pre>
             * 初版按"模糊层要更严"拍了个 0.35，实测只抓到 1/4 的风险措辞——阈值必须扫。
             */
            private double threshold = 0.25;

            /** 风险支配度：必须比第二名高出一截，避免"跟谁都像"。 */
            private double minMargin = 0.12;

            /**
             * {@code log} = 只记录不拦截；{@code enforce} = 命中即 BLOCKED。
             *
             * <p>默认 {@code enforce} 的依据是"<b>先观察、有数据再阻断</b>"而不是"模糊层一律不拦"：
             * 标注集上 0.25 阈值给出 86.7% 召回 / 7.1% 误报，这个量级可以辩护。
             * 若你的场景更保守（宁可漏也不误伤），改回 {@code log} 即可——
             * 那几行 WARN 日志就是真实流量误报率的原始数据。
             */
            private String mode = "enforce";

            public boolean isEnforce() {
                return mode != null && "enforce".equalsIgnoreCase(mode.trim());
            }
        }

        /** 意图种子语料路径。 */
        private String seedPath = "classpath:router/intent-seeds.json";

        /** TF-IDF 模型缓存落盘路径。删掉该文件即触发重建。 */
        private String storePath = "data/intent-tfidf.json";
    }

    @Getter
    @Setter
    public static class Llm {
        /** L3 LLM 兜底开关。默认关：会真实产生 token 消耗。 */
        private boolean enabled = false;

        /**
         * L3 用的模型。本项目唯一底座是 DeepSeek，不引入 qwen。
         * 默认值与 {@code UserMemoryExtractor} 实际在用的模型保持一致（那是本项目已验证可用的调用）。
         */
        private String model = "deepseek-v4-flash";

        private double temperature = 0.0;

        /** 单次判定超时（毫秒）。超时 → FALLBACK。 */
        private long timeoutMs = 3000;

        private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    }

    @Getter
    @Setter
    public static class CircuitBreaker {
        private boolean enabled = true;
        /** 连续失败达到该值 → 打开熔断。 */
        private int failureThreshold = 5;
        /** 熔断打开后的冷却秒数，冷却结束后放行一次试探。 */
        private long cooldownSeconds = 60;
    }

    @Getter
    @Setter
    public static class ToolFiltering {
        /**
         * 工具过滤开关。false → 永远发全量工具（回退到改造前行为）。
         */
        private boolean enabled = true;

        /**
         * 通用工具：任何意图命中时都额外保留的工具。
         *
         * <p>为什么需要：模型偶尔需要联网核实事实。只留意图内工具会让它「想查却查不到」，
         * 反而退化成幻觉。方案书同样要求保留 {@code web_search}。
         */
        private List<String> commonTools = List.of("searchWeb");

        /**
         * 单个工具 schema 的估算 token 数，仅用于 {@code toolsSaved} 埋点估算。
         * 实测本项目 68 个工具方法的描述平均约 60 token。
         */
        private int tokensPerTool = 60;

        /**
         * 安全阀：意图工具全部不存在于当前工具集时，是否回退全量工具。
         * 必须为 true——否则工具名写错就会让该意图静默失去全部能力。
         */
        private boolean fallbackOnEmpty = true;
    }

    @Getter
    @Setter
    public static class Eval {
        /** 路由评测 jsonl 落盘开关。 */
        private boolean logEnabled = true;

        /** jsonl 路径。 */
        private String logPath = "logs/router-eval.jsonl";

        /** 内存中保留的最近评测记录条数（供 REST 查询）。 */
        private int recentBufferSize = 500;
    }
}
