package com.demo.memory;

/**
 * 记忆类型。区分类型的意义在于<b>衰减策略不同</b>，不是为了分类好看。
 *
 * <p>方案书里的分代可视化（NEW / SURVIVOR / OLD / PENDING_GC）也依赖这个字段：
 * 例如 EPISODIC 类型的记忆天然更短命（年龄超 60 天就该回收），
 * 而 BASIC 类型（"用户叫张三"）几乎不会过期。
 */
public enum MemoryType {

    /** 基本事实：姓名、住址、职业等稳定属性。衰减最慢。 */
    BASIC,

    /** 情景记忆：某次具体事件（"上周去看了演唱会"）。衰减最快。 */
    EPISODIC,

    /** 语义记忆：偏好、习惯、观点（"喜欢清淡口味"）。 */
    SEMANTIC,

    /** 工作记忆：当前会话内的临时状态，会话结束即可丢弃。 */
    WORKING;

    /** 从字符串宽松解析，无法识别时返回 {@link #BASIC}（最保守，不会误删）。 */
    public static MemoryType parse(String raw) {
        if (raw == null || raw.isBlank()) return BASIC;
        for (MemoryType t : values()) {
            if (t.name().equalsIgnoreCase(raw.trim())) return t;
        }
        return BASIC;
    }
}
