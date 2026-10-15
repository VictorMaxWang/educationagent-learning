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
import java.util.Set;

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
    private static final Set<String> READ_PATHS = Set.of("v3/ip", "v3/place/text", "v3/place/around", "v3/place/detail",
            "v3/geocode/geo", "v3/geocode/regeo", "v3/direction/driving", "v3/direction/walking",
            "v3/direction/transit/integrated", "v3/distance", "v3/config/district", "v3/weather/weatherInfo");

    @GetMapping("/proxy")
    public ResponseEntity<?> proxy(@RequestParam String path, @RequestParam Map<String, String> params) {
        // 公网只代理现有地图/健康页面需要的只读接口，不接收任意上游路径或 URL。
        if (path == null || !READ_PATHS.contains(path)) {
            return ResponseEntity.badRequest().body(Map.of("status", "0", "info", "非法 path"));
        }
        try {
            UriComponentsBuilder ub = UriComponentsBuilder.fromHttpUrl(AMAP_HOST + "/" + path)
                    .queryParam("key", amapKey);
            params.forEach((k, v) -> {
                if (!Set.of("path", "key", "sig", "jscode", "callback").contains(k.toLowerCase(java.util.Locale.ROOT)) && v != null)
                    ub.queryParam(k, v);
            });
            URI uri = ub.build().encode().toUri();
            log.info("🗺️ 高德只读代理: {}", path);
            String json = restTemplate.getForObject(uri, String.class);
            JSONObject obj = json == null ? null : JSON.parseObject(json);
            if (obj == null) {
                return ResponseEntity.status(500).body(Map.of("status", "0", "info", "高德无响应"));
            }
            return ResponseEntity.ok(obj);
        } catch (Exception e) {
            // HTTP 异常常携带含 key 的完整请求 URL，不能写日志或返回浏览器。
            log.warn("高德只读代理失败: path={}, type={}", path, e.getClass().getSimpleName());
            return ResponseEntity.status(502).body(Map.of("status", "0", "info", "地图服务暂时不可用，请稍后重试"));
        }
    }
}
