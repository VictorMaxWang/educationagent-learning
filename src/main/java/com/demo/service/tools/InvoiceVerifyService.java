package com.demo.service.tools;

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

import java.util.Map;

/**
 * 票据核验服务 —— 后端转发第三方税务查验接口。
 * <p>
 * 前端 OCR 识别出结构化票面字段后，组装核验必填参数（发票代码/号码/开票日期/校验码/金额），
 * 由本服务转发到第三方查验 API，返回核验结果（发票状态、票面信息等）。
 * <p>
 * 密钥只存后端，前端零暴露。鉴权参数名默认 {@code key}，如接口不同可在此调整。
 */
@Slf4j
@Service
public class InvoiceVerifyService {

    @Value("${invoice.verify.api-url:}")
    private String apiUrl;

    @Value("${invoice.verify.api-key:}")
    private String apiKey;

    @Value("${invoice.verify.api-secret:}")
    private String apiSecret;

    @Resource
    private RestTemplate restTemplate;

    /** 发票验真固定类型 ID */
    private static final String TYPE_ID = "3007";

    /**
     * 核验票据（网OCR/翔云 verInvoice.do 接口格式）。
     *
     * @param params 核验参数：invoiceNumber、invoiceCode、billingDate、totalAmount、checkCode 等
     * @return 第三方查验接口原始 JSON 字符串
     */
    public String verify(Map<String, String> params) {
        if (!isConfigured()) {
            throw new IllegalStateException("票据核验未配置，请设置 invoice.verify.api-url / api-key");
        }
        if (params == null || params.isEmpty()) {
            throw new IllegalArgumentException("核验参数为空");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        params.forEach(body::add);
        body.add("key", apiKey);
        if (apiSecret != null && !apiSecret.isBlank()) {
            body.add("secret", apiSecret);
        }
        body.add("typeId", TYPE_ID);  // 发票验真固定值 3007
        body.add("format", "json");   // 返回 JSON

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, entity, String.class);

        String respBody = response.getBody();
        log.info("🔍 票据核验返回: HTTP {}, {} 字节, 内容={}", response.getStatusCodeValue(),
                respBody != null ? respBody.length() : 0, truncate(respBody, 500));
        return respBody;
    }

    private boolean isConfigured() {
        return apiUrl != null && !apiUrl.isBlank() && !apiUrl.startsWith("${")
                && apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("${");
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
