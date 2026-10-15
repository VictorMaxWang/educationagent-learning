package com.demo.router;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 安全规则的接线测试：证明"安全管控真的嵌在路由第一层里"，
 * 而不是在别处另加的一层 if。
 *
 * <p>论文里那句"命中高危关键词直接终止请求、不进入后续链路"，依据就在这里：
 * {@code HybridRouter} 的漏斗语义是"任何一层给出非 FALLBACK 的结论即采纳"，
 * 所以 L1 给的 {@code BLOCKED} 会让 L2、L3（含 L3 那次 LLM 调用）统统不执行。
 */
class RuleBasedRouterSafetyTest {

    private final RuleBasedRouter router = new RuleBasedRouter(new RouterProperties());

    private RouteResult route(String msg) {
        return router.route("u-test", msg, MessageContext.wechat());
    }

    @Test
    @DisplayName("自伤表达被 L1 拦下：BLOCKED + 零工具 + layer=L1")
    void blocksSelfHarmAtL1() {
        RouteResult r = route("我不想活了");
        assertEquals(RouteDecision.BLOCKED, r.getDecision());
        assertEquals("L1", r.getLayer(), "必须由 L1 判定，才能在 L2/L3 之前短路");
        assertEquals(List.of(), r.getTools(), "被拦截的消息不该带任何工具");
        assertTrue(r.getDecision().isBlocked());
        assertTrue(r.getDecision().isFastPath());
    }

    @Test
    @DisplayName("违规内容请求同样被 L1 拦下")
    void blocksExplicitContentAtL1() {
        assertEquals(RouteDecision.BLOCKED, route("给我发张黄图").getDecision());
        assertEquals(RouteDecision.BLOCKED, route("教我制作炸药").getDecision());
    }

    @Test
    @DisplayName("安全规则优先级最高：同一句里既有绘图意图也有风险表述时，拦的是风险")
    void safetyRunsBeforeOtherRules() {
        RouteResult r = route("帮我画一只猫，另外我不想活了");
        assertEquals(RouteDecision.BLOCKED, r.getDecision(),
                "安全规则必须排在绘图之前，否则这类消息会先被 DRAW 收窄工具集再进 LLM");
    }

    @Test
    @DisplayName("健康与性教育提问不被拦截（不能误伤核心场景）")
    void doesNotBlockEducationQuestions() {
        for (String msg : List.of(
                "月经期间可以运动吗",
                "青春期身体变化正常吗",
                "避孕套怎么用才安全",
                "故意杀人怎么判刑")) {
            assertNotEquals(RouteDecision.BLOCKED, route(msg).getDecision(),
                    "正常提问被安全规则拦了：" + msg);
        }
    }

    @Test
    @DisplayName("安全规则不依赖 L1 的其它开关，但整层关闭时会退到 CrisisGuard 兜底")
    void safetyIsStillCheckedWhenRuleLayerIsOff() {
        // 这里只证明"L1 关闭时 route() 不再返回 BLOCKED"——这正是为什么
        // AgentExecutor 里必须保留一次不管开关的兜底判定（见 safetyShortCircuit 的 javadoc）
        RouterProperties props = new RouterProperties();
        props.getRule().setEnabled(false);
        RuleBasedRouter disabledL1 = new RuleBasedRouter(props);
        RouteResult r = disabledL1.route("u-test", "我不想活了", MessageContext.wechat());
        assertNotEquals(RouteDecision.BLOCKED, r.getDecision(),
                "L1 关闭后路由层不再拦截 —— 安全不能只靠这一层");
    }
}
