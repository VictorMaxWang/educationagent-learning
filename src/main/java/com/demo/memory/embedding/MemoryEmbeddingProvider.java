package com.demo.memory.embedding;

/**
 * 用户记忆的向量化接口——把「记忆库」与「向量从哪来」解耦。
 *
 * <h3>为什么要有这层抽象</h3>
 * 方案书要求「脱离云端 embedding API，云端欠费时用户记忆仍可用」，实现手段是
 * ONNX Runtime + 本地 BGE-small。但那个方案有一个硬前提：
 * <b>90.5MB 的 ONNX 模型文件必须随项目分发</b>（方案书自己把它列为踩坑点之一）。
 * 本机全盘没有该文件，所以直接做 ONNX 实现会得到一个无法验证、
 * 而且需要新增 onnxruntime + DJL tokenizer 两个依赖（约 40MB jar）的空壳。
 *
 * <p>因此这里只定义<b>契约</b>，默认实现走项目已有链路：
 * <ul>
 *   <li>{@link ProjectEmbeddingProvider}（默认）——复用 {@code com.demo.chroma.base.EmbeddingService}，
 *       它已支持 {@code dashscope} / {@code siliconflow} / {@code ollama} 三种 provider。
 *       只要把 provider 切成 {@code ollama}（本地 bge-m3），就同样满足
 *       「云端欠费也能跑」这个目标，而<b>无需分发模型文件、无需新增依赖</b>。</li>
 * </ul>
 *
 * <h3>要接 ONNX 实现时，需要补齐的东西</h3>
 * <ol>
 *   <li>依赖：{@code com.microsoft.onnxruntime:onnxruntime} + DJL tokenizer
 *       （或自己解析 {@code tokenizer.json}）；</li>
 *   <li>模型：{@code bge-small-zh-v1.5} 导出为 ONNX，放 {@code data/models/bge-onnx/}
 *       （含 {@code model.onnx} 与 {@code tokenizer.json}）；</li>
 *   <li>池化必须是 <b>CLS</b>（取 {@code last_hidden_state[:, 0, :]}）而<b>不是</b> mean，
 *       再做 L2 归一化——对齐 sentence-transformers 的口径；</li>
 *   <li>检索侧要加 query 前缀「为这个句子生成表示以用于检索相关文章：」
 *       （所以本接口把 {@link #embed} 与 {@link #embedQuery} 分开）；
 *       注意维度是 512，与默认的 1024 不同，<b>换 provider 必须重建索引</b>——
 *       Lucene 的向量字段维度在索引创建时固定。</li>
 * </ol>
 */
public interface MemoryEmbeddingProvider {

    /** 实现名，用于日志与健康面板。 */
    String name();

    /** 向量维度。必须与 {@code memory.store.dimension} 及已有索引一致。 */
    int dimension();

    /**
     * 配置是否就绪。
     *
     * <p>注意语义：这里回答的是"配置层面是否具备向量化条件"，
     * <b>不保证</b>远端此刻可用——真正的运行时失败由 {@link #embed} 返回 {@code null} 表达。
     */
    boolean isReady();

    /**
     * 把一段文本向量化（入库侧）。
     *
     * @return 归一化向量；失败返回 {@code null}（调用方必须判空，不要吞成零向量——
     *         零向量参与余弦相似度会得到 NaN 或全零，污染检索结果）
     */
    float[] embed(String text);

    /** 把查询向量化（检索侧）。与 {@link #embed} 分开是为了支持需要 query 前缀的模型。 */
    float[] embedQuery(String query);
}
