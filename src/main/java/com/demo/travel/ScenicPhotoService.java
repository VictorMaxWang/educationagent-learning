package com.demo.travel;

import com.demo.config.AiChatProperties;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.util.CnToEnUtil;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 景点拍照参考服务：中英翻译 + Pexels 实景图 + LLM 拍照攻略。
 * <p>
 * 输入景点名（+拍照风格）→ 返回该景点人像打卡参考图 URL 列表 + 拍照姿势/机位/构图/时段攻略；
 * 图片不足 3 张时给出兜底提示文案。
 */
@Slf4j
@Service
public class ScenicPhotoService {

    @Resource
    private AiChatProperties aiChat;

    @Resource
    private RealImageSearchService realImageSearchService;

    @Resource
    private CnToEnUtil cnToEnUtil;

    @Resource
    @Qualifier("llmRestTemplate")
    private RestTemplate restTemplate;

    /** 拍照参考结果 */
    @Data
    public static class ScenicPhotoResult {
        private List<String> imageUrls;
        private String guideContent;
        private String tip; // 图片不足时的兜底提示
    }

    /**
     * 生成景点拍照参考。
     *
     * @param scenicName 景点名（中文）
     * @param style      拍照风格（氛围感/古风/胶片清新…，可为空）
     */
    public ScenicPhotoResult getReference(String scenicName, String style) {
        ScenicPhotoResult r = new ScenicPhotoResult();
        r.setImageUrls(new ArrayList<>());
        try {
            // 图源用 Wikimedia Commons（Pexels 在国内不可达，已弃用）。先英文名，再中文名。
            String en = cnToEnUtil.translateToEnglish(scenicName);
            if (en == null || en.isBlank()) en = scenicName;
            List<String> urls = realImageSearchService.searchPhotoUrlsWikimedia(en, 6);
            if (urls.size() < 3) {
                List<String> cn = realImageSearchService.searchPhotoUrlsWikimedia(scenicName, 6);
                for (String u : cn) {
                    if (!urls.contains(u)) urls.add(u);
                }
            }
            r.setImageUrls(urls);
            if (urls.size() < 3) {
                r.setTip("暂无充足实景参考图片，下方为文字拍照攻略");
            }
        } catch (Exception e) {
            log.error("📷 拍照参考图片获取失败", e);
        }
        // 3. LLM 生成拍照攻略（失败也返回提示文案，不空）
        r.setGuideContent(generateGuide(scenicName, style));
        return r;
    }

    /** DeepSeek 生成打卡拍照指南 */
    private String generateGuide(String scenicName, String style) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是旅行摄影向导。针对【").append(scenicName).append("】");
        if (style != null && !style.isBlank()) {
            sb.append("，拍照风格偏好：").append(style);
        }
        sb.append("，生成简洁实用的打卡拍照指南，分条展示：\n")
          .append("①推荐机位（尽量避开人群）\n")
          .append("②4 组实用人像姿势\n")
          .append("③构图小贴士\n")
          .append("④最佳拍摄时段\n")
          .append("每条 1-2 句，中文，Markdown 无序列表。");
        try {
            JSONObject reqBody = new JSONObject();
            aiChat.configureRequest(reqBody);
            reqBody.put("temperature", 0.5);
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", sb.toString());
            reqBody.put("messages", new Object[]{msg});

            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", "Bearer " + aiChat.getApiKey());
            headers.add("Content-Type", "application/json");
            HttpEntity<String> entity = new HttpEntity<>(reqBody.toJSONString(), headers);

            ResponseEntity<String> resp = restTemplate.postForEntity(aiChat.getCompletionsUrl(), entity, String.class);
            JSONObject result = JSON.parseObject(resp.getBody());
            String content = result.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            return content == null || content.isBlank() ? "暂无拍照攻略，换个景点试试" : content.trim();
        } catch (Exception e) {
            log.error("📷 拍照攻略生成失败: {}", e.getMessage());
            return "拍照攻略生成失败，请稍后再试";
        }
    }
}
