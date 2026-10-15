package com.demo.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 双路 RAG 融合后的一条结果。
 *
 * <p>保留 {@code originalScore} 与 {@code fusedScore} 两个分数是刻意的：
 * 融合分数只反映"在这两路里排得靠前"，无法反映"到底有多相关"。
 * 调参时如果只看融合分数，会分不清"两路都排第一但都只是勉强相关"
 * 和"一路排第一且高度相关"——前者不该进提示词。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FusedResult {

    /** 内容正文。 */
    private String content;

    /** 主要来源：{@code MEMORY} / {@code LAW}。 */
    private String source;

    /** 原始相关度：记忆侧是余弦相似度；法律侧由距离换算（1 - distance/2）。 */
    private double originalScore;

    /** RRF 融合分数。 */
    private double fusedScore;

    /** 融合后的排名，从 1 开始。 */
    private int rank;

    /** 该条内容被几路命中（2 = 记忆与法律都有类似内容，说明它很可能是核心事实）。 */
    private int hitSources;

    /** 附加信息（法律条文的距离、记忆的 ID 等）。 */
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    /** 来源常量。 */
    public static final String SOURCE_MEMORY = "MEMORY";
    public static final String SOURCE_LAW = "LAW";
}
