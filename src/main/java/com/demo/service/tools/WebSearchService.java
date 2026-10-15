package com.demo.service.tools;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 网页搜索后端服务
 * 使用百度千帆（百度智能云）网页搜索 API 实现联网搜索
 * 接口：POST https://qianfan.baidubce.com/v2/ai_search/web_search
 * 纯搜索模式（不含大模型总结），仅返回搜索结果给 LLM 自行处理
 * 每日免费额度：100 次
 */
@Slf4j
@Service
public class WebSearchService {

    /** 百度千帆网页搜索 API 端点（纯搜索，无大模型总结） */
    private static final String QIANFAN_SEARCH_URL =
            "https://qianfan.baidubce.com/v2/ai_search/web_search";

    @Value("${qianfan.search.key:}")
    private String apiKey;

    @Resource
    private RestTemplate restTemplate;

    /**
     * 执行网页搜索
     * @param query 搜索关键词
     * @param count 返回结果数量（最多 10 条）
     * @return 格式化搜索结果文本
     */
    public String search(String query, int count) {
        if (query == null || query.trim().isEmpty()) {
            return "错误：搜索关键词不能为空";
        }

        // 检查 API Key 是否配置
        if (apiKey == null || apiKey.isBlank()) {
            return "⚠️ 搜索功能暂未配置 API Key，请在 application-dev.properties 中设置 qianfan.search.key\n"
                    + "获取方式：百度智能云千帆 → AppBuilder → 应用 → API Key";
        }

        // 限制结果数量 1-10 条
        int resultCount = Math.max(1, Math.min(count, 10));

        try {
            log.info("🔍 执行百度千帆搜索：{}（返回 {} 条）", query, resultCount);

            // 构建请求头（Bearer Token 鉴权）
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Appbuilder-Authorization", "Bearer " + apiKey);

            // 构建请求体（使用 OpenAI 兼容格式，与官方文档一致）
            JSONObject requestBody = new JSONObject();
            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", query);
            messages.add(userMsg);
            requestBody.put("messages", messages);
            requestBody.put("search_source", "baidu_search_v2");

            // 搜索资源类型：[{type, top_k}] 格式
            JSONArray resourceTypes = new JSONArray();
            JSONObject webResource = new JSONObject();
            webResource.put("type", "web");
            webResource.put("top_k", resultCount);
            resourceTypes.add(webResource);
            requestBody.put("resource_type_filter", resourceTypes);

            HttpEntity<String> entity = new HttpEntity<>(
                    requestBody.toJSONString(), headers);

            // 发起 POST 请求,entity 请求体
            String responseBody = restTemplate.postForObject(
                    QIANFAN_SEARCH_URL, entity, String.class);

            // 解析响应
            return parseSearchResults(responseBody, query);

        } catch (Exception e) {
            log.error("🔍 百度千帆搜索失败：{}", e.getMessage());
            return "⚠️ 搜索服务暂时不可用，请稍后再试。错误：" + e.getMessage();
        }
    }

    /**
     * 解析百度千帆 web_search 返回结果
     * references[] 数组，每项含标题、URL、摘要、日期、类型
     */
    private String parseSearchResults(String responseBody, String query) {
        JSONObject json = JSONObject.parseObject(responseBody);

        // 提取 references 数组
        JSONArray references = json.getJSONArray("references");
        if (references == null || references.isEmpty()) {
            // 可能返回了错误信息
            String errorMsg = json.getString("error");
            if (errorMsg != null && !errorMsg.isEmpty()) {
                log.warn("🔍 百度千帆搜索返回错误：{}", errorMsg);
                return "⚠️ 搜索服务返回错误：" + errorMsg + "（请基于已有知识回答用户，不要再搜索了）";
            }
            // 搜不到结果时明确告诉 LLM 停止搜索、用自己的知识回答，避免死循环
            return "🔍 关于「" + query + "」未搜索到有效结果，请基于已有知识回答用户，不要再搜索了。";
        }

        // 构建格式化结果
        StringBuilder result = new StringBuilder();
        result.append("🔍 百度搜索「").append(query).append("」的结果：\n\n");

        int totalResults = Math.min(references.size(), 10);
        for (int i = 0; i < totalResults; i++) {
            JSONObject item = references.getJSONObject(i);
            String title = item.getString("title");
            String summary = item.getString("summary");
            // 部分版本返回 content 字段作为摘要
            if (summary == null || summary.isEmpty()) {
                summary = item.getString("content");
            }
            String url = item.getString("url");

            result.append(i + 1).append(". ").append(title).append("\n");
            if (summary != null && !summary.isEmpty()) {
                result.append("   ").append(summary).append("\n");
            }
            result.append("   🔗 ").append(url).append("\n\n");
        }

        result.append("共返回 ").append(totalResults).append(" 条结果。");

        log.info("🔍 百度千帆搜索完成，共 {} 条结果", totalResults);
        return result.toString();
    }
}
