package com.demo.router;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 意图库（TF-IDF 质心）测试 + 阈值诊断。
 *
 * <p>这个类既是单测也是<b>调参工具</b>：方案书给的 0.25 / 0.12 是在另一份语料上
 * 拍出来的，本项目必须用自己的语料重新看分布。{@link #打印相似度分布()} 会把
 * 每条验收语句的 top-1/top-2 相似度打出来，据此判断阈值该定在哪。
 */
class IntentTfIdfStoreTest {

    @TempDir
    Path tempDir;

    private IntentTfIdfStore newStore() {
        return newStore(new RouterProperties());
    }

    private IntentTfIdfStore newStore(RouterProperties props) {
        props.getTfidf().setStorePath(tempDir.resolve("intent-tfidf.json").toString());
        IntentTfIdfStore store = new IntentTfIdfStore(props, new DefaultResourceLoader());
        store.init();
        return store;
    }

    @Test
    void 种子全部加载且类别数与枚举一致() {
        IntentTfIdfStore store = newStore();
        assertTrue(store.isReady(), "意图库应当就绪");
        assertEquals(IntentLabel.values().length, store.labelCount());
        assertEquals("rebuilt", store.getSource(), "首次应当是从种子重建");
        // 11 类 × 15 条
        assertEquals(IntentLabel.values().length * 15, store.seedCount());
        assertTrue(store.vocabSize() > 0);
    }

    @Test
    void 标准说法命中自身意图() {
        IntentTfIdfStore store = newStore();
        assertEquals(IntentLabel.CHAT, top(store, "你好"));
        assertEquals(IntentLabel.CHAT, top(store, "晚上好"));
        assertEquals(IntentLabel.WEATHER, top(store, "今天北京天气怎么样"));
        assertEquals(IntentLabel.LAW, top(store, "正当防卫怎么判"));
        assertEquals(IntentLabel.TRAIN, top(store, "明天北京到上海的高铁"));
        assertEquals(IntentLabel.PET, top(store, "我家狗能吃什么"));
        assertEquals(IntentLabel.FILE, top(store, "把这个word转成pdf"));
    }

    /**
     * 泛化能力：种子之外的写法也要能落对意图。
     * <p>这是方案书验收标准「10 条泛化 &gt; 70%」的那一类。
     */
    @Test
    void 泛化说法命中意图() {
        IntentTfIdfStore store = newStore();
        assertEquals(IntentLabel.WEATHER, top(store, "明天下雨吗"));
        assertEquals(IntentLabel.MAP, top(store, "从家到公司开车要多久"));
        assertEquals(IntentLabel.SCHEDULE, top(store, "明早七点叫我起床"));
        assertEquals(IntentLabel.MAIL, top(store, "给团队发个通知邮件"));
        assertEquals(IntentLabel.IMAGE, top(store, "来张二次元头像"));
    }

    /**
     * 关键安全性质：<b>类别之外的正常需求不能被错误地判进某个意图</b>。
     *
     * <p>这不是理论担忧——实测就踩到了：
     * 「这个项目怎么部署」top1 = LAW@0.2871（高于 high 0.25），
     * 而 LAW 的工具集是<b>空的</b>，误判等于让这条消息失去全部工具能力。
     * 「帮我写个周报」top1 = MAIL@0.1948 / top2 = FILE@0.1696，两者咬得极紧。
     *
     * <p>靠绝对阈值分不开（正确项最低 0.2609，错误项 0.2871 夹在中间），
     * 所以这里断言的是 {@link TfIdfRouter} 的<b>最终决策</b>，
     * 它会同时卡绝对相似度与 top1-top2 支配度。
     */
    @Test
    void 类别外需求必须走FALLBACK兜底() {
        RouterProperties props = new RouterProperties();
        IntentTfIdfStore store = newStore(props);
        TfIdfRouter router = new TfIdfRouter(props, store);

        for (String msg : List.of("帮我写个周报", "这个项目怎么部署", "我昨天去了趟超市")) {
            RouteResult r = router.route("u-test", msg, MessageContext.wechat());
            assertEquals(RouteDecision.FALLBACK, r.getDecision(),
                    "「" + msg + "」应当走 FALLBACK（全量工具兜底），"
                            + "实际 " + r.getDecision() + " intent=" + r.getIntent()
                            + " rule=" + r.getMatchedRule());
        }
    }

    /** 与上一条互补：真实需求必须真的被路由到，不能用"提高阈值"一刀切保平安。 */
    @Test
    void 真实需求必须被路由命中() {
        RouterProperties props = new RouterProperties();
        IntentTfIdfStore store = newStore(props);
        TfIdfRouter router = new TfIdfRouter(props, store);

        assertIntent(router, "你好", IntentLabel.CHAT);
        assertIntent(router, "今天北京天气怎么样", IntentLabel.WEATHER);
        assertIntent(router, "明天会下雨吗", IntentLabel.WEATHER);
        assertIntent(router, "正当防卫怎么判", IntentLabel.LAW);
        assertIntent(router, "明天北京到上海的高铁", IntentLabel.TRAIN);
        assertIntent(router, "我家狗能吃什么", IntentLabel.PET);
        assertIntent(router, "从北京到上海怎么走", IntentLabel.MAP);
        assertIntent(router, "把这个word转成pdf", IntentLabel.FILE);
    }

    private static void assertIntent(TfIdfRouter router, String msg, IntentLabel expected) {
        RouteResult r = router.route("u-test", msg, MessageContext.wechat());
        assertEquals(expected, r.getIntent(),
                "「" + msg + "」应命中 " + expected + "，实际 " + r.getDecision()
                        + " intent=" + r.getIntent() + " rule=" + r.getMatchedRule());
        assertTrue(r.getDecision() == RouteDecision.TOOL || r.getDecision() == RouteDecision.CHAT);
    }

    @Test
    void 缓存可复用且结果一致() {
        RouterProperties props = new RouterProperties();
        Path cachePath = tempDir.resolve("cache.json");
        props.getTfidf().setStorePath(cachePath.toString());

        IntentTfIdfStore first = new IntentTfIdfStore(props, new DefaultResourceLoader());
        first.init();
        assertEquals("rebuilt", first.getSource());
        assertTrue(Files.exists(cachePath), "缓存文件应当落盘");

        IntentTfIdfStore second = new IntentTfIdfStore(props, new DefaultResourceLoader());
        second.init();
        assertEquals("cache", second.getSource(), "第二次应当直接读缓存");
        assertEquals(first.labelCount(), second.labelCount());
        assertEquals(first.vocabSize(), second.vocabSize());
        assertEquals(top(first, "正当防卫怎么判"), top(second, "正当防卫怎么判"));
    }

    /** 改种子 → 哈希失配 → 自动重建（本项目 Indexer 指纹自愈的同款思路）。 */
    @Test
    void 种子变更后缓存自动失效() {
        RouterProperties props = new RouterProperties();
        Path cachePath = tempDir.resolve("stale.json");
        props.getTfidf().setStorePath(cachePath.toString());

        IntentTfIdfStore first = new IntentTfIdfStore(props, new DefaultResourceLoader());
        first.init();
        assertEquals("rebuilt", first.getSource());

        // 手工把缓存里的指纹改坏，模拟"种子已改动"
        try {
            String json = Files.readString(cachePath).replace("\"seedHash\":\"", "\"seedHash\":\"dead");
            Files.writeString(cachePath, json);
        } catch (Exception e) {
            throw new AssertionError("改写缓存失败", e);
        }

        IntentTfIdfStore second = new IntentTfIdfStore(props, new DefaultResourceLoader());
        second.init();
        assertEquals("rebuilt", second.getSource(), "指纹不匹配必须重建");
    }

    /** 用户记忆去重用的就是这套字符切分，这里顺带钉住行为。 */
    @Test
    void 分词包含单字与双字且去掉标点() {
        List<String> tokens = IntentTfIdfStore.tokenize("今天天气，怎么样？");
        assertTrue(tokens.contains("b今天"), "应含双字词：今天");
        assertTrue(tokens.contains("u今"), "应含单字：今");
        assertTrue(tokens.stream().noneMatch(t -> t.contains("，")), "标点应被去除");
        assertTrue(tokens.stream().noneMatch(t -> t.contains("？")), "中文标点应被去除");
    }

    // ======================== 诊断输出 ========================

    /**
     * 打印相似度分布，用于人工调阈值。
     * <p>不是断言测试——是给调参看的。运行 {@code mvn test -Dtest=IntentTfIdfStoreTest} 查看输出。
     */
    @Test
    void 打印相似度分布() {
        String[] probes = {
                "你好", "晚上好", "在吗",
                "今天北京天气怎么样", "明天会下雨吗",
                "正当防卫怎么判", "离婚财产怎么分割",
                "明天北京到上海的高铁",
                "画一只猫", "给我画一只猫",
                "提醒我十分钟后开会",
                "把这个word转成pdf",
                "我家狗能吃什么",
                "帮我发一封邮件",
                "从北京到上海怎么走",
                "帮我搜一下最新的新闻",
                "帮我写个周报", "这个项目怎么部署", "解释一下什么是闭包",
        };
        RouterProperties props = new RouterProperties();
        IntentTfIdfStore store = newStore(props);
        TfIdfRouter router = new TfIdfRouter(props, store);
        System.out.println("\n===== L2 意图相似度分布（top1 / top2 / margin / 最终决策）=====");
        System.out.printf("%-24s %-10s %-8s %-10s %-8s %-8s %s%n",
                "消息", "top1", "sim1", "top2", "sim2", "margin", "决策");
        for (String p : probes) {
            List<IntentTfIdfStore.IntentScore> r = store.rank(p);
            String top1 = r.isEmpty() ? "-" : r.get(0).label().name();
            double s1 = r.isEmpty() ? 0 : r.get(0).similarity();
            String top2 = r.size() < 2 ? "-" : r.get(1).label().name();
            double s2 = r.size() < 2 ? 0 : r.get(1).similarity();
            RouteResult decision = router.route("u-test", p, MessageContext.wechat());
            System.out.printf("%-24s %-10s %-8.4f %-10s %-8.4f %-8.4f %s(%s)%n",
                    p, top1, s1, top2, s2, s1 - s2,
                    decision.getDecision(),
                    decision.getIntent() == null ? "-" : decision.getIntent().name());
        }
        System.out.println("==========================================\n");
        assertNotNull(store);
    }

    private static IntentLabel top(IntentTfIdfStore store, String msg) {
        List<IntentTfIdfStore.IntentScore> ranked = store.rank(msg);
        assertTrue(!ranked.isEmpty(), "「" + msg + "」应当有得分");
        return ranked.get(0).label();
    }
}
