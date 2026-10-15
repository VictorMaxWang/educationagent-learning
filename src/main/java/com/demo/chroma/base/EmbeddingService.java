package com.demo.chroma.base;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 向量嵌入服务 —— 支持三种提供方，通过 {@code memory.embedding.provider} 切换：
 *
 * <ul>
 *   <li>{@code dashscope}：阿里云百炼 text-embedding-v3（1024 维，云端付费，欠费即全部失败）</li>
 *   <li>{@code siliconflow}：硅基流动 BAAI/bge-m3（1024 维，OpenAI 兼容协议，独立 key）</li>
 *   <li>{@code ollama}：本地模型（默认 bge-m3，1024 维；完全免费、离线、无额度限制）</li>
 * </ul>
 *
 * <p>{@code siliconflow} 与 {@code ollama} 的默认模型均为 <b>1024 维</b>，与 DashScope 一致，
 * 因此在这三者之间切换 <b>不需要清理任何集合</b>；若换成 768 维的 nomic-embed-text 则需要。</p>
 *
 * <p><b>维度是硬约束</b>：Chroma 的集合维度在首次写入时确定，之后写入不同维度的向量会直接
 * 返回 400 {@code Collection expecting embedding with dimension of X, got Y}。
 * 因此切换提供方时若维度变化，<b>必须删掉旧集合重建</b>（知识库索引器已支持自动重建，
 * 用户记忆集合需要在切换时手工清理一次，见启动日志告警）。</p>
 *
 * <p>本类只负责「文本 → 向量」，不含任何检索/入库逻辑，切换提供方不影响上层业务。</p>
 */
@Slf4j
@Service
public class EmbeddingService {

    /** 提供方：dashscope | ollama */
    @Value("${memory.embedding.provider:dashscope}")
    private String provider;

    @Value("${ai.aliyun.key:}")
    private String dashscopeApiKey;

    @Value("${memory.embedding.model:text-embedding-v3}")
    private String dashscopeModel;

    /** 期望维度。DashScope 由本参数指定；Ollama 由模型决定，此值仅用于启动期校验告警 */
    @Value("${memory.embedding.dimension:1024}")
    private int expectedDimension;

    /** 兼容端点支持可变维度时显式请求，默认不改变既有固定维度模型的协议。 */
    @Value("${memory.embedding.request-dimensions:false}")
    private boolean requestDimensions;

    @Value("${memory.embedding.ollama.url:http://127.0.0.1:11434}")
    private String ollamaUrl;

    @Value("${memory.embedding.ollama.model:bge-m3}")
    private String ollamaModel;

    @Value("${memory.embedding.siliconflow.key:}")
    private String siliconflowKey;

    @Value("${memory.embedding.siliconflow.url:https://api.siliconflow.cn/v1/embeddings}")
    private String siliconflowUrl;

    /** 硅基流动模型名。BAAI/bge-m3 为 1024 维，与 DashScope 一致，切换后无需清理集合 */
    @Value("${memory.embedding.siliconflow.model:BAAI/bge-m3}")
    private String siliconflowModel;

    /** DashScope 用默认 30s 客户端（云端批量快）；Ollama 本地 CPU 推理慢，用 180s 的 llmRestTemplate */
    @Resource
    private RestTemplate restTemplate;

    @Resource
    @Qualifier("llmRestTemplate")
    private RestTemplate ollamaRestTemplate;

    private static final String DASHSCOPE_EMBEDDING_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    /** Ollama 批量嵌入接口（Ollama 较新版本） */
    private static final String OLLAMA_EMBED_PATH = "/api/embed";

    /** Ollama 单条嵌入接口（旧版本回退用） */
    private static final String OLLAMA_LEGACY_PATH = "/api/embeddings";

    /** 启动期探针文本，用于确认提供方可用 + 实际维度 */
    private static final String PROBE_TEXT = "连接测试";

