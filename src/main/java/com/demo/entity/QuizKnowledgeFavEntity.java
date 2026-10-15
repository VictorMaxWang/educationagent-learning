package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 知识点收藏实体 — 映射 quiz_knowledge_fav 表
 */
@TableName("quiz_knowledge_fav")
public class QuizKnowledgeFavEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String knowledgePoint;
    private LocalDateTime createdAt;

    public QuizKnowledgeFavEntity() {}

    public QuizKnowledgeFavEntity(String userId, String knowledgePoint) {
        this.userId = userId;
        this.knowledgePoint = knowledgePoint;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getKnowledgePoint() { return knowledgePoint; }
    public void setKnowledgePoint(String knowledgePoint) { this.knowledgePoint = knowledgePoint; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
