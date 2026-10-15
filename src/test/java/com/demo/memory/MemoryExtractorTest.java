package com.demo.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆抽取规则测试（纯规则层，LLM 兜底默认关闭，不产生任何外部调用）。
 */
class MemoryExtractorTest {

    /**
     * 规则层专用实例：<b>关掉埋点全量捕获</b>。
     *
     * <p>不关的话"规则没命中"会落到原句捕获，于是 {@code extract(...).isEmpty()} 永远为假，
     * 这些规则用例就没法表达"这条正则不该命中"了。捕获行为单独用 {@link #captureAll} 测。
     */
    private final MemoryExtractor ruleOnly = ruleOnly();

    /** 默认配置（捕获开启），验证"说什么都能加进去"。 */
    private final MemoryExtractor captureAll = new MemoryExtractor(new MemoryProperties(), null);

    private static MemoryExtractor ruleOnly() {
        MemoryProperties props = new MemoryProperties();
        props.getExtractor().setCaptureAll(false);
        return new MemoryExtractor(props, null);
    }

    /** 规则层抽取结果（不含埋点捕获的原句）。 */
    private List<MemoryRecord> extract(String msg) {
        return ruleOnly.extract("u1", msg);
    }

    @Test
    void 抽取姓名() {
        List<MemoryRecord> r = extract("我叫张三");
        assertEquals(1, r.size());
        assertEquals("用户叫张三", r.get(0).getContent());
        assertEquals(MemoryType.BASIC, r.get(0).getType());
        assertEquals(9, r.get(0).getImportance(), "姓名应当是高重要度");
    }

    @Test
    void 抽取姓名时清掉语气词() {
        assertEquals("用户叫张三", extract("我叫张三呀").get(0).getContent());
    }

    @Test
    void 抽取居住地() {
        assertEquals("用户住在杭州", extract("我住在杭州").get(0).getContent());
        assertEquals("用户住在四川", extract("我家在四川").get(0).getContent());
        assertEquals("用户住在东北", extract("我来自东北").get(0).getContent());
    }

    /**
     * 与方案书的有意差异：裸的「我在」不再被当作居住地。
     *
     * <p>方案书的正则 {@code (?:我住在|我在)(...)} 会把「我在想一个问题」抽成
     * "用户住想一个问题"、"我在开会"抽成"用户住开会"。
     * 错误信息一旦写进长期记忆，之后每轮对话都会被注入，而且要 90 天才自动清除——
     * 少抽一条远比抽错一条安全。
     */
    @Test
    void 我在不当作居住地() {
        assertTrue(extract("我在想一个问题").isEmpty(), "「我在想一个问题」不该被抽成居住地");
        assertTrue(extract("我在开会").isEmpty(), "「我在开会」不该被抽成居住地");
    }

    @Test
    void 抽取偏好() {
        List<MemoryRecord> like = extract("我喜欢跑步");
        assertEquals("用户喜欢跑步", like.get(0).getContent());
        assertEquals(MemoryType.SEMANTIC, like.get(0).getType());

        List<MemoryRecord> dislike = extract("我讨厌加班");
        assertEquals("用户不喜欢加班", dislike.get(0).getContent());
    }

    /** 「我不喜欢」必须走讨厌分支；正则本身不会把它误判成喜欢，这里钉住行为。 */
    @Test
    void 我不喜欢走讨厌分支() {
        List<MemoryRecord> r = extract("我不喜欢吃辣");
        assertEquals(1, r.size());
        assertEquals("用户不喜欢吃辣", r.get(0).getContent());
    }

    @Test
    void 抽取偏好时在标点处截断() {
        List<MemoryRecord> r = extract("我喜欢跑步，也喜欢游泳");
        assertEquals("用户喜欢跑步", r.get(0).getContent(), "应当在第一个逗号处截断");
    }

    /**
     * 喜欢与不喜欢必须**各自独立**判断。
     *
     * <p>原实现是 {@code if (dislike) … else if (like) …}，前提是"一条消息只说一个偏好"。
     * 但一条消息完全可以同时说两件——实测用户发「…我喜欢喝咖啡 我不喜欢吃香菜…」时，
     * dislike 一命中 like 就被整个跳过，「用户喜欢喝咖啡」直接丢了。
     */
    @Test
    void 喜欢与不喜欢各自独立抽取() {
        List<MemoryRecord> r = extract("我喜欢喝咖啡 我不喜欢吃香菜");
        List<String> got = r.stream().map(MemoryRecord::getContent).toList();
        assertTrue(got.contains("用户喜欢喝咖啡"), "实际=" + got);
        assertTrue(got.contains("用户不喜欢吃香菜"), "实际=" + got);
    }

