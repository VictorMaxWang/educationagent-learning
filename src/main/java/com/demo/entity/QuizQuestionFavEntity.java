package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 题目收藏实体 — 映射 quiz_question_fav 表
 */
@TableName("quiz_question_fav")
public class QuizQuestionFavEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long questionId;
    private LocalDateTime createdAt;

    public QuizQuestionFavEntity() {}

    public QuizQuestionFavEntity(String userId, Long questionId) {
        this.userId = userId;
        this.questionId = questionId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
