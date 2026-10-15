package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 定时提醒实体 — 映射 reminders 表
 */
@TableName("reminders")
public class ReminderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String message;
    private Long triggerAt;
    private Integer executed;
    private LocalDateTime createdAt;

    public ReminderEntity() {}

    public ReminderEntity(String userId, String message, long triggerAt) {
        this.userId = userId;
        this.message = message;
        this.triggerAt = triggerAt;
        this.executed = 0;
    }

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Long getTriggerAt() { return triggerAt; }
    public void setTriggerAt(Long triggerAt) { this.triggerAt = triggerAt; }

    public Integer getExecuted() { return executed; }
    public void setExecuted(Integer executed) { this.executed = executed; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