    /** 用户真实发过的整段（一条消息 7 句），逐条核对不遗漏。 */
    @Test
    void 一条消息里的多句自我陈述都不遗漏() {
        String msg = "我叫张三 我是女生 我今年 28 岁 我住在杭州 我在杭州工作 我喜欢喝咖啡 我不喜欢吃香菜";
        List<String> got = extract(msg).stream().map(MemoryRecord::getContent).toList();
        for (String expect : List.of(
                "用户叫张三", "用户是女生", "用户今年 28 岁", "用户住在杭州",
                "用户在杭州工作/学习", "用户喜欢喝咖啡", "用户不喜欢吃香菜")) {
            assertTrue(got.contains(expect), "缺了「" + expect + "」，实际=" + got);
        }
    }

    @Test
    void 一句话可同时抽到多条() {
        List<MemoryRecord> r = extract("我叫张三，我住在杭州，我喜欢跑步");
        assertEquals(3, r.size());
        assertTrue(r.stream().anyMatch(x -> x.getContent().equals("用户叫张三")));
        assertTrue(r.stream().anyMatch(x -> x.getContent().equals("用户住在杭州")));
        assertTrue(r.stream().anyMatch(x -> x.getContent().equals("用户喜欢跑步")));
    }

    @Test
    void 无自我陈述时不抽取() {
        assertTrue(extract("今天天气怎么样").isEmpty());
        assertTrue(extract("帮我写个周报").isEmpty());
        assertTrue(extract("").isEmpty());
        assertTrue(extract(null).isEmpty());
    }

    /** 规则已抽到就不该再调 LLM——省一次调用，也避免两路结果打架。 */
    @Test
    void 规则命中时不走LLM() {
        MemoryProperties props = new MemoryProperties();
        props.getExtractor().setLlmEnabled(true);
        // restTemplate 传 null：如果实现真的去调 LLM 就会 NPE，从而暴露"不该调用却调了"
        MemoryExtractor e = new MemoryExtractor(props, null);
        List<MemoryRecord> r = e.extract("u1", "我叫张三");
        assertEquals(1, r.size());
        assertEquals("用户叫张三", r.get(0).getContent());
    }

    // ======================== 扩展规则（2026-10） ========================
    // 起因：只有 4 条规则时，「我是女生」「我 30 岁」「我养了只猫」这类
    // 最常见的自我陈述一条都抽不到，用户会以为记忆功能坏了。

    @Test
    void 抽取身份() {
        assertEquals("用户是女生", extract("我是女生").get(0).getContent());
        assertEquals("用户是老师", extract("我是老师。").get(0).getContent());
        assertEquals("用户是软件工程师", extract("我是软件工程师").get(0).getContent());
    }

    /** 「我是说」是口头禅不是身份。写进长期记忆后每轮都会被注入，且要 90 天才清除。 */
    @Test
    void 我是说不是身份() {
        assertTrue(extract("我是说这个方案不行").isEmpty(), "「我是说」不该被当作身份");
        assertTrue(extract("我是来问个问题的").isEmpty(), "「我是来」不该被当作身份");
        assertTrue(extract("我是为了你好").isEmpty());
    }

    /** 没有标点边界时宁可不抽，也不要抽成"女生我喜欢咖啡"这种半句话。 */
    @Test
    void 身份要求标点边界() {
        List<MemoryRecord> r = extract("我是女生我喜欢咖啡");
        assertTrue(r.stream().noneMatch(x -> x.getContent().startsWith("用户是")),
                "抽不出干净的边界就不出身份这条——不能抽成「用户是女生我喜欢」");
        // 但同一句里的「我喜欢咖啡」是干净匹配，仍应正常抽到（不该因为身份放弃就一起放弃）
        assertTrue(r.stream().anyMatch(x -> x.getContent().equals("用户喜欢咖啡")));
    }

    @Test
    void 抽取年龄() {
        assertEquals("用户今年 30 岁", extract("我30岁").get(0).getContent());
        assertEquals("用户今年 28 岁", extract("我今年28岁").get(0).getContent());
    }

