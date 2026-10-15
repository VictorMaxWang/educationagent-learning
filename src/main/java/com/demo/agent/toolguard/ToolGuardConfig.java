package com.demo.agent.toolguard;

import com.demo.router.CircuitBreaker;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 工具守卫基础设施：裁判专用的短超时 RestTemplate + 熔断器。
 *
 * <p>为什么不复用项目里已有的 RestTemplate：那些读超时是 30s/120s/180s，都为长文本生成设计。
 * 裁判只输出 100 个 token，用 30s 超时意味着一次网络抖动就能让 VERIFY 阶段卡半分钟——
 * 而 VERIFY 是每轮对话的必经路径，比路由更敏感。
 *
 * <p>{@link CircuitBreaker} 直接复用 {@code com.demo.router} 里那个通用实现
 * （三态、无外部依赖）。它虽然放在 router 包下，但本身与路由无关，
 * 为它再复制一份没有意义。
 */
@Configuration
public class ToolGuardConfig {

    @Bean("toolGuardRestTemplate")
    public RestTemplate toolGuardRestTemplate(RestTemplateBuilder builder, ToolGuardProperties properties) {
        long readTimeoutMs = Math.max(300L, properties.getJudge().getTimeoutMs());
        return builder
                .connectTimeout(Duration.ofMillis(Math.min(readTimeoutMs, 2000L)))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }

    @Bean("toolGuardCircuitBreaker")
    public CircuitBreaker toolGuardCircuitBreaker(ToolGuardProperties properties) {
        ToolGuardProperties.CircuitBreaker cfg = properties.getJudge().getCircuitBreaker();
        return new CircuitBreaker(
                "toolguard-judge",
                cfg.isEnabled(),
                cfg.getFailureThreshold(),
                cfg.getCooldownSeconds() * 1000L);
    }
}
