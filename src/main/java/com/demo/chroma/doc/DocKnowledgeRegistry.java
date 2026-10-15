package com.demo.chroma.doc;

import com.demo.chroma.TravelKnowledgeChunker;
import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.base.EmbeddingService;
import com.demo.service.preprocessing.FileService;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公共知识库注册中心。
 * <p>
 * 启动时收集所有 {@link DocKnowledgeConfig} bean，为每个领域构造一个
 * {@link DocKnowledgeIndexer} + {@link DocKnowledgeAssistant}，按 domainId 管理。
 * 后续旅行/法律迁移时新增一个 Config bean 即自动接入。
 */
@Slf4j
@Service
@DependsOn("chromaAutoStarter")
public class DocKnowledgeRegistry {

    @Autowired(required = false)
    private List<DocKnowledgeConfig> configs;

    @Resource
    private ChromaClient chromaClient;
    @Resource
    private EmbeddingService embeddingService;
    @Resource
    private TravelKnowledgeChunker chunker;
    @Resource
    private FileService fileService;
    @Resource
    private OpenAiChatModel deepseekChatModel;

    private final Map<String, DocKnowledgeAssistant> assistants = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (configs == null || configs.isEmpty()) {
            log.info("📚 无公共知识库领域配置，跳过初始化");
            return;
        }
        for (DocKnowledgeConfig config : configs) {
            DocKnowledgeIndexer indexer = new DocKnowledgeIndexer(config, chromaClient, embeddingService, chunker, fileService);
            DocKnowledgeAssistant assistant = new DocKnowledgeAssistant(config, chromaClient, embeddingService, deepseekChatModel, indexer);
            assistants.put(config.getDomainId(), assistant);
            indexer.startAsync();
            log.info("📚 公共知识库助手已注册：{}（{} 子库）", config.getDomainId(), config.getSubLibraries().size());
        }
    }

    public DocKnowledgeAssistant getAssistant(String domainId) {
        return assistants.get(domainId);
    }

    /** 已注册的领域 ID 列表（供评估等场景遍历） */
    public Set<String> domainIds() {
        return assistants.keySet();
    }

    /**
     * 强制某领域重建索引。上传校本资源后必须调用——
     * 索引器在 DONE 状态会早退，新文件不会被自动扫到（见 {@code DocKnowledgeIndexer.forceReindex}）。
     *
     * @return {@code false} 表示该 domainId 未注册
     */
    public boolean reindex(String domainId) {
        DocKnowledgeAssistant assistant = assistants.get(domainId);
        if (assistant == null) {
            log.warn("⚠️ 强制重建失败：未注册的领域 {}", domainId);
            return false;
        }
        log.info("🔄 强制重建知识库领域：{}", domainId);
        assistant.indexer().forceReindex();
        return true;
    }
}
