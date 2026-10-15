package com.demo.chroma.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.demo.config.AiChatProperties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * RAG 评估裁判实跑诊断（需要联网 + DeepSeek 额度，默认跳过）。
 *
 * <p>用途：<b>在不依赖 embedding / Chroma 的前提下</b>验证创新点的核心 ——
 * 「给定额问、标准答案、检索资料、待评答案，裁判能否稳定输出三项指标」。
 * 检索链路因百炼欠费/本地模型缺失而不可用时，这条诊断仍能跑通，
 * 因为它只需要 DeepSeek（与百炼是两个独立账户）。</p>
 *
 * <p><b>启用方式</b>（默认跳过，避免 CI 里意外产生网络调用与费用）：</p>
 * <pre>
 * RAG_EVAL_LIVE=1 ./mvnw test -Dtest=RagEvalJudgeDiag
 * </pre>
 *
 * <p>诊断刻意包含一个「应当低分」的样本：若裁判对好样本与坏样本给出一模一样的高分，
 * 说明它在无脑打满分，指标没有区分度 —— 那才是真正要发现的问题。</p>
 */
public class RagEvalJudgeDiag {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * JUnit 入口（便于用项目现成的 surefire 运行）。
     * 未设置 RAG_EVAL_LIVE 时自动跳过，不会在普通 {@code mvn test} 中产生网络调用。
     */
    @org.junit.jupiter.api.Test
    void runDiagnostic() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("RAG_EVAL_LIVE") != null,
                "实跑诊断需显式开启：RAG_EVAL_LIVE=1 mvn test -Dtest=RagEvalJudgeDiag");
        main(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        if (System.getenv("RAG_EVAL_LIVE") == null) {
            System.out.println("跳过：设置环境变量 RAG_EVAL_LIVE=1 后再运行本诊断（会联网调用 DeepSeek）");
            return;
        }

        Properties props = loadProps();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new PropertiesPropertySource("diagnostic", props));
        AiChatProperties aiChat = new AiChatProperties(environment);
        if (!aiChat.hasApiKey()) {
            System.out.println("★ 未配置 AI_CHAT_API_KEY，无法运行");
            return;
        }

        RagEvalJudge judge = new RagEvalJudge();
        set(judge, "aiChat", aiChat);
        set(judge, "judgeTemperature", 0.0);
        set(judge, "answerTemperature", 0.0);
        set(judge, "objectMapper", MAPPER);
        judge.init();

        System.out.println("================ RAG 评估裁判实跑诊断 ================\n");

        // ---- 样本 A：应当高分（资料完整、答案忠实、与标准答案一致）----
        runCase(judge, "A 应当高分",
                "民法典规定的成年年龄是多少？",
                "十八周岁以上的自然人为成年人。",
                List.of("第十七条 十八周岁以上的自然人为成年人。不满十八周岁的自然人为未成年人。"),
                "根据民法典第十七条，十八周岁以上的自然人为成年人。");

        // ---- 样本 B：应当低分（资料与问题无关、答案凭空编造且与标准答案矛盾）----
        runCase(judge, "B 应当低分",
                "民法典规定的成年年龄是多少？",
                "十八周岁以上的自然人为成年人。",
                List.of("第三条 民事主体的人身权利、财产权利以及其他合法权益受法律保护。"),
                "民法典规定成年人年龄是二十岁，这是普遍适用的法律标准。");

        // ---- 生成答案链路（同样不需要 embedding，只验证基于资料的作答约束）----
        System.out.println("---------------- 生成答案链路 ----------------");
        List<String> ctx = List.of("第十七条 十八周岁以上的自然人为成年人。不满十八周岁的自然人为未成年人。");
        String ans = judge.generateAnswer("民法典规定的成年年龄是多少？", ctx);
        System.out.println("  问：" + "民法典规定的成年年龄是多少？");
        System.out.println("  答：" + ans);
        System.out.println("  （预期答案应严格来自资料、不引入外部知识）\n");

        String refusal = judge.generateAnswer("民法典对虚拟货币挖矿有何规定？", ctx);
        System.out.println("  问（资料中无答案）：民法典对虚拟货币挖矿有何规定？");
        System.out.println("  答：" + refusal);
        System.out.println("  （预期应明确表示资料中未提及，而不是拿常识作答）");

        System.out.println("\n======================================================");
    }

    private static void runCase(RagEvalJudge judge, String label, String question, String groundTruth,
                                List<String> contexts, String answer) {
        System.out.println("---------------- " + label + " ----------------");
        System.out.println("  问：" + question);
        System.out.println("  标准答案：" + groundTruth);
        System.out.println("  检索资料：" + (contexts.isEmpty() ? "（空）" : contexts.get(0)));
        System.out.println("  待评答案：" + answer);
        RagEvalJudge.JudgeResult r = judge.judge(question, groundTruth, contexts, answer);
        if (r == null) {
            System.out.println("  ★ 裁判返回 null（调用失败或 JSON 解析失败）—— 该用例会被记为 FAILED\n");
            return;
        }
        System.out.printf("  context_recall      = %.3f%n", r.contextRecall);
        System.out.printf("  faithfulness        = %.3f%n", r.faithfulness);
        System.out.printf("  factual_correctness = %.3f%n", r.factualCorrectness);
        System.out.println("  裁判原始输出：" + r.rawJson);
        System.out.println();
    }

    private static Properties loadProps() {
        Properties p = new Properties();
        for (String f : List.of("src/main/resources/application.properties",
                "src/main/resources/application-dev.properties")) {
            Path path = Path.of(f);
            if (!Files.exists(path)) continue;
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (Exception e) {
                System.out.println("读取 " + f + " 失败: " + e.getMessage());
            }
        }
        return p;
    }

    /** 直接实例化时 @Value 字段不会被注入，用反射补上（不引入 spring-test 依赖） */
    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
