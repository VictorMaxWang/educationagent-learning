package com.demo.chroma;

import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.base.EmbeddingService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 公共旅行知识库 RAG 检索服务
 * 核心职责：面向出行场景，在全局公共旅行向量知识库中执行相似度检索。
 * 数据流说明：
 * 1. 文档入库：由 {@link TravelKnowledgeIndexer} 负责，支持项目启动自动索引、调用/reindex接口手动重建索引
 * 2. 向量存储：统一存放于 Chroma 固定集合 travel_knowledge（全局公共知识库，所有用户共享）
 * 3. 对话链路：检索得到的旅行知识片段，会与用户私有知识库、用户记忆数据合并，一并送入大模型上下文实现增强生成
 * 开关控制：通过配置 memory.chroma.enabled 全局开启/关闭向量检索功能；关闭后直接返回空列表，不发起向量查询
 */
@Slf4j
@Service
public class TravelKnowledgeService {

    // 公共旅行知识库在Chroma中的集合名称，全局唯一共享集合
    private static final String COLLECTION_NAME = "travel_knowledge";

    // Chroma向量库总开关，配置文件控制是否启用知识库检索
    @Value("${memory.chroma.enabled:false}")
    private boolean chromaEnabled;

    @Resource
    private ChromaClient chromaClient;

    @Resource
    private EmbeddingService embeddingService;

    /**
     * 根据用户查询文本，在公共旅行知识库中执行向量相似度检索
     * @param query 用户原始提问文本
     * @param topK  需要返回相似度最高的文档片段数量
     * @return 匹配到的知识文本片段集合；关闭向量库、集合不存在、异常场景均返回空集合
     */
    public List<String> searchKnowledge(String query, int topK) {
        try {
            // 全局开关关闭，直接跳过检索
            if (!chromaEnabled) return Collections.emptyList();

            String collectionId = chromaClient.getOrCreateCollection(COLLECTION_NAME);
            if (collectionId == null) {
                log.debug("travel_knowledge 集合不存在（尚无知识入库）");
                return Collections.emptyList();
            }

            // 查询文本向量化
            List<Float> queryEmbedding = embeddingService.generateEmbedding(query);
            if (queryEmbedding == null || queryEmbedding.isEmpty()) {
                return Collections.emptyList();
            }

            // 执行相似度查询
            List<String> results = chromaClient.query(
                    collectionId, embeddingService.toDoubleList(queryEmbedding), topK);

            if (!results.isEmpty()) {
                log.info("📚 知识库命中 {} 条", results.size());
            }
            return results;
        } catch (Exception e) {
            log.warn("知识库检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取公共旅行知识库集合名称
     * 对外暴露常量入口，提供给 {@link TravelKnowledgeIndexer} 索引器使用，避免硬编码字符串散落各处
     * @return Chroma集合名称 travel_knowledge
     */
    public static String getCollectionName() {
        return COLLECTION_NAME;
    }
}
