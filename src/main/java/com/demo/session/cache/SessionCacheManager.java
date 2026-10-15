package com.demo.session.cache;

import com.demo.cache.ThreeTierCache;
import com.demo.session.entity.ChatSessionEntity;
import com.demo.session.entity.SessionMessageEntity;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 会话模块三级缓存管理器
 * <p>
 * <b>Redis Key 规范（与前端/设计严格对齐）：</b>
 * <pre>
 *   chat:session:{sessionId}   会话基础信息（ThreeTierCache，cacheName=chat:session）
 *   chat:messages:{sessionId}  会话消息列表（ThreeTierCache，cacheName=chat:messages）
 *   chat:memory:{sessionId}    LangChain4j 对话上下文序列化（ChatMessageSerializer 直接读写）
 * </pre>
 * <b>读写规则：</b>读走 L1 内存 → L2 Redis → L3 MySQL 并回填上层；写先 MySQL 再同步 L1+L2；
 * 删除级联清 MySQL + Redis 三 key + L1。
 * <b>内存淘汰：</b>定时清理长期不活跃会话的 L1/L2 缓存（数据仍在 MySQL，下次访问自动回源），
 * 防止长会话上下文占满内存。
 */
@Slf4j
@Component
public class SessionCacheManager {

    /** 会话基础信息三级缓存（Redis key 前缀 chat:session） */
    private final ThreeTierCache<ChatSessionEntity> sessionCache;
    /** 会话消息列表三级缓存（Redis key 前缀 chat:messages） */
    private final ThreeTierCache<SessionMessageList> messageCache;

    /** Redis 操作模板（langchain4j 上下文镜像直接使用） */
    private final StringRedisTemplate redis;
    /** chat:memory TTL（秒） */
    private final long memoryTtlSeconds;
    /** 会话闲置淘汰阈值（分钟） */
    private final int idleEvictMinutes;

    /** 会话最近访问时间戳（epoch 毫秒），用于闲置淘汰 */
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();

