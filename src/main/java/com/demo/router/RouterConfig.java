package com.demo.router;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 前置路由基础设施：一个短超时的 RestTemplate + 一个 L3 专用熔断器。
 *
 * <p>为什么不复用项目里已有的三个 RestTemplate：它们的读超时是 30s / 120s / 180s，
 * 都是为"长文本生成"设计的。L3 路由判定只需要 10 个 token，用 30s 超时意味着
 * 一次网络抖动就能把一条消息卡住半分钟——必须单独给一个毫秒级超时的实例。
 */
@Configuration
public class RouterConfig {

    @Bean("routerRestTemplate")
    public RestTemplate routerRestTemplate(RestTemplateBuilder builder, RouterProperties properties) {
        long readTimeoutMs = Math.max(300L, properties.getLlm().getTimeoutMs());
        return builder
                .connectTimeout(Duration.ofMillis(Math.min(readTimeoutMs, 2000L)))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }

    /**
     * L3 专属熔断器。DeepSeek 不可用时应快速失败，而不是每条消息都白等一次超时。
     */
    @Bean("llmRouterCircuitBreaker")
    public CircuitBreaker llmRouterCircuitBreaker(RouterProperties properties) {
        RouterProperties.CircuitBreaker cfg = properties.getLlm().getCircuitBreaker();
        return new CircuitBreaker(
                "llm-router",
                cfg.isEnabled(),
                cfg.getFailureThreshold(),
                cfg.getCooldownSeconds() * 1000L);
    }
}
