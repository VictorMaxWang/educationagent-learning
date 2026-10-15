package com.demo.skill;

/**
 * 技能调用异常。封装反射执行 / 参数转换中的各类异常。
 * <p>
 * 相比原始反射异常堆栈，此异常携带技能名、原始消息、参数上下文，
 * 方便链路追踪和排查。
 */
public class SkillInvokeException extends RuntimeException {

    private final String skillName;
    private final String rawMessage;
    private final String paramsSummary;

    public SkillInvokeException(String message) {
        super(message);
        this.skillName = null;
        this.rawMessage = null;
        this.paramsSummary = null;
    }

    public SkillInvokeException(String message, Throwable cause) {
        super(message, cause);
        this.skillName = null;
        this.rawMessage = null;
        this.paramsSummary = null;
    }

    /** 3 参数：无 cause */
    public SkillInvokeException(String skillName, String paramsSummary, String detail) {
        super(format(skillName, paramsSummary, detail));
        this.skillName = skillName;
        this.rawMessage = paramsSummary;
        this.paramsSummary = paramsSummary;
    }

    /** 4 参数：带 cause */
    public SkillInvokeException(String skillName, String paramsSummary, String detail, Throwable cause) {
        super(format(skillName, paramsSummary, detail), cause);
        this.skillName = skillName;
        this.rawMessage = paramsSummary;
        this.paramsSummary = paramsSummary;
    }

    public SkillInvokeException(String skillName, String rawMessage, String paramsSummary, String detail) {
        super(format(skillName, rawMessage, paramsSummary, detail));
        this.skillName = skillName;
        this.rawMessage = rawMessage;
        this.paramsSummary = paramsSummary;
    }

    public SkillInvokeException(String skillName, String rawMessage, String paramsSummary, String detail, Throwable cause) {
        super(format(skillName, rawMessage, paramsSummary, detail), cause);
        this.skillName = skillName;
        this.rawMessage = rawMessage;
        this.paramsSummary = paramsSummary;
    }

    private static String format(String skillName, String paramsSummary, String detail) {
        return String.format("[SkillInvoke] %s | 参数=%s | %s",
                skillName, paramsSummary, detail);
    }

    private static String format(String skillName, String rawMessage, String paramsSummary, String detail) {
        return String.format("[SkillInvoke] %s | 原始消息=%s | 参数=%s | %s",
                skillName, truncate(rawMessage, 50), paramsSummary, detail);
    }

    private static String truncate(String text, int max) {
        return text == null ? null : (text.length() <= max ? text : text.substring(0, max) + "...");
    }

    public String getSkillName() { return skillName; }
    public String getRawMessage() { return rawMessage; }
    public String getParamsSummary() { return paramsSummary; }
}
