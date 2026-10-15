package com.demo.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工作记忆：会话过程中的临时缓冲，闲置一段时间后沉淀为长期记忆。
 *
 * <h3>为什么需要它</h3>
 * 只靠"每轮对话抽一次"会漏掉两类信息：
 * 一是用户分几次说完整件事（"我下个月要搬家" … "搬到杭州" … "那边房租便宜"）；
 * 二是闲聊里夹带的稳定偏好，单看一句不成立，连起来看才明显。
 * 工作记忆把这些零散句子先攒着，等会话安静下来再一次性抽取。
 *
 * <h3>为什么自己维护缓冲，不去读会话上下文</h3>
 * 本项目的会话上下文（{@code ChatContextManager}）是<b>按 memoryId 存的</b>，
 * 而桌面端会话模式下 memoryId 是 {@code session_xxx}、不是 userId，
 * 记忆归属会错位。自己按 userId 攒几条最近消息，边界最清楚，也便于单测。
 *
 * <p>缓冲区有硬上限（{@link #MAX_BUFFERED}），防止用户一直说话导致内存无限增长。
 */
@Slf4j
@Component
public class WorkingMemoryService {

    /** 每个用户最多缓冲多少条消息。 */
    private static final int MAX_BUFFERED = 20;

    private final MemoryRagService memoryRagService;
    private final MemoryExtractor memoryExtractor;
    private final MemoryProperties properties;

    /** userId → 会话缓冲。 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private final AtomicLong flushCount = new AtomicLong();

    public WorkingMemoryService(MemoryRagService memoryRagService,
                                MemoryExtractor memoryExtractor,
                                MemoryProperties properties) {
        this.memoryRagService = memoryRagService;
        this.memoryExtractor = memoryExtractor;
        this.properties = properties;
    }

    /** 一个用户的工作记忆缓冲。 */
    private static final class Session {
        volatile long lastActivity;
        final Deque<String> recent = new ArrayDeque<>();
    }

    /** 记录一次用户发言。 */
    public void onMessage(String userId, String message) {
        if (!properties.getWorking().isEnabled() || userId == null
                || message == null || message.isBlank()) {
            return;
        }
        Session s = sessions.computeIfAbsent(userId, k -> new Session());
        synchronized (s) {
            s.lastActivity = System.currentTimeMillis();
            s.recent.addLast(message);
            while (s.recent.size() > MAX_BUFFERED) {
                s.recent.pollFirst();
            }
        }
    }

    /**
     * 把某用户的工作记忆沉淀为长期记忆，并清空缓冲。
     *
     * @return 写入的记忆条数
     */
    public int flush(String userId) {
        Session s = sessions.get(userId);
        if (s == null) return 0;

        List<String> buffered;
        synchronized (s) {
            buffered = List.copyOf(s.recent);
            s.recent.clear();
            s.lastActivity = System.currentTimeMillis();
        }
        if (buffered.isEmpty()) return 0;

        int written = 0;
        try {
            for (String msg : buffered) {
                for (MemoryRecord r : memoryExtractor.extract(userId, msg)) {
                    MemoryRecord saved = memoryRagService.addMemory(
                            userId, r.getContent(), r.getType(), r.getImportance(), r.getKeywords());
                    if (saved != null) written++;
                }
            }
        } catch (Exception e) {
            // 沉淀失败只丢这一次缓冲，不能影响任何在线对话
            log.warn("【记忆库-Working】[{}] 沉淀失败：{}", userId, e.toString());
        }
        if (written > 0) {
            flushCount.incrementAndGet();
            log.info("【记忆库-Working】[{}] 会话沉淀：缓冲 {} 条 → 写入 {} 条长期记忆",
                    userId, buffered.size(), written);
        }
        return written;
    }

    /** 丢弃某用户的工作记忆（清空时用，不写入）。 */
    public void discard(String userId) {
        Session s = sessions.get(userId);
        if (s != null) {
            synchronized (s) {
                s.recent.clear();
            }
        }
    }

    /**
     * 定期巡检：把闲置超过阈值的会话沉淀掉。
     */
    @Scheduled(fixedDelayString = "${memory.working.check-interval-ms:300000}")
    public void sweep() {
        if (!properties.getWorking().isEnabled() || !memoryRagService.isUsable()) return;

        long timeoutMs = Math.max(1, properties.getWorking().getSessionTimeoutMinutes()) * 60_000L;
        long now = System.currentTimeMillis();
        int flushedUsers = 0;
        int writtenFacts = 0;

        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            boolean idle;
            synchronized (s) {
                idle = !s.recent.isEmpty() && (now - s.lastActivity) >= timeoutMs;
            }
            if (idle) {
                writtenFacts += flush(e.getKey());
                flushedUsers++;
            }
        }
        if (flushedUsers > 0) {
            log.info("【记忆库-Working】巡检完成：沉淀 {} 个闲置会话，写入 {} 条记忆",
                    flushedUsers, writtenFacts);
        }
    }

    /** 当前的会话数（观测用）。 */
    public int activeSessions() {
        return sessions.size();
    }

    /** 累计沉淀次数（观测用）。 */
    public long totalFlushes() {
        return flushCount.get();
    }
}