    /**
     * 失败日志节流窗口。
     * <p>账户欠费/服务不可达这类<b>持续性</b>故障会在每个 chunk 上重复触发，一次入库就能刷出
     * 几十条一模一样的信息，把真正的问题淹掉（实测刷出过 40+ 条相同的 402）。
     * 这里同一个窗口内只打一条，同时仍在累计次数，既不刷屏也不丢信息。</p>
     */
    private static final long FAILURE_LOG_INTERVAL_MS = 30_000;

    private final AtomicLong lastFailureLogAt = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);

    /** 失败日志节流输出：窗口内首次用 WARN，其余降到 DEBUG（次数仍累计） */
    private void logThrottled(String label, String msg) {
        long n = failureCount.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastFailureLogAt.get();
        if (now - last >= FAILURE_LOG_INTERVAL_MS && lastFailureLogAt.compareAndSet(last, now)) {
            log.warn("{} Embedding 失败（累计第 {} 次；同一故障 {}s 内只打一条以免刷屏）: {}",
                    label, n, FAILURE_LOG_INTERVAL_MS / 1000, msg);
        } else {
            log.debug("{} Embedding 失败（第 {} 次）: {}", label, n, msg);
        }
    }

    // ======================== 启动期自检 ========================

    @PostConstruct
    public void init() {
        // 无论哪种提供方都探一次。这是刻意的：本模块曾被「失败被静默吞掉」坑过 ——
        // 云端账户欠费时入库全程无报错、指纹照样记「已完成」，直到很久以后才发现知识库是空的。
        // 启动期一次自检就能把「账户欠费 / 余额不足 / 模型未下载 / 维度不符」直接摆到日志里。
        log.info("🧬 Embedding 提供方={}，模型={}，期望维度={}", provider, describeModel(), expectedDimension);

        List<Float> probe = doEmbedOne(PROBE_TEXT);
        if (probe == null || probe.isEmpty()) {
            log.error("🧬 Embedding 自检失败 —— 入库与检索都会返回空。{}", hintOnFailure());
            return;
        }
        int actual = probe.size();
        if (actual != expectedDimension) {
            log.warn("🧬 模型真实维度为 {}，与配置 memory.embedding.dimension={} 不一致。"
                            + "Chroma 集合维度在首次写入时即固定，旧集合会拒绝新向量。"
                            + "知识库集合会在下次索引时自动删掉重建；"
                            + "用户记忆集合（user_memory_*）没有自动重建机制，需要手工清理一次。",
                    actual, expectedDimension);
        } else {
            log.info("🧬 Embedding 自检通过（实际维度={}）", actual);
        }
    }

    private String describeModel() {
        if (isOllama()) return ollamaModel + " @ " + ollamaUrl;
        if (isSiliconflow()) return siliconflowModel + " @ " + siliconflowUrl;
        return dashscopeModel;
    }

    /** 自检失败时给出针对当前提供方的可操作提示 */
    private String hintOnFailure() {
        if (isOllama()) {
            return "请确认已启动 ollama 服务，并已执行 `ollama pull " + ollamaModel + "`（地址 " + ollamaUrl + "）";
        }
        if (isSiliconflow()) {
            return "请确认 memory.embedding.siliconflow.key 有效，且硅基流动账户有余额"
                    + "（余额不足时接口返回 402 / code 30001，即使标记为免费的 embedding 亦然）";
        }
        return "请确认 ai.aliyun.key 有效且阿里云百炼账户未欠费"
                + "（欠费时接口返回 Arrearage / overdue-payment）";
    }

    private boolean isOllama() {
        return "ollama".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    private boolean isSiliconflow() {
        return "siliconflow".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    // ======================== 对外接口（签名与行为保持不变） ========================

    /**
     * 将单段文本转为向量。
     *
     * @return 向量（维度由提供方/模型决定），失败返回 null
     */
    public List<Float> generateEmbedding(String text) {
        return doEmbedOne(text);
    }

    /**
     * 批量转向量（每条独立调用，稳定性优先）。
     */
    public List<List<Float>> generateEmbeddings(List<String> texts) {
        return texts.stream()
                .map(this::generateEmbedding)
                .collect(Collectors.toList());
    }

    /**
     * 批量转向量（一次请求可含多条文本，减少请求数）。
     * <p>大批量文档入库（如民法典）专用：batchSize 条/请求 + 轻微延时防限流，
     * 单批失败时自动逐条兜底（复用 {@link #generateEmbedding}）。</p>
     *
     * @param texts     待向量化文本（顺序与返回一一对应）
     * @param batchSize 每请求条数（建议 8~12）
     * @return 每段文本一个向量；单条失败该位为 null
     */
    public List<List<Float>> generateEmbeddingsBatch(List<String> texts, int batchSize) {
        if (texts == null || texts.isEmpty()) return new ArrayList<>();
        int size = Math.max(1, batchSize);
        List<List<Float>> all = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += size) {
            List<String> batch = texts.subList(i, Math.min(i + size, texts.size()));
            List<List<Float>> batchVecs = doEmbedBatch(batch);
            if (batchVecs == null || batchVecs.size() != batch.size()) {
                // 批量失败，逐条兜底
                for (String t : batch) {
                    all.add(generateEmbedding(t));
                }
            } else {
                all.addAll(batchVecs);
            }
            // 轻微延时防限流（DashScope 有效；Ollama 本地无限制但延时也无害）
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return all;
    }

    /**
     * float 列表转 double 列表（Chroma API 要求 double 类型）
     */
    public List<Double> toDoubleList(List<Float> floats) {
        return floats.stream().map(Float::doubleValue).collect(Collectors.toList());
    }

    // ======================== 内部分发 ========================

    private List<Float> doEmbedOne(String text) {
        if (text == null || text.isBlank()) return null;
        List<List<Float>> r = doEmbedBatch(List.of(text));
        return (r != null && !r.isEmpty()) ? r.get(0) : null;
    }

    private List<List<Float>> doEmbedBatch(List<String> batch) {
        if (isOllama()) return ollamaEmbed(batch);
        if (isSiliconflow()) return siliconflowEmbed(batch);
        return dashscopeEmbed(batch);
    }

    // ======================== 硅基流动（OpenAI 兼容）========================

    /**
     * 调用硅基流动 /v1/embeddings（OpenAI 兼容协议）。
     * <p>默认模型 BAAI/bge-m3 为 1024 维，与 DashScope text-embedding-v3 一致，
     * 因此从 dashscope 切到 siliconflow <b>不需要清理任何集合</b>。</p>
     */
    private List<List<Float>> siliconflowEmbed(List<String> batch) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", siliconflowModel);
            body.put("input", batch);
            if (requestDimensions) body.put("dimensions", expectedDimension);

            ResponseEntity<String> resp = restTemplate.postForEntity(
                    siliconflowUrl,
                    new HttpEntity<>(body.toJSONString(), bearerHeaders(siliconflowKey)),
                    String.class);

            JSONObject result = JSON.parseObject(resp.getBody());
            JSONArray data = result == null ? null : result.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                log.warn("硅基流动未返回 data 字段，响应={}", truncate(String.valueOf(resp.getBody()), 300));
                return null;
            }

            // 按 index 归位，不假设返回顺序与入参一致
            List<List<Float>> out = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) out.add(null);
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = data.getJSONObject(i);
                if (item == null) continue;
                int idx = item.getIntValue("index", i);
                if (idx < 0 || idx >= out.size()) continue;
                out.set(idx, toFloatList(item.getJSONArray("embedding")));
            }
            return out;
        } catch (Exception e) {
            // 余额不足(402/code 30001)、key 无效(401)、限流(429) 都会走到这里。
            // 报错原文必须打出来，否则上层只看到「embedding 失败」而不知是账户问题
            logThrottled("硅基流动", truncate(e.getMessage(), 400));
            return null;
        }
    }

    // ======================== Ollama ========================

    /**
     * 调用本地 Ollama 生成向量。
     * <p>优先用 {@code /api/embed}（支持 input 数组批量）；若该接口不可用（旧版本 Ollama），
     * 回退到 {@code /api/embeddings}（每次一条）。</p>
     */
    private List<List<Float>> ollamaEmbed(List<String> texts) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", ollamaModel);
            body.put("input", texts);

            ResponseEntity<String> resp = ollamaRestTemplate.postForEntity(
                    ollamaUrl + OLLAMA_EMBED_PATH,
                    new HttpEntity<>(body.toJSONString(), jsonHeaders()),
                    String.class);

            JSONObject result = JSON.parseObject(resp.getBody());
            JSONArray arr = result == null ? null : result.getJSONArray("embeddings");
            if (arr == null || arr.isEmpty()) {
                log.debug("Ollama {} 未返回 embeddings，回退旧接口 {}", OLLAMA_EMBED_PATH, OLLAMA_LEGACY_PATH);
                return ollamaEmbedLegacy(texts);
            }
            List<List<Float>> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(toFloatList(i < arr.size() ? arr.getJSONArray(i) : null));
            }
            return out;
        } catch (Exception e) {
            logThrottled("Ollama", ollamaUrl + OLLAMA_EMBED_PATH + " -> " + e.getMessage());
            return ollamaEmbedLegacy(texts);
        }
    }

    /** 旧版 Ollama 接口：/api/embeddings，一次一条（prompt 单数） */
    private List<List<Float>> ollamaEmbedLegacy(List<String> texts) {
        List<List<Float>> out = new ArrayList<>();
        for (String t : texts) {
            try {
                JSONObject body = new JSONObject();
                body.put("model", ollamaModel);
                body.put("prompt", t);
                ResponseEntity<String> resp = ollamaRestTemplate.postForEntity(
                        ollamaUrl + OLLAMA_LEGACY_PATH,
                        new HttpEntity<>(body.toJSONString(), jsonHeaders()),
                        String.class);
                JSONObject result = JSON.parseObject(resp.getBody());
                out.add(toFloatList(result == null ? null : result.getJSONArray("embedding")));
            } catch (Exception e) {
                logThrottled("Ollama(回退接口)", "请确认 ollama 已启动且已执行 `ollama pull "
                        + ollamaModel + "`；原因：" + e.getMessage());
                out.add(null);
            }
        }
        return out;
    }

    // ======================== DashScope ========================

    /** 调用阿里云百炼批量嵌入；失败返回 null（由上层逐条兜底） */
    private List<List<Float>> dashscopeEmbed(List<String> batch) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", dashscopeModel);
            JSONObject input = new JSONObject();
            input.put("texts", batch);
            body.put("input", input);
            JSONObject params = new JSONObject();
            params.put("dimension", expectedDimension);
            body.put("parameters", params);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    DASHSCOPE_EMBEDDING_URL,
                    new HttpEntity<>(body.toJSONString(), dashscopeHeaders()),
                    String.class);

            JSONObject result = JSON.parseObject(response.getBody());
            JSONArray embeddings = result.getJSONObject("output").getJSONArray("embeddings");

            List<List<Float>> out = new ArrayList<>();
            for (int j = 0; j < batch.size(); j++) {
                out.add(toFloatList(embeddings.getJSONObject(j).getJSONArray("embedding")));
            }
            return out;
        } catch (Exception e) {
            // 欠费/限流/网络问题都会走到这里。报错原文（如 Arrearage）必须打出来，
            // 否则上层只看到「embedding 失败」而不知道是账户问题
            logThrottled("DashScope", e.getMessage() + "（将逐条重试）");
            return null;
        }
    }

    // ======================== 工具 ========================

    private static List<Float> toFloatList(JSONArray arr) {
        if (arr == null) return null;
        List<Float> embedding = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            embedding.add(arr.getFloatValue(i));
        }
        return embedding;
    }

    private HttpHeaders dashscopeHeaders() {
        return bearerHeaders(dashscopeApiKey);
    }

    /** 通用 Bearer 头（DashScope 与硅基流动都是 Bearer + JSON） */
    private HttpHeaders bearerHeaders(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + key);
        headers.add("Content-Type", "application/json");
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        return headers;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
