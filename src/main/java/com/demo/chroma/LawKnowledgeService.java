package com.demo.chroma;

import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.base.EmbeddingService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 法律知识库服务 — 完全按主工程方案实现，不依赖 law-rag-module Python 服务。
 * <p>
 * 链路：用户问题 → DashScope text-embedding-v3(1024) 向量化 → Chroma law_{lib} 余弦检索
 * → 距离阈值过滤 → 固定 System Prompt + 检索法条 + 多轮历史 → deepseekChatModel 生成回答
 * → 末尾 100% 追加免责声明。
 * <p>
 * 分块/向量化/存储与 {@link UserKnowledgeService}（知识中心）完全同源：
 * {@link TravelKnowledgeChunker} + {@link EmbeddingService} + {@link ChromaClient}。
 */
@Slf4j
@Service
public class LawKnowledgeService {

    /** 固定 System Prompt（与 law-rag-module prompt_builder.py 一致，禁止修改） */
    private static final String SYSTEM_PROMPT =
            "你是专业法律咨询助手，回答严格遵循提供的参考法条。\n"
          + "1. 仅能使用上下文内给出的法条内容作答，不得编造法条、自创法律规定；\n"
          + "2. 上下文不存在相关条款时，直接告知【暂无匹配法条，无法解答该问题】；\n"
          + "3. 语言通俗易懂，避免过度专业术语，条理清晰；\n"
          + "4. 不提供诉讼代理、司法鉴定、立案等实操指导，仅做法条解读。";

    private static final String NO_ARTICLES_RESPONSE = "【暂无匹配法条，无法解答该问题】";

    /** 免责声明（所有回答末尾 100% 追加，不可跳过） */
    private static final String DISCLAIMER =
            "⚠️免责声明：本内容仅基于现行法律条文提供参考解读，"
          + "不构成正式法律意见，不替代执业律师专业建议，"
          + "涉及纠纷请咨询专业律师。";

    private static final String COLLECTION_PREFIX = "law_";

    @Resource
    private ChromaClient chromaClient;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private OpenAiChatModel deepseekChatModel;

    @Resource
    private LawKnowledgeIndexer lawKnowledgeIndexer;

    @Value("${law.knowledge.top-k:3}")
    private int topK;

    /**
     * 绝对距离阈值。默认从 1.2 收紧到 0.85。
     *
     * <p>实测：query「正当防卫怎么判」的三条召回距离为 0.4397 / 0.5096 / 0.5120，
     * 1.2 的阈值对它们毫无约束力（三条全过），噪声直达提示词。
     * 0.85 是在本项目语料上试出来的起点，仍建议按实际召回日志继续调。
     */
    @Value("${law.knowledge.distance-threshold:0.85}")
    private double distanceThreshold;

    /** 相对 cutoff：只保留距离 ≤ top1×该系数的条文。 */
    @Value("${law.knowledge.relative-cutoff-ratio:1.08}")
    private double relativeCutoffRatio;

    /** 条文最短长度，低于此值视为空条文（例如 OCR 出来的"1. 。"）。 */
    @Value("${law.knowledge.min-content-length:10}")
    private int minContentLength;

    /** 请求的库无内容时是否软降级到民法典。 */
    @Value("${law.knowledge.fallback-to-civil:true}")
    private boolean fallbackToCivil;

    /** 召回是否保证至少保留一条，避免全部过滤后返回空结果。 */
    @Value("${law.knowledge.keep-at-least-one:true}")
    private boolean keepAtLeastOne;

    @Value("${law.knowledge.max-round:5}")
    private int maxRound;

    /** 多轮会话内存存储：uid → 消息队列 [role, content]（重启清空，与 Python session_manager 一致） */
    private final Map<String, Deque<String[]>> sessions = new ConcurrentHashMap<>();

    // ======================== 检索 + 回答 ========================

