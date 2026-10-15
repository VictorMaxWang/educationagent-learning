package com.demo.chroma;

import com.demo.router.IntentLabel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 法律 RAG 对话门控测试。
 *
 * <p>只测 {@code shouldQuery}（纯判断，不碰 Chroma），所以传 null 服务即可。
 * 覆盖方案书给的 4 条验收标准，以及一处有意的顺序调整。
 */
class LawContextProviderTest {

    private final LawContextProvider provider = new LawContextProvider(null);

    // ======================== 方案书 4 条验收标准 ========================

    @Test
    void 闲聊不查法律() {
        assertFalse(provider.shouldQuery(null, "你好"));
        assertFalse(provider.shouldQuery(null, "晚上好"));
    }

    @Test
    void 天气不查法律() {
        assertFalse(provider.shouldQuery(null, "今天天气"));
        assertFalse(provider.shouldQuery(null, "气温多少度"));
    }

    @Test
    void 正当防卫走LAW意图时查法律() {
        assertTrue(provider.shouldQuery(IntentLabel.LAW, "正当防卫怎么判"));
    }

    @Test
    void 离婚财产纠纷走LAW意图时查法律() {
        assertTrue(provider.shouldQuery(IntentLabel.LAW, "离婚财产纠纷怎么判"));
    }

    // ======================== 关键词兜底 ========================

    /** 路由没判成 LAW，但措辞明显是法律问题 → 关键词兜底仍要查。 */
    @Test
    void 意图未识别但关键词强制命中仍查法律() {
        assertTrue(provider.shouldQuery(null, "正当防卫怎么判"));
        assertTrue(provider.shouldQuery(IntentLabel.CHAT, "朋友借钱不还怎么办，这算违法吗"));
        assertTrue(provider.shouldQuery(null, "公司无故辞退我，有违约金吗"));
    }

    @Test
    void 无关领域既不命中强制词也不查() {
        assertFalse(provider.shouldQuery(IntentLabel.PET, "狗狗拉肚子了"));
        assertFalse(provider.shouldQuery(IntentLabel.SCHEDULE, "明天八点叫我"));
    }

    /**
     * 有意的顺序调整：跳过词优先于强制词。
     *
     * <p>方案书给的顺序是「强制词 → 跳过词」，但那样跳过词永远轮不到，等于死代码。
     * 「把合同文件存到知识库」同时含 {@code 合同}（强制）与 {@code 文件}（跳过），
     * 这明显是文件操作而不是法律咨询——跳过才对。
     */
    @Test
    void 跳过词优先于强制词() {
        assertFalse(provider.shouldQuery(null, "把合同文件存到知识库"),
                "含「合同」但也含「文件」，应当是文件操作而非法律咨询");
        assertFalse(provider.shouldQuery(null, "帮我把这份财产相关的图片发我"),
                "含「财产」但也含「图片」，不应触发法律检索");
    }

    /** 但显式 LAW 意图优先级最高，即使措辞里带跳过词。 */
    @Test
    void 显式LAW意图高于跳过词() {
        assertTrue(provider.shouldQuery(IntentLabel.LAW, "把这个合同文件的问题讲一下"));
    }

    @Test
    void 空消息安全() {
        assertFalse(provider.shouldQuery(null, null));
        assertFalse(provider.shouldQuery(null, "   "));
    }

    /** 意图为未识别且无任何关键词时不应触发（默认关闭，避免每条消息都打 Chroma）。 */
    @Test
    void 默认不触发() {
        assertFalse(provider.shouldQuery(null, "帮我写个周报"));
    }
}
