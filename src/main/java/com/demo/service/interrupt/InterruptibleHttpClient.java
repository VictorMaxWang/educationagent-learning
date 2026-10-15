package com.demo.service.interrupt;

import com.demo.util.VoiceContext;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import lombok.extern.slf4j.Slf4j;

/**
 * 可中断的 LLM HTTP 客户端包装器。
 * <p>
 * 【缺陷 1 修复】JDK HttpClient.send() 在阻塞 Socket 读取时不受线程中断影响，
 * 后台仍会跑完请求浪费 token。本包装器在每次 execute() 前检测中断信号，
 * 检测到用户中断时立即抛出 {@link LlmInterruptException}，跳过本次 HTTP 调用。
 * <p>
 * 对于 SSE 流式响应，包装 listener 在每个事件间检查中断信号，
 * 检测到中断时停止消费后续事件。
 */
@Slf4j
public class InterruptibleHttpClient implements HttpClient {

    private final HttpClient delegate;
    private final InterruptManager interruptManager;

    public InterruptibleHttpClient(HttpClient delegate, InterruptManager interruptManager) {
        this.delegate = delegate;
        this.interruptManager = interruptManager;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException {
        checkInterrupt();
        return delegate.execute(request);
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser,
                        ServerSentEventListener listener) {
        checkInterrupt();
        // 包装 listener，在每个事件间检查中断
        ServerSentEventListener wrapped = new InterruptibleListener(listener);
        delegate.execute(request, parser, wrapped);
    }

    /** execute 前检查中断信号 */
    private void checkInterrupt() {
        String userId = VoiceContext.getUserId();
        if (userId != null && interruptManager.isSignaled(userId)) {
            log.info("🚨 [{}] InterruptibleHttpClient 拦截 LLM HTTP 调用（用户已中断）", userId);
            throw new LlmInterruptException(userId);
        }
    }

    /**
     * SSE 事件监听器包装 — 每个事件间检查中断。
     * 检测到中断后停止消费后续事件，不再处理 LLM 流式返回。
     */
    private class InterruptibleListener implements ServerSentEventListener {

        private final ServerSentEventListener delegate;

        InterruptibleListener(ServerSentEventListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
            checkInterruptSse();
            delegate.onEvent(event, context);
        }

        @Override
        public void onEvent(ServerSentEvent event) {
            checkInterruptSse();
            delegate.onEvent(event);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public void onClose() {
            delegate.onClose();
        }

        private void checkInterruptSse() {
            String userId = VoiceContext.getUserId();
            if (userId != null && interruptManager.isSignaled(userId)) {
                log.info("🚨 [{}] SSE 事件间检测到中断，停止消费流式响应", userId);
                throw new LlmInterruptException(userId);
            }
        }
    }
}
