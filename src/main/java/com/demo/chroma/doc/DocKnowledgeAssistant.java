package com.demo.chroma.doc;

import com.demo.chroma.LawRetrievalPolicy;
import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.base.EmbeddingService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单领域检索 + LLM 回答 + 多轮会话（独立 LLM 调用，与主对话互不影响）。
 * <p>
 * 链路：向量化问题 → Chroma 余弦检索 → 距离阈值过滤 → 组 prompt（system + 检索片段 + 历史 + 问题）
 * → deepseekChatModel 生成 → 追加免责声明（若配置）。
 */
@Slf4j
public class DocKnowledgeAssistant {

    private final DocKnowledgeConfig config;
    private final ChromaClient chromaClient;
    private final EmbeddingService embeddingService;
    private final OpenAiChatModel chatModel;
    private final DocKnowledgeIndexer indexer;

    private final Map<String, Deque<String[]>> sessions = new ConcurrentHashMap<>();

    public DocKnowledgeAssistant(DocKnowledgeConfig config, ChromaClient chromaClient,
                                 EmbeddingService embeddingService, OpenAiChatModel chatModel,
                                 DocKnowledgeIndexer indexer) {
        this.config = config;
        this.chromaClient = chromaClient;
        this.embeddingService = embeddingService;
        this.chatModel = chatModel;
        this.indexer = indexer;
    }

    /** 一站式问答：检索 → LLM 生成 → 追加免责声明 → 存会话 */
    public Map<String, Object> searchAndAnswer(String uid, String question, String libId) {
        String lib = (libId == null || libId.isBlank()) ? defaultLib() : libId;
        Map<String, Object> result = new LinkedHashMap<>();

        ensureIndexed();
        DocKnowledgeIndexer.IndexState st = indexer.getState();
        if (st != DocKnowledgeIndexer.IndexState.DONE) {
            result.put("success", false);
            result.put("error", st == DocKnowledgeIndexer.IndexState.INDEXING
                    ? "知识库正在向量化入库中，请稍候再试"
                    : "知识库暂不可用（向量库未连接），请稍后重试");
            return result;
        }
        try {
            List<Map<String, Object>> articles = retrieve(lib, question);
            String answer = generateAnswer(uid, question, articles);
            if (config.getDisclaimer() != null && !config.getDisclaimer().isBlank()
                    && !answer.contains(config.getDisclaimer())) {
                answer = answer.stripTrailing() + "\n\n" + config.getDisclaimer();
            }
            addToSession(uid, "user", question);
            addToSession(uid, "assistant", answer);
            result.put("success", true);
            result.put("answer", answer);
            result.put("articles", articles);
            result.put("lib_used", lib);
            return result;
        } catch (Exception e) {
            log.error("[{}] 检索/回答异常: {}", config.getDomainId(), e.getMessage(), e);
            result.put("success", false);
            result.put("error", "知识库服务异常：" + e.getMessage());
            return result;
        }
    }

    private String defaultLib() {
        return config.getSubLibraries().isEmpty() ? null : config.getSubLibraries().get(0).id;
    }

    /** 向量检索：嵌入查询 → Chroma 余弦检索 → 距离阈值过滤 */
    /** 仅检索（返回片段文本，供主对话注入等场景复用，不做 LLM 回答） */
    public List<String> searchKnowledge(String question, String libId, int topK) {
        return searchKnowledge(question, libId, topK, null);
    }

    /**
     * 仅检索 + <b>元数据过滤</b>（按班/按权限隔离）。
     *
     * @param where 过滤条件（{@code ChromaClient.eqWhere(...)} 构造）；null 表示不过滤
     */
    public List<String> searchKnowledge(String question, String libId, int topK,
                                        Map<String, Object> where) {
        String lib = (libId == null || libId.isBlank()) ? defaultLib() : libId;
        List<Map<String, Object>> articles = retrieve(lib, question, topK, where);
        List<String> out = new ArrayList<>();
        for (Map<String, Object> a : articles) {
            out.add(String.valueOf(a.get("content")));
        }
        return out;
    }

    /**
     * 仅检索、但返回完整片段信息（含 {@code file} / {@code chunkIndex} / {@code distance}）。
     *
     * <p>与 {@link #searchKnowledge} 的区别：后者只给文本，用于"塞进提示词"；
     * 教师端"备课素材"需要向老师交代<b>依据了哪些校本资料</b>，所以要有文件名。
     */
    public List<Map<String, Object>> searchArticles(String question, String libId, int topK) {
        return searchArticles(question, libId, topK, null);
    }

