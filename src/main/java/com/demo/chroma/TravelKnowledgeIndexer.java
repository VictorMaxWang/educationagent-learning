package com.demo.chroma;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.base.EmbeddingService;
import com.demo.service.preprocessing.FileService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 知识库索引引擎
 * <p>
 * 扫描 data/travel-knowledge/ 目录下的文档，
 * 经分块 → 向量化 → 存入 Chroma travel_knowledge collection。
 * <p>
 * 特性：
 * - <b>文件指纹增量索引</b>：通过 SHA-256 + 文件大小 + 修改时间三重比对检测文件变更，
 * 启动时仅索引新增或修改过的文件，跳过未变更文件，大幅减少 Embedding API 调用。
 * - 全量重建：调用 reindex() 清空 Chroma 集合 + 指纹文件后重新索引所有文件。
 * <p>
 * 触发时机：
 * - 应用启动时自动扫描增量索引
 * - 控制台命令 /reindex 触发全量重建
 */
@Slf4j
@Service
public class TravelKnowledgeIndexer {

    private static final String KNOWLEDGE_DIR = "data/travel-knowledge";
    private static final String FINGERPRINT_FILE = KNOWLEDGE_DIR + "/.fingerprints.json";
    private static final int FINGERPRINT_VERSION = 1;

    @Value("${memory.chroma.enabled:false}")
    private boolean chromaEnabled;

    @Resource
    private ChromaClient chromaClient;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private TravelKnowledgeChunker chunker;

    @Resource
    private FileService fileService;

    /** 文件指纹缓存：文件名 → 指纹信息 */
    private Map<String, FileFingerprint> fingerprints = new HashMap<>();

    // ========================================================================
    //  文件指纹（SHA-256 + 文件大小 + 修改时间）
    // ========================================================================

    /**
     * 单个文件的持久化指纹
     */
    private static class FileFingerprint {
        String sha256;       // 文件内容的 SHA-256 哈希
        long lastModified;   // 最后修改时间戳（毫秒）
        long size;           // 文件大小（字节）
        int chunks;          // 上次索引时的分块数量

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

    /**
     * 从磁盘加载文件指纹（data/travel-knowledge/.fingerprints.json）
     * <p>
     * 首次运行或版本不匹配时返回空指纹表，下次启动会全量索引。
     */
    private void loadFingerprints() {
        Path fpPath = Paths.get(FINGERPRINT_FILE);
        if (!Files.exists(fpPath)) {
            fingerprints = new HashMap<>();
            return;
        }
        try {
            String content = Files.readString(fpPath);
            JSONObject root = JSON.parseObject(content);
            if (root == null || root.getIntValue("version", 0) != FINGERPRINT_VERSION) {
                log.info("📋 指纹文件版本不匹配或已损坏，将重新索引所有文件");
                fingerprints = new HashMap<>();
                return;
            }
            JSONObject files = root.getJSONObject("files");
            Map<String, FileFingerprint> map = new HashMap<>();
            if (files != null) {
                for (String key : files.keySet()) {
                    map.put(key, FileFingerprint.fromJson(files.getJSONObject(key)));
                }
            }
            fingerprints = map;
            log.info("📋 已加载 {} 个文件指纹", fingerprints.size());
        } catch (Exception e) {
            log.warn("加载指纹文件失败，将重新索引所有文件: {}", e.getMessage());
            fingerprints = new HashMap<>();
        }
    }

    /**
     * 持久化文件指纹到磁盘
     */
    private void saveFingerprints() {
        Path fpPath = Paths.get(FINGERPRINT_FILE);
        try {
            if (!Files.exists(fpPath.getParent())) {
                Files.createDirectories(fpPath.getParent());
            }
            JSONObject root = new JSONObject();
            root.put("version", FINGERPRINT_VERSION);
            JSONObject files = new JSONObject();
            for (Map.Entry<String, FileFingerprint> entry : fingerprints.entrySet()) {
                files.put(entry.getKey(), entry.getValue().toJson());
            }
            root.put("files", files);
            Files.writeString(fpPath, root.toJSONString());
            log.debug("💾 文件指纹已保存 ({} 条)", fingerprints.size());
        } catch (IOException e) {
            log.warn("保存指纹文件失败: {}", e.getMessage());
        }
    }

