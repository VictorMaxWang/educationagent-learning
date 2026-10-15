package com.demo.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 文本模型的唯一连接配置，供主对话、教育、裁判和辅助分类共同使用。
 *
 * <p>优先读取部署环境变量，其次读取 ai.chat.*，最后兼容 ai.deepseek.*。
 * URL 和模型必须一起切换，避免新供应商的密钥被发送到旧供应商地址。
 * 本配置不接管图像、语音或 embedding 服务，也不在日志中输出密钥。</p>
 */
@Component
public class AiChatProperties {

    private final String baseUrl;
    private final String completionsUrl;
    private final String apiKey;
    private final String model;
    private final boolean thinkingEnabled;

    public AiChatProperties(Environment environment) {
        String configuredBase = firstNonBlank(environment,
                "AI_CHAT_BASE_URL", "ai.chat.base-url", "ai.deepseek.base-url", "ai.deepseek.url");
        baseUrl = normalizeBaseUrl(configuredBase == null ? "https://api.deepseek.com/v1" : configuredBase);
        // 完整地址始终从生效的 base 派生，不再让遗留的完整 URL 将辅助请求分流到旧供应商。
        completionsUrl = baseUrl + "/chat/completions";
        String configuredKey = firstNonBlank(environment, "AI_CHAT_API_KEY", "ai.chat.api-key", "ai.deepseek.key");
        apiKey = configuredKey == null ? "" : configuredKey;
        String configuredModel = firstNonBlank(environment,
                "AI_CHAT_MODEL", "ai.chat.model", "ai.deepseek.model", "ai.deepseek.outfit-model");
        model = configuredModel == null ? "deepseek-chat" : configuredModel;
        String thinking = firstNonBlank(environment, "AI_CHAT_ENABLE_THINKING", "ai.chat.enable-thinking");
        if (thinking != null && !thinking.equalsIgnoreCase("true") && !thinking.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("AI_CHAT_ENABLE_THINKING 必须为 true 或 false");
        }
        thinkingEnabled = Boolean.parseBoolean(thinking);
    }

    public String getBaseUrl() { return baseUrl; }
    public String getCompletionsUrl() { return completionsUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public boolean isThinkingEnabled() { return thinkingEnabled; }
    public boolean hasApiKey() { return !apiKey.isBlank(); }

    /**
     * 千问默认关闭思考，避免 5～80 token 的短分类请求只产生思考而没有正文。
     * 保留开关便于显式启用；同步、流式和原生 REST 请求使用完全相同的参数。
     */
    public Map<String, Object> customParameters() {
        return Map.of("enable_thinking", thinkingEnabled);
    }

    /** 向已有的 OpenAI 兼容请求写入统一模型及供应商参数，保留业务自己的温度与输出限额。 */
    public void configureRequest(Map<String, Object> request) {
        request.put("model", model);
        request.putAll(customParameters());
    }

    /** 统一连接参数；调用方仍可保留自己的超时、中断客户端、温度与重试策略。 */
    public OpenAiChatModel.OpenAiChatModelBuilder chatModelBuilder() {
        return OpenAiChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(model)
                .customParameters(customParameters());
    }

    public OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder streamingModelBuilder() {
        return OpenAiStreamingChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(model)
                .customParameters(customParameters());
    }

    private static String firstNonBlank(Environment environment, String... names) {
        for (String name : names) {
            String value = environment.getProperty(name);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value.replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
            throw new IllegalArgumentException("AI_CHAT_BASE_URL 必须为 HTTP 或 HTTPS 地址");
        }
        return normalized;
    }
}
