package com.demo.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条用户记忆。
 *
 * <p>用可变类 + Lombok 而不是 record，是因为 {@code reinforce()}（访问计数 +1）和
 * {@code decay()}（重要度衰减）都是对同一条记录的原地小改，用 record 会变成
 * "每次改动产出一个新对象"，在批量衰减场景下白白制造垃圾。
 *
 * @see com.demo.memory.store.LuceneMemoryStore 关于 {@link #embedding} 何时为 null
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRecord {

    /** 唯一 ID（UUID）。 */
    private String id;

    /** 归属用户——用户隔离靠它，Lucene 检索时用 TermQuery 过滤。 */
    private String userId;

    /** 记忆正文（一句话事实）。 */
    private String content;

    /** 记忆类型，决定衰减策略。 */
    private MemoryType type;

    /** 重要度 1~10。由抽取规则给初值，后续被反馈与访问频次调整。 */
    private int importance;

    /** 创建时间（epoch millis）。 */
    private long createdAt;

    /** 最近一次被检索命中的时间（epoch millis）。 */
    private long lastAccessedAt;

    /** 被检索命中的累计次数，用于"高频记忆放大显示"与逐出评分。 */
    private int accessCount;

    /**
     * 本地抽取的关键词，逗号分隔（可能为空）。
     *
     * <p>给脑图当节点标签用——原句往往太长，同心环上挤不下也读不过来。
     * 由 {@link KeywordExtractor} 生成，**不经任何模型**。
     *
     * <p>⚠️ Lucene 的 {@code updateDocument} 是整文档替换：任何"部分更新"
     * （比如访问次数 +1）都必须把它一并回读，见
     * {@link com.demo.memory.store.LuceneMemoryStore#update(MemoryRecord)}。
     */
    private String keywords;

    /**
     * 向量。
     *
     * <p>{@code search()} 默认<b>不</b>回填该字段（置 null）以省内存——检索只需要距离，
     * 不需要原向量；需要原向量的场景（如记忆脑图算节点相似度）用
     * {@link com.demo.memory.store.LuceneMemoryStore#listByUserId(String, boolean)} 显式索取。
     */
    private float[] embedding;

    /**
     * 检索得分（余弦相似度，0.0~1.0）。
     *
     * <p><b>不持久化</b>——只在 {@code search()} 的结果里有意义，入库时被忽略。
     * 方案书原本让它固定为 {@code 1.0f} 占位，但那样在融合结果里无法区分
     * "高度相关" 和 "勉强达标"，排查召回质量时看不到任何信息，所以这里回填真实余弦。
     */
    private double score;
}
