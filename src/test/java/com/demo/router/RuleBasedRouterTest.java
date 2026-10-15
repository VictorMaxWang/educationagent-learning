package com.demo.router;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 规则路由测试。
 *
 * <p>纯 JUnit，不起 Spring 上下文——与本项目 {@code DocKnowledgeConfigTest} /
 * {@code TravelKnowledgeChunkerTest} 的风格一致。
 *
 * <p>其中 {@code 给我画一只猫} 是方案书 Prompt 10 明确记录过的回归用例：
 * 原实现只匹配以「画」开头的消息，导致「给我画一只猫」漏判、图片只生成不发送。
 */
class RuleBasedRouterTest {

    private final RuleBasedRouter router = new RuleBasedRouter(new RouterProperties());

    private RouteResult route(String msg) {
        return router.route("u-test", msg, MessageContext.wechat());
    }

    @Test
    void 系统指令命中且零工具() {
        RouteResult r = route("help");
        assertEquals(RouteDecision.SYSTEM_CMD, r.getDecision());
        assertEquals(List.of(), r.getTools());
        assertEquals("L1", r.getLayer());
    }

    @Test
    void 绘图前缀命中() {
        RouteResult r = route("画一只猫");
        assertEquals(RouteDecision.DRAW, r.getDecision());
        assertTrue(r.getTools().contains("generateImage"));
    }

    /** 方案书的回归用例：前缀式匹配漏掉「给我画…」，必须靠包含式兜住。 */
    @Test
    void 绘图包含式命中_给我画一只猫() {
        RouteResult r = route("给我画一只猫");
        assertEquals(RouteDecision.DRAW, r.getDecision());
        assertTrue(r.getTools().contains("generateImage"));
    }

    @Test
    void 帮我画也命中绘图() {
        assertEquals(RouteDecision.DRAW, route("帮我画一张海报").getDecision());
    }

    /** 「画」开头的非绘图词不能被误判成 DRAW。 */
    @Test
    void 画质不是绘图指令() {
        assertEquals(RouteDecision.FALLBACK, route("画质怎么样").getDecision());
        assertEquals(RouteDecision.FALLBACK, route("漫画推荐几部").getDecision());
    }

    @Test
    void 语音面板命中且收窄到两个语音工具() {
        RouteResult r = route("换音色");
        assertEquals(RouteDecision.VOICE_PANEL, r.getDecision());
        assertTrue(r.getTools().contains("controlVoiceMode"));
        assertTrue(r.getTools().contains("changeVoiceType"));
    }

    @Test
    void 开启语音命中语音面板() {
        assertEquals(RouteDecision.VOICE_PANEL, route("开启语音").getDecision());
    }

    @Test
    void 风格模仿命中() {
        assertEquals(RouteDecision.STYLE_IMITATE, route("模仿一下这段话的语气").getDecision());
    }

    /** 水印在本项目没有 @Tool，工具集如实为空，不能伪造工具名。 */
    @Test
    void 水印命中但工具集为空() {
        RouteResult r = route("给这张图加水印");
        assertEquals(RouteDecision.WATERMARK, r.getDecision());
        assertEquals(List.of(), r.getTools());
    }

    /** 闲聊不归 L1 管，交给 L2——L1 返回 FALLBACK 是"这层不管"，不是"失败"。 */
    @Test
    void 闲聊不归L1管() {
        assertEquals(RouteDecision.FALLBACK, route("你好").getDecision());
    }

    @Test
    void 未命中时返回三态null表示不过滤() {
        RouteResult r = route("帮我写个周报");
        assertEquals(RouteDecision.FALLBACK, r.getDecision());
        assertFalse(r.isToolFilterable());
        assertEquals(null, r.getTools());
    }

    @Test
    void 关闭开关后整层失效() {
        RouterProperties props = new RouterProperties();
        props.getRule().setEnabled(false);
        RuleBasedRouter disabled = new RuleBasedRouter(props);
        assertFalse(disabled.isEnabled());
    }
}
