package com.demo.service.interrupt;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import lombok.Setter;

import java.time.Duration;

/**
 * 可中断的 HttpClientBuilder — 构建时注入 {@link InterruptManager}，
 * 返回的 {@link InterruptibleHttpClient} 在每次 LLM HTTP 调用前检测中断信号。
 * <p>
 * 替代 OkHttp Interceptor 方案（langchain4j 1.17.2 使用 JDK HttpClient）。
 * 通过 {@code OpenAiChatModel.builder().httpClientBuilder(this)} 注入。
 */
public class InterruptibleHttpClientBuilder implements HttpClientBuilder {

    private final InterruptManager interruptManager;
    private final JdkHttpClientBuilder delegate = new JdkHttpClientBuilder();

    @Setter
    private Duration connectTimeout;

    @Setter
    private Duration readTimeout;

    public InterruptibleHttpClientBuilder(InterruptManager interruptManager) {
        this.interruptManager = interruptManager;
    }

    @Override
    public HttpClient build() {
        if (connectTimeout != null) delegate.connectTimeout(connectTimeout);
        if (readTimeout != null) delegate.readTimeout(readTimeout);
        HttpClient jdkClient = delegate.build();
        return new InterruptibleHttpClient(jdkClient, interruptManager);
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }
}