    /**
     * 带元数据过滤的检索（含文件名/距离），用于教师端"依据了哪些校本资料"这类需要交代出处的场景。
     *
     * @param where 过滤条件；null 表示不过滤（跨班共享的知识库就该传 null）
     */
    public List<Map<String, Object>> searchArticles(String question, String libId, int topK,
                                                    Map<String, Object> where) {
        String lib = (libId == null || libId.isBlank()) ? defaultLib() : libId;
        return retrieve(lib, question, topK, where);
    }

    private List<Map<String, Object>> retrieve(String lib, String question) {
        return retrieve(lib, question, config.getTopK(), null);
    }

    private List<Map<String, Object>> retrieve(String lib, String question, int topK) {
        return retrieve(lib, question, topK, null);
    }

    private List<Map<String, Object>> retrieve(String lib, String question, int topK,
                                               Map<String, Object> where) {
        String collectionId = chromaClient.getOrCreateCollection(config.collectionName(lib));
        if (collectionId == null) return Collections.emptyList();
        List<Float> q = embeddingService.generateEmbedding(question);
        if (q == null || q.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> hits = chromaClient.queryDetail(
                collectionId, embeddingService.toDoubleList(q), topK, where);

        List<Map<String, Object>> kept = selectHits(config, hits);

        List<Map<String, Object>> articles = new ArrayList<>();
        for (Map<String, Object> h : kept) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("content", LawRetrievalPolicy.content(h));
            a.put("distance", LawRetrievalPolicy.distance(h));
            String source = lib;
            String file = null;
            Object chunkIndex = null;
            if (h.get("metadata") instanceof Map<?, ?> m) {
                Object s = m.get("source");
                if (s != null && !String.valueOf(s).isBlank()) source = String.valueOf(s);
                Object f = m.get("file");
                if (f != null && !String.valueOf(f).isBlank()) file = String.valueOf(f);
                chunkIndex = m.get("chunkIndex");
            }
            a.put("source", source);
            // 溯源：文件名 + 片段序号，前端据此可展开"这条依据出自哪份材料的第几段"
            a.put("file", file);
            if (chunkIndex != null) a.put("chunkIndex", chunkIndex);
            articles.add(a);
        }
        return articles;
    }

