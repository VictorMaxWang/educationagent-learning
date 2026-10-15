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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 文生图服务 —— 按 ai.image.gen.provider 路由：
 * 1) siliconflow：硅基流动 OpenAI 兼容接口（默认，稳定）
 * 2) dashscope：阿里通义万相（异步提交 + 轮询 task_id，需开通对应模型）
 */
@Slf4j
@Service
public class ImageGenService {

    // 绘图任务状态查询地址
    private static final String TASK_URL =
            "https://dashscope.aliyuncs.com/api/v1/tasks/%s";

    /** 文生图模型（可在 application.properties / dev 配置 ai.image.gen.model 切换，如 wan2.5-t2i-flash） */
    @Value("${ai.image.gen.model:wan2.5-t2i-preview}")
    private String model;

    /** 文生图接口请求地址 */
    @Value("${ai.image.gen.url:https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis}")
    private String genUrl;

    /** 生成尺寸 */
    @Value("${ai.image.gen.size:1024*1024}")
    private String imageSize;

    /** 反向提示词，过滤低质量畸形画面 */
    @Value("${ai.image.gen.negative-prompt:低质量, 模糊, 变形, 畸形, 扭曲, 水印, 文本, 签名, 多余的手指, 糟糕的比例, 失焦}")
    private String negativePrompt;

    // 任务轮询最大超时时间
    private static final long POLL_TIMEOUT_MS = 60_000;

    // 每次轮询间隔时间
    private static final long POLL_INTERVAL_MS = 2_000;

    @Value("${ai.aliyun.key:}")
    private String apiKey;

    /** 生图服务商：siliconflow（硅基流动）/ dashscope（阿里通义万相） */
    @Value("${ai.image.gen.provider:dashscope}")
    private String provider;

    // ======================== 硅基流动（SiliconFlow，OpenAI 兼容） ========================

    @Value("${ai.siliconflow.api-key:}")
    private String siliconflowApiKey;

    @Value("${ai.siliconflow.endpoint:https://api.siliconflow.cn/v1/images/generations}")
    private String siliconflowEndpoint;

    @Value("${ai.siliconflow.model:stabilityai/stable-diffusion-xl-base-1.0}")
    private String siliconflowModel;

    /** 生成尺寸（小红书竖版封面） */
    @Value("${ai.image.gen.width:768}")
    private int genWidth;

    @Value("${ai.image.gen.height:1280}")
    private int genHeight;

    @Resource
    @Qualifier("visionRestTemplate")
    private RestTemplate restTemplate;

    /**
     * 文生图入口：按 ai.image.gen.provider 路由（siliconflow → 硅基流动，否则 DashScope）。
     * @param prompt 图片描述，支持中文
     * @return 图片字节数据，失败返回 null
     */
    public byte[] generate(String prompt) {
        if ("siliconflow".equalsIgnoreCase(provider)) {
            return generateViaSiliconFlow(prompt);
        }
        return generateViaDashScope(prompt);
    }

    /**
     * DashScope 通义万相文生图（异步提交 + 轮询），返回解码后的图片字节
     * @param prompt 图片描述，支持中文
     * @return 图片字节数据（PNG），失败返回 null
     */
    private byte[] generateViaDashScope(String prompt) {
        // 校验密钥是否配置
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            log.warn("⚠️ 阿里云 API Key 未配置，跳过图片生成");
            return null;
        }
        // 校验绘图提示词
        if (prompt == null || prompt.isBlank()) {
            log.warn("图片生成 prompt 为空");
            return null;
        }

        // ========== 1. 提交异步绘图任务 ==========
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // ★ 关键：开启异步调用标识，否则 API 会拒绝
        headers.set("X-DashScope-Async", "enable");
        headers.setBearerAuth(apiKey);

        // 组装请求主体参数
        JSONObject body = new JSONObject();
        body.put("model", model);

        // 组装图文提示词
        JSONObject input = new JSONObject();
        input.put("prompt", prompt);
        input.put("negative_prompt", negativePrompt);
        body.put("input", input);

