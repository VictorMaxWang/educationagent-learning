package com.demo.service.preprocessing;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

/**
 * 图片识别服务 —— 调用阿里云通义千问视觉模型（DashScope Qwen-VL）
 * 功能：将图片转为文字描述，让纯文本大模型能够理解图片内容
 * 接口格式：兼容OpenAI对话格式，直接对接阿里云百炼DashScope视觉接口
 */
@Slf4j
@Service
public class VisionService {

    @Value("${ai.aliyun.key:}")
    private String apiKey;

    @Value("${ai.aliyun.url:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}")
    private String apiUrl;

    @Value("${ai.aliyun.model:qwen-vl-max}")
    private String modelName;

    @Resource
    @Qualifier("visionRestTemplate")
    private RestTemplate restTemplate;

    /**
     * 识别图片内容，返回图片详细文字描述
     * @param imageBytes 图片原始二进制字节数组
     * @return 图片描述文本，异常/未配置返回null
     */
    private static final String DEFAULT_PROMPT =
            "请详细描述这张图片中的内容，包括场景、物体、人物、动作、文字、颜色、光线等关键信息，不要遗漏重要细节。";

    /** 默认提示词图片识别 */
    public String describe(byte[] imageBytes) {
        return describe(imageBytes, DEFAULT_PROMPT);
    }

    /**
     * 图片识别（自定义提示词）
     * @param imageBytes 图片字节
     * @param prompt     引导模型的提示词（如衣物识别、宠物症状识别等），为空时用默认提示词
     */
    public String describe(byte[] imageBytes, String prompt) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            log.warn("阿里云视觉API Key 未配置，跳过图片识别");
            return null;
        }
        if (imageBytes == null || imageBytes.length == 0) return null;

        try {
            // 1. 图片二进制字节 → 转Base64字符串
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            // 2. 判断图片类型 jpg/png/gif
            String mime = detectMime(imageBytes);
            // 3. 拼接成大模型通用可识别格式
            String dataUri = "data:" + mime + ";base64," + base64;

            log.info("🖼️ 阿里云视觉识别：{}KB, 格式={}, 模型={}",
                    imageBytes.length / 1024, mime, modelName);

            // ========== 构建请求头：携带认证信息 ==========
            HttpHeaders headers = new HttpHeaders();
            // 指定请求数据格式为JSON
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 填入阿里云密钥鉴权
            headers.setBearerAuth(apiKey);

            // ========== 组装请求体：发给阿里云大模型的核心数据 ==========
            JSONObject body = new JSONObject();
            // 指定调用模型
            body.put("model", modelName);
            // 设置最大返回文本长度
            body.put("max_tokens", 4096);

            // 构建对话内容数组（图文混合）
            JSONArray content = new JSONArray();

            // 1. 放入图片资源
            JSONObject img = new JSONObject();
            img.put("type", "image_url");
            JSONObject url = new JSONObject();
            url.put("url", dataUri);
            img.put("image_url", url);
            content.add(img);

            // 2. 放入识别提示词，引导模型精准描述图片
            JSONObject txt = new JSONObject();
            txt.put("type", "text");
            txt.put("text", prompt != null && !prompt.isBlank() ? prompt : DEFAULT_PROMPT);
            content.add(txt);

            // 封装用户对话消息
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", content);

            // 装入对话消息列表
            JSONArray messages = new JSONArray();
            messages.add(msg);
            body.put("messages", messages);

            // ========== 发送HTTP请求，正式调用阿里云大模型接口 ==========
            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl, entity, String.class);

            // 校验接口响应状态码
            if (response.getStatusCodeValue() != 200) {
                log.error("视觉API返回 HTTP {}: {}",
                        response.getStatusCodeValue(),
                        truncate(response.getBody(), 300));
                return null;
            }

            // ========== 解析阿里云返回的识别结果 ==========
            JSONObject result = JSON.parseObject(response.getBody());
            JSONArray choices = result.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.error("视觉API响应缺少choices");
                return null;
            }

            // 提取模型返回的图片描述文本
            String desc = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

            log.info("✅ 图片识别完成：{} 字", desc != null ? desc.length() : 0);
            return desc;

        } catch (Exception e) {
            log.error("❌ 图片识别失败", e);
            return null;
        }
    }

    /**
     * 自动识别图片MIME类型
     * @param bytes 图片字节数组
     * @return 图片格式标识
     */
    private static String detectMime(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return "image/jpeg";
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (bytes[0] == 0x89 && bytes[1] == 0x50) return "image/png";
        if (bytes[0] == 0x47 && bytes[1] == 0x49) return "image/gif";
        if (bytes[0] == 0x52 && bytes[1] == 0x49) return "image/webp";
        return "image/jpeg";
    }

    /**
     * 字符串长度截断，防止日志打印过长
     * @param text 原字符串
     * @param maxLen 最大展示长度
     * @return 截断后字符串
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