    @Test
    void 抽取联系方式() {
        assertEquals("用户的手机号是13800138000",
                extract("我的手机号是13800138000").get(0).getContent());
        assertEquals("用户的生日是1995-03-12",
                extract("我的生日是1995-03-12").get(0).getContent());
    }

    @Test
    void 抽取工作地() {
        assertEquals("用户在杭州工作/学习", extract("我在杭州工作").get(0).getContent());
        assertEquals("用户在北大工作/学习", extract("我在北大读书").get(0).getContent());
    }

    /** 新规则不能把「我在开会」这种旧的反例变成误抽——这是原有测试守的边界。 */
    @Test
    void 新规则未放宽我在() {
        assertTrue(extract("我在开会").isEmpty());
        assertTrue(extract("我在想一个问题").isEmpty());
    }

    @Test
    void 抽取宠物() {
        assertEquals("用户养了猫", extract("我养了一只猫").get(0).getContent());
        assertEquals("用户养了狗", extract("我养了狗").get(0).getContent());
    }

    @Test
    void 抽取日常习惯() {
        assertEquals("用户经常跑步", extract("我每天跑步").get(0).getContent());
        assertEquals("用户经常加班", extract("我经常加班").get(0).getContent());
    }

    @Test
    void 抽取计划() {
        assertEquals("用户想学编程", extract("我想学编程").get(0).getContent());
        assertEquals("用户打算考研", extract("我打算考研").get(0).getContent());
    }

    /** 「我想问一下」不是计划——排除表必须挡住，否则每句客套话都会进记忆库。 */
    @Test
    void 我想问不是计划() {
        assertTrue(extract("我想问一下这个怎么弄").isEmpty());
        assertTrue(extract("我准备说的是另一件事").isEmpty());
        assertTrue(extract("我想知道答案").isEmpty());
    }

    // ======================== 埋点全量捕获 ========================
    // 「说什么都能加进去」的落点：规则认不出的句子，原样存成一条 EPISODIC 记忆。
    // 它靠两道闸门控制代价：不进提示词（重要度 2 < 门槛 3）、优先被分代衰减回收。

    @Test
    void 规则未命中时原句被捕获() {
        List<MemoryRecord> r = captureAll.extract("u1", "我最近压力有点大");
        assertEquals(1, r.size(), "规则认不出，但这句话该被记下来");

        MemoryRecord m = r.get(0);
        assertEquals("我最近压力有点大", m.getContent(), "捕获的是原句");
        assertEquals(MemoryType.EPISODIC, m.getType(),
                "原句属于一次性事件，走更短的衰减档");
        assertEquals(MemoryProperties.Extractor.CAPTURE_IMPORTANCE, m.getImportance(),
                "重要度必须低于提示词门槛，否则每轮上下文都会被闲聊稀释");
        assertFalse(m.getKeywords() == null || m.getKeywords().isBlank(),
                "捕获的记忆必须带关键词，脑图才有短标签可用");
    }

    @Test
    void 规则命中时不重复捕获原句() {
        List<MemoryRecord> r = captureAll.extract("u1", "我叫张三");
        assertEquals(1, r.size(), "规则已给出干净事实，不该再存一份原句");
        assertEquals("用户叫张三", r.get(0).getContent());
    }

    @Test
    void 太短的消息不捕获() {
        assertTrue(captureAll.extract("u1", "嗯").isEmpty());
        assertTrue(captureAll.extract("u1", "好的").isEmpty());
    }

    @Test
    void 没有内容词的消息不捕获() {
        // 足够长，但切不出任何内容片段 → 依然是纯噪声
        assertTrue(captureAll.extract("u1", "的了呢吧吗啊呀").isEmpty());
    }

    @Test
    void 关闭捕获后回到纯规则行为() {
        assertTrue(ruleOnly.extract("u1", "我最近压力有点大").isEmpty(),
                "capture-all=false 时行为应当与改造前完全一致");
    }

    @Test
    void 超长原句被截断() {
        String longMsg = "这是一个很长很长的句子".repeat(30);
        List<MemoryRecord> r = captureAll.extract("u1", longMsg);
        assertEquals(1, r.size());
        assertTrue(r.get(0).getContent().length() <= 200,
                "原句入库要有长度上限，防止粘贴大段文字撑爆记忆库");
    }
}
