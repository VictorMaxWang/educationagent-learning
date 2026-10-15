package com.demo.chroma.doc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.chroma.TravelKnowledgeChunker;
import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.base.EmbeddingService;
import com.demo.service.preprocessing.FileService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 单领域增量索引引擎。
 * <p>
 * 扫描 knowledgeDir 下的 txt/pdf/docx 文档 → 文件名前缀/别名映射到子库 →
 * {@link TravelKnowledgeChunker} 分块（领域可配 chunkSize/overlap）→
 * {@link EmbeddingService} 批量向量化 → {@link ChromaClient} 写入 {prefix}{subLib} 集合。
 * <p>
 * 三重指纹增量（SHA-256 + 大小 + 修改时间，按文件）：某子库任一文件新增/修改/删除 → 清空该子库集合重入库。
 * 后台守护线程入库（不阻塞启动）+ 状态机 + 失败重试节流。
 */
@Slf4j
public class DocKnowledgeIndexer {

    public enum IndexState { NOT_STARTED, INDEXING, DONE, FAILED }

    private static final Set<String> SUPPORTED_EXT = Set.of("txt", "pdf", "docx");

    /**
     * 指纹格式版本。不匹配则旧指纹整体作废、下次启动全量重建。
     *
     * <p>v1 → v2：修正了单文档提取被 FileService 默认 2000 字上限截断的 bug。
     * <b>必须 bump</b> —— 否则旧指纹仍声称「已入库 3 个 chunk」，而集合里正好有 3 条，
     * 指纹与集合<b>一致</b>，自愈逻辑不会触发，索引器会继续跳过，修复永远不会生效。
     * 指纹只跟踪源文件的 hash/mtime/size，无法感知提取逻辑的变化，只能靠版本号显式作废。</p>
     */
    private static final int FINGERPRINT_VERSION = 2;

    private static final long RETRY_INTERVAL_MS = 60_000;

    /**
     * 入库时单文档允许提取的最大字符数。
     *
     * <p><b>绝不能用 {@code FileService.extractContent(bytes)} 的默认上限</b> ——
     * 那是 2000 字，为微信链路（把用户上传的小文件内容塞进 LLM prompt）设计的。
     * 用在知识库入库上会把正文截掉 99%：实测二十一大报告 35,148 字只提出约 2000 字、
     * 切出 3 个 chunk，导致任何实质问题都检索不到正文（表现为一律拒答）。
     * 这也解释了为什么只有法律库正常 —— 它读的是 .txt 且直接 Files.readString，绕开了这个上限。</p>
     *
     * <p>取值参考：本项目最大的文档是 755~1023 页的英文教材，约 230 万字符。取 300 万可完整覆盖。
     * 调小可控制入库耗时与 embedding 费用。</p>
     */
    private static final int MAX_CHARS_PER_DOC = 3_000_000;

    private final DocKnowledgeConfig config;
    private final ChromaClient chromaClient;
    private final EmbeddingService embeddingService;
    private final TravelKnowledgeChunker chunker;
    private final FileService fileService;

    private final Map<String, FileFingerprint> fingerprints = new HashMap<>();

    /**
     * 按文件补充的元数据（可选）：{@code <knowledgeDir>/.meta-map.json}
     * = {@code {"<相对路径>": {"classId": "class_demo_1"}}}。
     *
     * <p>用途：检索时的隔离与权限过滤——把 {@code classId} 之类写进 chunk 元数据，
     * 检索端就能用 {@code where} 条件随查询下发（见 {@code ChromaClient.queryDetail} 的四参重载）。
     *
     * <p>为什么用侧车文件而不是让索引器"懂业务"：索引器是通用框架，
     * 让它知道什么是班级/权限，等于把教育域的模型塞进基础设施。
     * 侧车只提供"文件 → 一组元数据"的映射，语义由调用方定义。
     *
     * <p>每次索引开始时重新加载（上传后立刻重建也要生效）。
     */
    private volatile Map<String, Map<String, Object>> metaMap = Map.of();
    private volatile IndexState state = IndexState.NOT_STARTED;
    private volatile long lastAttemptAt = 0;

    public DocKnowledgeIndexer(DocKnowledgeConfig config, ChromaClient chromaClient,
                               EmbeddingService embeddingService, TravelKnowledgeChunker chunker,
                               FileService fileService) {
        this.config = config;
        this.chromaClient = chromaClient;
        this.embeddingService = embeddingService;
        this.chunker = chunker;
        this.fileService = fileService;
    }

