package com.demo.skill;

import com.demo.skill.annotation.RouterMatch;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 可路由方法的封装。
 * <p>
 * 对一个同时标注了 {@code @Tool} + {@code @RouterMatch} 的方法，
 * 提供匹配评分、参数提取、反射调用三大能力。
 * <p>
 * Router 和 LLM 都通过此类调用同一个 {@code @Tool} 方法，
 * 不存在两套实现，不存在行为不一致。
 */
@Slf4j
public class RoutableMethod {

    /** 持有该方法的 Bean 实例 */
    private final Object instance;

    /** 方法反射句柄 */
    @Getter
    private final Method method;

    /** @Tool 注解 */
    @Getter
    private final Tool toolAnno;

    /** @RouterMatch 注解 */
    @Getter
    private final RouterMatch routerAnno;

    /** 参数名列表（与 method.getParameters() 一一对应） */
    private final String[] paramNames;

    /** 参数类型列表 */
    private final Class<?>[] paramTypes;

    /** 参数是否必填 */
    private final boolean[] paramRequired;

    /** 预编译的正则列表 */
    private final Pattern[] patterns;

    /** 所有正则中提取到的命名分组名集合 */
    private final Set<String> allNamedGroups;

    // ======================== 构造 ========================

    public RoutableMethod(Object instance, Method method) {
        this.instance = instance;
        this.method = method;
        this.toolAnno = method.getAnnotation(Tool.class);
        this.routerAnno = findRouterMatch(instance, method);

        // 提取参数元数据
        Parameter[] params = method.getParameters();
        this.paramNames = new String[params.length];
        this.paramTypes = new Class<?>[params.length];
        this.paramRequired = new boolean[params.length];
        extractParamMetadata(params);

        // 预编译正则
        this.patterns = Arrays.stream(routerAnno.patterns())
                .map(Pattern::compile)
                .toArray(Pattern[]::new);

        // 收集所有命名分组
        Set<String> groups = new LinkedHashSet<>();
        for (Pattern p : patterns) {
            groups.addAll(extractNamedGroups(p.pattern()));
        }
        this.allNamedGroups = Collections.unmodifiableSet(groups);
    }

    // ======================== 匹配评分 ========================

    /**
     * 对消息进行匹配评分。
     *
     * @param message 用户消息
     * @return 匹配得分（0~1），0 表示不匹配
     */
    public double match(String message) {
        double bestScore = 0;
        for (Pattern p : patterns) {
            double score = scorePattern(p, message);
            if (score > bestScore) bestScore = score;
        }
        return bestScore;
    }

    /**
     * 返回命中的正则字符串（用于参数提取）。
     * 返回得分最高的那条正则原文。
     */
    public String matchedPattern(String message) {
        double bestScore = 0;
        String best = null;
        for (int i = 0; i < patterns.length; i++) {
            double score = scorePattern(patterns[i], message);
            if (score > bestScore) {
                bestScore = score;
                best = routerAnno.patterns()[i];
            }
        }
        return best;
    }

    // ======================== 参数提取 ========================

    /**
     * 从消息中按正则命名分组提取参数，并转为方法入参类型。
     *
     * @param message      用户消息
     * @param matchedRegex 命中的正则原文
     * @return 参数名 → 转换后的值
     */
    public Map<String, Object> extractParams(String message, String matchedRegex) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (matchedRegex == null) return result;

