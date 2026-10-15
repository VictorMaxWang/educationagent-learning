package com.demo.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 会话元数据实体 — 映射 chat_session 表
 * <p>
 * 侧边栏会话列表条目，与前端 ChatSession 字段严格对齐。
 * 时间统一为 epoch 毫秒（long），前后端时区一致。
 */
@TableName("chat_session")
public class ChatSessionEntity {

    /** 会话唯一 ID（前端生成的 UUID） */
    @TableId(type = IdType.INPUT)
    private String sessionId;

    /** 所属桌面端用户 ID（desktop userId，如 user_xxx） */
    private String userId;

    /** 会话标题 */
    private String title;

    /** 创建时间（epoch 毫秒） */
    private Long createTime;

    /** 更新时间（epoch 毫秒） */
    private Long updateTime;

    public ChatSessionEntity() {}

    public ChatSessionEntity(String sessionId, String userId, String title, long createTime, long updateTime) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.title = title;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    // ======== Getters & Setters ========

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Long getCreateTime() { return createTime; }
    public void setCreateTime(Long createTime) { this.createTime = createTime; }

    public Long getUpdateTime() { return updateTime; }
    public void setUpdateTime(Long updateTime) { this.updateTime = updateTime; }
}
