package com.demo.memory;

import com.demo.chroma.LawContextProvider;
import com.demo.chroma.LawKnowledgeService;
import com.demo.router.IntentLabel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 双路 RAG 融合：把「用户个人记忆（本地 Lucene）」与「法律/公共知识（Chroma）」的召回结果
 * 用**加权 RRF（Reciprocal Rank Fusion）**重排成一份上下文。
 *
 * <h3>为什么用 RRF 而不是把分数归一化后加权求和</h3>
 * 两路的分数<b>根本不可比</b>：记忆侧是 Lucene 的余弦（0~1，分布集中在 0.3~0.6），
 * 法律侧是 Chroma 的距离换算（1 - distance/2）。它们的量纲、分布、阈值含义全都不同，
 * 强行归一化等于把两个不同尺子的读数相加。
 * RRF 只看<b>排名</b>，天然回避了这个问题，而且实践证明对异构检索器融合极其稳。
 *
 * <h3>为什么法律侧要有意图门控</h3>
 * 方案书任务 4 记录的真实现象：天气、闲聊也会被送进法律检索，
 * 结果融合出一个 TOP-5 里 2 条是法条的上下文，把无关法条污染进了回答。
 * 这里复用 {@link LawContextProvider#shouldQuery} 做门控——它同时覆盖
 * "LAW 意图命中" 与 "关键词兜底"（法条/违法/合同/离婚…），
 * 并让明确的跳过词（天气/提醒/文件…）优先。
 */
@Slf4j
@Service
public class HybridRagService {

    private final MemoryRagService memoryRagService;
    private final LawKnowledgeService lawKnowledgeService;
    private final LawContextProvider lawContextProvider;
    private final MemoryProperties properties;

    /** 并行检索用的执行器：两路各一个任务，2 个线程足够。 */
    private final ExecutorService retrievalExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "memory-hybrid-rag");
        t.setDaemon(true);
        return t;
    });

    public HybridRagService(MemoryRagService memoryRagService,
                            LawKnowledgeService lawKnowledgeService,
                            LawContextProvider lawContextProvider,
                            MemoryProperties properties) {
        this.memoryRagService = memoryRagService;
        this.lawKnowledgeService = lawKnowledgeService;
        this.lawContextProvider = lawContextProvider;
        this.properties = properties;
    }

    @PreDestroy
    public void shutdown() {
        retrievalExecutor.shutdown();
        try {
            retrievalExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 融合检索。
     *
     * @param userId 用户 ID（记忆侧隔离用）
     * @param query  用户原始问题
     * @param intent 路由判定的意图，可为 {@code null}
     * @param topK   最终返回条数；≤0 时用默认值
     */
    public List<FusedResult> fusedSearch(String userId, String query, IntentLabel intent, int topK) {
        MemoryProperties.Hybrid cfg = properties.getHybrid();
        int limit = topK > 0 ? topK : Math.max(cfg.getMemoryTopK(), cfg.getLawTopK());
        if (!cfg.isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }

        boolean includeLaw = lawContextProvider != null
                && lawContextProvider.shouldQuery(intent, query);

        // ---- 并行两路检索 ----
        CompletableFuture<List<FusedResult>> memoryFuture = CompletableFuture.supplyAsync(
                () -> memorySide(userId, query, cfg), retrievalExecutor);
        CompletableFuture<List<FusedResult>> lawFuture = includeLaw
                ? CompletableFuture.supplyAsync(() -> lawSide(query, cfg), retrievalExecutor)
                : CompletableFuture.completedFuture(List.of());

        try {
            CompletableFuture.allOf(memoryFuture, lawFuture)
                    .get(Math.max(1, cfg.getTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 超时即降级：下面用 getNow 拿已完成的部分，不阻塞主对话
            log.warn("【记忆库-Hybrid】融合检索超时（{}s），使用已返回的部分结果",
                    cfg.getTimeoutSeconds());
        } catch (Exception e) {
            log.warn("【记忆库-Hybrid】融合检索异常，使用已返回的部分结果：{}", e.toString());
        }

        List<FusedResult> memoryHits = memoryFuture.getNow(List.of());
        List<FusedResult> lawHits = lawFuture.getNow(List.of());

        // ---- 加权 RRF（算法本体在 RrfFusion 里，纯函数、可单测）----
        List<FusedResult> out = RrfFusion.fuse(
                memoryHits, lawHits,
                cfg.getRrfK(), cfg.getMemoryWeight(), cfg.getLawWeight(),
                cfg.getDedupeThreshold(), limit, cfg.getMinScore());

        log.info("【记忆库-Hybrid】融合 top-{} = {{MEMORY={}, LAW={}}} → 保留 {} 条（法律侧{}）",
                limit, memoryHits.size(), lawHits.size(), out.size(),
                includeLaw ? "已检索" : "已跳过");
        return out;
    }

    // ======================== 两路 ========================

    private List<FusedResult> memorySide(String userId, String query, MemoryProperties.Hybrid cfg) {
        try {
            List<MemoryRecord> records = memoryRagService.search(userId, query, cfg.getMemoryTopK());
            List<FusedResult> out = new ArrayList<>(records.size());
            for (MemoryRecord m : records) {
                out.add(FusedResult.builder()
                        .content(m.getContent())
                        .source(FusedResult.SOURCE_MEMORY)
                        .originalScore(m.getScore())
                        .metadata(new java.util.LinkedHashMap<>(Map.of(
                                "memoryId", String.valueOf(m.getId()),
                                "type", m.getType().name(),
                                "importance", m.getImportance())))
                        .build());
            }
            return out;
        } catch (Exception e) {
            log.warn("【记忆库-Hybrid】记忆侧检索失败：{}", e.toString());
            return List.of();
        }
    }

    private List<FusedResult> lawSide(String query, MemoryProperties.Hybrid cfg) {
        try {
            LawKnowledgeService.LawRetrieval retrieval =
                    lawKnowledgeService.retrieveForContext(query, null);
            List<FusedResult> out = new ArrayList<>();
            for (Map<String, Object> a : retrieval.articles()) {
                double distance = a.get("distance") instanceof Number n ? n.doubleValue() : 2.0;
                // Chroma 距离 → 相似度：距离 0 → 1.0，距离 2 → 0.0
                double similarity = Math.max(0.0, 1.0 - distance / 2.0);
                out.add(FusedResult.builder()
                        .content(String.valueOf(a.get("content")))
                        .source(FusedResult.SOURCE_LAW)
                        .originalScore(similarity)
                        .metadata(new java.util.LinkedHashMap<>(Map.of(
                                "distance", distance,
                                "lib", String.valueOf(a.get("source")),
                                "degraded", retrieval.degraded())))
                        .build());
            }
            return out;
        } catch (Exception e) {
            log.warn("【记忆库-Hybrid】法律侧检索失败：{}", e.toString());
            return List.of();
        }
    }

}
