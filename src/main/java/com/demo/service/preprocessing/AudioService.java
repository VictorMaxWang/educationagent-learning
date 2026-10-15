package com.demo.service.preprocessing;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 语音识别服务
 * 对接腾讯云ASR一句话识别接口
 * 适配微信AMR、SILK语音格式，无需额外转码
 * 采用TC3-HMAC-SHA256加密签名方式完成接口鉴权
 */
@Slf4j
@Service
public class AudioService {

    // 微信语音编码格式常量
    private static final int ENCODE_AMR = 0;
    private static final int ENCODE_SILK = 1;
    private static final int ENCODE_SILK_V3 = 4;

    // 腾讯云ASR接口固定常量
    private static final String ENDPOINT = "asr.tencentcloudapi.com";
    private static final String SERVICE = "asr";
    private static final String VERSION = "2019-06-14";
    private static final String ACTION = "SentenceRecognition";
    private static final String ALGORITHM = "TC3-HMAC-SHA256";

    @Value("${tencent.asr.secret-id:}")
    private String secretId;

    @Value("${tencent.asr.secret-key:}")
    private String secretKey;

    @Value("${tencent.asr.region:ap-guangzhou}")
    private String region;

    private boolean initialized = false;

    /** 仅表示配置完整；健康检查不会调用计费识别接口。 */
    public boolean isAvailable() {
        return initialized;
    }

    @Resource
    @Qualifier("visionRestTemplate")
    private RestTemplate restTemplate;

    /**
     * 项目初始化自动执行
     * 校验腾讯云密钥是否配置完成
     */
    @PostConstruct
    public void init() {
        if (secretId == null || secretId.isBlank()
                || secretKey == null || secretKey.isBlank()
                || secretId.startsWith("${")) {
            log.warn("⚠️ 腾讯云 ASR 未配置（tencent.asr.secret-id/secret-key），语音识别不可用");
            return;
        }
        initialized = true;
        log.info("🎤 腾讯云 ASR 就绪（region={}）", region);
    }

    /**
     * 语音文件转文字核心方法
     * @param audioBytes 语音原始二进制数据
     * @param encodeType 语音编码格式类型
     * @param playtime 语音播放时长
     * @return 识别后的文本内容，异常返回null
     */
    public String transcribe(byte[] audioBytes, int encodeType, int playtime) {
        return transcribe(audioBytes, detectVoiceFormat(encodeType), playtime);
    }

