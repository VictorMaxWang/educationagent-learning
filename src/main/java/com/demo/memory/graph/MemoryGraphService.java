package com.demo.memory.graph;

import com.demo.memory.MemoryProperties;
import com.demo.memory.MemoryRagService;
import com.demo.memory.MemoryRecord;
import com.demo.util.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 记忆脑图：把黑盒的长期记忆变成一张可看的图。
 *
 * <h3>分代的含义（不是装饰）</h3>
 * <pre>
 *   PENDING_GC  importance &lt; 2，或 EPISODIC 且 age &gt; 60 天  → 下次衰减就被删
 *   OLD         accessCount ≥ 5 且 importance ≥ 7            → 稳定事实
 *   SURVIVOR    importance ≥ 4，或 accessCount ≥ 2           → 有用的记忆
 *   NEW         其余                                          → 刚生成、还没被用过
 * </pre>
 * 这套阈值直接对应 {@code DecayScheduler} 的实际行为：图上颜色越冷，
 * 越接近被删除。演示时可以说清"这块是记忆的回收站"，而不只是一张好看的图。
 *
 * <h3>边的两种类型</h3>
 * <ul>
 *   <li>{@code HIERARCHY} —— 归属边，根节点「用户画像」→ 记忆。画像类记忆
 *       （BASIC / SEMANTIC / WORKING）天然属于"用户是谁"，直接挂根节点。</li>
 *   <li>{@code SIMILARITY} —— 语义边，记忆之间的余弦相似度 ≥ {@code similarityThreshold}。</li>
 * </ul>
 * <b>EPISODIC 的归属是补出来的：</b>方案书只写了 BASIC/SEMANTIC 连根节点，没说事件类记忆挂哪。
 * 若放任不管，一条"彼此都不相似、又没连根"的事件记忆会变成飘在空中的孤立点——
 * 看起来像渲染坏了，也看不出它属于谁。所以这里做兜底：<b>一条边都没有的节点补一条归属边</b>。
 *
 * <h3>执行方式</h3>
 * O(N²) 余弦计算放到 {@code graphTaskExecutor}（core=1/max=2/queue=20）里做，
 * 配 {@code timeoutSeconds} 超时降级，外加 {@code cacheTtlSeconds} 的结果缓存——
 * 前端每 15 秒轮询一次，没有缓存的话记忆多的用户会周期性把 CPU 打满。
 *
 * <p>节点大小 {@code 10 × (1 + ln(1+accessCount) × importance/10)}——用对数是为了让
 * "被访问 100 次"和"被访问 10 次"的视觉差距不至于夸张到把其他节点挤没。
 */
@Slf4j
@Service
public class MemoryGraphService {

    /** 根节点 ID。 */
    public static final String ROOT_ID = "__root__";

    /**
     * 根节点标签。方案书把这层叫「用户画像」——根节点代表的是"这个用户是谁"，
     * 而不是"这个用户的所有记忆"。
     */
    public static final String ROOT_LABEL = "用户画像";

    /** 根节点固定半径，方案书指定。 */
    private static final double ROOT_SIZE = 30.0;

    /** 标签截断长度。 */
    private static final int LABEL_CHARS = 24;

    private static final String ROOT_TYPE = "ROOT";

    /** 缓存条目上限。超了先清过期项，仍超就整体清空——展示用缓存，重建成本可接受。 */
    private static final int MAX_CACHE_ENTRIES = 500;

    private final MemoryRagService memoryRagService;
    private final MemoryProperties properties;

    /**
     * 用 ObjectProvider 而不是直接注入：{@code memory.graph.enabled=false} 时
     * {@link GraphExecutorConfig} 不会装配这个 Bean，直接注入会让整个应用起不来。
     * 取不到就退回同步构建（功能仍然可用，只是没有并发隔离和超时保护）。
     */
    private final ObjectProvider<GraphExecutorConfig.GraphExecutor> executorProvider;

