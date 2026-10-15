package com.demo.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 语音合成服务（TTS- Text To Speech）
 * 对接腾讯云 TTS 接口，直接输出 MP3（微信可播放格式）
 * 由 WechatMsgListener 通过 sendFile 发送。
 * 音色更换时注意同步调整 tencent.tts.sample-rate 配置。
 */
@Slf4j
@Service
public class TtsService {

    // 腾讯云 TTS 接口常量
    private static final String ENDPOINT = "tts.tencentcloudapi.com";
    private static final String SERVICE = "tts";
    private static final String VERSION = "2019-08-23";
    private static final String ACTION = "TextToVoice";
    private static final String ALGORITHM = "TC3-HMAC-SHA256";

    // 按中文限制保守分段，也适用于中英混排；任何调用都不能静默丢掉后半段。
    private static final int MAX_TEXT_LENGTH = 150;

    @Value("${tencent.asr.secret-id:}")
    private String secretId;

    @Value("${tencent.asr.secret-key:}")
    private String secretKey;

    @Value("${tencent.tts.voice-type:0}")
    private String voiceType;

    @Value("${tencent.tts.sample-rate:24000}")
    private int sampleRate;

    @Value("${tencent.tts.region:ap-guangzhou}")
    private String region;

    // 服务初始化状态标记
    private boolean initialized = false;

    // 注入自定义HTTP请求工具
    @Resource
    @Qualifier("visionRestTemplate")
    private RestTemplate restTemplate;


    public int getSampleRate() {
        return sampleRate;
    }

    /** 仅表示凭证已配置，不表示已探测额度或云端连通性。 */
    public boolean isAvailable() {
        return initialized;
    }

    /**
     * 项目启动自动初始化TTS服务
     * 校验密钥配置是否齐全，判断服务是否可用
     */
    @PostConstruct
    public void init() {
        if (secretId == null || secretId.isBlank()
                || secretKey == null || secretKey.isBlank()
                || secretId.startsWith("${")) {
            log.warn("⚠️ 腾讯云 TTS 未配置（tencent.asr.secret-id/secret-key），语音合成不可用");
            return;
        }
        initialized = true;
        log.info("🔊 腾讯云 TTS 就绪（voiceType={}, sampleRate={}, region={}）", voiceType, sampleRate, region);
    }

    /**
     * 文本转语音（直接返回 MP3），使用默认音色
     * @param text 要合成的文本内容
     * @return MP3二进制字节数组，失败返回 null
     */
    public byte[] synthesize(String text) {
        return synthesize(text, this.voiceType);
    }

    /**
     * 文本转语音（直接返回 MP3），指定音色
     * @param text      要合成的文本内容
     * @param voiceType 腾讯云 TTS 音色 ID
     * @return MP3二进制字节数组，失败返回 null
     */
    public byte[] synthesize(String text, String voiceType) {
        return synthesize(text, voiceType, sampleRate);
    }

