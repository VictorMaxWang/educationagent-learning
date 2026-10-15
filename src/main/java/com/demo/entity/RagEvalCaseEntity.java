package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * RAG 评估黄金集实体 — 映射 rag_eval_case 表。
 *
 * <p>问题与标准答案由 LLM 从知识库 chunk 反向生成后持久化，跨轮次复用，
 * 这样「改分块策略前后跑一遍对比分数」才有意义（每次重新出题就不可比了）。</p>
 */
@TableName("rag_eval_case")
public class RagEvalCaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** Chroma 集合名，如 travel_knowledge */
    private String collection;
    /** 测试问题 */
    private String question;
    /** 标准答案 */
    private String groundTruth;
    /** 生成该用例的原始 chunk 文本 */
    private String sourceChunk;
    /** 源 chunk 的 SHA-256，用于去重 */
    private String chunkHash;
    private LocalDateTime createTime;

    public RagEvalCaseEntity() {}

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getGroundTruth() { return groundTruth; }
    public void setGroundTruth(String groundTruth) { this.groundTruth = groundTruth; }

    public String getSourceChunk() { return sourceChunk; }
    public void setSourceChunk(String sourceChunk) { this.sourceChunk = sourceChunk; }

    public String getChunkHash() { return chunkHash; }
    public void setChunkHash(String chunkHash) { this.chunkHash = chunkHash; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
