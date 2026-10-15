package com.demo.agent.toolguard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具守卫的规则仲裁：命中多条规则时决定"到底期望哪个工具"，并给出三态裁决。
 *
 * <h3>关于方案书的「交集优先」：在本项目的数据形状下会退化</h3>
 * 方案书要求把规则表做成 {@code Map<String, List<String>>}（一个关键词 → 多个候选工具），
 * 于是「查询天气」可以先取 {@code 查询} → {web_search, get_weather, law}，
 * 再取 {@code 天气} → {get_weather}，两者求<b>交集</b>收敛到 {@code get_weather}。
 * 那套设计解决的是"泛化关键词抢走匹配"的问题。
 *
 * <p>但本项目的规则表是<b>反过来的</b>：一个工具 → 多个关键词
 * （{@code getWeather} 对应 天气/气温/温度/下雨/刮风…）。这种形状下，
 * 「查询天气」本来就<b>直接命中</b> {@code 天气} 那条规则，
 * 交集在每条规则只声明一个工具时也必然是"要么同一个工具、要么空集"，无法起到收敛作用。
 *
 * <p>所以这里按方案书的<b>意图</b>落成本项目真正需要的规则：
 * <b>最长命中关键词优先</b>（越长的关键词越具体，「查询天气」胜过「查询」），
 * 交集作为次要手段保留——当命中的多条规则恰好指向同一个工具时直接采纳。
 * 方案书那条验收标准（「查询天气 → get_weather」）由最长关键词规则满足，
 * 并有回归用例钉住。
 */
public final class ToolGuardArbitrator {

    /**
     * LLM「声称已完成」的短语集合。
     *
     * <p>只收完成时断言。曾经的 {@code 语音模式}/{@code 回复模式} 是名词短语不是断言，
     * 「现在是语音模式，有什么可以帮你」这种正常回复会被误判——已移除。
     */
    private static final Set<String> FAKE_EXECUTION_PHRASES = Set.of(
            "已切换", "已开启", "已关闭", "已设置", "已发送",
            "已经切换", "已经设置", "已生成", "已创建",
            "已预订", "已支付", "已下单", "已发布");

    /**
     * 工具等价表：规则里写的工具名 → 「也算调用了」的其他工具名。
     * <p>这些是历史遗留的别名关系，统一收在这里，避免散落在检测代码里。
     */
    private static final Map<String, List<String>> EQUIVALENTS = Map.of(
            "searchWeb", List.of("simulateSearch"),
            "xhs_publish", List.of("publishXhsNote", "xhs_publish_content"));

    /** 图表能力的 MCP 源名：其下 25+ 个 {@code generate_xxx_chart} 任一被调即视为满足。 */
    private static final String CHART_SOURCE = "antvchart";

    private ToolGuardArbitrator() {
    }

    /**
     * 对一轮回复做规则仲裁。
     *
     * @param message     原始用户消息（<b>必须是用户原话</b>，不能是拼了记忆/历史的完整 prompt——
     *                    否则历史里的"天气""法律"等词会污染规则匹配）
     * @param llmText     LLM 本轮回复文本
     * @param calledTools 本轮实际调用过的工具名
     * @param guards      规则表
     */
    public static AuditResult arbitrate(String message, String llmText,
                                        Set<String> calledTools, List<GuardSpec> guards) {
        if (message == null || message.isBlank() || guards == null || guards.isEmpty()) {
            return AuditResult.ofRule(AuditVerdict.PASS, List.of(), List.of(), List.of(), null, "no-guard-matched");
        }
        String msg = message.toLowerCase(Locale.ROOT);
        Set<String> called = calledTools == null ? Set.of() : calledTools;

        List<GuardSpec> matched = new ArrayList<>();
        for (GuardSpec g : guards) {
            if (containsAny(msg, g.keywords())) {
                matched.add(g);
            }
        }
        if (matched.isEmpty()) {
            return AuditResult.ofRule(AuditVerdict.PASS, List.of(), List.of(), List.of(), null, "no-guard-matched");
        }

        Expected expected = resolveExpected(msg, matched);
        List<String> calledHits = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String tool : expected.tools()) {
            if (isCalled(tool, called)) {
                calledHits.add(tool);
            } else {
                missing.add(tool);
            }
        }
        String ruleDesc = matched.get(0).ruleDesc();