    /** 指定采样率，供课堂角色使用独立音色配置，不修改用户的聊天设置。 */
    public byte[] synthesize(String text, String voiceType, int outputSampleRate) {
        if (!initialized) {
            log.warn("腾讯云 TTS 未初始化，跳过语音合成");
            return null;
        }
        if (text == null || text.isBlank()) {
            log.warn("TTS 文本为空，跳过合成");
            return null;
        }

        // 旧调用方仍得到完整 MP3；课堂则逐段调用并顺序播放，避免长课文被截断。
        if (text.length() > MAX_TEXT_LENGTH) {
            return synthesizeLong(text, voiceType, outputSampleRate);
        }
        String inputText = text;

        log.info("🔊 TTS 合成：{} 字, voiceType={}", inputText.length(), voiceType);

        try {
            // 组装TTS接口请求参数，指定输出格式为MP3
            JSONObject params = new JSONObject();
            params.put("Text", inputText);
            params.put("SessionId", java.util.UUID.randomUUID().toString().replace("-", ""));
            params.put("VoiceType", Integer.parseInt(voiceType));
            params.put("SampleRate", outputSampleRate);
            params.put("Codec", "mp3");

            String payload = params.toJSONString();

            // 生成TC3标准请求签名，腾讯云接口鉴权必备
            long timestamp = System.currentTimeMillis() / 1000;
            String date = formatDate(timestamp);
            String canonicalRequest = buildCanonicalRequest(payload);
            String credentialScope = date + "/" + SERVICE + "/tc3_request";
            String stringToSign = ALGORITHM + "\n" + timestamp + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);
            String signature = sign(stringToSign, date);
            String signedHeaders = "content-type;host";
            String authorization = ALGORITHM + " "
                    + "Credential=" + secretId + "/" + credentialScope + ", "
                    + "SignedHeaders=" + signedHeaders + ", "
                    + "Signature=" + signature;

            // 组装HTTP请求头，携带鉴权信息与接口请求标识
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Host", ENDPOINT);
            headers.set("X-TC-Action", ACTION);
            headers.set("X-TC-Timestamp", String.valueOf(timestamp));
            headers.set("X-TC-Version", VERSION);
            headers.set("X-TC-Region", region);
            headers.set("Authorization", authorization);

            // 发起POST请求调用腾讯云TTS接口
            String url = "https://" + ENDPOINT;
            HttpEntity<String> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            // 校验HTTP请求状态码
            if (response.getStatusCodeValue() != 200) {
                log.error("腾讯云 TTS 返回 HTTP {}: {}", response.getStatusCodeValue(), truncate(response.getBody(), 500));
                return null;
            }

            // 解析接口返回数据
            JSONObject result = JSON.parseObject(response.getBody());
            JSONObject resp = result.getJSONObject("Response");
            if (resp == null) {
                log.error("腾讯云 TTS 响应缺少 Response");
                return null;
            }

            // 捕获接口业务异常
            JSONObject error = resp.getJSONObject("Error");
            if (error != null) {
                String code = error.getString("Code");
                String message = error.getString("Message");
                log.error("腾讯云 TTS 错误: {} - {}", code, message);
                if ("PkgExhausted".equals(code) || "ResourceUnavailable.PackageExhausted".equals(code)) {
                    log.warn("💸 腾讯云 TTS 额度已用完，请在控制台续费后重试");
                }
                return null;
            }

            // 云端合成语音 → Base64 字符串返回 → 本地解码 → MP3 字节流 → 微信发送语音消息
            // 解析Base64编码的音频数据，解码为MP3二进制文件流
            String audioBase64 = resp.getString("Audio");
            if (audioBase64 == null || audioBase64.isBlank()) {
                log.error("腾讯云 TTS 响应缺少 Audio 字段");
                return null;
            }

            byte[] mp3Data = Base64.getDecoder().decode(audioBase64);
            log.info("✅ TTS 完成：{} 字 → MP3 {}KB", inputText.length(), mp3Data.length / 1024);
            return mp3Data;

        } catch (Exception e) {
            log.error("❌ TTS 合成异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 长文本分块合成（>150 字）：按句子边界切段逐段合成并拼接 MP3。
     * 用于听力材料等长文本的语音合成；失败返回 null。
     */
    public byte[] synthesizeLong(String text) {
        return synthesizeLong(text, this.voiceType);
    }

    /** 长文本分块合成，指定音色 */
    public byte[] synthesizeLong(String text, String voiceType) {
        return synthesizeLong(text, voiceType, sampleRate);
    }

    private byte[] synthesizeLong(String text, String voiceType, int outputSampleRate) {
        if (!initialized) return null;
        if (text == null || text.isBlank()) return null;
        List<String> segments = splitForTts(text);
        List<byte[]> parts = new ArrayList<>();
        int total = 0;
        for (String seg : segments) {
            // 纯空白不包含可朗读内容；保留拆分结果中的空白，但无需向云端提交。
            if (seg.isBlank()) continue;
            byte[] mp3 = synthesize(seg, voiceType, outputSampleRate);
            // 丢掉中间失败段会制造“成功但漏讲”的音频，必须让调用方明确重试。
            if (mp3 == null || mp3.length == 0) return null;
            parts.add(mp3);
            total += mp3.length;
        }
        if (parts.isEmpty()) return null;
        byte[] result = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, result, off, p.length);
            off += p.length;
        }
        log.info("🔊 长文本合成完成：{} 字 → {} 段 MP3 {}KB", text.length(), parts.size(), result.length / 1024);
        return result;
    }

    /**
     * 保留全文顺序和空白，优先在句末断开；无标点也保证每段不超过 150 个 UTF-16 单元。
     * 按码点遍历避免把表情等代理对从中间切开，课堂可复用同一边界生成逐句播放列表。
     */
    public static List<String> splitForTts(String text) {
        List<String> segs = new ArrayList<>();
        if (text == null || text.isEmpty()) return segs;
        StringBuilder sb = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int c = text.codePointAt(offset);
            int width = Character.charCount(c);
            if (sb.length() + width > MAX_TEXT_LENGTH) {
                segs.add(sb.toString());
                sb.setLength(0);
            }
            sb.appendCodePoint(c);
            offset += width;
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == '\n') {
                segs.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) segs.add(sb.toString());
        return segs;
    }

    // ========== TC3-HMAC-SHA256 签名实现 ==========
    //构建规范请求字符串，用于签名计算
    private String buildCanonicalRequest(String payload) {
        return "POST\n/\n\ncontent-type:application/json\nhost:" + ENDPOINT + "\n\ncontent-type;host\n" + sha256Hex(payload);
    }

    // 分层计算TC3标准签名
    private String sign(String stringToSign, String date) {
        try {
            byte[] secretDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
            byte[] secretService = hmacSha256(secretDate, SERVICE);
            byte[] secretSigning = hmacSha256(secretService, "tc3_request");
            byte[] signature = hmacSha256(secretSigning, stringToSign);
            return bytesToHex(signature);
        } catch (Exception e) {
            throw new RuntimeException("TC3 签名失败", e);
        }
    }

    // SHA256哈希加密
    private static String sha256Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return bytesToHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 计算失败", e);
        }
    }

    // HmacSHA256加密算法
    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(key, "HmacSHA256");
            mac.init(keySpec);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 计算失败", e);
        }
    }

    // 字节数组转十六进制字符串
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    // 格式化UTC标准日期，用于签名
    private static String formatDate(long timestamp) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new java.util.Date(timestamp * 1000));
    }

    // 文本长度截断工具，精简日志输出内容
    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
