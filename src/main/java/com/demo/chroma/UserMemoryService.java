package com.demo.chroma;

import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.base.EmbeddingService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户长期记忆服务 — 基于 Chroma 向量数据库的 RAG
 * <p>
 * 从对话提取的用户偏好/事实经 EmbeddingService 向量化后，
 * 通过 ChromaClient 存入/检索 user_memory_{userId} 集合。
 * <p>
 * 所有 Chroma 操作均有 try-catch 兜底，不可用时返回空列表，不阻塞主流程。
 */
@Slf4j
@Service
public class UserMemoryService {

    @Resource
    private ChromaClient chromaClient;

    @Resource
    private EmbeddingService embeddingService;

    // ======================== 对外核心接口 ========================

    /**
     * 搜索与当前 query 最相关的 N 条长期记忆
     */
    public List<String> searchMemories(String userId, String query, int topK) {
        try {
            String collectionName = "user_memory_" + sanitizeName(userId);
            String collectionId = chromaClient.getOrCreateCollection(collectionName);
            if (collectionId == null) return Collections.emptyList();

            List<Float> queryEmbedding = embeddingService.generateEmbedding(query);
            if (queryEmbedding == null || queryEmbedding.isEmpty()) return Collections.emptyList();

            return chromaClient.query(collectionId, embeddingService.toDoubleList(queryEmbedding), topK);
        } catch (Exception e) {
            log.warn("[{}] Chroma 长期记忆检索失败（Chroma 可能未启动）: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 添加一条长期记忆（生成向量 → 存入 Chroma）
     */
    public void addMemory(String userId, String fact) {
        try {
            String collectionName = "user_memory_" + sanitizeName(userId);
            String collectionId = chromaClient.getOrCreateCollection(collectionName);
            if (collectionId == null) return;

            List<Float> embedding = embeddingService.generateEmbedding(fact);
            if (embedding == null || embedding.isEmpty()) return;

            chromaClient.addDocument(collectionId, embeddingService.toDoubleList(embedding), fact,
                    Map.of("userId", userId, "timestamp", System.currentTimeMillis()));

            log.info("💾 [{}] 长期记忆已存储：{}", userId, truncate(fact, 50));
        } catch (Exception e) {
            log.warn("[{}] 存储长期记忆失败: {}", userId, e.getMessage());
        }
    }

    /**
     * 批量添加记忆（自动去重：基于 bigram Jaccard 相似度）
     */
    public void addMemories(String userId, List<String> newFacts) {
        if (newFacts == null || newFacts.isEmpty()) return;

        List<String> existing = searchMemories(userId, "", 50);

        int added = 0;
        for (String fact : newFacts) {
            if (fact == null || fact.isBlank()) continue;
            boolean duplicate = existing.stream()
                    .anyMatch(e -> bigramJaccard(e, fact) > 0.82);
            if (!duplicate) {
                addMemory(userId, fact);
                existing.add(fact);
                added++;
            }
        }
        if (added > 0) {
            log.info("📦 [{}] 批量存入 {} 条新记忆（去重 {} 条）", userId, added, newFacts.size() - added);
        }
    }

    // ======================== 工具方法 ========================

    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /** 基于字符 bigram 的 Jaccard 相似度，用于记忆去重 */
    private static double bigramJaccard(String a, String b) {
        if (a == null || b == null) return 0;
        Set<String> bigramsA = new HashSet<>();
        Set<String> bigramsB = new HashSet<>();
        String sa = a.length() > 100 ? a.substring(0, 100) : a;
        String sb = b.length() > 100 ? b.substring(0, 100) : b;
        for (int i = 0; i < sa.length() - 1; i++) bigramsA.add(sa.substring(i, i + 2));
        for (int i = 0; i < sb.length() - 1; i++) bigramsB.add(sb.substring(i, i + 2));

        Set<String> intersection = new HashSet<>(bigramsA);
        intersection.retainAll(bigramsB);
        Set<String> union = new HashSet<>(bigramsA);
        union.addAll(bigramsB);

        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }
}
