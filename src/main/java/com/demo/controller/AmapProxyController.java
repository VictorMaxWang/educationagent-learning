package com.demo.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * 高德 Web 服务 API 代理（桌面端地图用）。
 * <p>
 * 背景：Web端(JS API) key 在浏览器里直调 restapi.amap.com 会被判 USERKEY_PLAT_NOMATCH，
 * 且自定义安全密钥头被 CORS 预检拦截；而 JS 方向类插件在 Tauri webview 下回调不触发。
 * 故统一走本代理：服务端用 Web服务 key（amap.key）调用高德，返回原始 JSON，前端再绘制。
 * <p>
 * 用法：GET /api/desktop/amap/proxy?path=v3/direction/driving&origin=120.1,30.2&destination=118.7,32.0
 * 注意：必须用 UriComponentsBuilder 生成 URI 传给 RestTemplate，否则 String 方式会二次编码
 * （%E6→%25E6），高德 geocode/direction 直接报 ENGINE_RESPONSE_DATA_ERROR / INVALID_PARAMS。
 * 该接口同时是 health/utils/amap.js 期待已久的代理入口（此前一直 404）。
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop/amap")
public class AmapProxyController {

    @Value("${amap.key}")
    private String amapKey;

    @Resource
    private RestTemplate restTemplate;

    private static final String AMAP_HOST = "https://restapi.amap.com";

    @GetMapping("/proxy")
    public ResponseEntity<?> proxy(@RequestParam String path, @RequestParam Map<String, String> params) {
        // 白名单：只放行 v3/ 下的接口，防 SSRF
        if (path == null || !path.startsWith("v3/") || path.contains("..") || path.contains("://")) {
            return ResponseEntity.badRequest().body(Map.of("status", "0", "info", "非法 path"));
        }
        try {
            UriComponentsBuilder ub = UriComponentsBuilder.fromHttpUrl(AMAP_HOST + "/" + path)
                    .queryParam("key", amapKey);
            params.forEach((k, v) -> {
                if (!"path".equals(k) && v != null) ub.queryParam(k, v);
            });
            URI uri = ub.build().encode().toUri();
            log.info("🗺️ 高德代理: {} | {}", path, uri.getRawQuery());
            String json = restTemplate.getForObject(uri, String.class);
            JSONObject obj = json == null ? null : JSON.parseObject(json);
            if (obj == null) {
                return ResponseEntity.status(500).body(Map.of("status", "0", "info", "高德无响应"));
            }
            return ResponseEntity.ok(obj);
        } catch (Exception e) {
            log.error("高德代理失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("status", "0", "info", "代理异常：" + e.getMessage()));
        }
    }
}
