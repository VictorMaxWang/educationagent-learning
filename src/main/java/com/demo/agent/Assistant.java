package com.demo.agent;

import java.util.function.Consumer;

/**
 * LangChain4j Agent 接口
 * <p>
 * 由 {@link AgentExecutor} 实现，使用 LangChain4j 底层 API 手动构建
 * Agent 执行循环（ReAct 模式），无需 AiServices 动态代理。
 */
public interface Assistant {

    /**
     * 处理用户消息，返回 AI 回复
     *
     * @param memoryId    对话记忆 ID（微信用户 ID 或固定值）
     * @param userMessage 用户输入
     * @return AI 回复文本
     */
    String chat(String memoryId, String userMessage);

    /**
     * 流式处理用户消息。
     * <p>
     * 默认实现退化为非流式：先完整调用 {@link #chat}，再把整段回复作为单个
     * token 回调。支持真流式的实现（如 {@link AgentExecutor}）会覆盖此方法，
     * 实时增量回调。
     *
     * @param memoryId    对话记忆 ID
     * @param userMessage 用户输入
     * @param onToken     文本增量回调（可能从后台线程调用，实现需保证线程安全）
     * @return 完整回复文本
     */
    default String chatStream(String memoryId, String userMessage, Consumer<String> onToken) {
        return chatStream(memoryId, userMessage, onToken, null);
    }

    /**
     * 流式处理用户消息（可捕获 LLM 思考过程）。
     * <p>
     * 默认实现退化为非流式：先完整调用 {@link #chat}，再把整段回复作为单个
     * token 回调（思考回调不触发）。支持真流式的实现（如 {@link AgentExecutor}）
     * 覆盖此方法，实时增量回调。
     *
     * @param memoryId    对话记忆 ID
     * @param userMessage 用户输入
     * @param onToken     文本增量回调（可能从后台线程调用，实现需保证线程安全）
     * @param onThinking  LLM 思考过程增量回调（reasoning_content；可为 null 表示不捕获）
     * @return 完整回复文本
     */
    default String chatStream(String memoryId, String userMessage, Consumer<String> onToken,
                              Consumer<String> onThinking) {
        String reply = chat(memoryId, userMessage);
        if (onToken != null) {
            onToken.accept(reply);
        }
        return reply;
    }
}
