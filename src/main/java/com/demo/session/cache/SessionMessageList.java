package com.demo.session.cache;

import com.demo.session.entity.SessionMessageEntity;

import java.util.List;

/**
 * 会话消息列表包装类
 * <p>
 * {@link com.demo.cache.ThreeTierCache} 的泛型 valueType 需要具体类才能被 fastjson
 * 反序列化为带类型的元素列表，故用本类包裹 {@code List<SessionMessageEntity>}，
 * 作为 Redis key {@code chat:messages:{sessionId}} 的缓存值类型。
 */
public class SessionMessageList {

    private List<SessionMessageEntity> messages;

    public SessionMessageList() {}

    public SessionMessageList(List<SessionMessageEntity> messages) {
        this.messages = messages;
    }

    public List<SessionMessageEntity> getMessages() { return messages; }
    public void setMessages(List<SessionMessageEntity> messages) { this.messages = messages; }
}
