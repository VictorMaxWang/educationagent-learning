package com.demo.router;

import com.demo.skill.RoutableMethod;
import com.demo.skill.SkillInvokeException;
import com.demo.skill.SkillManager;
import com.demo.skill.SkillManager.ScoredRoute;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 前置路由引擎。
 * <p>
 * 插在 CmdService 和 AgentExecutor 之间：
 * <pre>
 *   CmdService.dealMsg()
 *     ├─ 系统指令 → 直返
 *     ├─ 验证码 → FileSystemService
 *     ├─ SkillRouter.route()       ← 新增
 *     │   ├─ 分支1：匹配 → 执行 → 回复（零 LLM 消耗）
 *     │   └─ 分支2：不匹配 → 走 LLM
 *     └─ AgentExecutor.chat()     ← 传统 LLM 路径
 * </pre>
 */
@Slf4j
@Component
public class SkillRouter {

    @Resource
    private SkillManager skillManager;

    /**
     * 前置路由入口。
     *
     * @param userId  用户 ID
     * @param message 用户消息（预处理后的文本）
     * @return 路由结果。{@link RouteResult#isHandled()} 判断走哪条分支
     */
    public RouteResult route(String userId, String message) {
        long start = System.nanoTime();

        // 1. 全量评分
        List<ScoredRoute> scored = skillManager.evaluateAll(message);

        // 无任何技能匹配
        if (scored.isEmpty()) {
            log.info("[{}] 🚫 路由未匹配 → 交 LLM", userId);
            return RouteResult.unhandled();
        }

        ScoredRoute best = scored.get(0);

        // 2. 同分降级：前两名得分接近 → 交 LLM
        if (scored.size() >= 2) {
            ScoredRoute second = scored.get(1);
            if (Math.abs(best.score() - second.score()) < 0.001) {
                log.warn("[{}] ⚠ 路由同分 {}={} vs {}={} → 降级 LLM",
                        userId, best.routable().getToolName(), String.format("%.2f", best.score()),
                        second.routable().getToolName(), String.format("%.2f", second.score()));
                logRouteDecision(userId, message, best, false);
                return RouteResult.unhandled();
            }
        }

        // 3. 低于阈值 → 交 LLM
        if (!best.isRoutable()) {
            log.info("[{}] 🚫 得分 {}/<{} → 交 LLM | skill={}",
                    userId, String.format("%.2f", best.score()),
                    String.format("%.2f", best.threshold()),
                    best.routable().getToolName());
            logRouteDecision(userId, message, best, false);
            return RouteResult.unhandled();
        }

        // ==================== 分支1：命中，直接执行 ====================

        try {
            String reply = skillManager.invokeRoutable(userId, best.routable(), message);

            log.info("[{}] ✅ 路由命中 → {} (score={}, {}ms)",
                    userId, best.routable().getToolName(),
                    String.format("%.2f", best.score()),
                    elapsedMs(start));
            logRouteDecision(userId, message, best, true);

            return RouteResult.handled(reply, best.routable().getToolName(), best.score());
        } catch (Exception e) {
            log.error("[{}] ❌ 路由执行异常 {}: {}", userId, best.routable().getToolName(),
                    e.getMessage(), e);
            logRouteDecision(userId, message, best, false);
            return RouteResult.unhandled();
        }
    }

    // ======================== 可观测性 ========================

    private void logRouteDecision(String userId, String message, ScoredRoute best, boolean handled) {
        log.info("ROUTE | uid={} | tool={} | score={} | thresh={} | {} | msg={}",
                userId,
                best.routable().getToolName(),
                String.format("%.2f", best.score()),
                String.format("%.2f", best.threshold()),
                handled ? "✅HANDLED" : "🚫UNHANDLED",
                truncate(message));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String truncate(String text) {
        if (text == null) return null;
        return text.length() > 60 ? text.substring(0, 60) + "..." : text;
    }
}
