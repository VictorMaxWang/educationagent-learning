package com.demo.util;

import com.demo.config.AiChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.Resource;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * 中文 → 英文图片搜索关键词翻译工具
 * 借助 DeepSeek (temp=0.1) 将中文图片描述转为英文搜索关键词
 * 与 CityIdAiUtil 相同模式，严格输出不要多余文字
 */
@Slf4j
@Component
public class CnToEnUtil {

    @Resource
    private AiChatProperties aiChat;

    @Resource
    private RestTemplate restTemplate;

    /**
     * 将中文图片描述翻译为英文搜索关键词
     * @param chinese 中文描述，如"小狗"、"故宫"
     * @return 英文关键词，如"cute puppy"、"Forbidden City"，失败返回 null
     */
    public String translateToEnglish(String chinese) {
        String prompt = "你只需要严格输出英文搜索关键词，不要多余文字、不要解释、不要标点。" +
                "将以下中文描述翻译成最合适的英文搜索词（用于图片搜索，用空格分隔关键词）：" + chinese;

        JSONObject reqBody = new JSONObject();
        aiChat.configureRequest(reqBody);
        reqBody.put("temperature", 0.1);

        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", prompt);
        reqBody.put("messages", new Object[]{msg});

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Authorization", "Bearer " + aiChat.getApiKey());
        headers.add("Content-Type", "application/json");
        org.springframework.http.HttpEntity<String> entity =
                new org.springframework.http.HttpEntity<>(reqBody.toJSONString(), headers);

        try {
            org.springframework.http.ResponseEntity<String> resp =
                    restTemplate.postForEntity(aiChat.getCompletionsUrl(), entity, String.class);
            JSONObject result = JSON.parseObject(resp.getBody());
            String content = result.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();
            log.info("🌐 中文→英文翻译：{} → {}", chinese, content);
            return content;
        } catch (Exception e) {
            log.error("🌐 中文→英文翻译失败", e);
            return null;
        }
    }
}
