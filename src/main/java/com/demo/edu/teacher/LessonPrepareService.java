package com.demo.edu.teacher;

import com.demo.config.AiChatProperties;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.doc.DocKnowledgeAssistant;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.study.school.SchoolResourceConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 备课素材生成：以<b>本校上传的校本资源</b>为依据，让统一文本模型组织成教师可直接用的素材。
 *
 * <h3>为什么走 llmRestTemplate 而不是新建客户端</h3>
 * 复用已有的长超时、HTTP 观测及按 Host 生效的旧供应商兼容处理。
 * 连接地址与模型由统一配置提供，因此迁移供应商不需要改变检索和备课流程。
 *
 * <h3>依据必须回传</h3>
 * 教师最关心的是"这段素材是从哪份材料来的"。所以除了生成素材，
 * 还把检索到的校本资料文件名一并返回（{@link DocKnowledgeAssistant#searchArticles}），
 * 让老师能自己核对——这也是"回答溯源"在教师侧的落点。
 */
@Slf4j
@Service
public class LessonPrepareService {

    @Resource
    private AiChatProperties aiChat;

    private static final int TOP_K = 5;
    private static final int MAX_CHUNK_CHARS = 600;

    @Resource(name = "llmRestTemplate")
    private RestTemplate llmRestTemplate;
    @Resource
    private DocKnowledgeRegistry docKnowledgeRegistry;
    @Resource
    private SchoolResourceConfig schoolResourceConfig;

    /**
     * 生成备课素材。
     *
     * @param topic   备课主题（如"青春期身体变化 一节"）
     * @param notes   教师的额外要求，可空
     * @param classId 教师所属班级（由角色门从 token 得到）；用于把校本资源检索<b>限定在本班材料</b>
     */
    public Map<String, Object> prepare(String topic, String notes, String classId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (topic == null || topic.isBlank()) {
            out.put("success", false);
            out.put("error", "请填写备课主题");
            return out;
        }
        if (aiChat == null || !aiChat.hasApiKey()) {
            out.put("success", false);
            out.put("error", "未配置 AI_CHAT_API_KEY，无法生成素材");
            return out;
        }

        // 1) 检索校本资源（把"依据"留痕），**按班过滤**：
        //    过滤条件随查询一起下发，别班的材料根本不会被召回（不是召回来再删）。
        List<Map<String, Object>> articles = new ArrayList<>();
        try {
            DocKnowledgeAssistant assistant = docKnowledgeRegistry.getAssistant("school");
            if (assistant != null) {
                Map<String, Object> where = ChromaClient.eqWhere("classId", classId);
                if (where == null) {
                    // 正常情况下角色门已经保证了 classId，这里只是兜底：不带过滤会看到本校所有班级的校本资源
                    log.warn("备课素材：未取得班级上下文，本次检索不按班过滤");
                }
                articles = assistant.searchArticles(topic, "school", TOP_K, where);
            }
        } catch (Exception e) {
            log.warn("备课素材检索校本资源失败（将只靠模型生成）：{}", e.toString());
        }

        StringBuilder material = new StringBuilder();
        List<String> references = new ArrayList<>();
        for (Map<String, Object> a : articles) {
            String content = String.valueOf(a.getOrDefault("content", ""));
            if (content.length() > MAX_CHUNK_CHARS) content = content.substring(0, MAX_CHUNK_CHARS) + "…";
            material.append("- ").append(content).append('\n');
            Object file = a.get("file");
            if (file != null && !references.contains(String.valueOf(file))) {
                references.add(String.valueOf(file));
            }
        }

        // 2) 组织 prompt：校本资料 + 教师主题
        String userPrompt = (material.length() == 0
                ? "【校本资料】学校还没有上传相关的校本资料，请基于通用教学经验给出素材，"
                        + "并在开头注明『未检索到校本资料，以下为通用建议』。\n"
                : "【校本资料】（请优先依据这些材料，标注【资料】与【补充】）\n" + material)
                + "\n【备课主题】" + topic
                + (notes == null || notes.isBlank() ? "" : "\n【教师补充要求】" + notes);

        // 3) 使用与主对话相同的模型，保留备课自己的提示词与温度。
        try {
            JSONObject body = new JSONObject();
            aiChat.configureRequest(body);
            body.put("temperature", 0.4);
            JSONArray messages = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", schoolResourceConfig.getSystemPrompt());
            JSONObject usr = new JSONObject();
            usr.put("role", "user");
            usr.put("content", userPrompt);
            messages.add(sys);
            messages.add(usr);
            body.put("messages", messages);

            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", "Bearer " + aiChat.getApiKey());
            headers.add("Content-Type", "application/json");

            ResponseEntity<String> resp = llmRestTemplate.postForEntity(
                    aiChat.getCompletionsUrl(), new HttpEntity<>(body.toJSONString(), headers), String.class);
            String text = resp.getBody();
            JSONObject json = JSON.parseObject(text);
            String material0 = json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");

            out.put("success", true);
            out.put("material", material0 == null ? "" : material0.trim());
            out.put("references", references);
            out.put("disclaimer", schoolResourceConfig.getDisclaimer());
            out.put("fromSchoolResources", !references.isEmpty());
            log.info("🧑🏫 备课素材已生成：主题={} 依据校本文件 {} 份", topic, references.size());
            return out;
        } catch (Exception e) {
            log.warn("备课素材生成失败：{}", e.toString());
            out.put("success", false);
            out.put("error", "素材生成失败：" + e.getMessage());
            out.put("references", references);
            return out;
        }
    }
}