        if (missing.isEmpty()) {
            // reason 里带上仲裁策略：排查"为什么期望的是这个工具"时，
            // 「交集收敛的」和「最长关键词赢的」是完全不同的两件事。
            return AuditResult.ofRule(AuditVerdict.PASS, expected.tools(), missing, calledHits,
                    ruleDesc, "all-called/" + expected.strategy());
        }
        if (!calledHits.isEmpty()) {
            // 期望多个工具、只调了一部分 → 灰区，不该直接判违规
            return AuditResult.ofRule(AuditVerdict.AMBIGUOUS, expected.tools(), missing, calledHits,
                    ruleDesc, "partial-called/" + expected.strategy());
        }
        if (containsAny(llmText, FAKE_EXECUTION_PHRASES)) {
            return AuditResult.ofRule(AuditVerdict.FAIL, expected.tools(), missing, calledHits,
                    ruleDesc, "fake-execution/" + expected.strategy());
        }
        // 命中规则但一个工具都没调，且没有"已完成"的断言：可能是模型直接用自身知识答了
        // （有时是对的，有时是幻觉）。这正是需要灰区的原因，交给裁判或日志。
        return AuditResult.ofRule(AuditVerdict.AMBIGUOUS, expected.tools(), missing, calledHits,
                ruleDesc, "none-called/" + expected.strategy());
    }

    // ======================== 期望工具解析 ========================

    private record Expected(List<String> tools, String strategy) {}

    private static Expected resolveExpected(String msg, List<GuardSpec> matched) {
        // 手段 1：交集 —— 命中的多条规则若指向同一个工具，直接采纳
        Set<String> intersection = null;
        for (GuardSpec g : matched) {
            if (intersection == null) {
                intersection = new LinkedHashSet<>(List.of(g.toolName()));
            } else {
                intersection.retainAll(Set.of(g.toolName()));
            }
        }
        if (intersection != null && !intersection.isEmpty()) {
            return new Expected(new ArrayList<>(intersection), "intersection");
        }

        // 手段 2：最长命中关键词优先（越具体越该赢）
        int longest = -1;
        Set<String> winners = new LinkedHashSet<>();
        for (GuardSpec g : matched) {
            for (String kw : g.keywords()) {
                if (!msg.contains(kw.toLowerCase(Locale.ROOT))) continue;
                int len = kw.length();
                if (len > longest) {
                    longest = len;
                    winners.clear();
                    winners.add(g.toolName());
                } else if (len == longest) {
                    winners.add(g.toolName()); // 等长则取并集，宁可多期望一个也别错杀
                }
            }
        }
        return new Expected(new ArrayList<>(winners), "longest-keyword");
    }

    /** 工具是否可视为"已调用"，含等价别名与图表源前缀特判。 */
    static boolean isCalled(String tool, Set<String> calledTools) {
        if (calledTools.contains(tool)) return true;
        for (String alt : EQUIVALENTS.getOrDefault(tool, List.of())) {
            if (calledTools.contains(alt)) return true;
        }
        if (CHART_SOURCE.equals(tool)) {
            return calledTools.stream().anyMatch(t -> t.startsWith(CHART_SOURCE + "_"));
        }
        return false;
    }

    private static boolean containsAny(String text, Set<String> keywords) {
        if (text == null || keywords == null) return false;
        for (String kw : keywords) {
            if (kw != null && !kw.isEmpty() && text.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 暴露给测试与埋点：判定"假装执行"用的短语集合。 */
    public static Set<String> fakeExecutionPhrases() {
        return FAKE_EXECUTION_PHRASES;
    }
}
