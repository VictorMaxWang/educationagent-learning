package com.demo.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 阿里云 Qwen-TTS 原生协议，支持官方百炼和用户已授权的 MaaS 渠道。
 * 两个渠道的凭证分别读取、分别校验端点，不能把 MaaS Key 发给官方百炼。
 * 非流式输出为 WAV；下载不附带 API Key，也不记录签名 URL 或错误正文。
 */
@Slf4j
@Service
public class AliyunTtsService {
    public static final int MAX_TEXT_LENGTH = 600;
    public static final int MAX_AUDIO_BYTES = 12 * 1024 * 1024;
    public static final String DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    public static final String MAAS_ENDPOINT = "https://maas.qianwenaiapi.com/api/v1/services/aigc/multimodal-generation/generation";
    public static final String DEFAULT_MODEL = "qwen3-tts-flash";
    private final RestTemplate http;
    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final String provider;

    public AliyunTtsService(@Qualifier("websiteSpeechRestTemplate") RestTemplate http,
            @Value("${website.tts.provider:maas-qwen}") String provider,
            @Value("${website.tts.aliyun.api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${website.tts.maas.api-key:${AI_CHAT_API_KEY:}}") String maasKey,
            @Value("${website.tts.aliyun.endpoint:" + DEFAULT_ENDPOINT + "}") String endpoint,
            @Value("${website.tts.maas.endpoint:" + MAAS_ENDPOINT + "}") String maasEndpoint,
            @Value("${website.tts.model:" + DEFAULT_MODEL + "}") String model) {
        this.http = http;
        this.provider = provider == null ? "maas-qwen" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        String selectedKey = "maas-qwen".equals(this.provider) ? maasKey : apiKey;
        this.apiKey = selectedKey == null ? "" : selectedKey.trim();
        this.endpoint = "maas-qwen".equals(this.provider) ? maasEndpoint : endpoint;
        this.model = model;
    }

    /** 配置检查不调用计费接口，不能据此声称云端额度或网络已验证。 */
    public boolean isAvailable() {
        // sk-ws- 属于当前项目的第三方 MaaS 凭据，绝不能发送给官方百炼域名。
        return !apiKey.isBlank() && !apiKey.startsWith("${")
                && (!"aliyun".equals(provider) || !apiKey.startsWith("sk-ws-")) && validEndpoint(endpoint)
                && model != null && !model.isBlank();
    }

    public String cacheIdentity() { return provider + "\n" + endpoint + "\n" + model; }
    public String model() { return model; }

    /** 合成单段中文 WAV；调用方负责保留分段顺序，失败不替换为另一家音色。 */
    public byte[] synthesizeWav(String text, String voice) {
        if (!isAvailable() || text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH
                || voice == null || voice.isBlank()) return null;
        try {
            JSONObject input = new JSONObject();
            input.put("text", text);
            input.put("voice", voice);
            input.put("language_type", "Chinese");
            JSONObject payload = new JSONObject();
            payload.put("model", model);
            payload.put("input", input);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            ResponseEntity<String> response = http.postForEntity(endpoint,
                    new HttpEntity<>(payload.toJSONString(), headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) return null;
            JSONObject body = JSON.parseObject(response.getBody());
            if (body == null || (body.containsKey("status_code") && body.getIntValue("status_code") != 200)
                    || (body.getString("code") != null && !body.getString("code").isBlank())) return null;
            JSONObject output = body.getJSONObject("output");
            JSONObject audio = output == null ? null : output.getJSONObject("audio");
            String url = audio == null ? null : audio.getString("url");
            URI download = trustedAudioUrl(url);
            if (download == null) return null;
            byte[] wav = http.execute(download, HttpMethod.GET, request -> {
                request.getHeaders().setAccept(java.util.List.of(MediaType.parseMediaType("audio/wav")));
            }, result -> {
                if (!result.getStatusCode().is2xxSuccessful()
                        || result.getHeaders().getContentLength() > MAX_AUDIO_BYTES) return null;
                byte[] bytes = result.getBody().readNBytes(MAX_AUDIO_BYTES + 1);
                return bytes.length <= MAX_AUDIO_BYTES ? bytes : null;
            });
            if (!isWav(wav)) {
                log.warn("阿里云 TTS 未返回有效 WAV，未写入音频缓存");
                return null;
            }
            log.info("阿里云 TTS 完成：{} 字，voice={}，WAV {}KB", text.length(), voice, wav.length / 1024);
            return wav;
        } catch (Exception e) {
            // 云端错误可能含原文、API Key 或带签名地址，日志只保留异常类型。
            log.warn("阿里云 TTS 失败：{}", e.getClass().getSimpleName());
            return null;
        }
    }

    private boolean validEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && (("aliyun".equals(provider) && Set.of("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com").contains(uri.getHost()))
                        || ("maas-qwen".equals(provider) && "maas.qianwenaiapi.com".equals(uri.getHost())))
                    && "/api/v1/services/aigc/multimodal-generation/generation".equals(uri.getPath())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (RuntimeException e) { return false; }
    }

    static URI trustedAudioUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            // 官方示例会返回 http 的 OSS 地址；同一签名路径升级到 HTTPS 后下载。
            URI uri = URI.create(value.startsWith("http://") ? "https://" + value.substring(7) : value);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawFragment() == null
                    && host != null && host.matches("[a-z0-9-]+\\.oss-[a-z0-9-]+\\.aliyuncs\\.com") ? uri : null;
        } catch (RuntimeException e) { return null; }
    }

    static boolean isWav(byte[] bytes) {
        return bytes != null && bytes.length > 44
                && "RIFF".equals(new String(bytes, 0, 4, StandardCharsets.US_ASCII))
                && "WAVE".equals(new String(bytes, 8, 4, StandardCharsets.US_ASCII));
    }
}
