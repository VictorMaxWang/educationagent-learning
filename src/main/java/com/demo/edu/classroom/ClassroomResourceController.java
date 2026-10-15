package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeAssistant;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.safety.CrisisGuard;
import com.demo.edu.safety.CrisisGuardProperties;
import com.demo.edu.safety.EduContentSafetyJudge;
import com.demo.edu.safety.EduContentSafetyProperties;
import com.demo.router.SafetyKeywordRules;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 公共课程资料入口：复用领域问答，每次只创建并清理服务端临时会话，不接受旧用户标识。 */
@RestController
@RequestMapping("/api/classroom/resources/{domain}")
public class ClassroomResourceController {
    private static final Set<String> DOMAINS = Set.of("tongshi", "psychology", "lifesafety", "sexedu");
    private static final Set<String> INDEX_STATES = Set.of("NOT_STARTED", "INDEXING", "DONE", "FAILED");
    private final DocKnowledgeRegistry registry;
    private final CrisisGuardProperties crisisProperties;
    private final EduContentSafetyJudge safetyJudge;
    private final EduContentSafetyProperties safetyProperties;

    public ClassroomResourceController(DocKnowledgeRegistry registry, CrisisGuardProperties crisisProperties,
                                       EduContentSafetyJudge safetyJudge, EduContentSafetyProperties safetyProperties) {
        this.registry = registry; this.crisisProperties = crisisProperties; this.safetyJudge = safetyJudge;
        this.safetyProperties = safetyProperties;
    }

    /** 仅发布准备状态；不转发旧会话数量、模型配置、目录或异常正文。 */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(@PathVariable String domain) {
        if (!DOMAINS.contains(domain)) return reply(404, "不支持的公共课程领域", null);
        try {
            DocKnowledgeAssistant assistant = registry.getAssistant(domain);
            if (assistant == null) return unavailable();
            Map<String, Object> raw = assistant.health();
            boolean ready = raw != null && Boolean.TRUE.equals(raw.get("chroma_ok"));
            String state = raw == null ? "NOT_STARTED" : String.valueOf(raw.get("indexing"));
            Map<String, Object> data = Map.of("chroma_ok", ready, "indexing", INDEX_STATES.contains(state) ? state : "NOT_STARTED");
            return reply(ready ? 200 : 503, ready ? "ok" : "知识库暂不可用，请稍后重试", data);
        } catch (RuntimeException unavailable) { return unavailable(); }
    }

    /** 子库只展示公开的标识和名称，拒绝把配置对象原样返回给浏览器。 */
    @GetMapping("/libs")
    public ResponseEntity<Map<String, Object>> libs(@PathVariable String domain) {
        if (!DOMAINS.contains(domain)) return reply(404, "不支持的公共课程领域", null);
        try {
            DocKnowledgeAssistant assistant = registry.getAssistant(domain);
            return assistant == null ? unavailable() : reply(200, "ok", publicLibraries(assistant));
        } catch (RuntimeException unavailable) { return unavailable(); }
    }

    /**
     * 请求兼容旧页面的 question/lib_id，uid 与其他字段不会参与会话或资料选择。
     * question 最多2000字符，lib_id必须属于当前公共领域配置；失败仍清理临时会话。
     */
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@PathVariable String domain,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        if (!DOMAINS.contains(domain)) return reply(404, "不支持的公共课程领域", null);
        Object questionValue = body == null ? null : body.get("question");
        if (!(questionValue instanceof String question) || question.isBlank() || question.length() > 2000)
            return reply(400, "问题须为1至2000字符", null);
        Object libraryValue = body.get("lib_id");
        if (libraryValue != null && !(libraryValue instanceof String)) return reply(400, "资料库标识无效", null);
        String library = libraryValue == null ? "" : ((String) libraryValue).strip();
        if (!library.isEmpty() && !library.matches("[A-Za-z0-9_-]{1,80}")) return reply(400, "资料库标识无效", null);
        question = question.strip();

