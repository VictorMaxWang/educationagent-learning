package com.demo.router;

import lombok.extern.slf4j.Slf4j;

/**
 * 极简熔断器（CLOSED / OPEN / HALF_OPEN 三态）。
 *
 * <p>本项目没有引入 resilience4j 之类的熔断框架——只有 L3 路由一处需要，
 * 引入一个依赖不划算。逻辑就是方案书 Prompt 6 描述的最小版：
 * 连续失败到阈值就打开，冷却期内直接拒绝，冷却结束后放行一次试探（HALF_OPEN）。
 *
 * <p>线程安全靠 {@code synchronized}：L3 调用是低频旁路，锁竞争可以忽略。
 */
@Slf4j
public class CircuitBreaker {

    public enum State {
        /** 正常放行。 */
        CLOSED,
        /** 已熔断，直接拒绝。 */
        OPEN,
        /** 冷却结束，放行一次试探。 */
        HALF_OPEN
    }

    private final String name;
    private final int failureThreshold;
    private final long cooldownMs;
    private final boolean enabled;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAtMs;
    private boolean halfOpenProbeInFlight;

    public CircuitBreaker(String name, boolean enabled, int failureThreshold, long cooldownMs) {
        this.name = name;
        this.enabled = enabled;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMs = Math.max(0L, cooldownMs);
    }

    /** 是否放行本次调用。 */
    public synchronized boolean allowRequest() {
        if (!enabled) return true;
        long now = System.currentTimeMillis();
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (now - openedAtMs >= cooldownMs) {
                    state = State.HALF_OPEN;
                    halfOpenProbeInFlight = true;
                    log.info("【前置路由】熔断器 [{}] → HALF_OPEN，放行一次试探", name);
                    return true;
                }
                return false;
            case HALF_OPEN:
            default:
                // 试探期间只放行一个请求，避免冷却刚结束就被并发打满
                if (halfOpenProbeInFlight) return false;
                halfOpenProbeInFlight = true;
                return true;
        }
    }

    /** 记录一次成功：任何状态都归位到 CLOSED。 */
    public synchronized void recordSuccess() {
        if (!enabled) return;
        if (state != State.CLOSED) {
            log.info("【前置路由】熔断器 [{}] → CLOSED（试探成功）", name);
        }
        state = State.CLOSED;
        consecutiveFailures = 0;
        halfOpenProbeInFlight = false;
    }

    /** 记录一次失败：达到阈值则打开。 */
    public synchronized void recordFailure() {
        if (!enabled) return;
        halfOpenProbeInFlight = false;
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAtMs = System.currentTimeMillis();
            log.warn("【前置路由】熔断器 [{}] → OPEN（连续失败 {} 次，冷却 {}s）",
                    name, consecutiveFailures, cooldownMs / 1000);
        }
    }

    /** 当前状态（观测用）。 */
    public synchronized State state() {
        return state;
    }

    /** 连续失败次数（观测用）。 */
    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }
}
