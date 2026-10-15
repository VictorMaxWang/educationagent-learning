package com.demo.memory.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 记忆脑图专用线程池。
 *
 * <p><b>为什么要独立线程池：</b>脑图要在 N 个记忆之间做 O(N²) 余弦计算。放在 Tomcat 请求线程上，
 * 一个记忆多的用户刷新一次就占住一个请求线程；而前端是每 15 秒轮询一次。
 * 独立成 1~2 个线程的池，等于给这条"后台展示"链路限了并发上限。
 *
 * <p><b>队列满时用 CallerRunsPolicy：</b>脑图是只读展示，请求变慢远好于请求被丢弃。
 *
 * <h3>⚠️ 为什么返回 {@link GraphExecutor} 包装类，而不是直接暴露 {@code ThreadPoolTaskExecutor}</h3>
 * {@code ThreadPoolTaskExecutor} 会被注册成一个 {@code TaskExecutor} Bean。项目里
 * {@code chroma/UserMemoryExtractor} 有两个 {@code @Async} 方法，而 Spring 的异步执行器解析要求
 * <b>上下文中只有一个 TaskExecutor，或其中一个叫 {@code taskExecutor}</b>。
 * 直接暴露就会变成两个（本池 + Boot 自动配置的 {@code taskScheduler}），启动时报：
 * <pre>
 *   More than one TaskExecutor bean found within the context, and none is named 'taskExecutor'
 * </pre>
 * 结果是 {@code @Async} 退化成"每次调用新起一条线程"，失去池化。
 * 包一层之后本池不再是 TaskExecutor，歧义消失，也不影响 {@code @Async} 的既有行为。
 *
 * <p>本池只在 {@code memory.graph.enabled=true} 时装配；关闭时
 * {@link MemoryGraphService} 会退回同步构建（它用 ObjectProvider 取这个 Bean）。
 */
@Slf4j
@Configuration
public class GraphExecutorConfig {

    @Bean("graphTaskExecutor")
    @ConditionalOnProperty(name = "memory.graph.enabled", havingValue = "true", matchIfMissing = true)
    public GraphExecutor graphTaskExecutor(
            @Value("${thread.graph.core-size:1}") int coreSize,
            @Value("${thread.graph.max-size:2}") int maxSize,
            @Value("${thread.graph.queue-capacity:20}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, coreSize));
        executor.setMaxPoolSize(Math.max(coreSize, maxSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("memory-graph-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        log.info("🧠 记忆脑图线程池初始化完成 (core={} max={} queue={})",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), queueCapacity);
        return new GraphExecutor(executor);
    }

    /**
     * 脑图线程池的包装。<b>刻意不实现 {@code TaskExecutor}</b>，见类注释。
     */
    public static class GraphExecutor implements DisposableBean {

        private final ThreadPoolTaskExecutor delegate;

        GraphExecutor(ThreadPoolTaskExecutor delegate) {
            this.delegate = delegate;
        }

        /** 提交任务；池已关闭时返回 null，由调用方退回同步执行。 */
        public <T> Future<T> submit(Callable<T> task) {
            try {
                return delegate.submit(task);
            } catch (RejectedExecutionException | IllegalStateException e) {
                log.warn("【记忆库-脑图】线程池不可用（{}），本轮退回同步构建", e.getClass().getSimpleName());
                return null;
            }
        }

        @Override
        public void destroy() {
            delegate.destroy();
        }
    }
}
