package com.demo.router;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * L1 纯规则路由层：零 token、零外部调用。
 *
 * <p>触发词来源于本项目里<b>已有的、权威的</b>触发词清单，不是凭空发明：
 * {@code VoiceModeTool} / {@code VoiceTypeTool} / {@code ChatModeTool} 三个工具的中文描述
 * 各自写着它认识的触发词，这里把它们抽出来集中成一张表——这正是方案书说的"抽取散落的触发词判断"。
 *
 * <p>未命中一律返回 {@link RouteDecision#FALLBACK}，表示"这层不管"，由 {@code HybridRouter} 交给 L2。
 *
 * <h3>与方案书的两处差异（都是刻意的）</h3>
 * <ol>
 *   <li>本项目没有 Handler 类，所以 L1 不"跳转执行"，只"分类 + 收窄工具集"，
 *       见 {@link RouteDecision} 的类注释。</li>
 *   <li>{@link RouteDecision#WATERMARK} 收窄后工具集为<b>空</b>——
 *       本项目水印只有 {@code /api/desktop/watermark/*} 两个 REST 接口，
 *       没有任何 {@code @Tool}，LLM 本来就调不到。这里如实反映现状，
 *       不去伪造一个不存在的工具名（伪造会导致过滤出空集并静默失效）。</li>
 * </ol>
 */
@Slf4j
@Component
public class RuleBasedRouter implements PreRouter {

    /** 水印意图——本项目无对应 @Tool，故工具集为空。 */
    private static final List<String> WATERMARK_TOOLS = List.of();

    /** 语音面板：两个语音工具。 */
    private static final List<String> VOICE_TOOLS = List.of("controlVoiceMode", "changeVoiceType");

    /** 绘图：生成 + 编辑（用户说"改一下这张图"也常带画字）。 */
    private static final List<String> DRAW_TOOLS = List.of("generateImage", "editImage");

    /** 风格模仿：模仿模式本身 + 对话模式切换。 */
    private static final List<String> STYLE_TOOLS = List.of("setMimicMode", "changeMode");

    /**
     * 「画」开头但显然不是绘图指令的词——避免"画质怎么样""漫画推荐"被误判成 DRAW。
     */
    private static final List<String> DRAW_PREFIX_EXCLUDES =
            List.of("画质", "画面", "画廊", "漫画", "画家", "画笔");

    /** 绘图包含式触发词（解决方案书记录的「给我画一只猫」漏匹配 bug）。 */
    private static final List<String> DRAW_CONTAINS = List.of(
            "帮我画", "给我画", "帮我生成", "给我生成", "帮我做张", "帮我做个",
            "画个", "画一张", "画一幅", "画只", "画一个", "画一下",
            "生成一张图", "生成图片", "生成海报", "制作海报", "做个头像", "做个封面",
            "帮我设计");

    /** 语音面板触发词（取自 VoiceModeTool / VoiceTypeTool 的工具描述）。 */
    private static final List<String> VOICE_CONTAINS = List.of(
            "开启语音", "关闭语音", "全程语音", "这句用语音", "用语音说", "用语音回",
            "不要语音", "切回文字", "静音",
            "换音色", "换声音", "男声", "女声", "童声", "御姐", "少年音", "音色");

    /**
     * 风格/表达方式类触发词。工具集 {@link #STYLE_TOOLS} 同时覆盖模仿模式与对话模式切换，
     * 所以这里既收"模仿"类词，也收"讲通俗点"类词——否则通俗释义模式切不过去：
     * 那几个词落到 L2 多半被判成 CHAT（工具 0 个），模型连 changeMode 都拿不到。
     *
     * <p>已知取舍：L1 命中即收窄工具集，所以"这道题我听不懂"会把工具收窄到
     * [setMimicMode, changeMode]。影响可接受——这类消息本来就该先换讲解方式，
     * 而模型不靠工具也能作答。要避免这种收窄，就只能放弃 L1 的零成本短路。
     */
    private static final List<String> STYLE_CONTAINS = List.of(
            "模仿", "仿写", "学我说话", "学习我的说话", "模仿模式",
            "按这个语气", "照这个风格", "用这个风格", "这个语气说话",
            "通俗", "大白话", "说人话", "讲人话", "听不太懂", "听不懂", "讲简单点", "简单点讲");

    /** 水印触发词。 */
    private static final List<String> WATERMARK_CONTAINS = List.of(
            "水印");

    private final RouterProperties properties;

    public RuleBasedRouter(RouterProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled() && properties.getRule().isEnabled();
    }

    @Override
    public String layerName() {
        return "L1";
    }

    @Override
    public RouteResult route(String userId, String message, MessageContext ctx) {
        long start = System.nanoTime();
        // 自己守开关：调用方（HybridRouter）本来就会先查 isEnabled()，
        // 但直接调用 route() 的代码（测试、干跑、将来的新入口）不该能绕过开关拿到结论 ——
        // 尤其是安全规则：配置上关掉 L1 之后，"直接调 route()"还照样拦截，行为就不可预期了。
        if (!isEnabled()) {
            return RouteResult.fallback("L1:disabled", elapsedMs(start));
        }
        if (message == null || message.isBlank()) {
            return RouteResult.fallback("L1:blank", elapsedMs(start));
        }
        String msg = message.trim();
        String lower = msg.toLowerCase(Locale.ROOT);

        // 优先级：SAFETY > SYSTEM_CMD > WATERMARK > VOICE_PANEL > DRAW > STYLE_IMITATE
        //
        // ★ 安全规则放在第一位，是为了借用 HybridRouter 的漏斗语义：
        //   "任何一层给出非 FALLBACK 的结论即采纳"，所以 BLOCKED 会直接短路 L2 与 L3，
        //   连 L3 的那次 LLM 调用都不会发生——这就是"命中即终止、不进入后续链路"的实现方式，
        //   不需要调用方再补判断。
        RouteResult hit;

        if ((hit = trySafety(msg, start)) != null) return hit;
        if ((hit = trySystemCmd(msg, lower, start)) != null) return hit;
        if ((hit = tryContains(msg, WATERMARK_CONTAINS, RouteDecision.WATERMARK,
                WATERMARK_TOOLS, start)) != null) return hit;
        if ((hit = tryContains(msg, VOICE_CONTAINS, RouteDecision.VOICE_PANEL,
                VOICE_TOOLS, start)) != null) return hit;
        if ((hit = tryDraw(msg, start)) != null) return hit;
        if ((hit = tryContains(msg, STYLE_CONTAINS, RouteDecision.STYLE_IMITATE,
                STYLE_TOOLS, start)) != null) return hit;

        return RouteResult.fallback("L1:no-match", elapsedMs(start));
    }

    // ======================== 各规则 ========================

    /**
     * 安全规则：命中自伤风险或违规内容请求 → {@link RouteDecision#BLOCKED}，工具集给空集。
     *
     * <p>判定逻辑与词表在 {@link SafetyKeywordRules}（自伤词表复用教育域的安全组件，
     * 避免两套词表漂移）。这里只负责"把安全做成一等公民的 L1 规则"。
     */
    private RouteResult trySafety(String msg, long start) {
        SafetyKeywordRules.Hit safety = SafetyKeywordRules.classify(msg);
        if (!safety.isHit()) return null;
        return build(RouteDecision.BLOCKED,
                "L1:safety=" + safety.verdict() + ":kw=" + safety.keyword(),
                List.of(), start,
                java.util.Map.of("keyword", safety.keyword(), "safety", safety.verdict().name()));
    }

    /**
     * 启动期校验安全词表：黑名单不得与健康教育白名单重叠。
     *
     * <p>做在启动期而不是运行期事后放行，是因为"事后放行"会留一个洞：
     * "月经期间能看黄片吗"含健康词就会被整体放行。构建期直接拦住越界的词，
     * 是更小、更严的做法。
     */
    @PostConstruct
    public void validateSafetyRules() {
        SafetyKeywordRules.assertNoConflict();
        log.info("🛡 L1 安全规则已装载：{}", SafetyKeywordRules.summary());
    }

    private RouteResult trySystemCmd(String msg, String lower, long start) {
        if ("help".equals(lower) || "version".equals(lower) || "status".equals(lower)) {
            return build(RouteDecision.SYSTEM_CMD, "L1:syscmd=" + lower, List.of(), start,
                    java.util.Map.of("keyword", lower));
        }
        return null;
    }

    private RouteResult tryContains(String msg, List<String> keywords, RouteDecision decision,
                                    List<String> tools, long start) {
        for (String kw : keywords) {
            if (msg.contains(kw)) {
                return build(decision, "L1:" + decision + ":kw=" + kw, tools, start,
                        java.util.Map.of("keyword", kw));
            }
        }
        return null;
    }

    private RouteResult tryDraw(String msg, long start) {
        // 前缀式：以「画」「绘制」「绘图」开头，但排除「画质」「漫画」这类非绘图词
        for (String prefix : List.of("画", "绘制", "绘图")) {
            if (msg.startsWith(prefix) && DRAW_PREFIX_EXCLUDES.stream().noneMatch(msg::startsWith)) {
                return build(RouteDecision.DRAW, "L1:DRAW:prefix=" + prefix, DRAW_TOOLS, start,
                        java.util.Map.of("prefix", prefix));
            }
        }
        // 包含式：覆盖「给我画一只猫」这类前缀不匹配的写法
        for (String kw : DRAW_CONTAINS) {
            if (msg.contains(kw)) {
                return build(RouteDecision.DRAW, "L1:DRAW:kw=" + kw, DRAW_TOOLS, start,
                        java.util.Map.of("keyword", kw));
            }
        }
        return null;
    }

    private RouteResult build(RouteDecision decision, String rule, List<String> tools,
                              long start, java.util.Map<String, Object> features) {
        return RouteResult.builder()
                .decision(decision)
                .matchedRule(rule)
                .confidence(1.0)
                .costMs(elapsedMs(start))
                .tools(tools)
                .layer(layerName())
                .features(features)
                .build();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