    /** userId → 图结果 + 过期时间。手写 TTL，项目没引 Caffeine。 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(MemoryGraphDTO.Graph graph, long expireAtMs) {
    }

    public MemoryGraphService(MemoryRagService memoryRagService,
                              MemoryProperties properties,
                              ObjectProvider<GraphExecutorConfig.GraphExecutor> executorProvider) {
        this.memoryRagService = memoryRagService;
        this.properties = properties;
        this.executorProvider = executorProvider;
    }

    /**
     * 构造某用户的记忆脑图。
     *
     * <p>走 TTL 缓存；未命中则在专用线程池里构建并限时等待，<b>超时/异常统一降级为
     * 只有根节点的空图</b>——脑图是只读展示，宁可显示"还没有记忆"，也不该让一个 O(N²)
     * 计算把接口拖垮或抛错。
     *
     * @return 图数据；功能未启用或该用户无记忆时返回只有根节点的空图（前端应显示"还没有记忆"）
     */
    public MemoryGraphDTO.Graph build(String userId) {
        if (userId == null || userId.isBlank()) {
            return emptyGraph(userId, TraceContext.getTraceId());
        }
        if (!properties.getGraph().isEnabled() || !memoryRagService.isUsable()) {
            return emptyGraph(userId, TraceContext.getTraceId());
        }

        MemoryGraphDTO.Graph cached = getCached(userId);
        if (cached != null) {
            return cached;
        }

        // TraceContext 是 ThreadLocal，跨线程会丢——必须在 submit 之前取出来带进任务。
        // 这里刻意不调 TraceContext.begin()：脑图每 15 秒被轮询一次，
        // begin/end 会在 data/traces/ 里刷出大量无意义的小文件。
        String traceId = TraceContext.getTraceId();
        int timeoutSeconds = Math.max(1, properties.getGraph().getTimeoutSeconds());

        MemoryGraphDTO.Graph graph;
        GraphExecutorConfig.GraphExecutor executor = executorProvider.getIfAvailable();
        if (executor == null) {
            // enabled=false 时线程池不装配，退回同步构建
            graph = buildGraph(userId, traceId);
        } else {
            Future<MemoryGraphDTO.Graph> future = executor.submit(() -> buildGraph(userId, traceId));
            if (future == null) {
                // 线程池已关闭/拒绝 → 退回同步构建，功能不受影响
                graph = buildGraph(userId, traceId);
            } else {
                try {
                    graph = future.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    log.warn("【记忆库-脑图】[{}] 构建超时（{}s），降级为空图", userId, timeoutSeconds);
                    graph = emptyGraph(userId, traceId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    graph = emptyGraph(userId, traceId);
                } catch (ExecutionException e) {
                    log.warn("【记忆库-脑图】[{}] 构建异常，降级为空图：{}", userId,
                            e.getCause() == null ? e.toString() : e.getCause().toString());
                    graph = emptyGraph(userId, traceId);
                }
            }
        }

        putCache(userId, graph);
        return graph;
    }

    /** 主动失效某用户的缓存（清空记忆后必须调用，否则最长 10 秒内还会看到旧图）。 */
    public void evict(String userId) {
        if (userId != null) {
            cache.remove(userId);
        }
    }

    // ======================== 缓存 ========================

    private MemoryGraphDTO.Graph getCached(String userId) {
        if (properties.getGraph().getCacheTtlSeconds() <= 0) return null;
        CacheEntry entry = cache.get(userId);
        if (entry == null) return null;
        if (System.currentTimeMillis() >= entry.expireAtMs()) {
            cache.remove(userId);
            return null;
        }
        return entry.graph();
    }

    private void putCache(String userId, MemoryGraphDTO.Graph graph) {
        int ttl = properties.getGraph().getCacheTtlSeconds();
        if (ttl <= 0) return;
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(e -> now >= e.getValue().expireAtMs());
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(userId, new CacheEntry(graph, System.currentTimeMillis() + ttl * 1000L));
    }

    // ======================== 构图 ========================

    private MemoryGraphDTO.Graph buildGraph(String userId, String traceId) {
        long now = System.currentTimeMillis();

        // 需要向量来算节点之间的相似度
        List<MemoryRecord> all = memoryRagService.listAll(userId, true);
        int maxNodes = Math.max(2, properties.getGraph().getMaxNodes());
        int minAccess = Math.max(0, properties.getGraph().getMinAccessCount());

        List<MemoryRecord> filtered = all.stream()
                .filter(r -> r.getAccessCount() >= minAccess)
                .toList();
        // truncated 只在"因为过滤而丢弃"时为真；minAccess 过滤掉的不算截断（那是用户主动设置的过滤）
        boolean truncated = filtered.size() > maxNodes - 1;
        List<MemoryRecord> picked = filtered.size() > maxNodes - 1
                ? filtered.subList(0, maxNodes - 1)
                : filtered;

        List<MemoryGraphDTO.Node> nodes = new ArrayList<>();
        // 根节点永远在，否则前端拿到空数组时不好区分"没数据"和"出错了"
        nodes.add(new MemoryGraphDTO.Node(ROOT_ID, ROOT_LABEL, "记忆根节点", "", "ROOT", ROOT_TYPE,
                0, 0, now, 0, ROOT_SIZE, true));

        Map<String, Integer> genCounts = new LinkedHashMap<>();
        for (MemoryGraphDTO.Generation g : MemoryGraphDTO.Generation.values()) {
            genCounts.put(g.name(), 0);
        }
        Map<String, Integer> typeDistribution = new LinkedHashMap<>();

        for (MemoryRecord r : picked) {
            MemoryGraphDTO.Generation gen = generation(r, now);
            genCounts.merge(gen.name(), 1, Integer::sum);
            String type = r.getType() == null ? "BASIC" : r.getType().name();
            typeDistribution.merge(type, 1, Integer::sum);
            nodes.add(new MemoryGraphDTO.Node(
                    r.getId(),
                    nodeLabel(r),
                    r.getContent(),
                    r.getKeywords(),
                    gen.name(),
                    type,
                    r.getImportance(),
                    r.getAccessCount(),
                    r.getCreatedAt(),
                    Math.max(0, (now - r.getCreatedAt()) / 86_400_000L),
                    nodeSize(r),
                    false));
        }

        List<MemoryGraphDTO.Link> links = buildLinks(picked);

        int rootDegree = 0;
        for (MemoryGraphDTO.Link l : links) {
            if (ROOT_ID.equals(l.source())) rootDegree++;
        }

        Map<String, Object> stats = new LinkedHashMap<>(genCounts);
        stats.put("totalCount", all.size());
        // 不含根节点：根恒有 1 个，算进去只会让"我有几条记忆"这个数每次都多 1
        stats.put("nodeCount", nodes.size() - 1);
        stats.put("linkCount", links.size());
        stats.put("rootDegree", rootDegree);
        stats.put("typeDistribution", typeDistribution);
        stats.put("traceId", traceId);

        log.info("【记忆库-脑图】[{}] 节点 {} 个 / 边 {} 条 / 分代 {} / traceId={}",
                userId, nodes.size() - 1, links.size(), genCounts, traceId);
        return new MemoryGraphDTO.Graph(userId, nodes, links, truncated, stats);
    }

    private MemoryGraphDTO.Graph emptyGraph(String userId, String traceId) {
        long now = System.currentTimeMillis();
        List<MemoryGraphDTO.Node> nodes = List.of(
                new MemoryGraphDTO.Node(ROOT_ID, ROOT_LABEL, "记忆根节点", "", "ROOT", ROOT_TYPE,
                        0, 0, now, 0, ROOT_SIZE, true));

        Map<String, Object> stats = new LinkedHashMap<>();
        for (MemoryGraphDTO.Generation g : MemoryGraphDTO.Generation.values()) {
            stats.put(g.name(), 0);
        }
        stats.put("totalCount", 0);
        stats.put("nodeCount", 0);
        stats.put("linkCount", 0);
        stats.put("rootDegree", 0);
        stats.put("typeDistribution", new LinkedHashMap<String, Integer>());
        stats.put("traceId", traceId);
        return new MemoryGraphDTO.Graph(userId, nodes, List.of(), false, stats);
    }

    /** 分代判定。阈值与 DecayScheduler 的实际行为对应。 */
    static MemoryGraphDTO.Generation generation(MemoryRecord r, long now) {
        long ageDays = Math.max(0, (now - r.getCreatedAt()) / 86_400_000L);
        boolean episodic = r.getType() == com.demo.memory.MemoryType.EPISODIC;

        if (r.getImportance() < 2 || (episodic && ageDays > 60)) {
            return MemoryGraphDTO.Generation.PENDING_GC;
        }
        if (r.getAccessCount() >= 5 && r.getImportance() >= 7) {
            return MemoryGraphDTO.Generation.OLD;
        }
        if (r.getImportance() >= 4 || r.getAccessCount() >= 2) {
            return MemoryGraphDTO.Generation.SURVIVOR;
        }
        return MemoryGraphDTO.Generation.NEW;
    }

    /** 节点半径：对数放大访问次数，避免高频节点把其他节点挤没。 */
    static double nodeSize(MemoryRecord r) {
        double size = 10.0 * (1.0 + Math.log1p(Math.max(0, r.getAccessCount())) * (r.getImportance() / 10.0));
        return Math.round(size * 10.0) / 10.0;
    }

    /**
     * 建边：归属边（根 → 画像类记忆）+ 语义边（记忆之间余弦 ≥ 阈值）+ 孤立节点兜底。
     *
     * <p>归属边的权重用重要度归一化，让"重要的记忆"在视觉上离根更近。
     */
    private List<MemoryGraphDTO.Link> buildLinks(List<MemoryRecord> records) {
        List<MemoryGraphDTO.Link> links = new ArrayList<>();
        double threshold = properties.getGraph().getSimilarityThreshold();

        // ① 归属边：画像类记忆（BASIC / SEMANTIC / WORKING）天然属于"用户是谁"
        Set<String> anchored = new HashSet<>();
        for (MemoryRecord r : records) {
            if (isProfileType(r)) {
                links.add(new MemoryGraphDTO.Link(ROOT_ID, r.getId(),
                        rootWeight(r), MemoryGraphDTO.EDGE_HIERARCHY));
                anchored.add(r.getId());
            }
        }

        // ② 语义边：O(N²) 余弦
        Set<String> touched = new HashSet<>();
        for (int i = 0; i < records.size(); i++) {
            MemoryRecord a = records.get(i);
            if (a.getEmbedding() == null) continue;
            for (int j = i + 1; j < records.size(); j++) {
                MemoryRecord b = records.get(j);
                if (b.getEmbedding() == null) continue;
                double sim = cosine(a.getEmbedding(), b.getEmbedding());
                if (sim >= threshold) {
                    links.add(new MemoryGraphDTO.Link(a.getId(), b.getId(),
                            Math.round(sim * 1000.0) / 1000.0, MemoryGraphDTO.EDGE_SIMILARITY));
                    touched.add(a.getId());
                    touched.add(b.getId());
                }
            }
        }

        // ③ 兜底：非画像类（EPISODIC）且一条边都没有 → 补归属边，避免孤立点
        for (MemoryRecord r : records) {
            if (!anchored.contains(r.getId()) && !touched.contains(r.getId())) {
                links.add(new MemoryGraphDTO.Link(ROOT_ID, r.getId(),
                        rootWeight(r), MemoryGraphDTO.EDGE_HIERARCHY));
                anchored.add(r.getId());
            }
        }
        return links;
    }

    /** 画像类 = 描述"用户是谁"的记忆；EPISODIC 是"发生过什么"，不在此列。 */
    private static boolean isProfileType(MemoryRecord r) {
        return r.getType() != com.demo.memory.MemoryType.EPISODIC;
    }

    private static double rootWeight(MemoryRecord r) {
        return Math.min(1.0, r.getImportance() / 10.0);
    }

    /** 余弦相似度。两侧都允许非归一化向量（不假设上游一定做了归一化）。 */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 节点显示标签：<b>优先用关键词</b>。
     *
     * <p>埋点捕获的原句可能有几十个字，截断到 24 字在同心环上根本读不过来；
     * 关键词是本地切出来的短片段（"最近压力,搬家"），天然适合当标签。
     * 规则抽出的干净事实本身就很短（"用户叫张三"），没有关键词也够读，所以回退到正文。
     */
    private static String nodeLabel(MemoryRecord r) {
        String kw = r.getKeywords();
        if (kw != null && !kw.isBlank()) {
            return truncate(kw.replace(',', ' '), LABEL_CHARS);
        }
        return truncate(r.getContent(), LABEL_CHARS);
    }

    /** 供排序稳定（前端也按此顺序绘制）。 */
    static Comparator<MemoryRecord> byImportanceThenRecency() {
        return Comparator.comparingInt(MemoryRecord::getImportance).reversed()
                .thenComparing(Comparator.comparingLong(MemoryRecord::getCreatedAt).reversed());
    }
}
