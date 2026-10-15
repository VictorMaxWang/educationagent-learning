package com.demo.chroma;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.base.EmbeddingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
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
 * 法律文库索引引擎 — 完全按主工程知识中心方案实现。
 * <p>
 * 链路：UTF-8 法律文本 → {@link TravelKnowledgeChunker} 分块（段落优先，500/50）
 * → {@link EmbeddingService}（DashScope text-embedding-v3，1024 维，批量 10 条/请求）
 * → {@link ChromaClient} 写入 law_{libId} 集合（cosine，入库前清空防重复）。
 * <p>
 * <b>目录扫描模式</b>：扫描 {@code data/law-knowledge/*.txt}，文件名决定文库：
 * {@code civil_code.txt}→civil、{@code labor.txt}→labor、{@code criminal_code.txt}→criminal。
 * 用户把法律文本丢进目录 → 重启后端即自动入库，无需改配置。
 * <p>
 * 增量索引：按文件记 SHA-256 + 大小 + 修改时间三重指纹；某文库下任一文件新增/修改/删除
 * → 重建该文库集合（清空 + 重新入库该文库全部文件），未变化则跳过。
 * <p>
 * 启动不阻塞：入库在后台守护线程执行，Spring 启动立即返回；
 * 状态经 {@link #getState()} 暴露给 {@link LawKnowledgeService}（首次检索时兜底触发/友好提示）。
 */
@Slf4j
@Service
public class LawKnowledgeIndexer {

    /** 入库状态机 */
    public enum IndexState { NOT_STARTED, INDEXING, DONE, FAILED }

    private static final String KNOWLEDGE_DIR = "data/law-knowledge";
    private static final String FINGERPRINT_FILE = KNOWLEDGE_DIR + "/.law-fingerprints.json";
    /** v2：指纹结构从「按 lib」改为「按文件」，旧指纹作废重建 */
    private static final int FINGERPRINT_VERSION = 2;
    private static final int EMBED_BATCH_SIZE = 10;

    @Resource
    private ChromaClient chromaClient;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private TravelKnowledgeChunker chunker;

    /** 指纹缓存：文件名 → 指纹（按文件，支持同文库多文件） */
    private final Map<String, FileFingerprint> fingerprints = new HashMap<>();

    /** 入库状态（volatile，跨线程可见） */
    private volatile IndexState state = IndexState.NOT_STARTED;

    /** 失败重试节流：避免 Chroma/DashScope 故障时反复触发全量入库 */
    private volatile long lastAttemptAt = 0;
    private static final long RETRY_INTERVAL_MS = 60_000;

    // ======================== 启动（后台线程，不阻塞） ========================

    @PostConstruct
    public void init() {
        // 后台守护线程入库：不阻塞 Spring 启动（全量向量化需数分钟）
        Thread worker = new Thread(this::indexIfNeeded, "law-knowledge-indexer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 增量索引（幂等）：扫描目录 → 找出有变化的文库 → 重建该文库集合。
     * 启动线程 + 首次检索兜底都会调用，内部加锁防并发。
     */
    public synchronized void indexIfNeeded() {
        if (state == IndexState.INDEXING || state == IndexState.DONE) return;
        long now = System.currentTimeMillis();
        if (state == IndexState.FAILED && now - lastAttemptAt < RETRY_INTERVAL_MS) return;
        lastAttemptAt = now;
        state = IndexState.INDEXING;
        try {
            loadFingerprints();

            Map<String, List<Path>> libFiles = scanLawDir();
            Set<String> affected = findAffectedLibs(libFiles);

            int indexedOk = 0;
            for (String lib : affected) {
                try {
                    if (indexLibrary(lib, libFiles.getOrDefault(lib, List.of()))) indexedOk++;
                } catch (Exception ex) {
                    log.warn("⚖️ 文库 {} 重建失败: {}", lib, ex.getMessage());
                }
            }

            pruneFingerprints();
            saveFingerprints();

            // 无变化(affected 空) 或全部成功 → DONE；有失败 → FAILED（稍后重试）
            state = indexedOk == affected.size() ? IndexState.DONE : IndexState.FAILED;
            log.info("⚖️ 法律文库入库结束：重建 {} / 待处理 {}，状态={}", indexedOk, affected.size(), state);
        } catch (Exception e) {
            state = IndexState.FAILED;
            log.warn("⚖️ 法律文库入库异常: {}", e.getMessage());
        }
    }

    /** 获取当前入库状态（供检索/健康检查判断） */
    public IndexState getState() {
        return state;
    }

    // ======================== 目录扫描 ========================

    /** 扫描 data/law-knowledge/*.txt，按文件名推导 libId 分组 */
    private Map<String, List<Path>> scanLawDir() {
        Map<String, List<Path>> libFiles = new LinkedHashMap<>();
        Path dir = Paths.get(KNOWLEDGE_DIR);
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.info("📁 法律知识库目录已创建：{}", dir.toAbsolutePath());
            } catch (IOException e) {
                log.warn("创建法律知识库目录失败: {}", e.getMessage());
            }
            return libFiles;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .forEach(p -> {
                        String lib = libFromFileName(p.getFileName().toString());
                        libFiles.computeIfAbsent(lib, k -> new ArrayList<>()).add(p);
                    });
        } catch (IOException e) {
            log.warn("⚖️ 扫描法律知识库目录失败: {}", e.getMessage());
        }
        return libFiles;
    }

    /**
     * 文件名 → 文库：去扩展名，取第一个 {@code _} 之前的部分。
     * civil_code.txt→civil、labor.txt→labor、criminal_code.txt→criminal
     */
    private static String libFromFileName(String fileName) {
        String stem = fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        int us = stem.indexOf('_');
        return (us > 0 ? stem.substring(0, us) : stem).trim();
    }

    /** 找出需要重建的文库：任一文件新增/修改，或某文件已被删除 */
    private Set<String> findAffectedLibs(Map<String, List<Path>> libFiles) {
        Set<String> affected = new HashSet<>();
        for (Map.Entry<String, List<Path>> e : libFiles.entrySet()) {
            boolean changed = false;
            for (Path p : e.getValue()) {
                String name = p.getFileName().toString();
                FileFingerprint prev = fingerprints.get(name);
                try {
                    if (prev == null || isChanged(p, prev)) {
                        changed = true;
                        break;
                    }
                } catch (IOException ex) {
                    changed = true;
                    break;
                }
            }
            if (changed) affected.add(e.getKey());
        }
        // 已删除的文件 → 其所属文库也需重建（清掉旧向量）
        Set<String> present = libFiles.values().stream()
                .flatMap(List::stream)
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toSet());
        for (String name : fingerprints.keySet()) {
            if (!present.contains(name)) {
                affected.add(libFromFileName(name));
            }
        }

        // 自愈：指纹与集合状态**自相矛盾**时安排重建。
        // 判据是「矛盾」而非单纯「集合为空」：指纹声称该文库有 N 个 chunk（N>0）却查不到向量，
        // 才是写入静默失败的证据。这正是 law_civil 当前的状况（指纹记着 266 chunks，集合不存在），
        // 旧逻辑会永远跳过重建；现在主动回读 Chroma 校正，不必再手工删指纹文件。
        for (String lib : libFiles.keySet()) {
            if (affected.contains(lib)) continue;
            int claimed = claimedChunksOf(lib);
            String collectionName = "law_" + sanitize(lib);
            try {
                String cid = chromaClient.findCollectionId(collectionName);
                if (cid == null) {
                    if (claimed > 0) {
                        log.warn("⚖️ 文库 {} 指纹声称已入库 {} 个 chunk，但集合 {} 不存在 → 安排重建",
                                lib, claimed, collectionName);
                        affected.add(lib);
                    }
                    continue;
                }
                int n = chromaClient.count(cid);
                if (n == 0 && claimed > 0) {
                    log.warn("⚖️ 文库 {} 指纹声称已入库 {} 个 chunk，但集合只有 0 条 → 安排重建",
                            lib, claimed);
                    affected.add(lib);
                }
            } catch (Exception ex) {
                log.warn("⚖️ 无法校验文库 {} 的集合状态（{}）→ 安排重建", lib, ex.getMessage());
                affected.add(lib);
            }
        }
        return affected;
    }

    /** 某文库所有文件指纹声称已入库的 chunk 总数（用于判定「指纹 vs 集合」是否自相矛盾） */
    private int claimedChunksOf(String lib) {
        int sum = 0;
        for (Map.Entry<String, FileFingerprint> e : fingerprints.entrySet()) {
            if (lib.equals(libFromFileName(e.getKey()))) {
                sum += e.getValue().chunks;
            }
        }
        return sum;
    }

    // ======================== 单文库重建 ========================

    /** 重建指定文库集合：清空 → 重新入库该文库全部文件；成功返回 true */
    private boolean indexLibrary(String libId, List<Path> files) {
        try {
            String collectionName = "law_" + sanitize(libId);
            // 删集合重建，而不是 clearCollection：
            //  ① Chroma 1.x 拒绝空 where（实测 400 InvalidArgumentError: Invalid where clause），
            //     clearCollection 发 {"where":{}} 根本清不掉任何东西；
            //  ② 只清空不删集合会保留旧的向量维度 —— 换 embedding 模型后写入必然失败
            //     （实测 Collection expecting embedding with dimension of 1024, got 768）。
            //     删掉重建才能让维度随新模型生效。
            chromaClient.deleteCollection(collectionName);
            String collectionId = chromaClient.getOrCreateCollection(collectionName);
            if (collectionId == null) {
                log.error("⚖️ 无法获取 Chroma 集合 {}", collectionName);
                return false;
            }

            int totalChunks = 0;
            // ioFailure=瞬时故障（embedding/写入失败 → 值得重试）；内容为空=文件本身没内容 → 重试无意义
            boolean ioFailure = false;
            for (Path path : files) {
                String name = path.getFileName().toString();
                String content = Files.readString(path, StandardCharsets.UTF_8);
                if (content.isBlank()) {
                    log.warn("⚖️ 文库 {} 文件 {} 内容为空，记为该文件 0 chunk", libId, name);
                    fingerprints.put(name, zeroChunkFingerprint(path));
                    continue;
                }

                List<TravelKnowledgeChunker.Chunk> chunks = chunker.chunk(content, libId);
                if (chunks.isEmpty()) {
                    log.warn("⚖️ 文库 {} 文件 {} 分块结果为空，记为该文件 0 chunk", libId, name);
                    fingerprints.put(name, zeroChunkFingerprint(path));
                    continue;
                }

                List<String> texts = chunks.stream().map(TravelKnowledgeChunker.Chunk::getText).toList();
                List<List<Float>> embeddings = embeddingService.generateEmbeddingsBatch(texts, EMBED_BATCH_SIZE);

                List<String> ids = new ArrayList<>();
                List<List<Double>> doubleEmbs = new ArrayList<>();
                List<String> docs = new ArrayList<>();
                List<Map<String, Object>> metadatas = new ArrayList<>();
                String fileKey = sanitize(name.replaceFirst("(?i)\\.txt$", ""));
                for (int i = 0; i < chunks.size(); i++) {
                    List<Float> emb = embeddings.get(i);
                    if (emb == null || emb.isEmpty()) continue;
                    TravelKnowledgeChunker.Chunk c = chunks.get(i);
                    ids.add("law_" + sanitize(libId) + "_" + fileKey + "_" + i);
                    doubleEmbs.add(embeddingService.toDoubleList(emb));
                    docs.add(c.getText());
                    Map<String, Object> meta = new HashMap<>(c.toMetadata());
                    meta.put("source", libId);
                    meta.put("file", name);
                    metadatas.add(meta);
                }

                // embedding 全失败（典型：DashScope 账户欠费 Arrearage）→ 一个向量都写不进去。
                // 历史实现在这里静默跳过并照写指纹、置 DONE，导致永久卡在「已完成 + 空集合」
                if (ids.isEmpty()) {
                    log.error("⚖️ 文库 {} 文件 {} 的 {} 个 chunk 全部 embedding 失败，未写入任何向量，指纹不更新",
                            libId, name, chunks.size());
                    ioFailure = true;
                    continue;
                }
                if (ids.size() < chunks.size()) {
                    log.warn("⚖️ 文库 {} 文件 {} 部分 chunk embedding 失败：{}/{}",
                            libId, name, ids.size(), chunks.size());
                    ioFailure = true;
                }

                if (!chromaClient.addDocuments(collectionId, ids, doubleEmbs, docs, metadatas)) {
                    log.error("⚖️ 文库 {} 文件 {} 写入 Chroma 失败，指纹不更新", libId, name);
                    ioFailure = true;
                    continue;
                }
                totalChunks += ids.size();

                // 更新该文件指纹（只在确认写入成功后）。
                // chunks 取 ids.size()（真正入库条数）而非 chunks.size()（分块产出条数）——
                // 旧实现写后者，导致 embedding 全失败时指纹仍声称 266 条、实际 0 条
                FileFingerprint fp = new FileFingerprint();
                fp.sha256 = computeSha256(path);
                fp.size = Files.size(path);
                fp.lastModified = Files.getLastModifiedTime(path).toMillis();
                fp.chunks = ids.size();
                fingerprints.put(name, fp);
            }

            // 回读校验：别信「调用没报错」，直接问 Chroma 到底有多少条
            int actual = chromaClient.count(collectionId);
            if (actual < 0) {
                log.error("⚖️ 文库 {} 无法回读集合向量数，判定失败", libId);
                return false;
            }
            if (totalChunks == 0) {
                if (ioFailure) {
                    log.error("⚖️ 文库 {} 未写入任何向量（embedding 或写入失败），判定失败，稍后会重试", libId);
                    return false;
                }
                log.warn("⚖️ 文库 {} 入库 0 条：文件都没有可提取内容。这不是故障，但该文库无可用内容", libId);
                return true;
            }
            if (actual != totalChunks) {
                log.error("⚖️ 文库 {} 写入校验不通过：报告写入 {} 条，集合实际 {} 条",
                        libId, totalChunks, actual);
                return false;
            }

            if (ioFailure) {
                log.warn("⚠️ 法律文库部分完成: {}（{} 个文件，{} chunks，集合校验 {} 条，有文件写入失败，稍后重试）",
                        libId, files.size(), totalChunks, actual);
                return false;
            }
            log.info("✅ 法律文库重建完成: {}（{} 个文件，{} chunks，集合 {}，已回读校验）",
                    libId, files.size(), totalChunks, collectionName);
            return true;
        } catch (Exception e) {
            log.error("⚖️ 重建文库 {} 异常: {}", libId, e.getMessage(), e);
            return false;
        }
    }

    /** 为「提取不出内容」的文件生成 chunks=0 的指纹（诚实记录 + 避免重复解析） */
    private FileFingerprint zeroChunkFingerprint(Path path) throws IOException {
        FileFingerprint fp = new FileFingerprint();
        fp.sha256 = computeSha256(path);
        fp.size = Files.size(path);
        fp.lastModified = Files.getLastModifiedTime(path).toMillis();
        fp.chunks = 0;
        return fp;
    }

    /** 清理指纹中已被删除的文件 */
    private void pruneFingerprints() {
        Path dir = Paths.get(KNOWLEDGE_DIR);
        if (!Files.exists(dir)) {
            fingerprints.clear();
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            Set<String> present = files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
            fingerprints.keySet().removeIf(name -> !present.contains(name));
        } catch (IOException e) {
            log.warn("⚖️ 清理法律指纹失败: {}", e.getMessage());
        }
    }

    // ======================== 指纹（SHA-256 + 大小 + 修改时间） ========================

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

    private void loadFingerprints() {
        Path fpPath = Paths.get(FINGERPRINT_FILE);
        if (!Files.exists(fpPath)) return;
        try {
            JSONObject root = JSON.parseObject(Files.readString(fpPath));
            if (root == null || root.getIntValue("version", 0) != FINGERPRINT_VERSION) {
                log.info("⚖️ 法律指纹版本不匹配或已损坏，将全量重建");
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
            log.info("⚖️ 已加载 {} 个法律文件指纹", fingerprints.size());
        } catch (Exception e) {
            log.warn("⚖️ 加载法律指纹失败，将全量重建: {}", e.getMessage());
            fingerprints.clear();
        }
    }

    private void saveFingerprints() {
        Path fpPath = Paths.get(FINGERPRINT_FILE);
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
            log.warn("⚖️ 保存法律指纹失败: {}", e.getMessage());
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
                while ((n = is.read(buffer)) != -1) {
                    md.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            log.warn("⚖️ 计算哈希失败 {}: {}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
