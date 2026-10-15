package com.demo.agent.toolguard;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工具守卫配置，键前缀 {@code ai.fc.toolguard.*}。
 *
 * <p><b>mode 默认 {@code log}</b>，这是刻意的：三态裁决会新识别出大量原先"没人管"的灰区
 * （命中规则但工具没调），直接上 {@code enforce} 会把现有对话行为改掉。
 * 先跑一段 log 看日志里的 FAIL/AMBIGUOUS 占比，确认误报可接受再切 enforce。
 * 方案书 Prompt 8 的决策点里同样写明"mode 默认 = log（仅记录，不阻断）"。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.fc.toolguard")
public class ToolGuardProperties {

    /** 总开关。false → 完全不做审计，行为与改造前一致。 */
    private boolean enabled = true;

    /** 裁决模式：{@code log} 只记录不阻断；{@code enforce} 时 FAIL 会触发违规重试。 */
    private String mode = "log";

    private final Judge judge = new Judge();

    /** 是否处于强制执行模式。 */
    public boolean isEnforce() {
        return "enforce".equalsIgnoreCase(mode);
    }

    @Getter
    @Setter
    public static class Judge {
        /**
         * LLM 裁判开关。默认<b>关</b>：裁判会真实调用 DeepSeek 花 token 与延迟，
         * 且只在 AMBIGUOUS 时才有用——先把 log 模式下的灰区占比看清楚，再决定要不要开。
         */
        private boolean enabled = false;

        private String model = "deepseek-v4-flash";
        private double temperature = 0.0;
        private int maxTokens = 100;
        private long timeoutMs = 3000;

        private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    }

    @Getter
    @Setter
    public static class CircuitBreaker {
        private boolean enabled = true;
        private int failureThreshold = 5;
        private long cooldownSeconds = 60;
    }
}
