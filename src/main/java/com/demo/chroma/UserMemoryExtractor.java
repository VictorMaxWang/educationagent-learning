package com.demo.chroma;

import com.demo.config.AiChatProperties;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 记忆提取器 — 使用 DeepSeek 从用户对话中提取值得长期存储的事实
 * <p>
 * 提取逻辑：
 * 1. 获取最近几轮对话文本
 * 2. 调用 DeepSeek（temp=0.1）提取事实性记忆
 * 3. 解析返回的 JSON 数组
 * 4. 去重后存入 UserMemoryService（Chroma user_memory 集合）
 * <p>
 * 异步执行（@Async），不阻塞主对话流程。
 */
@Slf4j
@Service
public class UserMemoryExtractor {

    @Resource
    private AiChatProperties aiChat;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private UserMemoryService userMemoryService;

    /**
     * DeepSeek 提取记忆的系统提示词
     */
    private static final String EXTRACT_PROMPT = """
            你是一个对话记忆提取器。分析以下对话，提取出关于用户的重要事实信息。

            提取规则：
            - 只提取确定的事实，不要编造或推断
            - 每条记忆应是一个简洁的陈述句（不超过 40 字）
            - 关注：用户偏好、生活习惯、个人信息、重要计划、说过的事实性内容
            - 忽略：日常寒暄、简单问答、语气词、无需记忆的常规对话
            - 如果本条对话中没有值得长期记住的信息，返回空数组 []
            - 输出必须是 JSON 字符串数组格式，如 ["记忆1", "记忆2"]

            对话：
            """;

    /**
     * 分析对话并提取记忆（异步）
     */
    @Async
    public CompletableFuture<Void> extractAndStoreAsync(String userId, String userMessage, String aiResponse) {
        try {
            List<String> facts = extractFacts(userMessage, aiResponse);
            if (!facts.isEmpty()) {
                userMemoryService.addMemories(userId, facts);
            }
        } catch (Exception e) {
            log.warn("[{}] 记忆提取异常（不影响主流程）: {}", userId, e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 批量分析多轮对话并提取记忆（异步）
     */
    @Async
    public CompletableFuture<Void> extractBatchAsync(String userId, List<String> conversations) {
        try {
            List<String> facts = extractFactsBatch(conversations);
            if (!facts.isEmpty()) {
                userMemoryService.addMemories(userId, facts);
            }
        } catch (Exception e) {
            log.warn("[{}] 批量记忆提取异常: {}", userId, e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    // ======================== 私有方法 ========================

    private List<String> extractFacts(String userMessage, String aiResponse) {
        String dialog = "用户：" + userMessage + "\n助手：" + aiResponse;
        String response = callDeepSeek(dialog);
        return parseResponse(response);
    }

    private List<String> extractFactsBatch(List<String> conversations) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conversations.size(); i += 2) {
            if (i + 1 < conversations.size()) {
                sb.append("用户：").append(conversations.get(i)).append("\n");
                sb.append("助手：").append(conversations.get(i + 1)).append("\n\n");
            }
        }
        if (sb.isEmpty()) return Collections.emptyList();

        String response = callDeepSeek(sb.toString());
        return parseResponse(response);
    }

    private String callDeepSeek(String dialog) {
        JSONObject reqBody = new JSONObject();
        aiChat.configureRequest(reqBody);
        reqBody.put("temperature", 0.1);
        reqBody.put("max_tokens", 512);

        JSONArray messages = new JSONArray();

        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", EXTRACT_PROMPT);
        messages.add(sysMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", dialog);
        messages.add(userMsg);

        reqBody.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + aiChat.getApiKey());
        headers.add("Content-Type", "application/json");

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    aiChat.getCompletionsUrl(),
                    new HttpEntity<>(reqBody.toJSONString(), headers),
                    String.class);
            // ⚠️ 必须用字节流解析，不能直接传 String。
            // fastjson2 2.0.61 的 JSONReaderUTF16.readString 在"字符串 token 跨内部
            // 512 字符缓冲边界、且含转义"时会算错目标数组长度，抛
            //   ArrayIndexOutOfBoundsException: arraycopy: last destination index 564
            //   out of bounds for char[512]
            // 走字节（UTF-8）解析会使用另一套 reader，绕开这个 bug。
            String body = resp.getBody();
            JSONObject result = JSON.parseObject(
                    body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
            return result.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();
        } catch (Exception e) {
            log.error("DeepSeek 记忆提取调用失败", e);
            return "[]";
        }
    }

    private List<String> parseResponse(String content) {
        if (content == null || content.isBlank()) return Collections.emptyList();

        try {
            return JSON.parseArray(content, String.class);
        } catch (Exception e) {
            try {
                int start = content.indexOf('[');
                int end = content.lastIndexOf(']');
                if (start >= 0 && end > start) {
                    String json = content.substring(start, end + 1);
                    return JSON.parseArray(json, String.class);
                }
            } catch (Exception ignored) {
            }
            log.warn("记忆提取返回无法解析：{}", content);
            return Collections.emptyList();
        }
    }
}
