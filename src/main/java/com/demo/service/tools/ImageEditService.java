package com.demo.service.tools;

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

import java.net.URI;
import java.util.Base64;

/**
 * 图生图/图像编辑服务 —— 调用通义万相图生图 API（异步模式）
 * 支持：风格迁移、局部修改、换背景、改颜色等
 * 与 ImageGenService 使用相同的异步提交 + 轮询模式
 */
@Slf4j
@Service
public class ImageEditService {

    private static final String GEN_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";

    private static final String TASK_URL =
            "https://dashscope.aliyuncs.com/api/v1/tasks/%s";

    private static final String MODEL = "wan2.5-t2i-preview";

    private static final String IMAGE_SIZE = "1024*1024";

    private static final long POLL_TIMEOUT_MS = 60_000;
    private static final long POLL_INTERVAL_MS = 2_000;

    @Value("${ai.aliyun.key:}")
    private String apiKey;

    @Resource
    @Qualifier("visionRestTemplate")
    private RestTemplate restTemplate;

    /**
     * 图生图编辑（异步提交 + 轮询）
     * @param imageBytes  原始图片字节
     * @param prompt      编辑描述，如"转换成动漫风格"、"把背景换成海滩"
     * @return 编辑后的图片字节（JPEG），失败返回 null
     */
    public byte[] editImage(byte[] imageBytes, String prompt) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            log.warn("⚠️ 阿里云 API Key 未配置，跳过图片编辑");
            return null;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("原始图片为空");
            return null;
        }
        if (prompt == null || prompt.isBlank()) {
            log.warn("编辑 prompt 为空");
            return null;
        }

        // 将原始图片转为 Base64
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        // ========== 1. 提交异步任务 ==========
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-DashScope-Async", "enable");
        headers.setBearerAuth(apiKey);

        JSONObject body = new JSONObject();
        body.put("model", MODEL);

        JSONObject input = new JSONObject();
        input.put("prompt", prompt);
        // 传入原始图片作为图生图的输入
        JSONObject imageInput = new JSONObject();
        imageInput.put("image", "data:image/png;base64," + base64Image);
        input.put("image", imageInput);
        body.put("input", input);

        JSONObject params = new JSONObject();
        params.put("size", IMAGE_SIZE);
        params.put("n", 1);
        body.put("parameters", params);

        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);
        log.info("🎨 提交图生图任务 | prompt={} | 原始图={}KB", prompt, imageBytes.length / 1024);

        String taskId;
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(GEN_URL, entity, String.class);
            if (response.getStatusCodeValue() != 200) {
                log.error("提交图生图任务失败 HTTP {}: {}",
                        response.getStatusCodeValue(), truncate(response.getBody(), 300));
                return null;
            }
            JSONObject result = JSON.parseObject(response.getBody());
            JSONObject output = result.getJSONObject("output");
            if (output == null) return null;
            taskId = output.getString("task_id");
            if (taskId == null || taskId.isBlank()) return null;
            log.info("📋 图生图任务已提交 | task_id={}", taskId);
        } catch (Exception e) {
            log.error("❌ 提交图生图任务失败: {}", e.getMessage());
            return null;
        }

        // ========== 2. 轮询 ==========
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth(apiKey);

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < POLL_TIMEOUT_MS) {
            try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            try {
                HttpEntity<String> getEntity = new HttpEntity<>(getHeaders);
                ResponseEntity<String> pollResp = restTemplate.exchange(
                        String.format(TASK_URL, taskId), HttpMethod.GET, getEntity, String.class);
                if (pollResp.getStatusCodeValue() != 200) continue;

                JSONObject pollResult = JSON.parseObject(pollResp.getBody());
                JSONObject pollOutput = pollResult.getJSONObject("output");
                if (pollOutput == null) continue;

                String taskStatus = pollOutput.getString("task_status");
                if ("SUCCEEDED".equals(taskStatus)) {
                    JSONArray results = pollOutput.getJSONArray("results");
                    if (results == null || results.isEmpty()) return null;
                    JSONObject first = results.getJSONObject(0);

                    // 优先 base64
                    String base64 = first.getString("base64_image");
                    if (base64 == null || base64.isBlank()) base64 = first.getString("b64_json");
                    if (base64 != null && !base64.isBlank()) {
                        byte[] decoded = decodeBase64Image(base64);
                        if (decoded != null) {
                            log.info("✅ 图生图成功 | {}KB | 耗时={}s",
                                    decoded.length / 1024, (System.currentTimeMillis() - start) / 1000);
                            return decoded;
                        }
                    }
                    // 回退 URL 下载
                    String url = first.getString("url");
                    if (url != null && !url.isBlank()) {
                        return downloadFromUrl(url);
                    }
                    return null;
                } else if ("FAILED".equals(taskStatus)) {
                    log.error("图生图任务失败: {}", pollOutput.getString("message"));
                    return null;
                }
            } catch (Exception e) {
                log.warn("轮询异常: {}", e.getMessage());
            }
        }
        log.error("⏰ 图生图轮询超时");
        return null;
    }

    private byte[] decodeBase64Image(String base64Data) {
        if (base64Data == null || base64Data.isBlank()) return null;
        String data = base64Data.trim();
        if (data.contains(",")) data = data.substring(data.indexOf(",") + 1);
        try { return Base64.getDecoder().decode(data); }
        catch (IllegalArgumentException e) { return null; }
    }

    private byte[] downloadFromUrl(String url) {
        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, null, byte[].class);
            return resp.getBody();
        } catch (Exception e) {
            log.warn("图片下载失败: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