    /**
     * 从 Chroma 召回结果里挑出保留项。
     *
     * <p>抽成包可见静态方法是为了能脱离 Chroma 单测——过滤策略是"过度召回"的唯一防线，
     * 而它一旦配错（比如把相对 cutoff 当成"宽松"）后果很隐蔽：噪声片段被拼进提示词，
     * 模型据此一本正经地引用无关材料。
     *
     * <p>两条路径：
     * <ul>
     *   <li>{@code relativeCutoffRatio > 0} → 走 {@link LawRetrievalPolicy} 的三道过滤
     *       （绝对阈值 + 相对 cutoff + 内容长度 + 条数下限），与法律库同一份实现；</li>
     *   <li>否则 → 保持<b>改造前逐字一致</b>的单阈值过滤。默认走这条，所以现有的
     *       通识/心理学行为不变（有 DocRetrievalFilterTest 钉住）。</li>
     * </ul>
     */
    static List<Map<String, Object>> selectHits(DocKnowledgeConfig config, List<Map<String, Object>> hits) {
        if (config.getRelativeCutoffRatio() > 0) {
            LawRetrievalPolicy.Decision decision = LawRetrievalPolicy.filter(
                    hits, config.getDistanceThreshold(), config.getRelativeCutoffRatio(),
                    config.getMinContentLength(), config.isKeepAtLeastOne());
            log.info("[{}] 检索过滤 state={} | 召回 {} 条 → 保留 {} 条 | {}",
                    config.getDomainId(), decision.state(), hits == null ? 0 : hits.size(),
                    decision.kept().size(), String.join("; ", decision.log()));
            return decision.kept();
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        if (hits == null) return kept;
        for (Map<String, Object> h : hits) {
            if (LawRetrievalPolicy.distance(h) > config.getDistanceThreshold()) continue;
            kept.add(h);
        }
        return kept;
    }

    /** LLM 生成：领域 system prompt + 检索片段 + 历史 + 用户问题 */
    private String generateAnswer(String uid, String question, List<Map<String, Object>> articles) {
        StringBuilder userPrompt = new StringBuilder();
        if (articles.isEmpty()) {
            // 这里只如实报告"没检索到"，**不在这里下结论该怎么回**：
            // 本方法被四个学科共用，而"检索不到时怎么办"是各领域自己的策略——
            // 教育学域要求"如实说明 + 用通俗语言补充"，通识/心理学仍要求回【暂无匹配内容】。
            // 写在 user prompt 里会跟它们的 system prompt 打架，规则统一留在各自的 systemPrompt。
            userPrompt.append("【参考资料】本轮未检索到相关片段。");
        } else {
            userPrompt.append("【参考资料】以下是知识库中与问题相关的片段"
                    + "（请读懂后重新组织成面向用户的回答，不要原句照抄，也不要罗列标题）：");
            int i = 1;
            for (Map<String, Object> art : articles) {
                userPrompt.append("\n片段").append(i++).append("（来源库：").append(art.get("source"));
                Object file = art.get("file");
                if (file != null && !String.valueOf(file).isBlank()) {
                    userPrompt.append("，文件：").append(file);
                }
                Object chunkIndex = art.get("chunkIndex");
                if (chunkIndex != null) {
                    userPrompt.append("，第 ").append(((Number) chunkIndex).intValue() + 1).append(" 段");
                }
                userPrompt.append("，检索距离 ").append(String.format("%.3f",
                                ((Number) art.getOrDefault("distance", 0)).doubleValue()))
                        .append("，越小越相关）：\n").append(art.get("content"));
            }
        }
        Deque<String[]> history = sessions.get(uid);
        if (history != null && !history.isEmpty()) {
            userPrompt.append("\n\n【历史对话】");
            for (String[] m : history) {
                userPrompt.append('\n').append("user".equals(m[0]) ? "用户" : "助手")
                        .append("：").append(m[1]);
            }
        }
        userPrompt.append("\n\n【用户问题】").append(question)
                .append("\n请严格按系统提示中的规则，基于以上参考资料回答用户问题。");
        try {
            ChatResponse resp = chatModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(config.getSystemPrompt()),
                            UserMessage.from(userPrompt.toString())))
                    .build());
            String text = resp.aiMessage().text();
            return (text != null && !text.isBlank()) ? text.trim() : "【暂无匹配内容，无法解答该问题】";
        } catch (Exception e) {
            log.error("[{}] LLM 生成失败: {}", config.getDomainId(), e.getMessage());
            return "AI 服务暂时不可用，请稍后重试。";
        }
    }

    private void addToSession(String uid, String role, String content) {
        Deque<String[]> q = sessions.computeIfAbsent(uid, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new String[]{role, content});
            long userTurns = q.stream().filter(m -> "user".equals(m[0])).count();
            while (userTurns > config.getMaxRound() && q.size() >= 2) {
                q.pollFirst();
                if (!q.isEmpty() && !"user".equals(q.peekFirst()[0])) q.pollFirst();
                userTurns--;
            }
        }
    }

    public void clearContext(String uid) {
        sessions.remove(uid);
        log.info("[{}] 用户 {} 会话已清除", config.getDomainId(), uid);
    }

    public int activeSessions() {
        return sessions.size();
    }

    /** 暴露索引器，供"上传校本资源后强制重建"等场景调用（见 DocKnowledgeRegistry.reindex）。 */
    public DocKnowledgeIndexer indexer() {
        return indexer;
    }

    public List<Map<String, String>> subLibraries() {
        List<Map<String, String>> out = new ArrayList<>();
        for (DocKnowledgeConfig.SubLibrary sl : config.getSubLibraries()) {
            out.add(Map.of("id", sl.id, "displayName", sl.displayName));
        }
        return out;
    }

    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean ok = false;
        try {
            ok = chromaClient.getOrCreateCollection(config.collectionName(defaultLib())) != null;
        } catch (Exception e) {
            log.debug("[{}] Chroma 健康检查失败: {}", config.getDomainId(), e.getMessage());
        }
        data.put("chroma_ok", ok);
        data.put("active_sessions", sessions.size());
        data.put("indexing", indexer.getState().name());
        return data;
    }

    private void ensureIndexed() {
        DocKnowledgeIndexer.IndexState st = indexer.getState();
        if (st == DocKnowledgeIndexer.IndexState.DONE) return;
        if (st != DocKnowledgeIndexer.IndexState.INDEXING) {
            try {
                indexer.indexIfNeeded();
            } catch (Exception e) {
                log.warn("[{}] 懒加载索引失败: {}", config.getDomainId(), e.getMessage());
            }
        }
    }
}