        try {
            Pattern p = Pattern.compile(matchedRegex);
            Matcher m = p.matcher(message);
            if (!m.find()) return result;

            for (String groupName : allNamedGroups) {
                try {
                    String value = m.group(groupName);
                    if (value != null && !value.isBlank()) {
                        result.put(groupName, value.trim());
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            log.warn("参数提取失败: pattern={}, msg={}", matchedRegex, message);
        }

        return result;
    }

    // ======================== 反射调用 ========================

    /**
     * 执行方法。
     * <p>
     * 将 params 按参数名匹配到方法入参位置，自动类型转换后反射调用。
     *
     * @param userId 用户 ID
     * @param params 参数包（参数名 → 值）
     * @return 方法返回的字符串
     * @throws SkillInvokeException 反射执行异常时包装抛出
     */
    public String invoke(String userId, Map<String, Object> params) {
        // 构造入参数组
        Object[] args = new Object[paramNames.length];
        List<String> missing = new ArrayList<>();

        for (int i = 0; i < paramNames.length; i++) {
            String name = paramNames[i];
            Object value = params != null ? params.get(name) : null;

            if (value == null && paramRequired[i]) {
                missing.add(name);
                args[i] = null;
            } else if (value != null) {
                // 类型转换
                args[i] = ParamConverter.convert(value.toString(), paramTypes[i]);
            } else {
                args[i] = null;
            }
        }

        if (!missing.isEmpty()) {
            throw new SkillInvokeException(getToolName(), params.toString(),
                    "缺少必填参数: " + String.join(", ", missing));
        }

        // 反射调用
        try {
            method.setAccessible(true);
            Object raw = method.invoke(instance, args);
            return raw != null ? raw.toString() : null;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new SkillInvokeException(getToolName(), params.toString(),
                    cause.getMessage(), cause);
        }
    }

    // ======================== 工具方法 ========================

    /** 提取方法参数元数据 */
    private void extractParamMetadata(Parameter[] params) {
        for (int i = 0; i < params.length; i++) {
            P pAnno = params[i].getAnnotation(P.class);
            // 参数名：优先 @P.name()，其次 Java 参数名
            if (pAnno != null && !pAnno.name().isEmpty()) {
                paramNames[i] = pAnno.name();
            } else {
                paramNames[i] = params[i].getName();
            }
            paramTypes[i] = params[i].getType();
            paramRequired[i] = pAnno == null || pAnno.required();
        }
    }

    /** 从 regex 字符串提取命名分组名 */
    private Set<String> extractNamedGroups(String regex) {
        Set<String> names = new LinkedHashSet<>();
        try {
            Pattern groupPattern = Pattern.compile("\\(\\?<([a-zA-Z_][a-zA-Z0-9_]*)>");
            Matcher m = groupPattern.matcher(regex);
            while (m.find()) {
                names.add(m.group(1));
            }
        } catch (Exception ignored) {}
        return names;
    }

    /** 单条正则评分 */
    private double scorePattern(Pattern p, String message) {
        Matcher m = p.matcher(message);
        if (!m.find()) return 0;

        String matched = m.group(0);
        if (matched == null || matched.isEmpty()) return 0;

        // 全文命中 = 1.0
        if (matched.equals(message)) return 1.0;

        // 覆盖率 + 已提取命名分组数
        double coverage = (double) matched.length() / message.length();
        int extracted = countExtractedGroups(m);
        double groupBonus = Math.min(extracted * 0.05, 0.2);

        return Math.min(coverage + groupBonus, 0.95);
    }

    /** 统计已提取的命名分组数 */
    private int countExtractedGroups(Matcher m) {
        int count = 0;
        for (String name : allNamedGroups) {
            try {
                String v = m.group(name);
                if (v != null && !v.isBlank()) count++;
            } catch (IllegalArgumentException ignored) {}
        }
        return count;
    }

    /** 查找 @RouterMatch：方法级优先，其次类级 */
    private static RouterMatch findRouterMatch(Object instance, Method method) {
        RouterMatch m = method.getAnnotation(RouterMatch.class);
        if (m != null) return m;
        return instance.getClass().getAnnotation(RouterMatch.class);
    }

    /** 技能名（来自 @Tool：name() 优先，其次 value()[0]，最后方法名） */
    public String getToolName() {
        String name = toolAnno.name();
        if (!name.isEmpty()) return name;
        String[] values = toolAnno.value();
        if (values.length > 0 && !values[0].isEmpty()) return values[0];
        return method.getName();
    }

    /** 有效阈值（技能级 > 全局默认） */
    public double effectiveThreshold(double globalDefault) {
        double t = routerAnno.confidence();
        return t > 0 ? t : globalDefault;
    }

    @Override
    public String toString() {
        return "RoutableMethod{" + getToolName() + "@" + instance.getClass().getSimpleName() + "}";
    }
}
