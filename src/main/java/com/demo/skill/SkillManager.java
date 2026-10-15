package com.demo.skill;

import com.demo.skill.annotation.RouterMatch;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 技能管理器 — 扫描 @Tool 实例中的 @RouterMatch 方法，提供路由匹配与统一调用。
 * <p>
 * 不创建新的 Skill 接口，直接复用现有的 {@code @Tool} 方法。
 * 一个方法两条路：
 * <ul>
 *   <li>Router 前置路由：通过 {@link #evaluateAll(String)} + {@link RoutableMethod#invoke(String, Map)}</li>
 *   <li>LLM 工具调用：通过 AgentExecutor 原有的反射机制（不变）</li>
 * </ul>
 */
@Slf4j
@Service
public class SkillManager {

    @Value("${skill.router.default-threshold:0.85}")
    private double defaultThreshold;

    /** 所有可路由方法的索引（工具名 → RoutableMethod） */
    private final Map<String, RoutableMethod> routeRegistry = new LinkedHashMap<>();

    /** 持有的 @Tool 实例列表（由 AgentConfig 注入） */
    private final List<Object> toolInstances = new CopyOnWriteArrayList<>();

    // ======================== @Tool 实例注册 ========================

    /**
     * 注册 @Tool 实例，扫描其中带 @RouterMatch 的方法。
     * 由 AgentConfig 在启动时调用。
     */
    public void registerToolInstances(Object... instances) {
        if (instances == null) return;
        for (Object instance : instances) {
            if (instance == null) continue;
            toolInstances.add(instance);
            scanRoutableMethods(instance);
        }
    }

    /** 扫描单个实例中同时带 @Tool + @RouterMatch 的方法 */
    private void scanRoutableMethods(Object instance) {
        Class<?> clazz = (instance instanceof Class) ? (Class<?>) instance : instance.getClass();

        // 先检查类级 @RouterMatch（可选，标记整个类参与路由）
        boolean classHasRouter = clazz.isAnnotationPresent(RouterMatch.class);

        for (Method method : clazz.getDeclaredMethods()) {
            Tool toolAnno = method.getAnnotation(Tool.class);
            if (toolAnno == null) continue;

            RouterMatch routerAnno = method.getAnnotation(RouterMatch.class);
            // 方法级 @RouterMatch 优先级高于类级
            if (routerAnno == null && !classHasRouter) continue;
            if (routerAnno == null && classHasRouter) {
                // 类级 @RouterMatch 也对方法生效
                routerAnno = clazz.getAnnotation(RouterMatch.class);
            }

            Object targetInstance = (instance instanceof Class) ? null : instance;
            RoutableMethod rm = new RoutableMethod(targetInstance, method);

            String toolName = rm.getToolName();
            RoutableMethod old = routeRegistry.put(toolName, rm);
            if (old != null) {
                log.debug("⚡ 路由方法覆盖: {} (旧: {})", toolName, old.getMethod().getName());
            }
        }
    }

    // ======================== 路由匹配 ========================

    /**
     * 全量评分，返回按得分降序排列的结果。
     * {@link com.demo.router.SkillRouter} 通过此方法做前置路由决策。
     */
    public List<ScoredRoute> evaluateAll(String message) {
        if (message == null || message.isBlank()) return Collections.emptyList();

        List<ScoredRoute> results = new ArrayList<>();

        for (RoutableMethod rm : routeRegistry.values()) {
            double score = rm.match(message);
            if (score > 0) {
                double threshold = rm.effectiveThreshold(defaultThreshold);
                int priority = rm.getRouterAnno().priority();
                results.add(new ScoredRoute(rm, score, threshold, priority));
            }
        }

        // 按分数降序 → 按优先级降序
        results.sort((a, b) -> {
            int cmp = Double.compare(b.score, a.score);
            if (cmp != 0) return cmp;
            return Integer.compare(b.priority, a.priority);
        });

        return results;
    }

    // ======================== 统一调用 ========================

    /**
     * 执行路由命中的方法。
     *
     * @param userId  用户 ID
     * @param route   评分结果中的 RoutableMethod
     * @param message 原始用户消息（再次从中提取参数）
     * @return 方法的返回文本
     */
    public String invokeRoutable(String userId, RoutableMethod route, String message) {
        String matchedPattern = route.matchedPattern(message);
        Map<String, Object> params = route.extractParams(message, matchedPattern);
        params.put("_route_source", "ROUTER");
        return route.invoke(userId, params);
    }

    // ======================== 查询方法 ========================

    /** 获取所有已注册的可路由方法 */
    public Collection<RoutableMethod> getAllRoutable() {
        return Collections.unmodifiableCollection(routeRegistry.values());
    }

    /** 按工具名查找 */
    public RoutableMethod getRoutable(String toolName) {
        return routeRegistry.get(toolName);
    }

    public int routableCount() { return routeRegistry.size(); }

    public int toolInstanceCount() { return toolInstances.size(); }

    // ======================== 内部类型 ========================

    public record ScoredRoute(RoutableMethod routable, double score, double threshold, int priority) {
        public boolean isRoutable() { return score >= threshold; }
    }

    // ======================== 启停日志 ========================

    @PostConstruct
    public void logStatus() {
        if (routeRegistry.isEmpty()) {
            log.info("🧩 SkillManager 就绪（当前无可路由的 @Tool 方法）");
        } else {
            log.info("🧩 SkillManager 就绪，{} 个工具实例，{} 个可路由方法",
                    toolInstances.size(), routeRegistry.size());
            routeRegistry.forEach((name, rm) ->
                    log.info("   - {} → {}#{}({})", name,
                            rm.getMethod().getDeclaringClass().getSimpleName(),
                            rm.getMethod().getName(),
                            String.format("阈%.2f", rm.effectiveThreshold(defaultThreshold))));
        }
    }
}