        // 与成长入口共用安全词表，但匿名访问不借用旧学生ID，也不写入班级危机统计。
        SafetyKeywordRules.Hit hit = SafetyKeywordRules.classify(question, crisisProperties.getExtraWords());
        if (hit.isHit()) {
            String text = hit.verdict() == SafetyKeywordRules.Verdict.SELF_HARM ? CrisisGuard.SCRIPT : CrisisGuard.EXPLICIT_SCRIPT;
            return reply(200, "已转接安全引导", Map.of("success", true, "crisis", true,
                    "answer", text, "articles", List.of(), "lib_used", library));
        }

        DocKnowledgeAssistant assistant;
        try {
            assistant = registry.getAssistant(domain);
            if (assistant == null) return unavailable();
            List<Map<String, String>> libraries = publicLibraries(assistant);
            if (!library.isEmpty()) {
                String requested = library;
                if (libraries.stream().noneMatch(item -> requested.equals(item.get("id"))))
                    return reply(400, "当前领域没有这个资料库", null);
            } else if (!libraries.isEmpty()) library = libraries.get(0).get("id");
        } catch (RuntimeException unavailable) { return unavailable(); }

        String temporaryId = "public_resource_" + UUID.randomUUID();
        try {
            Map<String, Object> raw = assistant.searchAndAnswer(temporaryId, question, library);
            if (raw == null || !Boolean.TRUE.equals(raw.get("success"))) return unavailable();
            String answer = raw.get("answer") instanceof String text ? text : "";
            if (answer.isBlank()) return unavailable();
            List<Map<String, Object>> articles = publicArticles(raw.get("articles"), library);
            List<String> snippets = articles.stream().map(item -> String.valueOf(item.get("content"))).toList();
            // 公共课堂独立消费真实判定，不受旧入口的log模式影响；未评估仍保留既有降级语义。
            EduContentSafetyJudge.Verdict verdict = safetyJudge.judge(answer, snippets);
            boolean replace = verdict.evaluated() && !verdict.safe();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true); result.put("answer", replace ? safetyProperties.getReplacement() : answer);
            // 若片段或回答被判不适合，浏览器也不能继续显示同批原文片段。
            result.put("articles", replace ? List.of() : articles);
            result.put("lib_used", library);
            if (replace) result.put("contentFlagged", true);
            return reply(200, "检索完成", result);
        } catch (RuntimeException unavailable) {
            return unavailable();
        } finally {
            assistant.clearContext(temporaryId);
        }
    }

    /** 新入口没有持久会话；页面只需清本机历史，不允许客户端指定并清除旧服务器会话。 */
    @PostMapping("/clear-context")
    public ResponseEntity<Map<String, Object>> clearContext(@PathVariable String domain,
                                                           @RequestBody(required = false) Map<String, Object> ignored) {
        return DOMAINS.contains(domain) ? reply(200, "本次公共问答未保存服务器会话", null)
                : reply(404, "不支持的公共课程领域", null);
    }

    private static List<Map<String, String>> publicLibraries(DocKnowledgeAssistant assistant) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> item : assistant.subLibraries()) {
            String id = item.get("id"), name = item.get("displayName");
            if (id != null && id.matches("[A-Za-z0-9_-]{1,80}"))
                result.add(Map.of("id", id, "displayName", name == null ? id : name));
        }
        return result;
    }

    private static List<Map<String, Object>> publicArticles(Object raw, String library) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> item) || !(item.get("content") instanceof String content)) continue;
            Map<String, Object> article = new LinkedHashMap<>();
            article.put("content", content); article.put("source", library);
            if (item.get("file") instanceof String file) {
                String normalized = file.replace('\\', '/');
                article.put("file", normalized.substring(normalized.lastIndexOf('/') + 1));
            }
            if (item.get("chunkIndex") instanceof Number index) article.put("chunkIndex", index);
            if (item.get("distance") instanceof Number distance) article.put("distance", distance);
            result.add(article);
        }
        return result;
    }

    private static ResponseEntity<Map<String, Object>> unavailable() {
        return reply(503, "知识库暂不可用，请稍后重试", null);
    }

    private static ResponseEntity<Map<String, Object>> reply(int code, String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code); body.put("msg", message); body.put("data", data);
        return ResponseEntity.status(code).body(body);
    }
}
