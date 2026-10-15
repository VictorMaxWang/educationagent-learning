package com.demo.memory.store;

import com.demo.memory.MemoryProperties;
import com.demo.memory.MemoryRecord;
import com.demo.memory.MemoryType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 用户记忆的本地向量库：Lucene 9 的 KnnFloatVectorField + SearcherManager。
 *
 * <h3>为什么不用 Chroma</h3>
 * 故障隔离。本项目的公共知识库（法律/旅行/心理学/通识）全部走 Chroma + 云端 embedding，
 * 一旦云端 embedding 欠费，那条链路上的入库与检索会<b>静默失败</b>（本项目真的发生过）。
 * 用户记忆放在本地 Lucene 里，即便云端全挂也能读写——这是两套并行链路，不是替换。
 *
 * <h3>三个必须踩对的点</h3>
 * <ol>
 *   <li><b>{@code KnnFloatVectorField} 只建索引、不存原向量。</b>
 *       想回读向量（记忆脑图要算节点相似度）必须额外加
 *       {@code StoredField("embeddingRaw", byte[])}，否则只能拿到 null。</li>
 *   <li><b>用户隔离靠 filter，不是靠多个索引。</b>
 *       {@link KnnFloatVectorQuery} 的第 4 个参数是过滤条件，
 *       用 {@code TermQuery(userId)} 把 KNN 限制在本用户内。
 *       注意 KNN 是"先取前 k 个近似邻居再过滤"，所以 k 要取得比期望条数大——
 *       这里直接把 k 放大到 {@code topK * 4 + 16}，否则多用户共库时会召回不足。</li>
 *   <li><b>维度一旦建立就不能改。</b>维度记在索引的 meta 文档里，
 *       启动时若发现与配置不一致，直接删索引重建并在日志里说明——
 *       不同维度的向量混在一个索引里会让检索结果完全不可用，且不报错。</li>
 * </ol>
 *
 * <h3>写入可见性</h3>
 * 写入后调 {@code searcherManager.maybeRefresh()}（NRT 近实时可见），<b>不</b>每次 commit。
 * 记忆是低频写入（一轮对话几条），但衰减任务会批量改上千条；
 * 每条都 fsync 会非常慢，而近实时可见已经满足"刚说的话马上能想起"的需求。
 * commit 在 {@link #close()} 与显式调用 {@link #commit()} 时执行。
 */
@Slf4j
@Component
public class LuceneMemoryStore implements Closeable {

    private static final String F_ID = "id";
    private static final String F_USER = "userId";
    private static final String F_CONTENT = "content";
    private static final String F_TYPE = "type";
    private static final String F_IMPORTANCE = "importance";
    private static final String F_CREATED = "createdAt";
    private static final String F_ACCESSED = "lastAccessedAt";
    private static final String F_ACCESS_COUNT = "accessCount";
    private static final String F_KEYWORDS = "keywords";
    private static final String F_VECTOR = "embedding";
    private static final String F_VECTOR_RAW = "embeddingRaw";
    private static final String F_META_DIM = "dimension";

    /** meta 文档的 id，用来在索引里记录建索引时的向量维度。 */
    private static final String META_DOC_ID = "__meta__";

    /** 扫描型操作（列用户、列记忆）的安全上限。 */
    private static final int SCAN_LIMIT = 20_000;

    private final MemoryProperties properties;

    private Directory directory;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private Analyzer analyzer;

    /** 写入串行化：IndexWriter 自身线程安全，但"写 + 刷新"作为一个整体需要互斥，避免刷新风暴。 */
    private final Object writeLock = new Object();

    /** 索引是否可用。初始化失败时为 false，所有方法退化为安全空操作。 */
    private volatile boolean available;

    private volatile int dimension;

    public LuceneMemoryStore(MemoryProperties properties) {
        this.properties = properties;
    }

    // ======================== 生命周期 ========================

    /**
     * 启动初始化。
     *
     * <p><b>刻意不抛异常</b>：记忆库是旁路能力，索引目录不可写、索引损坏都不能让应用起不来，
     * 最多让记忆功能失效（{@link #isAvailable()} = false）。
     */
    @PostConstruct
    public void init() {
        if (!properties.getStore().isEnabled()) {
            log.info("【记忆库】memory.store.enabled=false —— 本地记忆库未启用");
            return;
        }
        try {
            open(false);
            available = true;
            log.info("【记忆库】Lucene 索引就绪：{} 维 / {} 条记忆 / {} 个用户 / {}",
                    dimension, totalCount(), countUsers(), properties.getStore().getIndexPath());
        } catch (Exception e) {
            log.error("【记忆库】索引初始化失败，记忆功能将不可用（不影响主对话）：{}", e.toString(), e);
            available = false;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        try {
            if (writer != null) {
                writer.commit();
                writer.close();
            }
        } catch (Exception e) {
            log.warn("【记忆库】关闭 IndexWriter 失败：{}", e.toString());
        }
        try {
            if (searcherManager != null) searcherManager.close();
        } catch (Exception ignored) {
            // 忽略
        }
        try {
            if (directory != null) directory.close();
        } catch (Exception ignored) {
            // 忽略
        }
        if (analyzer != null) {
            analyzer.close();
        }
        available = false;
        log.info("【记忆库】已关闭");
    }

    /**
     * 打开索引；遇到维度不一致或索引损坏时自动重建。
     *
     * @param forceRebuild 为 true 时无条件清空重建
     */
    private void open(boolean forceRebuild) throws IOException {
        Path path = Paths.get(properties.getStore().getIndexPath());
        Files.createDirectories(path);

        if (forceRebuild && Files.exists(path)) {
            deleteRecursively(path);
            Files.createDirectories(path);
        }

        dimension = properties.getStore().getDimension();
        analyzer = "keyword".equalsIgnoreCase(properties.getStore().getAnalyzer())
                ? new KeywordAnalyzer() : new CJKAnalyzer();

        try {
            directory = FSDirectory.open(path);
            writer = new IndexWriter(directory, writerConfig());
            searcherManager = new SearcherManager(writer, new SearcherFactory());

            int stored = readStoredDimension();
            if (stored > 0 && stored != dimension) {
                // 维度变了就必须重建：混维度的向量索引不会报错，但检索结果完全不可用
                log.warn("【记忆库】索引维度 {} 与配置 {} 不一致 → 删除重建",
                        stored, dimension);
                reopenAfterWipe("维度变更 " + stored + " → " + dimension);
            } else if (stored <= 0) {
                writeMetaDimension();
            }
        } catch (IOException e) {
            // CorruptIndexException 是 IOException 的子类，会被这里一并捕获
            log.warn("【记忆库】索引打开失败（{}），尝试删除重建", e.toString());
            reopenAfterWipe("索引损坏：" + e.getClass().getSimpleName());
        }
    }

    private void reopenAfterWipe(String reason) throws IOException {
        closeQuietly();
        Path path = Paths.get(properties.getStore().getIndexPath());
        deleteRecursively(path);
        Files.createDirectories(path);
        analyzer = "keyword".equalsIgnoreCase(properties.getStore().getAnalyzer())
                ? new KeywordAnalyzer() : new CJKAnalyzer();
        directory = FSDirectory.open(path);
        writer = new IndexWriter(directory, writerConfig());
        searcherManager = new SearcherManager(writer, new SearcherFactory());
        writeMetaDimension();
        log.info("【记忆库】已重建索引（{}）", reason);
    }

    private IndexWriterConfig writerConfig() {
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        return cfg;
    }

    private void closeQuietly() {
        try {
            if (writer != null) writer.close();
        } catch (Exception ignored) {
            // 忽略
        }
        try {
            if (searcherManager != null) searcherManager.close();
        } catch (Exception ignored) {
            // 忽略
        }
        try {
            if (directory != null) directory.close();
        } catch (Exception ignored) {
            // 忽略
        }
        if (analyzer != null) {
            analyzer.close();
            analyzer = null;
        }
    }

    /** 读 meta 文档里记录的维度；没有 meta 返回 0。 */
    private int readStoredDimension() {
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            TopDocs td = searcher.search(new TermQuery(new Term(F_ID, META_DOC_ID)), 1);
            if (td.scoreDocs.length == 0) return 0;
            Document doc = searcher.storedFields().document(td.scoreDocs[0].doc);
            String v = doc.get(F_META_DIM);
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        } finally {
            if (searcher != null) releaseQuietly(searcher);
        }
    }

    private void writeMetaDimension() throws IOException {
        Document doc = new Document();
        doc.add(new StringField(F_ID, META_DOC_ID, Field.Store.YES));
        doc.add(new StoredField(F_META_DIM, dimension));
        writer.updateDocument(new Term(F_ID, META_DOC_ID), doc);
        writer.commit();
        searcherManager.maybeRefresh();
    }

    private void releaseQuietly(IndexSearcher searcher) {
        try {
            searcherManager.release(searcher);
        } catch (Exception ignored) {
            // 忽略
        }
    }

    // ======================== 写 ========================

    /**
     * 新增一条记忆。
     *
     * @return 生成的记忆 ID；索引不可用或向量缺失/维度不符时返回 {@code null}
     */
    public String add(MemoryRecord record) {
        if (!available || record == null) return null;
        if (record.getId() == null || record.getId().isBlank()) {
            record.setId(UUID.randomUUID().toString());
        }
        if (record.getType() == null) record.setType(MemoryType.BASIC);
        if (record.getCreatedAt() <= 0) record.setCreatedAt(System.currentTimeMillis());
        if (record.getLastAccessedAt() <= 0) record.setLastAccessedAt(record.getCreatedAt());

        float[] vec = record.getEmbedding();
        if (vec == null || vec.length != dimension) {
            log.warn("【记忆库】记忆向量缺失或维度不符（{} / {}），拒绝写入以免污染索引",
                    vec == null ? "null" : vec.length, dimension);
            return null;
        }
        try {
            synchronized (writeLock) {
                ensureWriterOpen();
                writer.updateDocument(new Term(F_ID, record.getId()), toDocument(record));
                searcherManager.maybeRefresh();
            }
            return record.getId();
        } catch (Exception e) {
            log.warn("【记忆库】写入失败：{}", e.toString());
            return null;
        }
    }

    /**
     * 确保 {@link IndexWriter} 可用；已失效则原地重开（<b>不删数据</b>）。
     *
     * <h3>为什么必须有这一步</h3>
     * Lucene 会在检测到<b>索引被外部力量改动</b>时自我关闭 writer（典型报错
     * {@code AlreadyClosedException: Underlying file changed by an external force}）。
     * 触发场景很现实：<b>另一个 JVM 打开过同一个索引目录</b>——比如跑
     * {@code @SpringBootTest} 的测试类时，它默认读的就是生产配置里的 {@code data/memory-index}，
     * 于是正在运行的后端下一次写就被判无效。
     *
     * <p>不重开的话后果极其隐蔽：{@code isAvailable()} 仍返回 true →
     * {@code isUsable()} 仍为 true → 每轮对话都尝试写入、每次都失败，
     * 而且只打 WARN。用户看到的是"脑图永远空的"，而日志里连一条 ERROR 都没有。
     */
    private void ensureWriterOpen() throws IOException {
        if (writer != null && writer.isOpen()) return;
        log.warn("【记忆库】索引写入端已失效（多因另一个 JVM 打开过同一目录），尝试原地重新打开");
        reopenInPlace();
        if (writer == null || !writer.isOpen()) {
            throw new IOException("索引写入端不可用，重开失败");
        }
    }

    /** 原地重开：保留磁盘上的全部数据，只重建 writer / searcher。 */
    private synchronized void reopenInPlace() {
        try {
            closeQuietly();
            open(false);
            available = true;
            log.info("【记忆库】索引已重新打开：{} 维 / {} 条记忆", dimension, totalCount());
        } catch (Exception e) {
            available = false;
            log.error("【记忆库】索引重新打开失败，记忆写入将不可用：{}", e.toString(), e);
        }
    }

    /** 按 id 更新（用于 reinforce / decay / 重要度调整）。 */
    public boolean update(MemoryRecord record) {
        if (!available || record == null || record.getId() == null) return false;
        try {
            synchronized (writeLock) {
                // update 时若没有向量，保留索引里已有的向量字段（Lucene 的 update 是整文档替换，
                // 所以必须先回读原向量，否则一次"访问计数+1"会把向量抹掉、该条记忆再也搜不到）
                //
                // keywords 同理：reinforce / decay / adjustImportance 都只 set 了变化的那个字段，
                // 不回读的话，一次"访问次数+1"就会把关键词清空（同一个坑，同一个位置）。
                ensureWriterOpen();
                boolean needEmbedding = record.getEmbedding() == null;
                boolean needKeywords = record.getKeywords() == null;
                if (needEmbedding || needKeywords) {
                    getByIdRaw(record.getId(), needEmbedding).ifPresent(old -> {
                        if (needEmbedding) record.setEmbedding(old.getEmbedding());
                        if (needKeywords) record.setKeywords(old.getKeywords());
                    });
                }
                writer.updateDocument(new Term(F_ID, record.getId()), toDocument(record));
                searcherManager.maybeRefresh();
            }
            return true;
        } catch (Exception e) {
            log.warn("【记忆库】更新失败：{}", e.toString());
            return false;
        }
    }

    /** 按 id 删除。 */
    public boolean deleteById(String id) {
        if (!available || id == null) return false;
        try {
            synchronized (writeLock) {
                writer.deleteDocuments(new Term(F_ID, id));
                searcherManager.maybeRefresh();
            }
            return true;
        } catch (Exception e) {
            log.warn("【记忆库】删除失败：{}", e.toString());
            return false;
        }
    }

    /** 删除某用户的全部记忆。 */
    public int deleteByUserId(String userId) {
        if (!available || userId == null) return 0;
        int before = countByUserId(userId);
        try {
            synchronized (writeLock) {
                writer.deleteDocuments(new Term(F_USER, userId));
                searcherManager.maybeRefresh();
            }
            return before;
        } catch (Exception e) {
            log.warn("【记忆库】按用户删除失败：{}", e.toString());
            return 0;
        }
    }

    /** 显式提交（批量写完后调用）。 */
    public void commit() {
        if (!available) return;
        try {
            synchronized (writeLock) {
                writer.commit();
                searcherManager.maybeRefresh();
            }
        } catch (Exception e) {
            log.warn("【记忆库】commit 失败：{}", e.toString());
        }
    }

    // ======================== 读 ========================

    /**
     * KNN 检索（自动做用户隔离）。
     *
     * @param minCosine 最低余弦相似度；Lucene 的 COSINE 得分是 {@code (1+cos)/2}，这里换算回余弦后过滤
     */
    public List<MemoryRecord> search(String userId, float[] queryVector, int topK, double minCosine) {
        if (!available || userId == null || queryVector == null || queryVector.length != dimension) {
            return List.of();
        }
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            Query filter = new TermQuery(new Term(F_USER, userId));
            // k 放大：KNN 先取近似邻居再按 filter 过滤，k 取太小会导致本用户的候选不足
            int k = Math.max(topK * 4 + 16, 32);
            KnnFloatVectorQuery knn = new KnnFloatVectorQuery(F_VECTOR, queryVector, k, filter);
            TopDocs td = searcher.search(knn, topK);

            List<MemoryRecord> out = new ArrayList<>();
            for (ScoreDoc sd : td.scoreDocs) {
                double cosine = 2.0 * sd.score - 1.0;
                if (cosine < minCosine) continue;
                Document doc = searcher.storedFields().document(sd.doc);
                MemoryRecord r = fromDocument(doc, false);
                r.setScore(cosine); // 检索得分仅在检索结果里有意义，不写回索引
                out.add(r);
            }
            return out;
        } catch (Exception e) {
            log.warn("【记忆库】检索失败：{}", e.toString());
            return List.of();
        } finally {
            if (searcher != null) releaseQuietly(searcher);
        }
    }

    /** 按 id 查（不做用户校验，内部用）。 */
    public Optional<MemoryRecord> getById(String userId, String id) {
        Optional<MemoryRecord> r = getByIdRaw(id, true);
        if (r.isPresent() && userId != null && !userId.equals(r.get().getUserId())) {
            return Optional.empty();
        }
        return r;
    }

    private Optional<MemoryRecord> getByIdRaw(String id, boolean withEmbedding) {
        if (!available || id == null) return Optional.empty();
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            TopDocs td = searcher.search(new TermQuery(new Term(F_ID, id)), 1);
            if (td.scoreDocs.length == 0) return Optional.empty();
            return Optional.of(fromDocument(
                    searcher.storedFields().document(td.scoreDocs[0].doc), withEmbedding));
        } catch (Exception e) {
            return Optional.empty();
        } finally {
            if (searcher != null) releaseQuietly(searcher);
        }
    }

    /**
     * 列出某用户的全部记忆。
     *
     * @param withEmbedding 是否回填向量。记忆脑图需要向量来算节点相似度，
     *                      而普通列举不需要——回填会显著增加内存占用。
     */
    public List<MemoryRecord> listByUserId(String userId, boolean withEmbedding) {
        if (!available || userId == null) return List.of();
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            TopDocs td = searcher.search(new TermQuery(new Term(F_USER, userId)), SCAN_LIMIT);
            List<MemoryRecord> out = new ArrayList<>(td.scoreDocs.length);
            for (ScoreDoc sd : td.scoreDocs) {
                out.add(fromDocument(searcher.storedFields().document(sd.doc), withEmbedding));
            }
            // 按创建时间倒序：最近说的排前面，可视化与逐出都按这个顺序更直观
            out.sort(Comparator.comparingLong(MemoryRecord::getCreatedAt).reversed());
            return out;
        } catch (Exception e) {
            log.warn("【记忆库】列举失败：{}", e.toString());
            return List.of();
        } finally {
            if (searcher != null) releaseQuietly(searcher);
        }
    }

    /** 某用户的记忆条数。 */
    public int countByUserId(String userId) {
        if (!available || userId == null) return 0;
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            return searcher.count(new TermQuery(new Term(F_USER, userId)));
        } catch (Exception e) {
            return 0;
        } finally {
            if (searcher != null) releaseQuietly(searcher);
        }
    }

    /** 索引里的记忆总条数（不含 meta 文档）。 */
    public int totalCount() {
        if (!available) return 0;
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            int all = searcher.getIndexReader().numDocs();
            // meta 文档不算记忆
            return Math.max(0, all - (readStoredDimension() > 0 ? 1 : 0));
        } catch (Exception e) {
            return 0;
        } finally {
            if (searcher != null) releaseQuietly(searcher);
        }
    }

    /** 有记忆的用户数。 */
    public int countUsers() {
        return listAllUserIds().size();
    }

    /** 全部有记忆的用户 ID。 */
    public Set<String> listAllUserIds() {
        if (!available) return Set.of();
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            TopDocs td = searcher.search(new MatchAllDocsQuery(), SCAN_LIMIT);
            Set<String> users = new LinkedHashSet<>();
            for (ScoreDoc sd : td.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                String uid = doc.get(F_USER);
                if (uid != null && !uid.isBlank() && !META_DOC_ID.equals(doc.get(F_ID))) {
                    users.add(uid);
                }
            }
            return users;
        } catch (Exception e) {
            log.warn("【记忆库】列举用户失败：{}", e.toString());
            return Set.of();
        } finally {
            if (searcher != null) releaseQuietly(searcher);
        }
    }

    /** 索引是否可用。 */
    public boolean isAvailable() {
        return available;
    }

    /** 当前向量维度。 */
    public int dimension() {
        return dimension;
    }

    // ======================== 文档映射 ========================

    private Document toDocument(MemoryRecord r) {
        Document doc = new Document();
        doc.add(new StringField(F_ID, r.getId(), Field.Store.YES));
        doc.add(new StringField(F_USER, r.getUserId() == null ? "" : r.getUserId(), Field.Store.YES));
        doc.add(new TextField(F_CONTENT, r.getContent() == null ? "" : r.getContent(), Field.Store.YES));
        doc.add(new StringField(F_TYPE, r.getType().name(), Field.Store.YES));
        doc.add(new StoredField(F_IMPORTANCE, r.getImportance()));
        doc.add(new StoredField(F_CREATED, r.getCreatedAt()));
        doc.add(new StoredField(F_ACCESSED, r.getLastAccessedAt()));
        doc.add(new StoredField(F_ACCESS_COUNT, r.getAccessCount()));
        // 关键词用 TextField（而非 StoredField）：既存下来给脑图当标签，
        // 又顺带走 CJKAnalyzer 建索引，将来"按关键词检索"不用改索引结构
        doc.add(new TextField(F_KEYWORDS, r.getKeywords() == null ? "" : r.getKeywords(), Field.Store.YES));
        if (r.getEmbedding() != null && r.getEmbedding().length == dimension) {
            doc.add(new KnnFloatVectorField(F_VECTOR, r.getEmbedding(), VectorSimilarityFunction.COSINE));
            // ★ KnnFloatVectorField 只建索引不存原向量，必须额外 StoredField 才能回读
            doc.add(new StoredField(F_VECTOR_RAW, toBytes(r.getEmbedding())));
        }
        return doc;
    }

    private MemoryRecord fromDocument(Document doc, boolean withEmbedding) {
        MemoryRecord r = new MemoryRecord();
        r.setId(doc.get(F_ID));
        r.setUserId(doc.get(F_USER));
        r.setContent(doc.get(F_CONTENT));
        r.setType(MemoryType.parse(doc.get(F_TYPE)));
        r.setImportance(intOf(doc.get(F_IMPORTANCE), 5));
        r.setCreatedAt(longOf(doc.get(F_CREATED), 0L));
        r.setLastAccessedAt(longOf(doc.get(F_ACCESSED), 0L));
        r.setAccessCount(intOf(doc.get(F_ACCESS_COUNT), 0));
        r.setKeywords(doc.get(F_KEYWORDS));
        if (withEmbedding) {
            r.setEmbedding(toFloats(binaryValue(doc, F_VECTOR_RAW)));
        }
        return r;
    }

    /**
     * 取二进制字段。
     *
     * <p>不能直接拿 {@code BytesRef.bytes}：它可能只是<b>某个更大数组的一个视图</b>
     * （{@code offset}/{@code length} 未必覆盖整个数组），
     * 直接使用会把别的字段的字节当成向量，解出一堆垃圾 float。
     */
    private static byte[] binaryValue(Document doc, String field) {
        BytesRef ref = doc.getBinaryValue(field);
        if (ref == null || ref.length <= 0) return null;
        byte[] out = new byte[ref.length];
        System.arraycopy(ref.bytes, ref.offset, out, 0, ref.length);
        return out;
    }

    private static int intOf(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long longOf(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static byte[] toBytes(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) bb.putFloat(f);
        return bb.array();
    }

    static float[] toFloats(byte[] b) {
        if (b == null || b.length < Float.BYTES) return null;
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.length / Float.BYTES];
        for (int i = 0; i < v.length; i++) v[i] = bb.getFloat();
        return v;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }
}
