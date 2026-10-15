package com.demo.travel;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.util.CnToEnUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 实景图搜索服务 —— 调用 Pexels API 搜索真实照片
 * 借助 CnToEnUtil 将中文描述转为英文关键词，再用英文搜索 Pexels 图库
 * Pexels 提供免费高清真实图库，每日免费额度 200 次
 */
@Slf4j
@Service
public class RealImageSearchService {

    private static final String SEARCH_URL = "https://api.pexels.com/v1/search";

    @Value("${pexels.api.key:}")
    private String apiKey;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private CnToEnUtil cnToEnUtil;

    /**
     * 搜索真实照片，返回第一张图片的字节数据
     * @param query 用户输入的搜索内容（支持中文）
     * @return 图片字节数据（JPEG），失败返回 null
     */
    public byte[] searchPhoto(String query) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            log.warn("⚠️ Pexels API Key 未配置，请在 application-dev.properties 中设置 pexels.api.key");
            return null;
        }
        if (query == null || query.isBlank()) {
            log.warn("搜索关键词为空");
            return null;
        }

        // 1. 借助 CnToEnUtil 将中文描述转为英文搜索关键词
        String englishQuery = cnToEnUtil.translateToEnglish(query);
        if (englishQuery == null || englishQuery.isBlank()) {
            log.warn("翻译失败，使用原始查询词");
            englishQuery = query;
        }
        log.info("📸 中文「{}」→ 英文「{}」", query, englishQuery);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", apiKey);

        try {
            String encodedQuery = java.net.URLEncoder.encode(englishQuery, "UTF-8");
            URI uri = URI.create(SEARCH_URL + "?query=" + encodedQuery + "&per_page=1");

            log.info("📸 搜索 Pexels：{}", englishQuery);

            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (response.getStatusCodeValue() != 200) {
                log.error("Pexels 请求失败 HTTP {}: {}", response.getStatusCodeValue(), response.getBody());
                return null;
            }

            JSONObject json = JSONObject.parseObject(response.getBody());
            JSONArray photos = json.getJSONArray("photos");
            if (photos == null || photos.isEmpty()) {
                log.warn("未找到「{}」的实景图片", englishQuery);
                return null;
            }

            // 取第一张图片的中等尺寸 URL
            JSONObject firstPhoto = photos.getJSONObject(0);
            JSONObject src = firstPhoto.getJSONObject("src");
            String imageUrl = src.getString("medium");
            if (imageUrl == null || imageUrl.isBlank()) {
                imageUrl = src.getString("original");
            }

            log.info("📸 找到图片，下载中：{}", imageUrl);
            byte[] imageBytes = downloadImage(imageUrl);

            if (imageBytes != null) {
                log.info("✅ 实景图下载成功 | {}KB", imageBytes.length / 1024);
            }
            return imageBytes;

        } catch (Exception e) {
            log.error("📸 实景图搜索失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 图源：Wikimedia Commons 图片搜索（免费、无需 key；Pexels 国内不可达已弃用）。
     *
     * @param keyword 搜索关键词（英文或中文均可）
     * @param perPage 期望张数
     */
    public List<String> searchPhotoUrlsWikimedia(String keyword, int perPage) {
        List<String> urls = new ArrayList<>();
        if (keyword == null || keyword.isBlank()) return urls;
        String body = null;
        try {
            String encoded = java.net.URLEncoder.encode(keyword, "UTF-8");
            String url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search"
                    + "&gsrsearch=" + encoded
                    + "&gsrnamespace=6"
                    + "&gsrlimit=" + Math.min(perPage, 10)
                    + "&prop=imageinfo&iiprop=url&iiurlwidth=640&format=json";
            log.info("📸 兜底搜索 Wikimedia：{}", keyword);
            // Wikimedia 要求规范 User-Agent，否则 403
            HttpHeaders wmHeaders = new HttpHeaders();
            wmHeaders.set("User-Agent", "WeChatTravelAssistant/1.0 (outfit photo reference; contact: 2283625346@qq.com)");
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(wmHeaders), String.class);
            body = response.getBody();
            if (response.getStatusCodeValue() != 200 || body == null || body.isBlank()) {
                return urls;
            }
            JsonNode pages = objectMapper.readTree(body).path("query").path("pages");
            if (!pages.isObject()) return urls;
            pages.forEach(p -> {
                if (urls.size() >= Math.min(perPage, 10)) return;
                String thumb = p.path("imageinfo").path(0).path("thumburl").asText("");
                if (thumb.isEmpty()) thumb = p.path("imageinfo").path(0).path("url").asText("");
                if (!thumb.isEmpty()) urls.add(thumb);
            });
            log.info("📸 Wikimedia 返回 {} 张图片 URL", urls.size());
            return urls;
        } catch (Exception e) {
            log.error("📸 Wikimedia 图片搜索失败: {} body={}", e.getMessage(), truncate(body, 200));
            return urls;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 下载图片字节
     */
    private byte[] downloadImage(String imageUrl) {
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    URI.create(imageUrl),
                    HttpMethod.GET,
                    null,
                    byte[].class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("图片下载失败: {}", e.getMessage());
            return null;
        }
    }
}
