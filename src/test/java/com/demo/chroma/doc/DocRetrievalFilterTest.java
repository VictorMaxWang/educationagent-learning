package com.demo.chroma.doc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库检索过滤测试。
 *
 * <h3>这里守的是两条相反的东西，都要钉住</h3>
 * <ol>
 *   <li><b>新知识库开启三道过滤后确实在收敛</b>：生命安全与健康教育/性教育用
 *       {@code 0.85 + 1.08 + 10 字}，噪声片段不能被拼进提示词；</li>
 *   <li><b>已有学科零回归</b>：通识/心理学没有显式开启（{@code relativeCutoffRatio=0}），
 *       必须保持改造前"只有绝对阈值"的行为——不能因为改了框架，把两个已上线学科的召回
 *       悄悄收紧。</li>
 * </ol>
 */
class DocRetrievalFilterTest {

    /** 通识/心理学那种配置：未开启三级过滤（ratio=0）。 */
    private static DocKnowledgeConfig legacyConfig() {
        return new DocKnowledgeConfig("legacy", "legacy_", "data/legacy-knowledge",
                List.of(new DocKnowledgeConfig.SubLibrary("general", "通用", List.of())),
                "prompt", 3, 1.2, 5, "免责", 800, 80, 10);
    }

    /** 生命安全与健康教育/性教育那种配置：显式开启三级过滤。 */
    private static DocKnowledgeConfig eduConfig() {
        return new DocKnowledgeConfig("edu", "edu_", "data/edu-knowledge",
                List.of(new DocKnowledgeConfig.SubLibrary("general", "通用", List.of())),
                "prompt", 3, 0.85, 5, "免责", 800, 80, 10,
                1.08, 10, true);
    }

    private static Map<String, Object> hit(String content, double distance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", content);
        m.put("distance", distance);
        m.put("metadata", Map.of("source", "general", "file", "doc.txt", "chunkIndex", 0));
        return m;
    }

    private static String text(int len) {
        return "内".repeat(Math.max(1, len));
    }

    @Test
    @DisplayName("未开启（ratio=0）：只有绝对阈值生效 —— 现有学科零回归")
    void legacyKeepsBehaviorUnchanged() {
        List<Map<String, Object>> hits = List.of(
                hit(text(50), 0.43),
                hit(text(50), 0.51),
                hit(text(50), 0.60));
        // 改造前的行为：三条都小于 1.2，全部保留（不会被"相对 cutoff"收掉）
        assertEquals(3, DocKnowledgeAssistant.selectHits(legacyConfig(), hits).size(),
                "未开启三级过滤时不能收紧召回，否则通识/心理学的行为被悄悄改了");
    }

    @Test
    @DisplayName("未开启：超过绝对阈值的照旧丢弃")
    void legacyStillDropsOverThreshold() {
        List<Map<String, Object>> hits = List.of(
                hit(text(50), 0.43),
                hit(text(50), 1.30));
        List<Map<String, Object>> kept = DocKnowledgeAssistant.selectHits(legacyConfig(), hits);
        assertEquals(1, kept.size());
        assertEquals(0.43, kept.get(0).get("distance"));
    }

    @Test
    @DisplayName("开启三级过滤：相对 cutoff 把明显不相关的尾条切掉")
    void threeStageConverges() {
        List<Map<String, Object>> hits = List.of(
                hit(text(50), 0.43),
                hit(text(50), 0.51),
                hit(text(50), 0.60));
        // best=0.43 → cutoff=0.43*1.08=0.4644 → 只留 0.43
        List<Map<String, Object>> kept = DocKnowledgeAssistant.selectHits(eduConfig(), hits);
        assertEquals(1, kept.size(), "三道过滤应把 0.51 / 0.60 两条切掉");
        assertEquals(0.43, kept.get(0).get("distance"));
    }

    @Test
    @DisplayName("开启三级过滤：内容过短的片段先被丢弃（OCR 产物那种）")
    void threeStageDropsTooShortContent() {
        List<Map<String, Object>> hits = List.of(
                hit(text(50), 0.43),
                hit(text(50), 0.50),
                hit("1. 。", 0.60));           // 3 字，过短
        List<Map<String, Object>> kept = DocKnowledgeAssistant.selectHits(eduConfig(), hits);
        assertTrue(kept.size() <= 1, "过短片段不该进入提示词");
        for (Map<String, Object> k : kept) {
            assertTrue(String.valueOf(k.get("content")).length() >= 10);
        }
    }

    @Test
    @DisplayName("开启三级过滤：全部超绝对阈值时返回空（答'暂无匹配'而不是硬塞噪声）")
    void threeStageReturnsEmptyWhenNothingCloseEnough() {
        List<Map<String, Object>> hits = List.of(
                hit(text(50), 0.91),
                hit(text(50), 1.20));
        assertTrue(DocKnowledgeAssistant.selectHits(eduConfig(), hits).isEmpty());
    }

    @Test
    @DisplayName("空召回与 null 安全")
    void handlesEmpty() {
        assertTrue(DocKnowledgeAssistant.selectHits(eduConfig(), null).isEmpty());
        assertTrue(DocKnowledgeAssistant.selectHits(eduConfig(), new ArrayList<>()).isEmpty());
        assertTrue(DocKnowledgeAssistant.selectHits(legacyConfig(), null).isEmpty());
    }
}
