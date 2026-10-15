package com.demo.memory;

import com.demo.memory.embedding.MemoryEmbeddingProvider;
import com.demo.memory.store.LuceneMemoryStore;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用户记忆的读写入口：入库（含去重与逐出）、检索（含访问强化）、衰减。
 *
 * <h3>三个容易做错的地方</h3>
 * <ol>
 *   <li><b>写入必须真正带上向量。</b>{@link LuceneMemoryStore} 会拒绝没有向量的记录——
 *       这是刻意的：一条没有向量的记忆永远不会被检索到，属于"写了但等于没写"。</li>
 *   <li><b>访问强化必须保留向量。</b>Lucene 的 {@code updateDocument} 是整文档替换，
 *       如果强化时拿着一个没有向量的记录去更新，向量会被抹掉、这条记忆从此静默消失。
 *       所以 {@link #reinforce} 用"读回来 → 改计数 → 写回去"的方式，而不是 new 一个新对象。</li>
 *   <li><b>去重阈值不能太高也不能太低。</b>0.82 来自本项目 {@code UserMemoryService} 的实测值：
 *       「用户叫张三」与「用户的名字是张三」相似度约 0.5（不会被误判为重复），
 *       而重复抽取出的同义句一般在 0.9 以上。低到 0.6 会误杀，高到 0.95 会重复入库。</li>
 * </ol>
 */
@Slf4j
@Service
public class MemoryRagService {

    /** 去重相似度阈值（字符 bigram Jaccard）。 */
    private static final double DEDUPE_THRESHOLD = 0.82;

    /** 去重时最多比对多少条已有记忆，避免记忆多了以后入库变慢。 */
    private static final int DEDUPE_LOOKBACK = 200;

    private final LuceneMemoryStore store;
    private final MemoryEmbeddingProvider embeddingProvider;
    private final MemoryProperties properties;

    /** 业务指标（可选，缺失时跳过埋点）。用字段注入避免构造签名变动。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.demo.metrics.BusinessMetrics businessMetrics;

    /** 访问强化用的单线程执行器：低频、可丢，不占用主流程。 */
    private final ExecutorService reinforceExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-reinforce");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong reinforceFailures = new AtomicLong();

    public MemoryRagService(LuceneMemoryStore store,
                            MemoryEmbeddingProvider embeddingProvider,
                            MemoryProperties properties) {
        this.store = store;
        this.embeddingProvider = embeddingProvider;
        this.properties = properties;
    }

    @PreDestroy
    public void shutdown() {
        reinforceExecutor.shutdown();
        try {
            reinforceExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ======================== 写入 ========================

    /**
     * 批量写入（先与已有记忆去重）。
     *
     * @return 实际写入的记录（被去重跳过的不会出现在结果里）
     */
    public List<MemoryRecord> addMemories(String userId, List<String> facts) {
        List<MemoryRecord> added = new ArrayList<>();
        if (userId == null || facts == null || facts.isEmpty()) return added;
        if (!isUsable()) return added;

        // 这里额外判一次重复，是为了让返回值只含**真正新写入**的记录：
        // addMemory 遇到重复会返回已有那条（刻意不返回 null，免得调用方当成写入失败），
        // 不判的话调用方会把"跳过"也数成"新增"。
        List<MemoryRecord> known = store.listByUserId(userId, false);
        for (String fact : facts) {
            if (fact == null || fact.isBlank()) continue;
            if (findDuplicate(fact, known) != null) {
                log.debug("【记忆库-RAG】[{}] 跳过重复记忆：{}", userId, fact);
                continue;
            }
            MemoryRecord r = addMemory(userId, fact, MemoryType.BASIC, 5);
            if (r != null) {
                added.add(r);
                known.add(r); // 同批次内部也去重
            }
        }
        return added;
    }

    /** 写入一条记忆。返回写入后的记录；向量化失败或维度不符时返回 null。 */
    public MemoryRecord addMemory(String userId, String content, MemoryType type, int importance) {
        return addMemory(userId, content, type, importance, null);
    }

    /**
     * 写入一条记忆（可带关键词）。
     *
     * @param keywords 本地抽取的关键词，逗号分隔；null 表示没有（脑图标签会回退到正文）
     */
    public MemoryRecord addMemory(String userId, String content, MemoryType type,
                                  int importance, String keywords) {
        if (!isUsable() || content == null || content.isBlank()) return null;

        // 查重放在向量化**之前**：重复的记忆连 embedding 都不用算。
        // 放在这里而不是调用方，是因为这是唯一的真实写入路径——
        // 之前去重只写在 addMemories（批量）里，而那个方法在生产代码中没有任何调用方，
        // 结果"我叫张三"说几遍就存几条，脑图上直接刷屏。
        MemoryRecord dup = findDuplicate(userId, content);
        if (dup != null) {
            log.debug("【记忆库-RAG】[{}] 跳过重复记忆：{}", userId, truncate(content));
            return dup;
        }

        float[] vec = embeddingProvider.embed(content);
        if (vec == null) {
            // 云端 embedding 挂了 → 这条记忆只能放弃。
            // 不降级成"无向量存储"：没有向量的记忆检不出来，只会白白占空间。
            log.warn("【记忆库-RAG】[{}] 向量化失败，本条记忆不写入：{}", userId, truncate(content));
            return null;
        }

        long now = System.currentTimeMillis();
        MemoryRecord r = MemoryRecord.builder()
                .userId(userId)
                .content(content)
                .keywords(keywords)
                .type(type == null ? MemoryType.BASIC : type)
                .importance(Math.max(1, Math.min(10, importance)))
                .createdAt(now)
                .lastAccessedAt(now)
                .accessCount(0)
                .embedding(vec)
                .build();

        String id = store.add(r);
        if (id == null) return null;
        r.setId(id);

        enforceUserCap(userId);
        return r;
    }

    /**
     * 找出与 {@code fact} 重复的已有记忆，没有则返回 null。
     *
     * <p>去重是记忆库的命门：用户会把同一件自我陈述说很多遍（"我叫张三"），
     * 没有去重的话脑图很快就是同一句话刷屏，逐出也会把真正有用的记忆挤掉。
     */
    private MemoryRecord findDuplicate(String userId, String fact) {
        return findDuplicate(fact, store.listByUserId(userId, false));
    }

    /** 在给定集合里找重复项（批量写入时用它避免重复扫库）。 */
    private static MemoryRecord findDuplicate(String fact, List<MemoryRecord> existing) {
        int checked = 0;
        for (MemoryRecord r : existing) {
            if (checked++ >= DEDUPE_LOOKBACK) break;
            if (r == null || r.getContent() == null) continue;
            if (TextSimilarity.bigramJaccard(fact, r.getContent()) >= DEDUPE_THRESHOLD) {
                return r;
            }
        }
        return null;
    }

    /**
     * 超出单用户上限时逐出评分最低的记忆。
     * <p>评分 {@code importance / (1 + ageDays/30)}：重要度越低、越旧，越先被挤出。
     */
    private void enforceUserCap(String userId) {
        int cap = Math.max(1, properties.getStore().getMaxMemoryPerUser());
        List<MemoryRecord> all = store.listByUserId(userId, false);
        if (all.size() <= cap) return;

        List<MemoryRecord> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(MemoryRagService::evictionScore));
        int toRemove = all.size() - cap;
        for (int i = 0; i < toRemove && i < sorted.size(); i++) {
            store.deleteById(sorted.get(i).getId());
            log.info("【记忆库-RAG】[{}] 超出上限 {} 条，逐出：{}",
                    userId, cap, truncate(sorted.get(i).getContent()));
        }
    }

    /** 逐出评分：越低越该被淘汰。 */
    static double evictionScore(MemoryRecord r) {
        double ageDays = Math.max(0, (System.currentTimeMillis() - r.getCreatedAt()) / 86_400_000.0);
        double base = r.getImportance() / (1.0 + ageDays / 30.0);
        // 被访问过的记忆给一点加成，避免"用过的东西反而先被丢掉"
        return base * (1.0 + Math.log1p(Math.max(0, r.getAccessCount())) * 0.5);
    }

    // ======================== 检索 ========================

    /**
     * 检索与 query 相关的记忆。命中后会异步强化（访问计数 +1、刷新访问时间）。
     */
    public List<MemoryRecord> search(String userId, String query, int topK) {
        if (!isUsable() || query == null || query.isBlank()) return List.of();

        long startMs = System.currentTimeMillis();
        float[] vec = embeddingProvider.embedQuery(query);
        if (vec == null) {
            log.warn("【记忆库-RAG】[{}] 查询向量化失败，本轮无记忆可用", userId);
            if (businessMetrics != null) {
                businessMetrics.recordMemorySearch(0, System.currentTimeMillis() - startMs);
            }
            return List.of();
        }
        int k = topK > 0 ? topK : properties.getRag().getTopK();
        List<MemoryRecord> hits = store.search(userId, vec, k, properties.getRag().getMinScore());
        if (businessMetrics != null) {
            businessMetrics.recordMemorySearch(hits.size(), System.currentTimeMillis() - startMs);
        }

        if (!hits.isEmpty()) {
            if (properties.getRag().isAsyncReinforce()) {
                for (MemoryRecord r : hits) {
                    reinforceExecutor.submit(() -> reinforce(userId, r.getId()));
                }
            } else {
                for (MemoryRecord r : hits) {
                    reinforce(userId, r.getId());
                }
            }
        }
        return hits;
    }

    /**
     * 访问强化：accessCount+1 并刷新 lastAccessedAt。
     *
     * <p>关键在于"读回来再改"——直接 new 一个记录会把向量丢掉（见类注释）。
     */
    public void reinforce(String userId, String memoryId) {
        try {
            Optional<MemoryRecord> found = store.getById(userId, memoryId);
            if (found.isEmpty()) return;
            MemoryRecord r = found.get();
            r.setAccessCount(r.getAccessCount() + 1);
            r.setLastAccessedAt(System.currentTimeMillis());
            r.setEmbedding(null); // 显式置空，交给 store 去回填原向量（它知道怎么保向量）
            store.update(r);
        } catch (Exception e) {
            if (reinforceFailures.incrementAndGet() % 50 == 1) {
                log.warn("【记忆库-RAG】访问强化失败（已忽略 {} 次）：{}",
                        reinforceFailures.get(), e.toString());
            }
        }
    }

    // ======================== 衰减与维护 ========================

    /**
     * 对单个用户执行衰减：过老的删除，稍旧的降权。
     *
     * @return 删除的条数
     */
    public int decay(String userId) {
        if (!isUsable()) return 0;
        MemoryProperties.Decay cfg = properties.getDecay();
        long now = System.currentTimeMillis();
        int deleted = 0;

        for (MemoryRecord r : store.listByUserId(userId, false)) {
            double ageDays = Math.max(0, (now - r.getCreatedAt()) / 86_400_000.0);

            // 按类型分档——相当于 JVM 的"年轻人代回收更频繁"。
            // 埋点捕获的原句和一次性事件都是 EPISODIC，没有长期价值，该比
            // "用户叫张三"这类稳定事实死得早；否则脑图迟早被闲聊淹没。
            boolean episodic = r.getType() == MemoryType.EPISODIC;
            int maxAge = episodic ? cfg.getEpisodicMaxAgeDays() : cfg.getMaxAgeDays();
            int softDays = episodic ? cfg.getEpisodicSoftDecayDays() : cfg.getSoftDecayDays();

            if (ageDays > maxAge) {
                store.deleteById(r.getId());
                deleted++;
                continue;
            }
            if (ageDays > softDays) {
                int decayed = (int) Math.round(r.getImportance() * cfg.getSoftDecayFactor());
                int next = Math.max(1, Math.min(r.getImportance(), decayed));
                if (next < r.getImportance()) {
                    r.setImportance(next);
                    r.setEmbedding(null); // 同样交给 store 保向量
                    store.update(r);
                }
            }
        }
        if (deleted > 0) {
            store.commit();
            log.info("【记忆库-RAG】[{}] 衰减完成：删除 {} 条老记忆", userId, deleted);
        }
        return deleted;
    }

    /** 调整重要度（反馈用）。 */
    public boolean adjustImportance(String userId, String memoryId, int delta) {
        Optional<MemoryRecord> found = store.getById(userId, memoryId);
        if (found.isEmpty()) return false;
        MemoryRecord r = found.get();
        r.setImportance(Math.max(1, Math.min(10, r.getImportance() + delta)));
        r.setEmbedding(null);
        return store.update(r);
    }

    /** 删除一条记忆。 */
    public boolean deleteMemory(String userId, String memoryId) {
        Optional<MemoryRecord> found = store.getById(userId, memoryId);
        if (found.isEmpty()) return false;
        return store.deleteById(memoryId);
    }

    /** 清空某用户的全部记忆。 */
    public int deleteAll(String userId) {
        int n = store.deleteByUserId(userId);
        if (n > 0) store.commit();
        return n;
    }

    /**
     * 只清理重复记忆：每组近似重复里<b>保留评分最高的那条</b>，其余删除。
     *
     * <h3>为什么需要它</h3>
     * 去重只能防新的，清不掉已经写进去的——而"清空全部"会连好记忆一起误伤。
     * 这个方法的语义是"压缩"，不是"重置"。
     *
     * <p>保留哪条用 {@link #evictionScore}（与逐出同一个评分），
     * 也就是"重要度高、较新、被检索过"的那条活下来——和记忆库整体的价值判断保持一致。
     *
     * <p>朴素 O(n²) 分组。单用户上限 1000 条，最坏 50 万次 bigram 比较，
     * 属于低频的运维操作，不值得为它引入索引结构。
     *
     * @return 删除的条数
     */
    public int deleteDuplicates(String userId) {
        if (!isUsable()) return 0;
        List<MemoryRecord> all = store.listByUserId(userId, false);
        if (all.size() < 2) return 0;

        boolean[] removed = new boolean[all.size()];
        int deleted = 0;

        for (int i = 0; i < all.size(); i++) {
            if (removed[i] || all.get(i).getContent() == null) continue;

            // 收集与 i 近似重复的全部下标（含 i 自己）
            List<Integer> group = new ArrayList<>();
            group.add(i);
            for (int j = i + 1; j < all.size(); j++) {
                if (removed[j] || all.get(j).getContent() == null) continue;
                if (TextSimilarity.bigramJaccard(all.get(i).getContent(), all.get(j).getContent())
                        >= DEDUPE_THRESHOLD) {
                    group.add(j);
                }
            }
            if (group.size() < 2) continue;

            int keep = group.get(0);
            for (int idx : group) {
                if (evictionScore(all.get(idx)) > evictionScore(all.get(keep))) {
                    keep = idx;
                }
            }
            for (int idx : group) {
                if (idx == keep) continue;
                store.deleteById(all.get(idx).getId());
                removed[idx] = true;
                deleted++;
            }
        }

        if (deleted > 0) {
            store.commit();
            log.info("【记忆库-RAG】[{}] 去重压缩完成：删除 {} 条重复记忆", userId, deleted);
        }
        return deleted;
    }

    // ======================== 只读 ========================

    public List<MemoryRecord> listAll(String userId, boolean withEmbedding) {
        return store.listByUserId(userId, withEmbedding);
    }

    public int count(String userId) {
        return store.countByUserId(userId);
    }

    public int totalCount() {
        return store.totalCount();
    }

    public int countUsers() {
        return store.countUsers();
    }

    public java.util.Set<String> allUserIds() {
        return store.listAllUserIds();
    }

    /** 记忆功能是否可用（配置开启 + 索引就绪 + 向量化就绪）。 */
    public boolean isUsable() {
        return properties.getStore().isEnabled() && properties.getRag().isEnabled()
                && store.isAvailable() && embeddingProvider.isReady();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }
}
