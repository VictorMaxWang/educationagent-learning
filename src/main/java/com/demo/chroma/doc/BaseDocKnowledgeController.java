package com.demo.chroma.doc;

import com.demo.edu.knowledge.EduKnowledgeAccessService;
import com.demo.edu.safety.CrisisGuard;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 公共知识库 REST 端点基类。子类只需声明 domainId 与 @RequestMapping 路径。
 * <p>
 * 返回统一结构 {@code {code, msg, data}}，与 {@code LawRagController} 保持一致。
 */
@Slf4j
public abstract class BaseDocKnowledgeController {

    @Resource
    private DocKnowledgeRegistry registry;

    /**
     * 危机兜底。<b>知识库链路不经过 AgentExecutor</b>（它是独立 REST + 独立 LLM 调用），
     * 所以必须在这里单独挡一道，否则"在知识库页面对话框里说自己不想活"会绕过全部拦截，
     * 直接交给 {@code DocKnowledgeAssistant} 自由生成。
     *
     * <p>放在基类而不是各个子类：生命安全与健康教育/性教育/通识/心理/校本全部继承本类，守一处即全覆盖。
     * 依赖方向（框架 → 教育域安全组件）确实有点别扭，但比在 5 个子类里各写一遍要安全得多。
     */
    @Resource
    private CrisisGuard crisisGuard;

    /** 知识库访问校验（可选，缺失时跳过校验）。见 {@link EduKnowledgeAccessService}。 */
    @Resource
    private EduKnowledgeAccessService knowledgeAccessService;

    /** L4 后置内容校验（可选，缺失时跳过）。知识库问答是非流式，因此能真正替换。 */
    @Resource
    private com.demo.edu.safety.EduContentSafetyJudge contentSafetyJudge;

    protected abstract String domainId();

    private DocKnowledgeAssistant assistant() {
        DocKnowledgeAssistant a = registry.getAssistant(domainId());
        if (a == null) throw new IllegalStateException(domainId() + " 助手未注册");
        return a;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> data = assistant().health();
            if (Boolean.TRUE.equals(data.get("chroma_ok"))) {
                result.put("code", 200);
                result.put("msg", "ok");
                result.put("data", data);
            } else {
                result.put("code", 500);
                result.put("msg", "Chroma 向量库不可用，请确认 Chroma 服务已启动");
                result.put("data", null);
            }
        } catch (Exception e) {
            log.error("[{}] RAG 健康检查异常", domainId(), e);
            result.put("code", 500);
            result.put("msg", domainId() + " RAG 服务异常：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String uid = body != null ? str(body.get("uid")) : "";
        String question = body != null ? str(body.get("question")) : "";
        String libId = body != null ? str(body.get("lib_id")) : "";
        if (uid.isBlank() || question.isBlank()) {
            result.put("code", 400);
            result.put("msg", "uid 和 question 不能为空");
            return result;
        }

        // ★ 访问校验：先不认识你，再谈你问什么。
        //   两道闸——领域等级（security_level ≤ user_authorization_level）与班级开关（默认开放）。
        //   只装在 /search 这一个有内容的接口上：health/libs 不含内容，加了反而会把知识中心页弄坏。
        if (knowledgeAccessService != null) {
            EduKnowledgeAccessService.Decision access = knowledgeAccessService.check(domainId(), uid);
            if (!access.allowed()) {
                result.put("code", 403);
                result.put("msg", access.reason());
                return result;
            }
        }
        // ★ 危机兜底：命中即返回固定引导话术，不做检索、不调 LLM。
        if (crisisGuard != null) {
            // 用**请求体里的 uid**，不能依赖 VoiceContext（REST 链路没设置过它，
            // 线程复用会读到上一个请求残留的用户 id，把信号记到别人班里）
            Optional<String> crisis = crisisGuard.checkForUser(uid, question);
            if (crisis.isPresent()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("success", true);
                data.put("crisis", true);
                data.put("answer", crisis.get());
                data.put("articles", List.of());
                data.put("lib_used", libId);
                result.put("code", 200);
                result.put("msg", "已转接危机引导（未做知识检索）");
                result.put("data", data);
                return result;
            }
        }

        Map<String, Object> data = assistant().searchAndAnswer(uid, question, libId);
        if (data != null && Boolean.TRUE.equals(data.get("success"))) {
            // ★ L4 后置内容校验：知识库问答是非流式（答案已生成完、还没返回），
            //   所以这里能真正替换掉不合适的回答（流式链路做不到，见 AgentExecutor.applyContentCheck）
            if (contentSafetyJudge != null) {
                String answer = String.valueOf(data.getOrDefault("answer", ""));
                // 片段与回答一起判（一次裁判调用）：片段若本身不适龄，回答迟早会带出来
                List<String> snippets = new java.util.ArrayList<>();
                if (data.get("articles") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> article && article.get("content") != null) {
                            snippets.add(String.valueOf(article.get("content")));
                        }
                    }
                }
                Optional<String> replaced = contentSafetyJudge.replacementIfUnsafe(answer, snippets);
                if (replaced.isPresent()) {
                    data.put("answer", replaced.get());
                    data.put("contentFlagged", true);
                }
            }
            result.put("code", 200);
            result.put("msg", "检索完成");
            result.put("data", data);
        } else {
            result.put("code", 500);
            result.put("msg", data != null ? String.valueOf(data.getOrDefault("error", "检索失败")) : "检索失败");
        }
        return result;
    }

    @PostMapping("/clear-context")
    public Map<String, Object> clearContext(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String uid = body != null ? str(body.get("uid")) : "";
        if (uid.isBlank()) {
            result.put("code", 400);
            result.put("msg", "uid 不能为空");
            return result;
        }
        assistant().clearContext(uid);
        result.put("code", 200);
        result.put("msg", "已清除该用户的对话上下文");
        return result;
    }

    @GetMapping("/libs")
    public Map<String, Object> libs() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("msg", "ok");
        result.put("data", assistant().subLibraries());
        return result;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
