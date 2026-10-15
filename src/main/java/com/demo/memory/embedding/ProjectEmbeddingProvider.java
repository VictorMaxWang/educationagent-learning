package com.demo.memory.embedding;

import com.demo.chroma.base.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认实现的记忆向量化：复用项目已有的 {@link EmbeddingService}。
 *
 * <p>这是一条<b>已经在跑</b>的链路（当前 {@code memory.embedding.provider=siliconflow}，
 * 启动自检通过、维度 1024）。把它接给记忆库的好处是零新增依赖、零模型分发；
 * 而"云端欠费也能跑"的目标通过把 provider 切成 {@code ollama}（本地 bge-m3）即可达成，
 * 不需要 ONNX。理由详见 {@link MemoryEmbeddingProvider} 的类注释。
 */
@Slf4j
@Component
public class ProjectEmbeddingProvider implements MemoryEmbeddingProvider {

    private final EmbeddingService embeddingService;

    /** 记忆库期望的维度；与 store 的维度配置同源，不一致时会在启动日志里报警。 */
    @Value("${memory.store.dimension:1024}")
    private int expectedDimension;

    public ProjectEmbeddingProvider(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    public String name() {
        return "project-embedding-service";
    }

    @Override
    public int dimension() {
        return expectedDimension;
    }

    @Override
    public boolean isReady() {
        // EmbeddingService 自己在 @PostConstruct 里做过真实探测并打印了实际维度，
        // 这里不重复探测（避免启动时多花一次云端调用）；
        // 运行期的失败由 embed() 返回 null 表达，调用方会跳过该条记忆而不是写入坏数据。
        return true;
    }

    @Override
    public float[] embed(String text) {
        return toFloats(embeddingService.generateEmbedding(text));
    }

    @Override
    public float[] embedQuery(String query) {
        // 当前几种模型（text-embedding-v3 / bge-m3）都不要求 query 前缀，
        // 所以检索与入库走同一条路径。若将来换成 BGE 原版（需要前缀），
        // 只需在这里加前缀即可，不必改调用方。
        return toFloats(embeddingService.generateEmbedding(query));
    }

    private float[] toFloats(List<Float> vector) {
        if (vector == null || vector.isEmpty()) {
            return null;
        }
        float[] out = new float[vector.size()];
        for (int i = 0; i < out.length; i++) {
            Float v = vector.get(i);
            out[i] = v == null ? 0f : v;
        }
        if (out.length != expectedDimension) {
            log.warn("【记忆库】向量维度 {} 与配置 memory.store.dimension={} 不一致，"
                            + "该条记忆将被跳过（写入维度不符的向量会让整个索引不可用）",
                    out.length, expectedDimension);
            return null;
        }
        return out;
    }
}
