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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 向量嵌入服务 —— 支持三种提供方，通过 {@code memory.embedding.provider} 切换：
 *
 * <ul>
 *   <li>{@code dashscope}：阿里云百炼 text-embedding-v3（1024 维，云端付费，欠费即全部失败）</li>
 *   <li>{@code siliconflow}：硅基流动 BAAI/bge-m3（1024 维，OpenAI 兼容协议，独立 key）</li>
 *   <li>{@code ollama}：本地模型（默认 bge-m3，1024 维；完全免费、离线、无额度限制）</li>
 * </ul>
 *
 * <p>默认模型维度可能都为 1024，但不同模型的语义空间并不兼容。
 * 切换模型须按集合归属规划重新嵌入，不能仅凭维度相同混用新旧向量；本服务不会自动迁移集合。</p>
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

    /** 硅基流动模型名；同维度模型切换仍需由集合持有者处理语义空间迁移。 */
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

    /** 新推荐入口不经可能自动重试 POST 的旧 Apache/RestTemplate 客户端。 */
    private final HttpClient onceHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();

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
     * 向调用者公开不含凭据的模型身份；相同维度不表示不同模型的向量空间兼容。
     * 新推荐模块据此分区自己的缓存，本方法不迁移或清理任何既有 Chroma 集合。
     */
    /** provider 是历史配置中的适配器标签，不据此宣称兼容网关背后的真实模型供应商。 */
    public record Identity(String provider, String model, int dimension, String endpointFingerprint) {
        public Identity(String provider, String model, int dimension) { this(provider,model,dimension,"legacy-unspecified"); }
    }
    public Identity describeIdentity() {
        return new Identity(isOllama() ? "ollama" : isSiliconflow() ? "siliconflow" : "dashscope",
                isOllama() ? ollamaModel : isSiliconflow() ? siliconflowModel : dashscopeModel,
                expectedDimension,endpointFingerprint(isOllama()?ollamaUrl+OLLAMA_EMBED_PATH:isSiliconflow()?siliconflowUrl:DASHSCOPE_EMBEDDING_URL));
    }

    private static String endpointFingerprint(String endpoint) {
        try {
            URI uri=URI.create(endpoint).normalize();String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host=uri.getHost()==null?"":uri.getHost().toLowerCase(java.util.Locale.ROOT);int port=uri.getPort();
            if((scheme.equals("https")&&port==443)||(scheme.equals("http")&&port==80))port=-1;
            String canonical=scheme+"://"+host+(port<0?"":":"+port)+(uri.getRawPath()==null||uri.getRawPath().isEmpty()?"/":uri.getRawPath())
                    +(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception invalid){throw new IllegalStateException("embedding endpoint identity unavailable");}
    }

    public record BatchResult(List<List<Float>> vectors,ObjectNode evidence) {
        public BatchResult {
            vectors=vectors==null?null:vectors.stream().map(List::copyOf).toList();evidence=evidence.deepCopy();
        }
    }

    /**
     * 新资源推荐的单次批量入口（最多 10 条），失败返回 null，不逐条补发或切换旧接口。
     * 显式校验响应索引、数量、维度及有限值；调用方同时绑定 describeIdentity 的模型空间身份。
     * 旧接口的容错行为保持不变，未知结果不能靠重复付费请求伪装成成功。
     */
    public List<List<Float>> generateEmbeddingsOnce(List<String> texts) {
        return generateEmbeddingsOnce(texts, Duration.ofSeconds(20));
    }

    /** 总预算包含请求构造、响应正文和解析；429/503、慢正文、连接中断均不重发 POST。 */
    public List<List<Float>> generateEmbeddingsOnce(List<String> texts, Duration budget) {
        return generateEmbeddingsOnceWithEvidence(texts,budget).vectors();
    }

    public BatchResult generateEmbeddingsOnceWithEvidence(List<String> texts) {
        return generateEmbeddingsOnceWithEvidence(texts,Duration.ofSeconds(20));
    }

    /** 返回本次真实调用证据；配置模型与响应模型分别记录，无响应字段时明确为 null。 */
    public BatchResult generateEmbeddingsOnceWithEvidence(List<String> texts, Duration budget) {
        if (texts == null || texts.isEmpty() || texts.size() > 10
                || texts.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 20_000)
                || budget == null || budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(20)) > 0)
            throw new IllegalArgumentException("embedding batch requires 1 to 10 bounded texts and a budget up to 20 seconds");
        long deadline = System.nanoTime() + budget.toNanos();
        List<String> inputTexts=List.copyOf(texts);
        EmbeddingTrace trace=new EmbeddingTrace(describeIdentity());
        java.util.concurrent.atomic.AtomicReference<CompletableFuture<HttpResponse<byte[]>>> transport = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.FutureTask<List<List<Float>>> operation = new java.util.concurrent.FutureTask<>(() -> performOnce(inputTexts, deadline, transport,trace));
        Thread.ofVirtual().name("recommendation-embedding-once").start(operation);
        try {
            List<List<Float>> vectors=operation.get(onceRemaining(deadline), TimeUnit.NANOSECONDS);
            return new BatchResult(vectors,trace.snapshot(vectors!=null?null:"response-unknown"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();return new BatchResult(null,trace.snapshot("cancelled"));
        } catch (Exception unavailable) {
            logThrottled("推荐单次批量", "供应商响应未知；未重试，请手动重新准备推荐");
            return new BatchResult(null,trace.snapshot(unavailable instanceof java.util.concurrent.TimeoutException?"deadline":"response-unknown"));
        } finally {
            if (!operation.isDone()) operation.cancel(true);
            CompletableFuture<HttpResponse<byte[]>> pending = transport.get();
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private List<List<Float>> performOnce(List<String> texts, long deadline,
            java.util.concurrent.atomic.AtomicReference<CompletableFuture<HttpResponse<byte[]>>> transport,EmbeddingTrace trace) throws Exception {
            JSONObject body = new JSONObject();
            String endpoint;
            String apiKey = null;
            if (isOllama()) {
                body.put("model", ollamaModel);body.put("input", texts);
                endpoint = ollamaUrl + OLLAMA_EMBED_PATH;
            } else if (isSiliconflow()) {
                body.put("model", siliconflowModel);body.put("input", texts);
                if (requestDimensions) body.put("dimensions", expectedDimension);
                endpoint = siliconflowUrl;apiKey = siliconflowKey;
            } else {
                body.put("model", dashscopeModel);
                JSONObject input = new JSONObject();input.put("texts", texts);body.put("input", input);
                JSONObject parameters = new JSONObject();parameters.put("dimension", expectedDimension);body.put("parameters", parameters);
                endpoint = DASHSCOPE_EMBEDDING_URL;apiKey = dashscopeApiKey;
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofNanos(onceRemaining(deadline))).header("Content-Type", "application/json")
                    .header("Accept-Encoding", "identity")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
            onceRemaining(deadline);
            trace.started();
            CompletableFuture<HttpResponse<byte[]>> pending = onceHttp.sendAsync(request.build(), info -> {trace.headers(info.statusCode(),info.headers());return new OnceBody();});
            transport.set(pending);
            try {
            HttpResponse<byte[]> response = pending.get(onceRemaining(deadline), TimeUnit.NANOSECONDS);
            onceRemaining(deadline);
            JSONObject parsed = JSON.parseObject(new String(response.body(), StandardCharsets.UTF_8));
            trace.received(parsed);
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            List<List<Float>> result = parseOnceEmbeddings(parsed,texts.size(),isOllama(),isSiliconflow());
            onceRemaining(deadline);
            return result;
            } finally { if (!pending.isDone()) pending.cancel(true); }
    }

    private List<List<Float>> parseOnceEmbeddings(JSONObject parsed,int count,boolean ollama,boolean siliconflow) {
            JSONArray rows;
            List<List<Float>> result = new ArrayList<>(java.util.Collections.nCopies(count, null));
            if (ollama) {
                rows = parsed == null ? null : parsed.getJSONArray("embeddings");
                if (rows == null || rows.size() != count) return null;
                for (int i = 0; i < rows.size(); i++) result.set(i, onceVector(rows.getJSONArray(i)));
            } else {
                rows = parsed == null ? null : siliconflow ? parsed.getJSONArray("data")
                        : parsed.getJSONObject("output") == null ? null : parsed.getJSONObject("output").getJSONArray("embeddings");
                if (rows == null || rows.size() != count) return null;
                for (int i = 0; i < rows.size(); i++) {
                    JSONObject item = rows.getJSONObject(i);String field = siliconflow ? "index" : "text_index";
                    if (item == null || !item.containsKey(field)) return null;
                    Object rawIndex = item.get(field);
                    if (!(rawIndex instanceof Integer) && !(rawIndex instanceof Long)) return null;
                    long index = ((Number) rawIndex).longValue();
                    if (index < 0 || index >= count || result.get((int) index) != null) return null;
                    result.set((int) index, onceVector(item.getJSONArray("embedding")));
                }
            }
            if (result.stream().anyMatch(v -> v == null || v.size() != expectedDimension
                    || v.stream().anyMatch(n -> n == null || !Float.isFinite(n)))) return null;
            return result;
    }

    /** 只记录响应确实给出的元数据，不保存URL、请求文本、密钥或完整上游错误正文。 */
    private final class EmbeddingTrace {
        private final long startedAt=System.nanoTime();
        private final ObjectMapper mapper=new ObjectMapper();
        private final ObjectNode value=mapper.createObjectNode();
        EmbeddingTrace(Identity identity) {
            value.put("transport","jdk-http-once").put("adapterMode",identity.provider()).put("endpointFingerprint",identity.endpointFingerprint())
                    .put("attemptCount",0).put("startedAt",java.time.Instant.now().toString()).put("dimension",identity.dimension());
            safePut("requestedModel",identity.model());
            for(String field:List.of("httpStatus","requestId","model","provider","tokenUsage"))value.putNull(field);
        }
        synchronized void started(){value.put("attemptCount",1);}
        synchronized void headers(int status,java.net.http.HttpHeaders headers) {
            value.put("httpStatus",status);
            safePut("requestId",headers.firstValue("x-request-id").orElseGet(()->headers.firstValue("x-dashscope-request-id")
                    .orElseGet(()->headers.firstValue("request-id").orElse(null))));
        }
        synchronized void received(JSONObject response) {
            if(response==null)return;
            safePut("model",response.get("model") instanceof String model?model:null);
            if(value.path("requestId").isNull()) {
                Object id=response.get("request_id");if(!(id instanceof String))id=response.get("requestId");if(!(id instanceof String))id=response.get("id");
                safePut("requestId",id instanceof String text?text:null);
            }
            if(response.get("provider") instanceof String provider)safePut("provider",provider);
            Object raw=response.get("usage");ObjectNode usage=mapper.createObjectNode();
            if(raw instanceof JSONObject source)for(String field:List.of("prompt_tokens","completion_tokens","total_tokens","input_tokens","output_tokens")) {
                var count=mapper.valueToTree(source.get(field));
                if(count!=null&&count.isIntegralNumber()&&count.canConvertToLong()&&count.asLong()>=0)usage.put(field,count.asLong());
            }
            value.set("tokenUsage",usage.isEmpty()?mapper.nullNode():usage);
        }
        synchronized ObjectNode snapshot(String failureKind) {
            ObjectNode out=value.deepCopy().put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-startedAt))
                    .put("mode",value.path("attemptCount").asInt()==0?"configured":"live").put("validation",failureKind==null?"passed":"unknown");
            if(failureKind==null)out.putNull("failureKind");else out.put("failureKind",failureKind);
            return out;
        }
        private void safePut(String field,String raw) {
            if(raw==null||raw.isBlank()||raw.length()>200||!raw.matches("[A-Za-z0-9._:/@-]+")
                    ||raw.startsWith("http:")||raw.startsWith("https:")){value.putNull(field);return;}
            for(String key:new String[]{dashscopeApiKey,siliconflowKey})if(key!=null&&!key.isBlank()&&raw.contains(key)){value.putNull(field);return;}
            value.put(field,raw);
        }
    }

    private static long onceRemaining(long deadline) throws java.util.concurrent.TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.TimeoutException("embedding deadline");
        return remaining;
    }

    private static final class OnceBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > 2 * 1024 * 1024 - bytes.size()) {
                    subscription.cancel();result.completeExceptionally(new IllegalStateException("embedding response too large"));return;
                }
                byte[] data = new byte[chunk.remaining()];chunk.get(data);bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    @PreDestroy public void closeOnceTransport() { onceHttp.shutdownNow(); }

    private List<Float> onceVector(JSONArray raw) {
        if (raw == null || raw.size() != expectedDimension) return null;
        List<Float> vector = new ArrayList<>(raw.size());
        for (Object value : raw) {
            if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())) return null;
            vector.add(number.floatValue());
        }
        return vector;
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
     * <p>返回维度可能与其他提供方相同，但不能据此混合不同模型的缓存或集合。</p>
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
