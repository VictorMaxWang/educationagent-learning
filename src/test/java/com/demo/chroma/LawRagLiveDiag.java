package com.demo.chroma;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 法律 RAG 实连诊断：真的去打 Chroma + 真的调 DeepSeek，用于人工核对检索质量。
 *
 * <p>默认<b>不跑</b>（需要 MySQL / Chroma / DeepSeek 全部在线，且会产生 LLM 费用），
 * 用环境变量开启——与本项目 {@code EmbeddingChannelDiag} / {@code RagEvalJudgeDiag}
 * 的门控方式一致：
 * <pre>
 *   LAW_RAG_LIVE=1 ./mvnw test -Dtest=LawRagLiveDiag
 * </pre>
 *
 * <p>它不是"必须常绿"的回归测试（语料/模型一变数字就会动），而是把
 * 「阈值收紧后到底召回了几条」这件事变得可观测——这正是方案书任务 2 的验收方式。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LAW_RAG_LIVE", matches = "1")
class LawRagLiveDiag {

    @Autowired
    private LawKnowledgeService lawKnowledgeService;

    @Test
    void 民法典检索_过度召回是否收住() {
        String[] queries = {
                "正当防卫怎么判",
                "离婚财产怎么分割",
                "朋友借钱不还怎么办",
        };
        System.out.println("\n===== 法律 RAG 实连诊断（民法典）=====");
        for (String q : queries) {
            Map<String, Object> r = lawKnowledgeService.searchAndAnswer("diag-" + q.hashCode(), q, "civil");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> articles = (List<Map<String, Object>>) r.get("articles");
            System.out.printf("%n【%s】%n  成功=%s  保留条文=%d 条%n",
                    q, r.get("success"), articles == null ? -1 : articles.size());
            if (articles != null) {
                for (int i = 0; i < articles.size(); i++) {
                    Map<String, Object> a = articles.get(i);
                    String content = String.valueOf(a.get("content")).replaceAll("\\s+", " ");
                    if (content.length() > 60) content = content.substring(0, 60) + "…";
                    System.out.printf("    %d) d=%.4f  %s%n", i + 1, a.get("distance"), content);
                }
            }
            Object answer = r.get("answer");
            System.out.printf("  回复长度=%d 字%n", answer == null ? 0 : String.valueOf(answer).length());
            assertTrue(Boolean.TRUE.equals(r.get("success")), "检索应当成功");
        }
        System.out.println("====================================\n");
    }

    /** 请求未入库的库（labor / criminal 都只有空壳）→ 必须软降级到 civil 并给出提示。 */
    @Test
    void 未收录的库软降级到民法典() {
        Map<String, Object> r = lawKnowledgeService.searchAndAnswer("diag-labor", "劳动法规定一周工作时间", "labor");
        System.out.printf("%n【劳动法 → 软降级】success=%s lib_used=%s lib_requested=%s degraded=%s%n note=%s%n",
                r.get("success"), r.get("lib_used"), r.get("lib_requested"), r.get("degraded"), r.get("note"));

        assertTrue(Boolean.TRUE.equals(r.get("success")));
        assertNotNull(r.get("note"), "未收录的库应当给出提示");
        // 若民法典有向量则应降级到 civil；若连民法典都空，则 lib_used 保持 labor 且提示"暂未收录"
        Object used = r.get("lib_used");
        assertTrue("civil".equals(used) || "labor".equals(used));
    }

    @Test
    void 门控_闲聊不触发法律检索() {
        LawContextProvider provider = new LawContextProvider(lawKnowledgeService);
        assertFalse(provider.shouldQuery(null, "你好"));
        assertTrue(provider.shouldQuery(null, "正当防卫怎么判"));
    }
}