    /**
     * 一站式法律问答：检索法条 → LLM 生成回答 → 追加免责声明 → 存入会话。
     *
     * @return {success, answer, articles:[{content,distance,source}], lib_used} 或 {success:false, error}
     */
    public Map<String, Object> searchAndAnswer(String uid, String question, String libId) {
        String lib = (libId == null || libId.isBlank()) ? "civil" : libId;
        Map<String, Object> result = new LinkedHashMap<>();

        // 入库未完成时返回友好提示（后台正在入库）
        ensureIndexed();
        LawKnowledgeIndexer.IndexState st = lawKnowledgeIndexer.getState();
        if (st != LawKnowledgeIndexer.IndexState.DONE) {
            result.put("success", false);
            result.put("error", st == LawKnowledgeIndexer.IndexState.INDEXING
                    ? "法律知识库正在向量化入库中（首次约需数分钟），请稍候再试"
                    : "法律知识库暂不可用（Chroma 向量库未连接），请稍后重试");
            return result;
        }

        try {
            LawRetrieval retrieval = retrieveForContext(question, lib);
            List<Map<String, Object>> articles = retrieval.articles();
            String answer = generateAnswer(uid, question, articles, retrieval.note());

            // 追加免责声明（去重）
            if (!answer.contains(DISCLAIMER)) {
                answer = answer.stripTrailing() + "\n\n" + DISCLAIMER;
            }

            // 保存会话（user + assistant），超过轮数自动移除最早一轮
            addToSession(uid, "user", question);
            addToSession(uid, "assistant", answer);

            result.put("success", true);
            result.put("answer", answer);
            result.put("articles", articles);
            result.put("lib_used", retrieval.usedLib());
            result.put("lib_requested", retrieval.requestedLib());
            result.put("degraded", retrieval.degraded());
            result.put("note", retrieval.note());
            return result;
        } catch (Exception e) {
            log.error("⚖️ 法律检索/回答异常: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", "法律咨询服务异常：" + e.getMessage());
            return result;
        }
    }

    /**
     * 一次法律检索的完整结果（含降级信息）。
     *
     * @param articles    保留的条文，每项 {content, distance, source}
     * @param usedLib     实际命中的库（可能因软降级与 {@code requestedLib} 不同）
     * @param requestedLib 用户/调用方请求的库
     * @param note        需要转达给用户的提示（软降级提示 / 暂未收录 / null 表示无需提示）
     * @param degraded    是否发生了软降级
     */
    public record LawRetrieval(List<Map<String, Object>> articles,
                               String usedLib,
                               String requestedLib,
                               String note,
                               boolean degraded) {

        public boolean isEmpty() {
            return articles == null || articles.isEmpty();
        }
    }

    /**
     * 检索法条（带软降级），供 REST 与对话链路共用。
     *
     * <p>软降级语义：请求的库压根没入库（如 labor / criminal 目前都只有空壳）时，
     * 退回民法典检索并在 {@code note} 里如实说明"当前仅检索到民法典相关内容，XX法暂未收录"。
     * 这比直接回一句"无法解答"有用得多——用户问劳动法问题，民法典里的相关条款仍有参考价值，
     * 但<b>必须告知这是降级结果</b>，否则就成了答非所问还装作答上了。
     */
    public LawRetrieval retrieveForContext(String question, String libId) {
        String requested = (libId == null || libId.isBlank()) ? "civil" : libId;
        boolean requestedHasVectors = hasVectors(requested);

        List<Map<String, Object>> articles = requestedHasVectors
                ? retrieve(requested, question)
                : Collections.emptyList();

        if (articles.isEmpty() && fallbackToCivil && !"civil".equals(requested) && hasVectors("civil")) {
            List<Map<String, Object>> civil = retrieve("civil", question);
            if (!civil.isEmpty()) {
                String note = "（注：当前仅检索到民法典相关内容，" + displayName(requested)
                        + "暂未收录，建议咨询专业律师）";
                log.info("⚖️ 法律检索软降级：{} → civil，命中 {} 条", requested, civil.size());
                return new LawRetrieval(civil, "civil", requested, note, true);
            }
        }

        if (articles.isEmpty()) {
            String note = requestedHasVectors
                    ? NO_ARTICLES_RESPONSE
                    : "暂未收录《" + displayName(requested) + "》相关内容，建议咨询专业律师。";
            return new LawRetrieval(articles, requested, requested, note, false);
        }
        return new LawRetrieval(articles, requested, requested, null, false);
    }