    /** 后台守护线程入库（不阻塞调用方） */
    public void startAsync() {
        Thread worker = new Thread(this::indexIfNeeded, "doc-indexer-" + config.getDomainId());
        worker.setDaemon(true);
        worker.start();
    }

    /** 增量索引（幂等，内部加锁防并发） */
    public synchronized void indexIfNeeded() {
        if (state == IndexState.INDEXING || state == IndexState.DONE) return;
        long now = System.currentTimeMillis();
        if (state == IndexState.FAILED && now - lastAttemptAt < RETRY_INTERVAL_MS) return;
        lastAttemptAt = now;
        state = IndexState.INDEXING;
        try {
            loadFingerprints();
            metaMap = loadMetaMap();
            Map<String, List<Path>> libFiles = scanDir();
            Set<String> affected = findAffectedLibs(libFiles);
            int ok = 0;
            for (String lib : affected) {
                try {
                    if (indexLibrary(lib, libFiles.getOrDefault(lib, List.of()))) ok++;
                } catch (Exception ex) {
                    log.warn("[{}] 子库 {} 重建失败: {}", config.getDomainId(), lib, ex.getMessage());
                }
            }
            pruneFingerprints();
            saveFingerprints();
            state = ok == affected.size() ? IndexState.DONE : IndexState.FAILED;
            log.info("[{}] 入库结束：重建 {} / 待处理 {}，状态={}", config.getDomainId(), ok, affected.size(), state);
        } catch (Exception e) {
            state = IndexState.FAILED;
            log.warn("[{}] 入库异常: {}", config.getDomainId(), e.getMessage());
        }
    }

    public IndexState getState() {
        return state;
    }

    /**
     * 强制重建：复位状态后再走一次增量。
     *
     * <p>为什么必须单独开这个入口：{@link #indexIfNeeded()} 在 {@code state == DONE} 时
     * <b>直接 return</b>，所以"程序启动之后才出现的文件"永远扫不到——典型场景是教师上传校本资源：
     * 文件躺在目录里、指纹里没有它、集合里也没有它，而状态仍是 DONE，表现像"一切正常"，
     * 检索却永远不命中新内容。必须显式复位让 {@code findAffectedLibs} 重新比对指纹。
     *
     * <p>复用同一把锁（与 {@link #indexIfNeeded()} 同）避免并发重建。
     */
    public synchronized void forceReindex() {
        if (state == IndexState.INDEXING) {
            log.warn("[{}] 正在入库中，忽略本次强制重建请求", config.getDomainId());
            return;
        }
        state = IndexState.NOT_STARTED;
        lastAttemptAt = 0;
        indexIfNeeded();
    }

