package com.demo.edu.safety;

import java.util.ArrayList;
import java.util.List;

/**
 * 危机信号检测（自伤 / 自杀意念），并按<b>风险等级</b>分档。
 *
 * <p>纯静态、无 Spring 依赖，便于单测——这套词表是"未成年人自伤风险"的唯一自动防线，
 * 改词表必须能立刻跑测试验证，不能依赖上下文才能测。
 *
 * <h3>三级分档的用途</h3>
 * {@link #SIGNALS} 由三个等级列表<b>拼接而成</b>（不是另抄一份），所以不存在"加了词忘了分档"的漂移。
 * 等级供教师端预警看板使用，也用于"同一学生当天等级只升不降"的判定：
 * <ul>
 *   <li><b>3 级</b>：出现方法性表述（割腕、跳楼、服毒…）——最需要立刻关注；</li>
 *   <li><b>2 级</b>：直接的自伤/自杀意念（想死、不想活、自杀…）；</li>
 *   <li><b>1 级</b>：无望感与隐喻式表达（活着没意思、没人会在乎我、想消失…）。</li>
 * </ul>
 * 分档<b>不改变处置</b>：三级都返回同一套引导话术（含热线）——
 * 话术里该给的电话一个都不能少，等级只是给教师端看的关注度提示。
 *
 * <h3>设计取舍：宁可多触发，不做否定消歧</h3>
 * 常见做法是加否定消歧（"我不想死" ≠ "我想死"）。<b>这里刻意不做</b>：
 * 漏报的代价（一个孩子说了"活不下去了"却得到一段科普）远大于误报的代价
 * （得到一段关心 + 热线号码）；且误报可通过 {@code edu.safety.mode=log} 量化。
 *
 * <p>词条刻意<b>不含光杆"死"字</b>以及"笑死/累死/去死"这类高频口语：
 * 它们会把这个功能变成"每三句话弹一次热线"，反而让人关掉它。
 */
public final class CrisisSignalDetector {

    /** 3 级：方法性表述——出现即最高关注。 */
    private static final List<String> LEVEL_3 = List.of(
            "割腕", "割手", "跳楼", "跳河", "服毒", "烧炭", "吞药", "安眠药自杀", "遗书"
    );

    /** 2 级：直接的自伤 / 自杀意念。 */
    private static final List<String> LEVEL_2 = List.of(
            "自杀", "自尽", "轻生", "自残", "自伤", "伤害自己", "伤害我自己",
            "想死", "死了算了", "不如死", "一死了之", "想结束生命", "结束自己的生命",
            "了结自己", "了结生命", "想解脱",
            "不想活", "不想活了", "活不下去"
    );

    /** 1 级：无望感与隐喻式表达。 */
    private static final List<String> LEVEL_1 = List.of(
            "活着没意思", "活着没有意思", "活着没有意义", "活着好累", "活着好痛苦",
            "没有活下去的意义", "活着的意义是什么都没了", "世界没有我会更好",
            "没人会在乎我", "没人在乎我死活",
            "想消失", "想离开这个世界", "告别这个世界", "离开这个世界", "不想再醒",
            "从世界上消失", "想睡过去不醒"
    );

    /**
     * 全部信号词 = 三级拼接。
     *
     * <p><b>刻意不单独维护一份平铺清单</b>：那样一定会出现"加了词忘了分档"，
     * 而漏档的后果是那条词按最低等级统计，教师端看到的关注度偏低。
     */
    private static final List<String> SIGNALS = buildSignals();

    private CrisisSignalDetector() {
    }

    private static List<String> buildSignals() {
        List<String> all = new ArrayList<>(LEVEL_3.size() + LEVEL_2.size() + LEVEL_1.size());
        all.addAll(LEVEL_3);
        all.addAll(LEVEL_2);
        all.addAll(LEVEL_1);
        return List.copyOf(all);
    }

    /**
     * 命中检测。
     *
     * @param text       待检测文本（用户原话，或图片/语音/文件预处理后拼成的描述文本）
     * @param extraWords 运行时追加的词（{@code edu.safety.extra-words}），可为 null；
     *                   自定义词按 2 级计（既非方法性描述，也不该被当成最轻的一档）
     * @return 命中的信号词列表（去重、保持出现顺序）；无命中返回空列表
     */
    public static List<String> hits(String text, List<String> extraWords) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String w : SIGNALS) {
            if (text.contains(w) && !out.contains(w)) out.add(w);
        }
        if (extraWords != null) {
            for (String w : extraWords) {
                if (w != null && !w.isBlank() && text.contains(w) && !out.contains(w)) out.add(w.trim());
            }
        }
        return out;
    }

    /** 是否命中（只要有一个信号就算）。 */
    public static boolean hit(String text, List<String> extraWords) {
        return !hits(text, extraWords).isEmpty();
    }

    /**
     * 单条信号词的风险等级：3 = 方法性表述 / 2 = 直接意念 / 1 = 无望与隐喻。
     * 未收录的词（含自定义词）按 2 级计。
     */
    public static int levelOf(String word) {
        if (word == null) return 2;
        if (LEVEL_3.contains(word)) return 3;
        if (LEVEL_2.contains(word)) return 2;
        if (LEVEL_1.contains(word)) return 1;
        return 2;
    }

    /**
     * 一段文本的风险等级 = 命中词里的<b>最高</b>等级（无命中返回 0）。
     *
     * <p>取最高而不是最低：一个孩子说"活着没意思，我还想过割腕"，要按 3 级关注。
     */
    public static int levelOfText(String text, List<String> extraWords) {
        int level = 0;
        for (String w : hits(text, extraWords)) {
            level = Math.max(level, levelOf(w));
        }
        return level;
    }

    /** 词表规模（供健康快照展示，便于确认"词表是不是空的"）。 */
    public static int signalCount() {
        return SIGNALS.size();
    }

    /** 分档规模摘要（供启动面板展示）。 */
    public static String levelSummary() {
        return "3级 " + LEVEL_3.size() + " / 2级 " + LEVEL_2.size() + " / 1级 " + LEVEL_1.size();
    }
}