    /** 某库是否有可用向量（集合存在且条数 > 0）。 */
    private boolean hasVectors(String lib) {
        try {
            String cid = chromaClient.findCollectionId(COLLECTION_PREFIX + sanitize(lib));
            return cid != null && chromaClient.count(cid) > 0;
        } catch (Exception e) {
            log.debug("⚖️ 检查文库 {} 失败（视为不可用）：{}", lib, e.getMessage());
            return false;
        }
    }

    /**
     * 向量检索：嵌入查询 → Chroma law_{lib} 检索 → {@link LawRetrievalPolicy} 三道过滤。
     *
     * <p>过滤策略刻意抽到独立类里（纯函数、可单测）——这是"过度召回"的唯一防线，
     * 而过度召回的后果很隐蔽：噪声条文进提示词后模型会一本正经地引用无关法条。
     */
    private List<Map<String, Object>> retrieve(String lib, String question) {
        String collectionId = chromaClient.getOrCreateCollection(COLLECTION_PREFIX + sanitize(lib));
        if (collectionId == null) return Collections.emptyList();

        List<Float> queryEmb = embeddingService.generateEmbedding(question);
        if (queryEmb == null || queryEmb.isEmpty()) return Collections.emptyList();

        List<Map<String, Object>> hits = chromaClient.queryDetail(
                collectionId, embeddingService.toDoubleList(queryEmb), topK);

        LawRetrievalPolicy.Decision decision = LawRetrievalPolicy.filter(
                hits, distanceThreshold, relativeCutoffRatio, minContentLength, keepAtLeastOne);

        if (!decision.log().isEmpty()) {
            log.info("⚖️ 法律检索过滤（lib={} 召回 {} 条 → 保留 {} 条）：\n  {}",
                    lib, hits.size(), decision.kept().size(), String.join("\n  ", decision.log()));
        }

        List<Map<String, Object>> articles = new ArrayList<>();
        for (Map<String, Object> h : decision.kept()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("content", h.get("content"));
            a.put("distance", LawRetrievalPolicy.distance(h));
            Object meta = h.get("metadata");
            String source = lib;
            if (meta instanceof Map<?, ?> m) {
                Object s = m.get("source"); // Map.get(Object) 对通配符类型合法
                if (s != null && !String.valueOf(s).isBlank()) source = String.valueOf(s);
            }
            a.put("source", source);
            articles.add(a);
        }
        return articles;
    }

    /** 库 ID → 中文名（用于"暂未收录《XX法》"这类面向用户的提示）。 */
    private static String displayName(String lib) {
        return switch (lib == null ? "" : lib) {
            case "civil" -> "民法典";
            case "labor" -> "劳动法";
            case "criminal" -> "刑法";
            default -> lib + "法";
        };
    }