    /** 扫描目录：支持「子目录名→子库」与「根目录文件名前缀→子库」两种方式 */
    private Map<String, List<Path>> scanDir() {
        Map<String, List<Path>> libFiles = new LinkedHashMap<>();
        Path dir = Paths.get(config.getKnowledgeDir());
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.info("📁 知识库目录已创建：{}", dir.toAbsolutePath());
            } catch (IOException e) {
                log.warn("[{}] 创建知识库目录失败: {}", config.getDomainId(), e.getMessage());
            }
            return libFiles;
        }
        List<Path> entries;
        try (Stream<Path> s = Files.list(dir)) {
            entries = s.sorted().toList();
        } catch (IOException e) {
            log.warn("[{}] 扫描知识库目录失败: {}", config.getDomainId(), e.getMessage());
            return libFiles;
        }
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                String lib = config.resolveSubLibraryId(entry.getFileName().toString());
                if (lib == null) {
                    log.warn("[{}] 无法识别子目录，跳过: {}", config.getDomainId(), entry.getFileName());
                    continue;
                }
                collectFiles(entry, lib, libFiles);
            } else if (Files.isRegularFile(entry)) {
                String fileName = entry.getFileName().toString();
                if (fileName.startsWith(".") || !SUPPORTED_EXT.contains(ext(fileName))) continue;
                String lib = config.resolveSubLibraryId(fileName);
                if (lib == null) {
                    log.warn("[{}] 无法识别子库，跳过文件: {}", config.getDomainId(), fileName);
                    continue;
                }
                libFiles.computeIfAbsent(lib, k -> new ArrayList<>()).add(entry);
            }
        }
        return libFiles;
    }

    /** 收集子目录下所有支持的文档，归入指定子库 */
    private void collectFiles(Path subDir, String lib, Map<String, List<Path>> libFiles) {
        try (Stream<Path> files = Files.list(subDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> SUPPORTED_EXT.contains(ext(p.getFileName().toString())))
                    .forEach(p -> libFiles.computeIfAbsent(lib, k -> new ArrayList<>()).add(p));
        } catch (IOException e) {
            log.warn("[{}] 扫描子目录失败 {}: {}", config.getDomainId(), subDir.getFileName(), e.getMessage());
        }
    }

    private static String ext(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }

    /** 找出需要重建的子库：任一文件新增/修改，或某文件已被删除 */
    private Set<String> findAffectedLibs(Map<String, List<Path>> libFiles) {
        Set<String> affected = new HashSet<>();
        for (Map.Entry<String, List<Path>> e : libFiles.entrySet()) {
            for (Path p : e.getValue()) {
                FileFingerprint prev = fingerprints.get(relativeKey(p));
                try {
                    if (prev == null || isChanged(p, prev)) {
                        affected.add(e.getKey());
                        break;
                    }
                } catch (IOException ex) {
                    affected.add(e.getKey());
                    break;
                }
            }
        }
        Set<String> present = libFiles.values().stream().flatMap(List::stream)
                .map(this::relativeKey).collect(Collectors.toSet());
        for (String key : fingerprints.keySet()) {
            if (!present.contains(key)) {
                String lib = subLibraryOfKey(key);
                if (lib != null) affected.add(lib);
            }
        }

        // 自愈：指纹与集合状态**自相矛盾**时安排重建。
        //
        // 判据必须是「矛盾」而不是单纯「集合为空」：指纹声称该子库有 N 个 chunk（N>0），
        // 实际集合却不存在或 0 条 —— 这才是上次写入静默失败的证据（旧实现里 embedding
        // 全部失败也会置 DONE 并写指纹，于是永久卡在「已完成 + 空集合」）。
        // 若指纹本身就记着 0 chunk（子库里全是无文本层的扫描件），那是内容问题不是故障，
        // 不该安排重建，否则会陷入每次启动都无意义重试的循环。
        for (String lib : libFiles.keySet()) {
            if (affected.contains(lib)) continue;
            int claimed = claimedChunksOf(lib);
            String collectionName = config.collectionName(lib);
            try {
                String cid = chromaClient.findCollectionId(collectionName);
                if (cid == null) {
                    if (claimed > 0) {
                        log.warn("[{}] 子库 {} 指纹声称已入库 {} 个 chunk，但集合 {} 不存在 → 安排重建",
                                config.getDomainId(), lib, claimed, collectionName);
                        affected.add(lib);
                    }
                    continue;
                }
                int n = chromaClient.count(cid);
                if (n == 0 && claimed > 0) {
                    log.warn("[{}] 子库 {} 指纹声称已入库 {} 个 chunk，但集合只有 0 条 → 安排重建",
                            config.getDomainId(), lib, claimed);
                    affected.add(lib);
                }
            } catch (Exception ex) {
                // Chroma 不可达：安排重建，让它走正常失败路径（indexLibrary 会报错并保持 FAILED）
                log.warn("[{}] 无法校验子库 {} 的集合状态（{}）→ 安排重建",
                        config.getDomainId(), lib, ex.getMessage());
                affected.add(lib);
            }
        }
        return affected;
    }

    /** 某子库所有文件指纹声称已入库的 chunk 总数（用于判定「指纹 vs 集合」是否自相矛盾） */
    private int claimedChunksOf(String lib) {
        int sum = 0;
        for (Map.Entry<String, FileFingerprint> e : fingerprints.entrySet()) {
            if (lib.equals(subLibraryOfKey(e.getKey()))) {
                sum += e.getValue().chunks;
            }
        }
        return sum;
    }

    /** 文件 → 相对 knowledgeDir 的路径（指纹 key，兼容子目录） */
    private String relativeKey(Path file) {
        return Paths.get(config.getKnowledgeDir()).toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** 指纹 key → 子库 id（取第一段：子目录名或文件名） */
    private String subLibraryOfKey(String key) {
        int slash = key.lastIndexOf('/');
        String first = slash > 0 ? key.substring(0, slash) : key;
        return config.resolveSubLibraryId(first);
    }

    /** 提取文本：txt 直接读 UTF-8（中文无 BOM 时 FileService 魔数检测只认 ASCII 会漏判），pdf/docx 走 FileService */
    private String extractText(Path path) {
        String lower = path.getFileName().toString().toLowerCase();
        if (lower.endsWith(".txt")) {
            try {
                byte[] bytes = Files.readAllBytes(path);
                String utf8 = new String(bytes, StandardCharsets.UTF_8);
                if (!utf8.contains("�")) {
                    return utf8.trim();
                }
                return new String(bytes, Charset.forName("GBK")).trim();
            } catch (IOException e) {
                log.warn("[{}] 读取 txt 失败 {}: {}", config.getDomainId(), path.getFileName(), e.getMessage());
                return null;
            }
        }
        try {
            // 必须显式传 maxChars：默认重载会在 2000 字处截断，把知识库正文截没
            return fileService.extractContent(Files.readAllBytes(path), MAX_CHARS_PER_DOC);
        } catch (IOException e) {
            log.warn("[{}] 读取文件失败 {}: {}", config.getDomainId(), path.getFileName(), e.getMessage());
            return null;
        }
    }

    /** 重建指定子库集合：清空 → 重新入库该子库全部文件 */
    private boolean indexLibrary(String libId, List<Path> files) {
        try {
            String collectionName = config.collectionName(libId);
            // 删旧集合重建（clearCollection 用空 where 在新版 Chroma 会报 Invalid where clause）
            chromaClient.deleteCollection(collectionName);
            String collectionId = chromaClient.getOrCreateCollection(collectionName);
            if (collectionId == null) {
                log.error("[{}] 无法获取 Chroma 集合 {}", config.getDomainId(), collectionName);
                return false;
            }

            int totalChunks = 0;
            // 区分两类问题：ioFailure=瞬时故障（embedding/写入失败 → 值得重试）
            //              contentEmpty=内容问题（扫描件提取不出文本 → 重试无意义）
            boolean ioFailure = false;
            for (Path path : files) {
                String name = path.getFileName().toString();
                String content = extractText(path);
                if (content == null || content.isBlank()) {
                    // 内容问题，不是故障。记 WARN 并留下 chunks=0 的指纹 —— 这个指纹是诚实的
                    // （该文件确实贡献 0 个 chunk），同时避免每次启动都重新解析几十 MB 的 PDF
                    log.warn("[{}] 文件 {} 提取不出文本（扫描件无文本层？），记为该文件 0 chunk",
                            config.getDomainId(), name);
                    fingerprints.put(relativeKey(path), zeroChunkFingerprint(path));
                    continue;
                }

                List<TravelKnowledgeChunker.Chunk> chunks =
                        chunker.chunk(content, name, config.getChunkSize(), config.getChunkOverlap());
                if (chunks.isEmpty()) {
                    log.warn("[{}] 文件 {} 分块结果为空，记为该文件 0 chunk", config.getDomainId(), name);
                    fingerprints.put(relativeKey(path), zeroChunkFingerprint(path));
                    continue;
                }

                List<String> texts = chunks.stream().map(TravelKnowledgeChunker.Chunk::getText).toList();
                List<List<Float>> embeddings = embeddingService.generateEmbeddingsBatch(texts, config.getEmbedBatchSize());

                List<String> ids = new ArrayList<>();
                List<List<Double>> doubleEmbs = new ArrayList<>();
                List<String> docs = new ArrayList<>();
                List<Map<String, Object>> metadatas = new ArrayList<>();
                String fileKey = sanitize(name.replaceFirst("(?i)\\.(txt|pdf|docx)$", ""));
                for (int i = 0; i < chunks.size(); i++) {
                    List<Float> emb = embeddings.get(i);
                    if (emb == null || emb.isEmpty()) continue;
                    TravelKnowledgeChunker.Chunk c = chunks.get(i);
                    ids.add(collectionName + "_" + fileKey + "_" + i);
                    doubleEmbs.add(embeddingService.toDoubleList(emb));
                    docs.add(c.getText());
                    Map<String, Object> meta = new HashMap<>(c.toMetadata());
                    meta.put("source", libId);
                    meta.put("file", name);
                    // 侧车补充元数据（如 classId）：检索端据此做隔离/权限过滤
                    Map<String, Object> extra = metaMap.get(relativeKey(path));
                    if (extra != null && !extra.isEmpty()) {
                        meta.putAll(extra);
                    }
                    metadatas.add(meta);
                }

                // embedding 全失败（典型：DashScope 账户欠费返回 Arrearage）→ 一个向量都写不进去。
                // 历史实现在这里静默 continue 然后报「✅ 重建完成」并写指纹，导致永久卡在空集合
                if (ids.isEmpty()) {
                    log.error("[{}] 文件 {} 的 {} 个 chunk 全部 embedding 失败，未写入任何向量，指纹不更新",
                            config.getDomainId(), name, chunks.size());
                    ioFailure = true;
                    continue;
                }
                if (ids.size() < chunks.size()) {
                    log.warn("[{}] 文件 {} 部分 chunk embedding 失败：{}/{}",
                            config.getDomainId(), name, ids.size(), chunks.size());
                    ioFailure = true;
                }

                if (!chromaClient.addDocuments(collectionId, ids, doubleEmbs, docs, metadatas)) {
                    log.error("[{}] 文件 {} 写入 Chroma 失败，指纹不更新", config.getDomainId(), name);
                    ioFailure = true;
                    continue;
                }
                totalChunks += ids.size();

                // 只有确认写入成功才更新指纹（指纹的语义是「这些内容已经进向量库了」）。
                // 注意 chunks 取 ids.size()（**真正写进向量库的条数**）而不是 chunks.size()
                // （分块产出的条数）—— 旧实现写的是后者，于是 embedding 全部失败时
                // 分块产出 18 条、实际入库 0 条，指纹照样声称 18 条，属于构造性撒谎。
                FileFingerprint fp = new FileFingerprint();
                fp.sha256 = computeSha256(path);
                fp.size = Files.size(path);
                fp.lastModified = Files.getLastModifiedTime(path).toMillis();
                fp.chunks = ids.size();
                fingerprints.put(relativeKey(path), fp);
            }

            // 回读校验：别信「调用没报错」，直接问 Chroma 到底有多少条
            int actual = chromaClient.count(collectionId);
            if (actual < 0) {
                log.error("[{}] 子库 {} 无法回读集合向量数，判定失败", config.getDomainId(), libId);
                return false;
            }
            if (totalChunks == 0) {
                if (ioFailure) {
                    log.error("[{}] 子库 {} 未写入任何向量（embedding 或写入失败），判定失败，稍后会重试",
                            config.getDomainId(), libId);
                    return false;
                }
                // 全部文件都提取不出文本：这是内容问题，重试一万次也没用 → 不判失败（避免无意义重试），
                // 但要说清楚这个知识库其实没有可用内容
                log.warn("[{}] 子库 {} 入库 0 条：全部文件都提取不出文本（多为扫描件）。"
                                + "这不是故障，但该子库没有可用内容，检索不会命中任何东西",
                        config.getDomainId(), libId);
                return true;
            }
            if (actual != totalChunks) {
                log.error("[{}] 子库 {} 写入校验不通过：报告写入 {} 条，集合实际 {} 条",
                        config.getDomainId(), libId, totalChunks, actual);
                return false;
            }

            if (ioFailure) {
                log.warn("⚠ [{}] 子库 {} 部分完成：{} 文件，{} chunks（集合校验 {} 条，有文件写入失败，稍后重试）",
                        config.getDomainId(), libId, files.size(), totalChunks, actual);
                return false;
            }
            log.info("✅ [{}] 子库 {} 重建完成（{} 文件，{} chunks，已回读校验）",
                    config.getDomainId(), libId, files.size(), totalChunks);
            return true;
        } catch (Exception e) {
            log.error("[{}] 重建子库 {} 异常: {}", config.getDomainId(), libId, e.getMessage(), e);
            return false;
        }
    }

    /** 为「提取不出内容」的文件生成 chunks=0 的指纹（诚实记录 + 避免重复解析大文件） */
    private FileFingerprint zeroChunkFingerprint(Path path) throws IOException {
        FileFingerprint fp = new FileFingerprint();
        fp.sha256 = computeSha256(path);
        fp.size = Files.size(path);
        fp.lastModified = Files.getLastModifiedTime(path).toMillis();
        fp.chunks = 0;
        return fp;
    }

    private void pruneFingerprints() {
        Path dir = Paths.get(config.getKnowledgeDir());
        if (!Files.exists(dir)) {
            fingerprints.clear();
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            Set<String> present = files.filter(Files::isRegularFile)
                    .map(this::relativeKey)
                    .collect(Collectors.toSet());
            fingerprints.keySet().removeIf(key -> !present.contains(key));
        } catch (IOException e) {
            log.warn("[{}] 清理指纹失败: {}", config.getDomainId(), e.getMessage());
        }
    }

    /**
     * 解析 {@code .meta-map.json} 的内容：{@code {"<相对路径>": {"classId": "..."}}}。
     *
     * <p>解析失败或格式不符时返回空表（视为"无补充元数据"），<b>绝不抛异常</b>——
     * 一个元数据文件写坏，不该让整个知识库起不来。
     *
     * <p>公开为静态是为了让"按班隔离"这条链路可被单测验证：
     * 索引器本身依赖 Chroma，端到端隔离没法在单测里跑，但格式解析与写入可以。
     */
    public static Map<String, Map<String, Object>> parseMetaMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            JSONObject root = JSON.parseObject(json);
            if (root == null) return Map.of();
            Map<String, Map<String, Object>> out = new HashMap<>();
            for (String key : root.keySet()) {
                JSONObject obj = root.getJSONObject(key);
                if (obj == null) continue;
                out.put(key, new HashMap<>(obj));
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 读取 {@code <knowledgeDir>/.meta-map.json}；不存在或格式错误时返回空表。
     * 文件缺失不是错误——绝大多数知识库不需要它。
     */
    private Map<String, Map<String, Object>> loadMetaMap() {
        Path path = Paths.get(config.getKnowledgeDir(), ".meta-map.json");
        if (!Files.exists(path)) return Map.of();
        try {
            Map<String, Map<String, Object>> out = parseMetaMap(Files.readString(path));
            log.info("[{}] 已加载 {} 条文件元数据映射（.meta-map.json）", config.getDomainId(), out.size());
            return out;
        } catch (Exception e) {
            log.warn("[{}] 读取 .meta-map.json 失败（按无补充元数据处理）：{}",
                    config.getDomainId(), e.getMessage());
            return Map.of();
        }
    }

    private void loadFingerprints() {
        Path fpPath = Paths.get(config.fingerprintFile());
        if (!Files.exists(fpPath)) return;
        try {
            JSONObject root = JSON.parseObject(Files.readString(fpPath));
            if (root == null || root.getIntValue("version", 0) != FINGERPRINT_VERSION) {
                fingerprints.clear();
                return;
            }
            JSONObject files = root.getJSONObject("files");
            fingerprints.clear();
            if (files != null) {
                for (String key : files.keySet()) {
                    fingerprints.put(key, FileFingerprint.fromJson(files.getJSONObject(key)));
                }
            }
            log.info("[{}] 已加载 {} 个文件指纹", config.getDomainId(), fingerprints.size());
        } catch (Exception e) {
            log.warn("[{}] 加载指纹失败，将全量重建: {}", config.getDomainId(), e.getMessage());
            fingerprints.clear();
        }
    }

    private void saveFingerprints() {
        Path fpPath = Paths.get(config.fingerprintFile());
        try {
            if (fpPath.getParent() != null) Files.createDirectories(fpPath.getParent());
            JSONObject root = new JSONObject();
            root.put("version", FINGERPRINT_VERSION);
            JSONObject files = new JSONObject();
            for (Map.Entry<String, FileFingerprint> e : fingerprints.entrySet()) {
                files.put(e.getKey(), e.getValue().toJson());
            }
            root.put("files", files);
            Files.writeString(fpPath, root.toJSONString());
        } catch (IOException e) {
            log.warn("[{}] 保存指纹失败: {}", config.getDomainId(), e.getMessage());
        }
    }

    private boolean isChanged(Path file, FileFingerprint prev) throws IOException {
        long size = Files.size(file);
        if (size != prev.size) return true;
        FileTime mtime = Files.getLastModifiedTime(file);
        if (mtime.toMillis() != prev.lastModified) return true;
        String sha256 = computeSha256(file);
        return sha256 == null || !sha256.equals(prev.sha256);
    }

    private String computeSha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream is = Files.newInputStream(file)) {
                int n;
                while ((n = is.read(buffer)) != -1) md.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            log.warn("[{}] 计算哈希失败 {}: {}", config.getDomainId(), file.getFileName(), e.getMessage());
            return null;
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static class FileFingerprint {
        String sha256;
        long lastModified;
        long size;
        int chunks;

        static FileFingerprint fromJson(JSONObject obj) {
            FileFingerprint fp = new FileFingerprint();
            fp.sha256 = obj.getString("sha256");
            fp.lastModified = obj.getLongValue("lastModified");
            fp.size = obj.getLongValue("size");
            fp.chunks = obj.getIntValue("chunks");
            return fp;
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            obj.put("sha256", sha256);
            obj.put("lastModified", lastModified);
            obj.put("size", size);
            obj.put("chunks", chunks);
            return obj;
        }
    }
}