    /** 闲置淘汰调度器（守护线程，仅清理 L1/L2 缓存，不影响 MySQL 持久数据） */
    private final ScheduledExecutorService evictExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chat-session-cache-evict");
                t.setDaemon(true);
                return t;
            });

    public SessionCacheManager(StringRedisTemplate redisTemplate,
                               @Value("${app.cache.redis.ttl.chat-session:1800}") long sessionTtl,
                               @Value("${app.cache.redis.ttl.chat-messages:600}") long messageTtl,
                               @Value("${app.cache.redis.ttl.chat-memory:1800}") long memoryTtl,
                               @Value("${app.chat.session.idle-evict-minutes:30}") int idleEvictMinutes) {
        this.redis = redisTemplate;
        this.memoryTtlSeconds = memoryTtl;
        this.idleEvictMinutes = idleEvictMinutes;
        this.sessionCache = new ThreeTierCache<>("chat:session", ChatSessionEntity.class,
                redisTemplate, Duration.ofSeconds(sessionTtl));
        this.messageCache = new ThreeTierCache<>("chat:messages", SessionMessageList.class,
                redisTemplate, Duration.ofSeconds(messageTtl));

        // 定时扫描淘汰不活跃会话（每隔 max(1, 阈值/3) 分钟一次，最小 1 分钟）
        long scanMinutes = Math.max(1, idleEvictMinutes / 3);
        evictExecutor.scheduleWithFixedDelay(this::evictIdleSessions,
                scanMinutes, scanMinutes, TimeUnit.MINUTES);
        log.info("📦 会话缓存初始化完成 | session TTL={}s, messages TTL={}s, memory TTL={}s, 闲置淘汰={}min",
                sessionTtl, messageTtl, memoryTtl, idleEvictMinutes);
    }

    // ======================== 会话元数据缓存 ========================

    /**
     * 三级缓存读取会话元数据（L1→L2→L3，命中回填上层）
     */
    public ChatSessionEntity getSession(String sessionId, Supplier<ChatSessionEntity> mysqlLoader) {
        ChatSessionEntity entity = sessionCache.get(sessionId, mysqlLoader);
        touch(sessionId);
        return entity;
    }

    /** 写穿更新会话元数据（先由调用方落 MySQL，再同步 L1+L2） */
    public void putSession(ChatSessionEntity entity) {
        if (entity == null || entity.getSessionId() == null) return;
        sessionCache.put(entity.getSessionId(), entity);
        touch(entity.getSessionId());
    }

    /** 删除会话元数据缓存（L1 + Redis） */
    public void evictSession(String sessionId) {
        sessionCache.evict(sessionId);
        lastAccess.remove(sessionId);
    }

    // ======================== 会话消息缓存 ========================

    /**
     * 三级缓存读取会话消息全量列表（升序，L1→L2→L3，命中回填上层）
     */
    public List<SessionMessageEntity> getMessages(String sessionId, Supplier<List<SessionMessageEntity>> mysqlLoader) {
        SessionMessageList wrapped = messageCache.get(sessionId, () -> {
            List<SessionMessageEntity> list = mysqlLoader.get();
            if (list == null || list.isEmpty()) return null;
            return new SessionMessageList(list);
        });
        touch(sessionId);
        return wrapped == null ? null : wrapped.getMessages();
    }

    /** 写穿更新会话消息缓存 */
    public void putMessages(String sessionId, List<SessionMessageEntity> messages) {
        if (sessionId == null || messages == null) return;
        messageCache.put(sessionId, new SessionMessageList(messages));
        touch(sessionId);
    }

    /** 删除会话消息缓存（L1 + Redis） */
    public void evictMessages(String sessionId) {
        messageCache.evict(sessionId);
    }

    // ======================== LangChain4j 上下文镜像（chat:memory） ========================

    /**
     * 把当前会话的 LangChain4j 上下文序列化镜像写入 Redis chat:memory:{sessionId}
     * 供服务重启后上下文恢复（ChatMessageSerializer 官方序列化器）。
     */
    public void saveMemory(String sessionId, List<ChatMessage> messages) {
        if (sessionId == null || messages == null || messages.isEmpty()) return;
        try {
            String json = ChatMessageSerializer.messagesToJson(messages);
            redis.opsForValue().set("chat:memory:" + sessionId, json, Duration.ofSeconds(memoryTtlSeconds));
        } catch (Exception e) {
            log.warn("⚠ chat:memory 镜像写入失败 session={} | {}", sessionId, e.getMessage());
        }
    }

    /**
     * 从 Redis chat:memory:{sessionId} 反序列化恢复 LangChain4j 上下文
     *
     * @return 消息列表；Redis 无数据或反序列化失败返回 null
     */
    public List<ChatMessage> loadMemory(String sessionId) {
        try {
            String json = redis.opsForValue().get("chat:memory:" + sessionId);
            if (json == null) return null;
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (Exception e) {
            log.warn("⚠ chat:memory 读取失败 session={} | {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 删除会话上下文镜像缓存（Redis chat:memory） */
    public void evictMemory(String sessionId) {
        try {
            redis.delete("chat:memory:" + sessionId);
        } catch (Exception e) {
            log.warn("⚠ chat:memory 删除失败 session={} | {}", sessionId, e.getMessage());
        }
    }

    // ======================== 闲置淘汰 ========================

    /** 刷新会话最近访问时间（每次读写调用） */
    public void touch(String sessionId) {
        if (sessionId != null) {
            lastAccess.put(sessionId, System.currentTimeMillis());
        }
    }

    /** 清理长期不活跃会话的 L1/L2 缓存（MySQL 数据保留，下次访问自动回源） */
    private void evictIdleSessions() {
        long now = System.currentTimeMillis();
        long threshold = TimeUnit.MINUTES.toMillis(idleEvictMinutes);
        lastAccess.forEach((sessionId, lastTime) -> {
            if (now - lastTime > threshold) {
                try {
                    sessionCache.evict(sessionId);
                    messageCache.evict(sessionId);
                    log.info("🧹 闲置会话缓存已淘汰 session={}（{}min 未活跃）", sessionId, idleEvictMinutes);
                } catch (Exception e) {
                    log.warn("⚠ 闲置会话缓存淘汰异常 session={} | {}", sessionId, e.getMessage());
                } finally {
                    lastAccess.remove(sessionId);
                }
            }
        });
    }
}
