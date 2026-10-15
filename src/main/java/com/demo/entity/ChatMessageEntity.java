package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对话消息实体 — 映射 chat_messages 表
 */
@TableName("chat_messages")
public class ChatMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String role;
    private String content;
    private Integer roundNum;
    private String violation;
    private LocalDateTime createdAt;

    public ChatMessageEntity() {}

    public ChatMessageEntity(String userId, String role, String content, int roundNum) {
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.roundNum = roundNum;
    }

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getRoundNum() { return roundNum; }
    public void setRoundNum(Integer roundNum) { this.roundNum = roundNum; }

    public String getViolation() { return violation; }
    public void setViolation(String violation) { this.violation = violation; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