    /**
     * 删除指纹文件（全量重建时调用）
     */
    private void deleteFingerprints() {
        fingerprints = new HashMap<>();
        try {
            Files.deleteIfExists(Paths.get(FINGERPRINT_FILE));
            log.info("🗑 文件指纹已清除");
        } catch (IOException e) {
            log.warn("删除指纹文件失败: {}", e.getMessage());
        }
    }

    /**
     * 计算文件的 SHA-256 哈希（流式读取，内存友好）
     */
    private String computeSha256(Path filePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream is = Files.newInputStream(filePath)) {
                int n;
                while ((n = is.read(buffer)) != -1) {
                    md.update(buffer, 0, n);
                }
            }
            byte[] digest = md.digest();
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | IOException e) {
            log.warn("计算文件哈希失败: {} - {}", filePath.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * 判断文件是否发生变化（三层检测）
     * <ol>
     *   <li>指纹不存在 → 新文件，需索引</li>
     *   <li>文件大小或修改时间变化 → 内容已变更，需重新索引</li>
     *   <li>SHA-256 哈希不匹配 → 内容确实变了，需重新索引</li>
     * </ol>
     */
    private boolean isFileChanged(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        FileFingerprint prev = fingerprints.get(fileName);
        if (prev == null) {
            return true; // 新文件
        }

        // 第一层：快速检查文件大小
        long size = Files.size(filePath);
        if (size != prev.size) {
            return true;
        }

        // 第二层：检查修改时间（毫秒级精度）
        FileTime lastModified = Files.getLastModifiedTime(filePath);
        if (lastModified.toMillis() != prev.lastModified) {
            return true;
        }

        // 第三层：SHA-256 哈希精确比对（毫秒级，远快于 Embedding API 调用）
        String sha256 = computeSha256(filePath);
        return sha256 == null || !sha256.equals(prev.sha256);
    }

    // ========================================================================
    //  启动时自动索引（增量模式）
    // ========================================================================

    @PostConstruct
    public void indexOnStartup() {
        if (!chromaEnabled) {
            log.info("📴 Chroma 未启用，跳过知识库索引");
            return;
        }

        Path dir = Paths.get(KNOWLEDGE_DIR);
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.info("📁 知识库目录已创建：{}", dir.toAbsolutePath());
            } catch (IOException e) {
                log.warn("创建知识库目录失败: {}", e.getMessage());
            }
            return;
        }

        // 1. 加载已有文件指纹
        loadFingerprints();

        log.info("📚 开始扫描知识库目录：{}", dir.toAbsolutePath());
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> docs = files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .collect(Collectors.toList());
            if (docs.isEmpty()) {
                log.info("📭 知识库目录为空，请放入文档后手动执行 /reindex");
                return;
            }

            // 2. 确保 Chroma 集合存在
            String collectionId = chromaClient.getOrCreateCollection(
                    TravelKnowledgeService.getCollectionName());
            if (collectionId == null) {
                log.error("无法获取 travel_knowledge 集合，索引中止");
                return;
            }

            // 3. 逐文件检查指纹，仅索引变更的文件
            int indexed = 0;
            int skipped = 0;
            int failed = 0;
            for (Path doc : docs) {
                try {
                    if (isFileChanged(doc)) {
                        indexFile(doc);
                        indexed++;
                    } else {
                        log.info("⏭️ 文件未变更，跳过: {}", doc.getFileName());
                        skipped++;
                    }
                } catch (Exception e) {
                    log.warn("索引文件失败: {} - {}", doc.getFileName(), e.getMessage());
                    failed++;
                }
            }

            // 4. 持久化更新后的指纹
            saveFingerprints();

            if (indexed > 0) {
                log.info("✅ 知识库增量索引完成：新增 {}，跳过 {}，失败 {}",
                        indexed, skipped, failed);
            } else {
                log.info("⏭️ 所有文件均未变更，跳过索引 ({} 个文件)", skipped);
            }
        } catch (IOException e) {
            log.warn("扫描知识库目录失败: {}", e.getMessage());
        }
    }

    // ========================================================================
    //  手动全量重建
    // ========================================================================

    /**
     * 全量重建索引
     * <p>
     * 1. 清空 Chroma travel_knowledge 集合
     * 2. 删除指纹文件（确保所有文件被重新索引）
     * 3. 重新索引所有文件
     */
    public void reindex() {
        log.info("🔄 开始全量重建知识库索引...");

        // 1. 清空 Chroma 集合
        chromaClient.deleteCollection(TravelKnowledgeService.getCollectionName());
        log.info("🗑 Chroma 集合已清空");

        // 2. 删除指纹（确保启动时所有文件都被重新索引）
        deleteFingerprints();

        // 3. 重新索引
        indexOnStartup();

        log.info("✅ 知识库全量重建完成");
    }

    // ========================================================================
    //  单文件索引
    // ========================================================================

    /**
     * 索引单个文件（提取内容 → 分块 → 向量化 → 存入 Chroma → 更新指纹）
     */
    public void indexFile(Path filePath) {
        if (!Files.exists(filePath)) {
            log.warn("文件不存在: {}", filePath);
            return;
        }

        String fileName = filePath.getFileName().toString();
        log.info("📄 索引文件: {}", fileName);

        // 1. 提取文本内容（复用 FileService）
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(filePath);
        } catch (IOException e) {
            log.warn("读取文件失败: {} - {}", fileName, e.getMessage());
            return;
        }
        String content = fileService.extractContent(fileBytes);
        if (content == null || content.isBlank()) {
            log.warn("文件内容为空，跳过: {}", fileName);
            return;
        }

        // 2. 分块
        List<TravelKnowledgeChunker.Chunk> chunks = chunker.chunk(content, fileName);
        if (chunks.isEmpty()) {
            log.warn("分块结果为空，跳过: {}", fileName);
            return;
        }

        // 3. 批量转向量（⚠ 此处为最大耗时点：每个 chunk 一次网络调用）
        List<String> texts = chunks.stream()
                .map(TravelKnowledgeChunker.Chunk::getText)
                .collect(Collectors.toList());
        List<List<Float>> embeddings = embeddingService.generateEmbeddings(texts);

        // 4. 存入 Chroma
        String collectionId = chromaClient.getOrCreateCollection(
                TravelKnowledgeService.getCollectionName());
        if (collectionId == null) {
            log.error("无法获取 travel_knowledge 集合，索引中止");
            return;
        }

        List<String> ids = new ArrayList<>();
        List<List<Double>> doubleEmbeddings = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            List<Float> emb = embeddings.get(i);
            if (emb == null || emb.isEmpty()) continue;

            TravelKnowledgeChunker.Chunk chunk = chunks.get(i);
            ids.add("tk_" + fileName.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + i);
            doubleEmbeddings.add(embeddingService.toDoubleList(emb));
            documents.add(chunk.getText());
            metadatas.add(chunk.toMetadata());
        }

        chromaClient.addDocuments(collectionId, ids, doubleEmbeddings, documents, metadatas);
        log.info("✅ 已索引: {} ({} chunks)", fileName, chunks.size());

        // 5. 索引成功后更新文件指纹
        updateFingerprint(filePath, chunks.size());
    }

    /**
     * 索引成功后更新文件指纹
     */
    private void updateFingerprint(Path filePath, int chunkCount) {
        try {
            String fileName = filePath.getFileName().toString();
            FileFingerprint fp = new FileFingerprint();
            fp.sha256 = computeSha256(filePath);
            fp.lastModified = Files.getLastModifiedTime(filePath).toMillis();
            fp.size = Files.size(filePath);
            fp.chunks = chunkCount;
            fingerprints.put(fileName, fp);
            log.debug("🔖 文件指纹已更新: {}", fileName);
        } catch (IOException e) {
            log.warn("更新文件指纹失败: {} - {}", filePath.getFileName(), e.getMessage());
        }
    }
}
