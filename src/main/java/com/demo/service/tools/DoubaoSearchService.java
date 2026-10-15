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
 * 豆包搜索后端服务（火山引擎「搜索 Infinity Global版」）。
 * <p>
 * 纯网页检索接口，返回原始 Documents，不做任何 LLM 总结；
 * 与 {@link WebSearchService}（百度千帆）组成双源搜索，由上层 LLM 对比筛选。
 */
@Slf4j
@Service
public class DoubaoSearchService {

    /** 默认接口端点（可在 application-dev.properties 的 doubao.search.url 覆盖） */
    private static final String DEFAULT_API_URL =
            "https://open.feedcoopapi.com/search_api/global_search";

    @Value("${doubao.search.key:}")
    private String apiKey;

    @Value("${doubao.search.url:" + DEFAULT_API_URL + "}")
    private String apiUrl;

    @Resource
    private RestTemplate restTemplate;

    /**
     * 执行豆包网页搜索。
     *
     * @param query 搜索关键词（1-100 字符）
     * @param count 期望文档数（最大 20，建议 5-8）
     * @return 格式化搜索结果文本（失败返回友好提示）
     */
    public String search(String query, int count) {
        if (query == null || query.isBlank()) {
            return "错误：搜索关键词不能为空";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return "⚠️ 豆包搜索未配置 API Key，请在 application-dev.properties 设置 doubao.search.key。";
        }

        int docCount = Math.max(1, Math.min(count, 20));
        try {
            // 构建请求体（Global版裸搜索接口：无 model / 无 stream）
            JSONObject body = new JSONObject();
            body.put("Query", query.trim());
            body.put("DocCount", docCount);
            body.put("MaxSnippetLength", 1000);
            body.put("MaxImageCountPerDoc", 0); // 业务 Agent 场景关闭图片

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            log.info("🔍 执行豆包搜索：{}（{} 条）", query, docCount);
            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);
            String responseBody = restTemplate.postForObject(apiUrl, entity, String.class);
            return parseResults(responseBody, query);

        } catch (Exception e) {
            log.error("🔍 豆包搜索失败：{}", e.getMessage());
            return "⚠️ 豆包搜索暂时不可用，请稍后再试。错误：" + e.getMessage();
        }
    }

    /**
     * 解析 Global版返回：Result.Documents[] → 格式化文本。
     * 每项含 Title / Url / Snippet[{Type,Text}] / HostInfo.Hostname。
     */
    private String parseResults(String responseBody, String query) {
        JSONObject json = JSONObject.parseObject(responseBody);
        JSONObject result = json != null ? json.getJSONObject("Result") : null;
        if (result == null) {
            log.warn("🔍 豆包搜索响应无 Result 字段");
            return "🔍 豆包搜索「" + query + "」接口响应异常。";
        }

        int errorCode = result.getIntValue("ErrorCode");
        if (errorCode != 0) {
            log.warn("🔍 豆包搜索返回错误：{}", result.getString("ErrorMsg"));
            return "⚠️ 豆包搜索返回错误：" + result.getString("ErrorMsg");
        }

        JSONArray docs = result.getJSONArray("Documents");
        if (docs == null || docs.isEmpty()) {
            return "🔍 豆包搜索「" + query + "」未搜索到有效结果。";
        }

        StringBuilder sb = new StringBuilder("🔍 豆包搜索「").append(query).append("」的结果：\n\n");
        int total = Math.min(docs.size(), 20);
        for (int i = 0; i < total; i++) {
            JSONObject doc = docs.getJSONObject(i);
            String title = doc.getString("Title");
            String url = doc.getString("Url");

            // Snippet 是 [{Type, Text}] 数组，拼接 Text
            String summary = "";
            JSONArray snippets = doc.getJSONArray("Snippet");
            if (snippets != null) {
                StringBuilder sn = new StringBuilder();
                for (int j = 0; j < snippets.size(); j++) {
                    String t = snippets.getJSONObject(j).getString("Text");
                    if (t != null && !t.isBlank()) sn.append(t);
                }
                summary = sn.toString();
            }

            String host = "";
            JSONObject hostInfo = doc.getJSONObject("HostInfo");
            if (hostInfo != null) host = hostInfo.getString("Hostname");

            sb.append(i + 1).append(". ").append(title != null ? title : "").append("\n");
            if (!summary.isEmpty()) sb.append("   ").append(summary).append("\n");
            sb.append("   🔗 ").append(url != null ? url : "")
                    .append(host == null || host.isBlank() ? "" : "（" + host + "）").append("\n\n");
        }
        sb.append("共返回 ").append(total).append(" 条结果。");
        log.info("🔍 豆包搜索完成，共 {} 条结果", total);
        return sb.toString();
    }
}