    /**
     * 显式格式入口，供已校验的浏览器 WAV 录音使用；保留原微信编码入口。
     * 腾讯云 SentenceRecognition 限 60 秒，Data 经 Base64 后不超过 3 MB。
     * @param playtime 秒数；旧微信调用无法取得时长时允许 0，由云端再次校验
     * @return 转写文本；配置、格式、大小或云端失败返回 null
     */
    public String transcribe(byte[] audioBytes, String voiceFormat, int playtime) {
        // 判断服务是否初始化成功
        if (!initialized) {
            log.warn("腾讯云 ASR 未初始化，跳过语音识别");
            return null;
        }
        // 校验语音数据是否为空
        if (audioBytes == null || audioBytes.length == 0) return null;
        if (voiceFormat == null || !Set.of("wav", "pcm", "ogg-opus", "speex", "silk", "mp3", "m4a", "aac", "amr").contains(voiceFormat)
                || playtime < 0 || playtime > 60 || 4L * ((audioBytes.length + 2L) / 3) > 3L * 1024 * 1024) {
            log.warn("ASR 输入超出格式、60 秒或 3 MB Base64 限制");
            return null;
        }

        try {
            // 语音字节数据转为Base64编码
            String base64Data = Base64.getEncoder().encodeToString(audioBytes);
            // 生成随机唯一音频标识
            String audioKey = UUID.randomUUID().toString().replace("-", "");

            // 规范语音时长范围
            int realPlaytime = playtime;
            log.info("🎤 腾讯云 ASR 识别：{}s, {}KB, 格式={}", realPlaytime, audioBytes.length / 1024, voiceFormat);

            // 组装腾讯云ASR接口请求参数
            JSONObject params = new JSONObject();
            params.put("ProjectId", 0);
            params.put("SubServiceType", 2);
            params.put("EngSerViceType", "16k_zh");
            params.put("SourceType", 1);
            params.put("VoiceFormat", voiceFormat);
            params.put("UsrAudioKey", audioKey);
            params.put("Data", base64Data);
            params.put("DataLen", audioBytes.length);

            // 请求参数转为JSON字符串
            String payload = params.toJSONString();

            // ========== TC3-HMAC-SHA256 签名 ==========
            long timestamp = System.currentTimeMillis() / 1000;
            String date = formatDate(timestamp);

            // 1.构建规范请求字符串，用于加密签名
            String canonicalRequest = buildCanonicalRequest(payload);
            log.debug("CanonicalRequest:\n{}", canonicalRequest);

            // 2.拼接凭证作用域
            String credentialScope = date + "/" + SERVICE + "/tc3_request";
            // 拼接待签名字符串
            String stringToSign = ALGORITHM + "\n"
                    + timestamp + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest);
            log.debug("StringToSign:\n{}", stringToSign);

            // 3.计算最终加密签名
            String signature = sign(stringToSign, date);

            // 4.定义参与签名的请求头
            String signedHeaders = "content-type;host";
            // 拼接完整授权认证头部
            String authorization = ALGORITHM + " "
                    + "Credential=" + secretId + "/" + credentialScope + ", "
                    + "SignedHeaders=" + signedHeaders + ", "
                    + "Signature=" + signature;

            // ========== 发送请求 ==========
            // 组装HTTP请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Host", ENDPOINT);
            headers.set("X-TC-Action", ACTION);
            headers.set("X-TC-Timestamp", String.valueOf(timestamp));
            headers.set("X-TC-Version", VERSION);
            headers.set("X-TC-Region", region);
            headers.set("Authorization", authorization);

            // 发送POST请求调用接口
            String url = "https://" + ENDPOINT;
            HttpEntity<String> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            int statusCode = response.getStatusCodeValue();
            String responseBody = response.getBody();

            // 判断接口响应状态码
            if (statusCode != 200) {
                log.error("腾讯云 ASR 返回 HTTP {}: {}", statusCode, truncate(responseBody, 1000));
                return null;
            }

            // ========== 解析响应 ==========
            JSONObject result = JSON.parseObject(responseBody);
            JSONObject resp = result.getJSONObject("Response");
            if (resp == null) {
                log.error("腾讯云 ASR 响应缺少 Response: {}", truncate(responseBody, 500));
                return null;
            }

            // 检查错误
            JSONObject error = resp.getJSONObject("Error");
            if (error != null) {
                log.error("腾讯云 ASR 业务错误: {} - {}",
                        error.getString("Code"), error.getString("Message"));
                return null;
            }

            // 提取最终识别文本
            String text = resp.getString("Result");
            if (text == null || text.isBlank()) {
                log.error("腾讯云 ASR 转写结果为空");
                return null;
            }

            // 课堂口述可能涉及未成年人隐私，不把转写正文写入运行日志。
            log.info("✅ 语音转文字完成：{} 字", text.length());
            return text;

        } catch (Exception e) {
            log.error("❌ 语音识别异常: {}", e.getMessage());
            return null;
        }
    }

    // ========== TC3-HMAC-SHA256 签名实现 ==========
    // 构建标准化请求报文，签名前置处理
    private String buildCanonicalRequest(String payload) {
        return "POST\n"
                + "/\n"
                + "\n"
                + "content-type:application/json\n"
                + "host:" + ENDPOINT + "\n"
                + "\n"
                + "content-type;host\n"
                + sha256Hex(payload);
    }

    // 三层HmacSHA256加密生成最终签名
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

    // 对字符串进行SHA256哈希加密
    private static String sha256Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return bytesToHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 计算失败", e);
        }
    }

    //HmacSHA256加密工具方法
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

    // 字节数组转为十六进制字符串
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    // 时间戳转为UTC标准年月日格式
    private static String formatDate(long timestamp) {
        // 返回 YYYY-MM-DD 格式的日期字符串
        // 使用 SimpleDateFormat 避免依赖
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new java.util.Date(timestamp * 1000));
    }

    // ========== 工具方法 ==========
    // 根据传入类型匹配腾讯云标准语音格式名
    private static String detectVoiceFormat(int encodeType) {
        if (encodeType == ENCODE_AMR) return "amr";
        return "silk";
    }

    // 字符串长度截断，精简日志输出
    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
