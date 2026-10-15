package com.demo.study.controller;
import com.demo.controller.DesktopFileStore;

import com.demo.chroma.UserKnowledgeService;
import com.demo.service.tools.holder.UserFileHolder;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识中心接口（桌面端）。
 * <p>
 * 资料库：save（上传→索引）/ list / view / delete / search
 * 问答：ask —— RAG 检索个人知识库 + LLM 基于资料回答
 * 学习掌握（刷题知识点）复用 {@code /api/desktop/quiz/*} 接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop/knowledge")
public class KnowledgeController {

    @Resource
    private UserKnowledgeService userKnowledgeService;

    @Resource
    private DesktopFileStore fileStore;

    @Resource
    private UserFileHolder userFileHolder;

    @Resource
    private OpenAiChatModel deepseekChatModel;

    /** 保存并索引：fileId → 存盘 + 写入 Chroma */
    @PostMapping("/save")
    public ResponseEntity<?> save(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        String fileId = payload.get("fileId");
        DesktopFileStore.FileEntry entry = fileStore.get(fileId);
        if (entry == null || entry.bytes() == null || entry.bytes().length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不存在或已过期，请重新上传"));
        }
        // 复用 UserFileHolder 桥接，走现有的 saveAndIndex（存盘 + 分块 + 向量化 + Chroma）
        userFileHolder.put(userId, entry.bytes(), entry.fileName());
        fileStore.remove(fileId);
        String result = userKnowledgeService.saveAndIndex(userId);
        return ResponseEntity.ok(Map.of("success", true, "message", result));
    }

    /** 知识库文档列表（文件名 + 大小 + 时间） */
    @PostMapping("/list")
    public ResponseEntity<?> list(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        return ResponseEntity.ok(userKnowledgeService.listDetail(userId));
    }

    /** 查看某文档内容 */
    @PostMapping("/view")
    public ResponseEntity<?> view(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        String content = userKnowledgeService.viewContent(userId, payload.get("fileName"));
        if (content == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不存在"));
        }
        return ResponseEntity.ok(Map.of("success", true, "content", content));
    }

    /** 删除文档（含 Chroma 向量） */
    @PostMapping("/delete")
    public ResponseEntity<?> delete(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        boolean ok = userKnowledgeService.delete(userId, payload.get("fileName"));
        return ok
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.badRequest().body(Map.of("error", "删除失败，文件不存在"));
    }

    /** 在知识库资料中检索（返回命中的文本片段） */
    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        String query = payload.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入搜索关键词"));
        }
        List<String> chunks = userKnowledgeService.searchKnowledge(userId, query, 5);
        return ResponseEntity.ok(Map.of("success", true, "results", chunks));
    }

    /** 知识库问答：RAG 检索 + LLM 基于资料回答 */
    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        String question = payload.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入问题"));
        }
        List<String> chunks = userKnowledgeService.searchKnowledge(userId, question, 5);
        if (chunks.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", "你的知识库里还没有相关资料，先上传一些文档吧。",
                    "sources", List.of()));
        }
        String context = chunks.stream().map(c -> "- " + c).collect(Collectors.joining("\n"));
        String prompt = "以下是从用户个人知识库中检索到的资料片段，请严格基于这些资料回答用户的问题。"
                + "资料不足以回答时就如实说明，不要编造。\n\n【资料片段】\n" + context + "\n\n【用户问题】\n" + question;
        try {
            ChatResponse resp = deepseekChatModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from("你是个人知识助手，严格基于提供的资料回答，不编造。"),
                            UserMessage.from(prompt)))
                    .build());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", resp.aiMessage().text(),
                    "sources", chunks));
        } catch (Exception e) {
            log.error("知识库问答失败", e);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", "检索到资料但回答失败：" + e.getMessage(),
                    "sources", chunks));
        }
    }
}
