package com.demo.service.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

/**
 * 百度智能云 OCR —— 通用票据识别（增值税专票/普票、电子发票、折叠票、PDF）。
 * <p>
 * 密钥仅存后端，前端零暴露。access_token 内存缓存复用（百度有效期 30 天，提前 1 小时重刷）。
 * 接口自动结构化返回票面字段（发票代码/号码/税号/金额/开票日期/校验码等），无需本地正则。
 */
@Slf4j
@Service
public class BaiduOcrService {

    @Value("${baidu.ocr.api-key:}")
    private String apiKey;

    @Value("${baidu.ocr.secret-key:}")
    private String secretKey;

    @Resource
    private RestTemplate restTemplate;

    /** 通用票据识别接口（纸质增值税专/普票、老版电子 PDF 发票） */
    private static final String OCR_INVOICE_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/invoice";

    /** 数电票（全电发票/电子发票）专用识别接口 */
    private static final String E_INVOICE_OCR_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/electronic_invoice";

    /** 通用文字识别（兜底：提取电子发票接口漏掉的日期等字段） */
    private static final String GENERAL_OCR_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic";

    /** 鉴权接口 */
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";

    /** access_token 缓存（volatile 保证多线程可见） */
    private volatile String accessToken;
    private volatile long tokenExpireAt;

    /**
     * 识别纸质/老版电子发票，返回百度原始 JSON 字符串（含 words_result 结构化字段）。
     *
     * @param imageBytes 图片二进制（jpg/png/pdf）
     * @return 百度接口原始 JSON
     */
    public String recognizeInvoice(byte[] imageBytes) {
        return postOcr(OCR_INVOICE_URL, imageBytes);
    }

    /**
     * 识别数电票（全电发票/电子发票），返回百度原始 JSON 字符串（含 words_result 结构化字段）。
     * <p>
     * 数电票不能用 invoice 通用接口（只会返回整图零散文字，无结构化字段），必须走本接口。
     *
     * @param imageBytes 图片二进制（jpg/png/pdf）
     * @return 百度接口原始 JSON
     */
    public String recognizeElectronicInvoice(byte[] imageBytes) {
        return postOcr(E_INVOICE_OCR_URL, imageBytes);
    }

    /**
     * 通用文字识别（整图文字）。
     * <p>
     * 用于兜底：电子发票接口可能把日期字段读成"年月日"标签，用通用识别把整图文字拿回来，
     * 再由上层正则提取真实日期。返回 words_result 数组（含 words 文本）。
     *
     * @param imageBytes 图片二进制（jpg/png）
     * @return 百度接口原始 JSON
     */
    public String recognizeGeneral(byte[] imageBytes) {
        return postOcr(GENERAL_OCR_URL, imageBytes);
    }

    /**
     * 调百度 OCR 接口公共逻辑：图片 base64 + access_token，form-urlencoded POST。
     */
    private String postOcr(String url, byte[] imageBytes) {
        if (!isConfigured()) {
            throw new IllegalStateException("百度OCR未配置，请设置 baidu.ocr.api-key / secret-key");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("图片数据为空");
        }

        // 图片转 Base64（百度入参要求，不含 data: 前缀）
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String token = getAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("image", base64);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url + "?access_token=" + token, entity, String.class);

        log.info("🧾 百度OCR({})返回: HTTP {}, {} 字节", url,
                response.getStatusCodeValue(),
                response.getBody() != null ? response.getBody().length() : 0);
        return response.getBody();
    }

    /**
     * 获取百度 access_token（内存缓存复用，提前 1 小时过期重刷）。
     */
    private synchronized String getAccessToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < tokenExpireAt) {
            return accessToken;
        }

        String url = TOKEN_URL + "?grant_type=client_credentials&client_id=" + apiKey
                + "&client_secret=" + secretKey;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        JSONObject json = JSON.parseObject(response.getBody());
        accessToken = json.getString("access_token");
        int expiresIn = json.getIntValue("expires_in", 0);
        // 提前 1 小时过期重刷，避免临界失效
        tokenExpireAt = now + (expiresIn - 3600) * 1000L;
        log.info("🧾 百度OCR access_token 已刷新");
        return accessToken;
    }

    private boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("${")
                && secretKey != null && !secretKey.isBlank() && !secretKey.startsWith("${");
    }
}
