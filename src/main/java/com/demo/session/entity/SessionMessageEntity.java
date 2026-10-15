package com.demo.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 会话消息实体 — 映射 chat_message 表
 * <p>
 * 与前端 ChatMessage 字段严格对齐：msgId / sessionId / role / content / image / createTime。
 * 时间统一为 epoch 毫秒（long）。
 */
@TableName("chat_message")
public class SessionMessageEntity {

    /** 消息唯一 ID（UUID） */
    @TableId(type = IdType.INPUT)
    private String msgId;

    /** 所属会话 ID */
    private String sessionId;

    /** 消息角色：user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 消息关联图片 URL（相对路径，如 /chat/images/xxx.png；AI 生成图片落盘 data/images 后引用） */
    private String image;

    /** 创建时间（epoch 毫秒） */
    private Long createTime;

    public SessionMessageEntity() {}

    public SessionMessageEntity(String msgId, String sessionId, String role, String content, long createTime) {
        this.msgId = msgId;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.createTime = createTime;
    }

    // ======== Getters & Setters ========

    public String getMsgId() { return msgId; }
    public void setMsgId(String msgId) { this.msgId = msgId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public Long getCreateTime() { return createTime; }
    public void setCreateTime(Long createTime) { this.createTime = createTime; }
}