    /** LLM 生成回答：固定 System Prompt + 参考法条 + 历史对话 + 用户问题 */
    private String generateAnswer(String uid, String question, List<Map<String, Object>> articles,
                                  String retrievalNote) {
        StringBuilder userPrompt = new StringBuilder();

        // 1. 参考法条（与 prompt_builder.py 一致）
        if (articles.isEmpty()) {
            userPrompt.append("【参考法条】").append(NO_ARTICLES_RESPONSE);
        } else {
            userPrompt.append("【参考法条】以下是与问题相关的法律条文：");
            int i = 1;
            for (Map<String, Object> art : articles) {
                // 注意：这里打印的是 Chroma 距离，越小越相关。
                // 旧代码把它标成"相似度"，语义正好相反——模型看到的数字越大，
                // 旧标签会让它以为越相关，属于会误导模型的标注错误。
                userPrompt.append("\n第").append(i++)
                        .append("条（来源：").append(art.get("source"))
                        .append("，检索距离 ").append(String.format("%.4f",
                                ((Number) art.getOrDefault("distance", 0)).doubleValue()))
                        .append("，越小越相关")
                        .append("）：\n").append(art.get("content"));
            }
        }

        // 1.5 降级/未收录提示：必须原样转达，不能让模型把降级结果当成直接答案
        if (retrievalNote != null && !retrievalNote.isBlank()) {
            userPrompt.append("\n\n【重要提示，必须在回答中如实转达】").append(retrievalNote);
        }

        // 2. 历史对话
        Deque<String[]> history = sessions.get(uid);
        if (history != null && !history.isEmpty()) {
            userPrompt.append("\n\n【历史对话】");
            for (String[] m : history) {
                userPrompt.append('\n').append("user".equals(m[0]) ? "用户" : "助手")
                        .append("：").append(m[1]);
            }
        }

        // 3. 用户当前问题
        userPrompt.append("\n\n【用户问题】").append(question)
                .append("\n请基于以上参考法条回答用户问题。");

        try {
            ChatResponse resp = deepseekChatModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(SYSTEM_PROMPT),
                            UserMessage.from(userPrompt.toString())))
                    .build());
            String text = resp.aiMessage().text();
            return (text != null && !text.isBlank()) ? text.trim() : NO_ARTICLES_RESPONSE;
        } catch (Exception e) {
            log.error("⚖️ LLM 生成失败: {}", e.getMessage());
            return "AI法律咨询服务暂时不可用，请稍后重试。";
        }
    }

    // ======================== 会话管理（内存，重启清空） ========================

    private void addToSession(String uid, String role, String content) {
        Deque<String[]> q = sessions.computeIfAbsent(uid, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new String[]{role, content});
            long userTurns = q.stream().filter(m -> "user".equals(m[0])).count();
            while (userTurns > maxRound && q.size() >= 2) {
                q.pollFirst(); // 移除最早 user
                if (!q.isEmpty() && !"user".equals(q.peekFirst()[0])) {
                    q.pollFirst(); // 紧随其后的 assistant
                }
                userTurns--;
            }
        }
    }

    /** 清除指定用户的法律会话上下文 */
    public void clearContext(String uid) {
        sessions.remove(uid);
        log.info("⚖️ 用户 {} 法律会话已清除", uid);
    }

    /** 活跃会话数（供健康检查展示） */
    public int activeSessions() {
        return sessions.size();
    }

    // ======================== 健康检查 ========================

    /**
     * 健康检查：Chroma 可达且 law_civil 集合存在。
     *
     * @return {chroma_ok, active_sessions}
     */
    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            // 旧实现用 getOrCreateCollection(...) != null 判活，那个方法会「查不到就创建」，
            // 所以永远返回非 null、永远报健康 —— 即使集合里一条向量都没有。
            // 现在改成只读查找 + 真实回读向量数，以「有向量」为唯一健康标准。
            List<Map<String, Object>> libs = new ArrayList<>();
            int total = 0;
            for (String libId : List.of("civil", "labor", "criminal")) {
                String collectionName = COLLECTION_PREFIX + libId;
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("lib", libId);
                one.put("collection", collectionName);
                try {
                    String cid = chromaClient.findCollectionId(collectionName);
                    int n = (cid == null) ? -1 : chromaClient.count(cid);
                    one.put("exists", cid != null);
                    one.put("vectors", n);
                    if (n > 0) total += n;
                } catch (Exception ex) {
                    one.put("exists", null);
                    one.put("vectors", null);
                    one.put("error", ex.getMessage());
                }
                libs.add(one);
            }
            data.put("chroma_ok", total > 0);
            data.put("total_vectors", total);
            data.put("libraries", libs);
            if (total == 0) {
                data.put("hint", "法律文库没有可用向量。若上次入库因账户欠费/网络失败而中断，"
                        + "重启会因「指纹声称已索引但集合为空」被自动识别并重建，无需手工删指纹");
            }
        } catch (Exception e) {
            log.error("⚖️ 法律RAG健康检查失败: {}", e.getMessage(), e);
            data.put("chroma_ok", false);
            data.put("error", e.getMessage());
        }
        data.put("active_sessions", sessions.size());
        data.put("indexing", lawKnowledgeIndexer.getState().name());
        return data;
    }

    // ======================== 懒加载索引 ========================

    /** 首次检索前确保法律文库已入库（未完成则触发一次兜底，Chroma 暂不可用自动重试） */
    private void ensureIndexed() {
        LawKnowledgeIndexer.IndexState st = lawKnowledgeIndexer.getState();
        if (st == LawKnowledgeIndexer.IndexState.DONE) return;
        if (st != LawKnowledgeIndexer.IndexState.INDEXING) {
            try {
                lawKnowledgeIndexer.indexIfNeeded(); // 内部有状态锁 + 重试节流，幂等
            } catch (Exception e) {
                log.warn("⚖️ 法律文库懒加载索引失败: {}", e.getMessage());
            }
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
