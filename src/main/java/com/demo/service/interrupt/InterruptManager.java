package com.demo.service.interrupt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 中断信号管理器。
 * <p>
 * 【缺陷 2 修复】时间戳替代布尔值，信号 5 秒有效期，避免时序竞态：
 *   旧方案 Boolean 消费即清空，新任务可能清空 flag 导致旧任务无法中断。
 *   新方案时间戳 + 有效期，定时清理不过期信号，不依赖消费删除。
 * <p>
 * 职责：
 *   ① 用户中断信号的设置/检测/清理
 *   ② 消息处理的异步提交（poll 线程不阻塞）
 *   ③ 运行中任务的追踪与取消
 */
@Slf4j
@Component
public class InterruptManager {

    /** userId → 中断信号时间戳 */
    private final Map<String, Long> interruptSignals = new ConcurrentHashMap<>();

    /** userId → 当前运行中任务的 Future */
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    /** 信号有效期 5 秒 */
    private static final long SIGNAL_VALID_MS = 5000;

    /** 消息处理线程池（8 线程，全 daemon） */
    private final ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "msg-process");
        t.setDaemon(true);
        return t;
    });

    /** 信号清理线程 */
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "interrupt-cleaner");
        t.setDaemon(true);
        return t;
    });

    public InterruptManager() {
        cleaner.scheduleAtFixedRate(this::cleanExpiredSignals, 1, 1, TimeUnit.SECONDS);
    }

    // ======================== 中断信号 API ========================

    /**
     * 查询用户当前是否有任务正在执行。
     * 供 InterruptDetector 第③层判断是否值得调用 LLM。
     */
    public boolean isProcessing(String userId) {
        Future<?> task = runningTasks.get(userId);
        return task != null && !task.isDone();
    }

    /**
     * 发送中断信号 + 取消正在执行的任务。
     * 由 poll 线程在检测到中断指令时调用。
     */
    public void signal(String userId) {
        interruptSignals.put(userId, System.currentTimeMillis());
        log.info("🚨 [{}] 中断信号已发送", userId);

        Future<?> task = runningTasks.get(userId);
        if (task != null && !task.isDone()) {
            task.cancel(true);  // 设置线程中断标记
            log.info("🚨 [{}] 已尝试取消运行中的任务", userId);
        }
    }

    /**
     * 非消费式检查 — 供 OkHttp 拦截器轮询使用。
     * 仅查看信号是否在有效期内，不删除。
     */
    public boolean isSignaled(String userId) {
        Long ts = interruptSignals.get(userId);
        if (ts == null) return false;
        return System.currentTimeMillis() - ts < SIGNAL_VALID_MS;
    }

    /**
     * 消费式检查 — 供 FSM 状态机使用。
     * 检测到有效信号后立即消费并返回 true。
     * 消费即删除，确保每个中断只让一个 FSM 循环感知到。
     */
    public boolean checkAndConsume(String userId) {
        Long ts = interruptSignals.remove(userId);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts < SIGNAL_VALID_MS) {
            log.info("🚨 [{}] 中断信号已消费", userId);
            return true;
        }
        return false;
    }

    // ======================== 异步任务提交 ========================

    /**
     * 异步提交用户消息处理任务。
     * poll 线程调用后立即返回，不阻塞轮询。
     */
    public void submit(String userId, Runnable task) {
        Future<?> future = executor.submit(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                log.error("[{}] 消息处理异常: {}", userId, e.getMessage(), e);
            } finally {
                runningTasks.remove(userId);
            }
        });
        runningTasks.put(userId, future);
    }

    // ======================== 清理 ========================

    /** 每秒清理过期信号（信号 5 秒后自动消失） */
    private void cleanExpiredSignals() {
        long now = System.currentTimeMillis();
        interruptSignals.entrySet().removeIf(entry ->
                now - entry.getValue() > SIGNAL_VALID_MS);
    }

    /** 清理已完成的任务引用 */
    public void cleanFinishedTasks() {
        runningTasks.entrySet().removeIf(e -> e.getValue().isDone());
    }
}
