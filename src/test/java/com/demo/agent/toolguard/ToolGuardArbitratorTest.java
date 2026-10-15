package com.demo.agent.toolguard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具守卫仲裁测试。
 *
 * <p>重点钉住两件事：
 * <ol>
 *   <li>方案书那条回归用例——「查询天气」不能被泛化关键词「查询」抢走匹配；</li>
 *   <li>三态裁决的边界——尤其是"命中规则但没调工具"必须落在 AMBIGUOUS 而不是 FAIL。</li>
 * </ol>
 */
class ToolGuardArbitratorTest {

    /** 合成一张含"泛化关键词 vs 具体关键词"冲突的规则表，复刻方案书的 bug 场景。 */
    private static final List<GuardSpec> CONFLICT_GUARDS = List.of(
            new GuardSpec(Set.of("查询"), "searchWeb", "网页搜索"),
            new GuardSpec(Set.of("查询天气"), "getWeather", "天气查询"),
            new GuardSpec(Set.of("天气", "气温"), "getWeather", "天气查询"));

    private static AuditResult audit(String msg, String llmText, Set<String> called, List<GuardSpec> guards) {
        return ToolGuardArbitrator.arbitrate(msg, llmText, called, guards);
    }

    /** 方案书验收标准：「查询天气」必须收敛到 getWeather，而不是被判成 web_search。 */
    @Test
    void 查询天气应收敛到天气工具而非泛化搜索() {
        AuditResult r = audit("查询天气", "已为你查询", Set.of("getWeather"), CONFLICT_GUARDS);
        assertEquals(AuditVerdict.PASS, r.verdict());
        assertTrue(r.expectedTools().contains("getWeather"));
        assertFalse(r.expectedTools().contains("searchWeb"),
                "泛化关键词「查询」不该抢走匹配，实际期望=" + r.expectedTools());
    }

    /** 最长关键词优先：确认仲裁策略本身生效。 */
    @Test
    void 最长命中关键词优先() {
        AuditResult r = audit("查询天气", "今天晴", Set.of(), CONFLICT_GUARDS);
        assertEquals(List.of("getWeather"), r.expectedTools());
        assertTrue(r.reason().contains("longest-keyword"), "实际 reason=" + r.reason());
    }

    /**
     * 命中的多条规则指向同一工具时走交集，直接采纳。
     * <p>注意必须用「天气气温」这种只命中同工具规则的说法——一旦消息里出现「查询」，
     * 那条 {@code 查询→searchWeb} 的规则也会命中，交集就变成空集进而走最长关键词回退。
     */
    @Test
    void 多规则指向同一工具时走交集() {
        AuditResult r = audit("天气气温怎么样", "今天晴", Set.of("getWeather"), CONFLICT_GUARDS);
        assertEquals(List.of("getWeather"), r.expectedTools());
        assertTrue(r.reason().contains("intersection"), "实际 reason=" + r.reason());
    }

    @Test
    void 没命中任何规则直接PASS() {
        AuditResult r = audit("帮我写个周报", "好的", Set.of(), CONFLICT_GUARDS);
        assertEquals(AuditVerdict.PASS, r.verdict());
        assertEquals("no-guard-matched", r.reason());
    }

    @Test
    void 期望工具已调用则PASS() {
        AuditResult r = audit("今天天气怎么样", "今天晴，25度", Set.of("getWeather"), CONFLICT_GUARDS);
        assertEquals(AuditVerdict.PASS, r.verdict());
        // reason 形如 all-called/intersection —— 后半段是仲裁策略
        assertTrue(r.reason().startsWith("all-called"), "实际 reason=" + r.reason());
    }

    /** 命中规则 + 一个工具都没调 + 有"已完成"断言 → 明确违规。 */
    @Test
    void 声称已完成却没调工具判FAIL() {
        List<GuardSpec> guards = List.of(new GuardSpec(Set.of("提醒我"), "setReminder", "定时提醒"));
        AuditResult r = audit("提醒我十分钟后开会", "已经设置好了，到点我会提醒你", Set.of(), guards);
        assertEquals(AuditVerdict.FAIL, r.verdict());
        assertEquals("setReminder", r.primaryTool());
        assertTrue(r.reason().contains("fake-execution"), "实际 reason=" + r.reason());
    }

