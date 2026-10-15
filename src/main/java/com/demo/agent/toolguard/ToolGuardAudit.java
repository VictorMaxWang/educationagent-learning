package com.demo.agent.toolguard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 工具守卫审计入口：规则仲裁 →（灰区时）LLM 裁判 → 按 mode 决定是否阻断。
 *
 * <p>调用方（{@code AgentExecutor} 的 VERIFY 阶段）只需要问两件事：
 * {@link #audit} 拿结论、{@link #shouldBlock} 决定要不要重试。
 * 把"记日志"和"要不要拦"拆成两个方法，是因为<b>记录</b>与<b>阻断</b>必须能分开——
 * 这正是 log 模式存在的意义：先把结论记下来观察，暂时不改变行为。
 */
@Slf4j
@Component
public class ToolGuardAudit {

    private final ToolGuardProperties properties;
    private final ToolGuardJudgeService judgeService;

    public ToolGuardAudit(ToolGuardProperties properties, ToolGuardJudgeService judgeService) {
        this.properties = properties;
        this.judgeService = judgeService;
    }

    /**
     * 执行一次审计。
     *
     * @param userMessage <b>必须是用户原始消息</b>，不能传拼了记忆/历史的完整 prompt。
     *                    本项目曾有这个缺陷：审计拿到的是含长期记忆与历史对话的 prompt，
     *                    里面的"天气""法律"等词会把规则匹配带偏，导致无关回复被判违规。
     */
    public AuditResult audit(String userMessage, String llmText,
                             Set<String> calledTools, List<GuardSpec> guards) {
        if (!properties.isEnabled()) {
            return AuditResult.ofRule(AuditVerdict.PASS, List.of(), List.of(), List.of(), null, "toolguard-disabled");
        }

        AuditResult rule = ToolGuardArbitrator.arbitrate(userMessage, llmText, calledTools, guards);

        AuditResult result = rule;
        if (rule.isAmbiguous() && judgeService != null && judgeService.isAvailable()) {
            result = judgeService.judge(userMessage, llmText, rule);
        }

        // 非 PASS 一律记 info：这是排查"模型是不是在幻觉"的主要证据来源。
        // PASS 走 debug，否则每轮对话都会刷一行，日志会被淹没。
        if (result.verdict() == AuditVerdict.PASS) {
            log.debug("【ToolGuard-审计】PASS | {}", result.reason());
        } else {
            log.info("【ToolGuard-审计】{} | mode={} | 期望={} 已调={} 缺失={} | 来源={} | {}{}",
                    result.verdict(), properties.getMode(),
                    result.expectedTools(), result.calledTools(), result.missingTools(),
                    result.source(), result.reason(),
                    result.judgeReason() == null ? "" : " | 裁判：" + result.judgeReason());
        }
        return result;
    }

    /** 该审计结果是否应当阻断本轮（触发违规重试）。log 模式下恒为 false。 */
    public boolean shouldBlock(AuditResult result) {
        return properties.isEnabled() && properties.isEnforce() && result.isFail();
    }

    /** 健康面板用：当前是否启用、什么模式、裁判处于什么状态。 */
    public String describe() {
        if (!properties.isEnabled()) return "enabled=false";
        return "mode=" + properties.getMode() + " judge=" + (judgeService == null ? "absent" : judgeService.state());
    }
}
