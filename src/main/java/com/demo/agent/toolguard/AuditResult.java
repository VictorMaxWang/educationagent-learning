package com.demo.agent.toolguard;

import java.util.List;

/**
 * 一次工具守卫审计的完整结论。
 *
 * @param verdict      三态裁决
 * @param expectedTools 规则期望调用的工具（交集优先 / 最长关键词回退后的结果）
 * @param missingTools 期望里没被调用的那些
 * @param calledTools  期望里确实被调用了的那些
 * @param ruleDesc     触发的那条规则的中文说明（用于违规重试提示词）
 * @param reason       判定理由（机器可读的短标识，如 {@code fake-execution} / {@code longest-keyword}）
 * @param source       结论来源（规则 / 裁判 / 裁判兜底）
 * @param judgeReason  裁判给出的自然语言理由（未触发裁判时为 null）
 */
public record AuditResult(AuditVerdict verdict,
                          List<String> expectedTools,
                          List<String> missingTools,
                          List<String> calledTools,
                          String ruleDesc,
                          String reason,
                          JudgeSource source,
                          String judgeReason) {

    public boolean isFail() {
        return verdict == AuditVerdict.FAIL;
    }

    public boolean isAmbiguous() {
        return verdict == AuditVerdict.AMBIGUOUS;
    }

    /** 期望工具里的第一个（用于违规重试提示词——提示词只需要点名一个具体工具）。 */
    public String primaryTool() {
        return expectedTools.isEmpty() ? null : expectedTools.get(0);
    }

    /** 用规则结论构造（未触发裁判）。 */
    public static AuditResult ofRule(AuditVerdict verdict, List<String> expected, List<String> missing,
                                     List<String> called, String ruleDesc, String reason) {
        return new AuditResult(verdict, List.copyOf(expected), List.copyOf(missing), List.copyOf(called),
                ruleDesc, reason, JudgeSource.RULE, null);
    }

    /** 用裁判结论覆盖规则结论。 */
    public AuditResult withJudge(AuditVerdict verdict, JudgeSource source, String judgeReason) {
        return new AuditResult(verdict, expectedTools, missingTools, calledTools,
                ruleDesc, reason, source, judgeReason);
    }

    @Override
    public String toString() {
        return "verdict=" + verdict + " expected=" + expectedTools + " missing=" + missingTools
                + " reason=" + reason + " source=" + source;
    }
}