    /**
     * 已知局限：完成断言用的是短语白名单，覆盖不到所有自然说法。
     *
     * <p>「我已经为你查到了」语义上就是在声称已执行，但不在白名单里，因此只判到 AMBIGUOUS。
     * 这<b>不是</b>缺陷被掩盖——正是这种"规则看不出、语义上明显"的情况需要 LLM 裁判
     * （{@code ToolGuardJudgeService}）来收口，三态设计的意义就在这里。
     * 若将来发现白名单漏得太多，正确做法是加大裁判的权重，而不是继续往白名单里堆词。
     */
    @Test
    void 白名单之外的完成说法落到AMBIGUOUS由裁判收口() {
        AuditResult r = audit("今天天气怎么样", "我已经为你查到了，今天晴", Set.of(), CONFLICT_GUARDS);
        assertEquals(AuditVerdict.AMBIGUOUS, r.verdict());
        assertEquals("getWeather", r.primaryTool(), "期望工具仍应被识别出来，供裁判参考");
    }

    /**
     * 关键：命中规则但没调工具、且没有"已完成"断言 → 必须是 AMBIGUOUS 而不是 FAIL。
     * <p>旧实现把这个灰区当成"不违规"直接放过（守卫形同虚设）；直接判 FAIL 又会误伤
     * 模型用自身知识正确作答的情况（例如它凭常识就能答的题）。三态就是为了不被迫二选一。
     */
    @Test
    void 没调工具但没声称完成判AMBIGUOUS() {
        AuditResult r = audit("今天天气怎么样", "今天应该是晴天吧", Set.of(), CONFLICT_GUARDS);
        assertEquals(AuditVerdict.AMBIGUOUS, r.verdict());
        assertTrue(r.reason().contains("none-called"), "实际 reason=" + r.reason());
    }

    /** 期望多个工具、只调了一部分 → 灰区，不该直接判违规。 */
    @Test
    void 只调用了一部分期望工具判AMBIGUOUS() {
        List<GuardSpec> guards = List.of(
                new GuardSpec(Set.of("查一下"), "searchWeb", "网页搜索"),
                new GuardSpec(Set.of("查一下"), "getWeather", "天气查询"));
        AuditResult r = audit("查一下", "好的", Set.of("getWeather"), guards);
        // 两条规则工具不同 → 交集为空 → 最长关键词回退 → 等长取并集 → 期望两个工具
        assertEquals(AuditVerdict.AMBIGUOUS, r.verdict());
        assertEquals(1, r.calledTools().size());
        assertEquals(1, r.missingTools().size());
    }

    // ======================== 等价/别名特判 ========================

    @Test
    void 浏览器搜索满足网页搜索守卫() {
        List<GuardSpec> guards = List.of(new GuardSpec(Set.of("搜一下"), "searchWeb", "网页搜索"));
        AuditResult r = audit("帮我搜一下", "好的", Set.of("simulateSearch"), guards);
        assertEquals(AuditVerdict.PASS, r.verdict());
    }

    @Test
    void 任意图表工具满足图表守卫() {
        List<GuardSpec> guards = List.of(new GuardSpec(Set.of("柱状图"), "antvchart", "图表生成"));
        AuditResult r = audit("画个柱状图", "好的", Set.of("antvchart_generate_bar_chart"), guards);
        assertEquals(AuditVerdict.PASS, r.verdict());
    }

    @Test
    void 小红书两种工具名任一即可() {
        List<GuardSpec> guards = List.of(new GuardSpec(Set.of("发小红书"), "xhs_publish", "小红书发布"));
        assertEquals(AuditVerdict.PASS,
                audit("发小红书", "好的", Set.of("publishXhsNote"), guards).verdict());
        assertEquals(AuditVerdict.PASS,
                audit("发小红书", "好的", Set.of("xhs_publish_content"), guards).verdict());
    }

    // ======================== fakeExecution 词表 ========================

    /**
     * 「语音模式」这类名词短语不能被当成"已完成"的断言。
     * <p>原先它在 fakeExecution 词表里，导致"现在是语音模式，有什么可以帮你"这种正常回复被判违规。
     */
    @Test
    void 名词短语不算假装执行() {
        assertFalse(ToolGuardArbitrator.fakeExecutionPhrases().contains("语音模式"));
        assertFalse(ToolGuardArbitrator.fakeExecutionPhrases().contains("回复模式"));

        List<GuardSpec> guards = List.of(new GuardSpec(Set.of("语音"), "controlVoiceMode", "语音模式控制"));
        AuditResult r = audit("语音", "现在是语音模式，有什么可以帮你？", Set.of(), guards);
        assertEquals(AuditVerdict.AMBIGUOUS, r.verdict(), "名词短语不该被当成完成断言");
    }

    @Test
    void 空消息与空规则表都安全() {
        assertEquals(AuditVerdict.PASS, audit(null, "x", Set.of(), CONFLICT_GUARDS).verdict());
        assertEquals(AuditVerdict.PASS, audit("天气", "x", Set.of(), List.of()).verdict());
    }
}