        // 组装生成配置参数
        JSONObject params = new JSONObject();
        params.put("size", imageSize);
        params.put("n", 1);
        // ★ wan2.5-t2i-preview 不支持 output_format 参数，删除；其它模型如需可自行加回
        body.put("parameters", params);

        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);
        log.info("🎨 提交文生图任务 | model={} prompt={}", model, prompt);

        String taskId;
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(genUrl, entity, String.class);
            if (response.getStatusCodeValue() != 200) {
                log.error("提交任务失败 HTTP {}: {}",
                        response.getStatusCodeValue(), truncate(response.getBody(), 500));
                return null;
            }

            JSONObject result = JSON.parseObject(response.getBody());
            JSONObject output = result.getJSONObject("output");
            if (output == null) {
                log.error("响应缺少 output: {}", truncate(response.getBody(), 500));
                return null;
            }

            taskId = output.getString("task_id");
            if (taskId == null || taskId.isBlank()) {
                log.error("响应中未找到 task_id: {}", truncate(response.getBody(), 500));
                return null;
            }
            log.info("📋 任务已提交 | task_id={}", taskId);
        } catch (Exception e) {
            log.error("❌ 提交文生图任务失败: {}", e.getMessage());
            return null;
        }

        // ========== 2. 循环轮询任务执行状态 ==========
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth(apiKey);

        long start = System.currentTimeMillis();
        // 在超时时间内持续轮询
        while (System.currentTimeMillis() - start < POLL_TIMEOUT_MS) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            try {
                HttpEntity<String> getEntity = new HttpEntity<>(getHeaders);
                ResponseEntity<String> pollResp = restTemplate.exchange(
                        String.format(TASK_URL, taskId),
                        HttpMethod.GET,
                        getEntity,
                        String.class);

                if (pollResp.getStatusCodeValue() != 200) {
                    log.warn("轮询返回 HTTP {}", pollResp.getStatusCodeValue());
                    continue;
                }

                JSONObject pollResult = JSON.parseObject(pollResp.getBody());
                JSONObject pollOutput = pollResult.getJSONObject("output");
                if (pollOutput == null) continue;

                // 获取当前任务状态
                String taskStatus = pollOutput.getString("task_status");

                // 任务生成成功
                if ("SUCCEEDED".equals(taskStatus)) {
                    // 提取 base64 图片
                    JSONArray results = pollOutput.getJSONArray("results");
                    if (results == null || results.isEmpty()) {
                        log.error("任务完成但结果为空");
                        return null;
                    }

                    JSONObject firstResult = results.getJSONObject(0);

                    // 1) 优先取 base64（wan2.5 可能返回 base64_image 或 b64_json）
                    String base64 = firstResult.getString("base64_image");
                    if (base64 == null || base64.isBlank()) {
                        base64 = firstResult.getString("b64_json");
                    }
                    byte[] imageBytes = null;
                    if (base64 != null && !base64.isBlank()) {
                        imageBytes = decodeBase64Image(base64);
                        if (imageBytes != null) {
                            log.info("✅ 从 base64 解码成功 | 耗时={}s",
                                    (System.currentTimeMillis() - start) / 1000);
                        }
                    }

                    // 2) Base64解析失败，通过OSS链接下载图片
                    if (imageBytes == null) {
                        String url = firstResult.getString("url");
                        if (url != null && !url.isBlank()) {
                            imageBytes = downloadFromOssUrl(url);
                        }
                    }

                    // 两种方式均获取失败直接返回
                    if (imageBytes == null) {
                        log.error("既无 base64 也无法从 URL 下载图片 (已尝试所有方式)");
                        return null;
                    }

                    log.info("✅ 文生图成功 | {}KB | 耗时={}s",
                            imageBytes.length / 1024,
                            (System.currentTimeMillis() - start) / 1000);
                    return imageBytes;

                } else if ("FAILED".equals(taskStatus)) {
                    // 任务生成失败，返回错误信息
                    String msg = pollOutput.getString("message");
                    log.error("任务失败: {}", msg);
                    return null;
                } else {
                    // 任务执行中，继续等待
                    log.debug("任务状态: {} | 已等待 {}s",
                            taskStatus, (System.currentTimeMillis() - start) / 1000);
                }

            } catch (Exception e) {
                log.warn("轮询异常: {}", e.getMessage());
                // 继续轮询，不退出
            }
        }

        // 超出最大等待时间判定为超时
        log.error("⏰ 轮询超时 ({}s)", POLL_TIMEOUT_MS / 1000);
        return null;
    }

    // ======================== 硅基流动（SiliconFlow，OpenAI 兼容接口） ========================

    /**
     * 硅基流动文生图（/v1/images/generations，OpenAI 兼容，同步返回）。
     *
     * @param prompt 图片描述，支持中文
     * @return 图片字节数据，失败返回 null
     */
    private byte[] generateViaSiliconFlow(String prompt) {
        if (siliconflowApiKey == null || siliconflowApiKey.isBlank() || siliconflowApiKey.startsWith("${")) {
            log.warn("⚠️ 硅基流动 API Key 未配置，跳过图片生成");
            return null;
        }
        if (prompt == null || prompt.isBlank()) {
            log.warn("图片生成 prompt 为空");
            return null;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("model", siliconflowModel);
            body.put("prompt", prompt);
            body.put("image_size", genWidth + "x" + genHeight);
            body.put("batch_size", 1);
            // 只传各模型都兼容的最小字段（negative_prompt/steps/guidance/seed 某些模型会拒）
            body.put("response_format", "b64_json"); // 返回 url 时走下方下载兜底

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(siliconflowApiKey);

            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);
            log.info("🎨 硅基流动文生图 | model={} prompt={}", siliconflowModel, prompt);
            ResponseEntity<String> response = restTemplate.postForEntity(siliconflowEndpoint, entity, String.class);
            if (response.getStatusCodeValue() != 200) {
                log.error("硅基流动 HTTP {}: {}", response.getStatusCodeValue(), truncate(response.getBody(), 500));
                return null;
            }

            JSONObject r = JSON.parseObject(response.getBody());
            // 兼容 images 或 data 数组
            JSONArray images = r.getJSONArray("images");
            if (images == null) images = r.getJSONArray("data");
            if (images == null || images.isEmpty()) {
                log.error("硅基流动返回无图片: {}", truncate(response.getBody(), 500));
                return null;
            }
            JSONObject first = images.getJSONObject(0);

            // base64 优先
            String b64 = first.getString("b64_json");
            if (b64 == null || b64.isBlank()) b64 = first.getString("base64");
            if (b64 != null && !b64.isBlank()) {
                byte[] bytes = decodeBase64Image(b64);
                if (bytes != null) {
                    log.info("✅ 硅基流动文生图成功（base64）| {}KB", bytes.length / 1024);
                    return bytes;
                }
            }
            // URL 兜底
            String url = first.getString("url");
            if (url != null && !url.isBlank()) {
                byte[] bytes = downloadFromOssUrl(url);
                if (bytes != null) {
                    log.info("✅ 硅基流动文生图成功（URL）| {}KB", bytes.length / 1024);
                    return bytes;
                }
            }
            log.error("硅基流动文生图：结果为空");
            return null;
        } catch (Exception e) {
            log.error("❌ 硅基流动文生图失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从OSS预签名链接下载图片
     * 注意：OSS预签名URL对请求头极其敏感，只能使用原生裸GET（不能通过RestTemplate，
     *      因其会携带默认Header破坏签名校验）
     * @param url 图片OSS访问地址
     * @return 图片字节数组
     */
    private byte[] downloadFromOssUrl(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.connect();

            int code = conn.getResponseCode();
            if (code == 200) {
                try (InputStream in = conn.getInputStream()) {
                    byte[] body = in.readAllBytes();
                    if (body.length > 0) {
                        log.info("✅ OSS 下载成功 (原生 URL 裸 GET) | {}KB", body.length / 1024);
                        return body;
                    }
                }
            } else {
                log.warn("OSS 下载返回 HTTP {}", code);
            }
            conn.disconnect();
        } catch (Exception e) {
            log.warn("OSS 下载失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Base64字符串解码为图片二进制数据
     * @param base64Data Base64编码字符串
     * @return 解码后字节数组
     */
    private static byte[] decodeBase64Image(String base64Data) {
        if (base64Data == null || base64Data.isBlank()) return null;

        String data = base64Data.trim();
        if (data.contains(",")) {
            data = data.substring(data.indexOf(",") + 1);
        }

        try {
            return java.util.Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            log.error("Base64 解码失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 日志字符串长度截断工具
     * @param text 原始字符串
     * @param maxLen 最大展示长度
     * @return 精简后字符串
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
