package com.demo.controller;

import com.demo.chroma.LawKnowledgeService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 法律 RAG 检索接口 — 桌面端「法律检索」页。
 * <p>
 * 完全走主工程方案：检索 + LLM 回答全部由 {@link LawKnowledgeService} 完成
 * （DashScope text-embedding-v3 + Chroma + deepseekChatModel），
 * 不依赖 law-rag-module 的 Python Flask 服务。
 * <p>
 * 返回统一结构 {@code {code, msg, data}}，与 {@link DesktopOcrController} 保持一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop/law")
public class LawRagController {

    @Resource
    private LawKnowledgeService lawKnowledgeService;

    /**
     * 健康检查：Chroma 是否可达 + 活跃会话数。
     * GET /api/desktop/law/health
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> data = lawKnowledgeService.health();
            if (Boolean.TRUE.equals(data.get("chroma_ok"))) {
                result.put("code", 200);
                result.put("msg", "ok");
                result.put("data", data);
            } else {
                // chroma_ok=false 现在表示「法律文库没有可用向量」，而不是「Chroma 挂了」。
                // 旧文案把两者混为一谈，会把人引去排查 Chroma 进程，方向是错的。
                // 把 data 一并返回，让调用方能直接看到每个子库的真实向量数。
                result.put("code", 500);
                result.put("msg", "法律RAG不可用：没有已入库的向量（Chroma 服务本身可能正常，"
                        + "详见 data.libraries 各子库的 vectors 字段）");
                result.put("data", data);
            }
        } catch (Exception e) {
            log.error("⚖️ 法律RAG健康检查异常", e);
            result.put("code", 500);
            result.put("msg", "法律RAG服务异常：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    /**
     * 法律条文检索 + AI 回答。
     * body: {@code {"uid":"user_xxx","question":"劳动合同法第39条规定是什么","lib_id":"labor"}}
     * lib_id: civil(民法典) / labor(劳动法) / criminal(刑法)，缺省为 civil
     */
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

        Map<String, Object> data = lawKnowledgeService.searchAndAnswer(uid, question, libId);
        if (data != null && Boolean.TRUE.equals(data.get("success"))) {
            result.put("code", 200);
            result.put("msg", "检索完成");
            result.put("data", data);
        } else {
            result.put("code", 500);
            result.put("msg", data != null ? String.valueOf(data.getOrDefault("error", "法律检索失败"))
                    : "法律检索失败");
        }
        return result;
    }

    /**
     * 清除某用户的法律对话上下文（内存会话，重启清空）。
     * body: {@code {"uid":"user_xxx"}}
     */
    @PostMapping("/clear-context")
    public Map<String, Object> clearContext(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String uid = body != null ? str(body.get("uid")) : "";
        if (uid.isBlank()) {
            result.put("code", 400);
            result.put("msg", "uid 不能为空");
            return result;
        }
        lawKnowledgeService.clearContext(uid);
        result.put("code", 200);
        result.put("msg", "已清除该用户的法律对话上下文");
        return result;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
