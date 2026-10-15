package com.demo.skill.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 {@code Skill} 实现类上，声明前置路由匹配规则。
 * <p>
 * 有此注解的技能才会参与路由评分；无此注解的技能仅由 LLM 调用。
 *
 * <pre>{@code
 * @RouterMatch(
 *     patterns = {
 *         "查(?<date>.*?)从(?<from>.+?)到(?<to>.+?)的(?:高铁|火车|动车|车票)",
 *         "从(?<from>.+?)到(?<to>.+?)的(?:高铁|火车|动车)"
 *     },
 *     confidence = 0.80,
 *     priority = 5
 * )
 * public class TrainTicketSkill implements Skill { ... }
 * }</pre>
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>patterns 中<strong>必须使用命名捕获组</strong> {@code (?<name>...)}，禁止匿名分组</li>
 *   <li>confidence 覆盖全局阈值，不填则使用 {@code SkillRouter} 的默认阈值</li>
 *   <li>priority 同分决胜，数值越大优先级越高</li>
 * </ul>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RouterMatch {

    /**
     * 匹配正则模板（OR 关系，命中任意一条即参与评分）。
     * <p>
     * <strong>强制：必须使用命名捕获组 {@code (?<name>...)}。</strong>
     * 不允许纯匿名分组 {@code (xxx)}，否则参数提取会错乱。
     * 示例：
     * <pre>{@code "查(?<date>.*?)从(?<from>.+?)到(?<to>.+?)的(?:高铁|火车)"}</pre>
     */
    String[] patterns();

    /**
     * 技能级置信阈值（0.0 ~ 1.0）。
     * 用于覆盖全局默认阈值。简单查询（时间/计算器）可放宽到 0.75，
     * 复杂或高成本操作（订票/支付）建议收紧到 0.92+。
     * 默认 -1 表示使用全局默认阈值。
     */
    double confidence() default -1.0;

    /**
     * 同分决胜优先级（数值越大越优先）。
     * 仅在 {@code confidence} 评分完全一致时生效。
     * 同分且同优先级时，路由放弃判定，降级为 LLM 处理。
     */
    int priority() default 0;
}
