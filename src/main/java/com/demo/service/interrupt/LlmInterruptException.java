package com.demo.service.interrupt;

/**
 * LLM 调用因用户中断而取消的标记异常。
 * <p>
 * AgentExecutor 的 FSM 捕获此异常后，会检查中断信号并返回中断提示，
 * 而非返回通用的"AI 服务不可用"错误消息。
 */
public class LlmInterruptException extends RuntimeException {

    private final String userId;

    public LlmInterruptException(String userId) {
        super("LLM 调用因用户中断而取消");
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }
}
