package com.demo.chroma;

import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.base.EmbeddingService;
import com.demo.service.preprocessing.FileService;
import com.demo.service.tools.holder.UserFileHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户个人知识库服务
 * 业务流程：
 * 1. 用户通过微信上传文件，文件二进制缓存至 {@link UserFileHolder}
 * 2. 执行入库指令：文件持久化至磁盘目录 data/user-knowledge/{userId}/
 * 3. 文档文本提取 → 文本分块 → 向量编码
 * 4. 写入 Chroma 独立向量集合（每个用户单独一个 Collection，隔离数据）
 * 5. 对话阶段根据用户ID检索私有知识库片段，实现个性化RAG
 * 存储规范：
 * - 磁盘路径：data/user-knowledge/{安全处理后userId}/
 * - Chroma集合命名前缀：user_knowledge_
 * - 文件冲突策略：同名文件自动追加时间戳避免覆盖
 * - 向量元数据携带：userId、本地文件路径、分块来源信息
 */
@Slf4j
@Service
public class UserKnowledgeService {

    private static final String BASE_DIR = "data/user-knowledge";
    private static final String COLLECTION_PREFIX = "user_knowledge_";
    private Path storageDirectory = Paths.get(BASE_DIR).toAbsolutePath().normalize();

    @Resource
    private ChromaClient chromaClient;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private UserFileHolder userFileHolder;

    @Resource
    private FileService fileService;

    // ======================== 文件持久化 + 向量索引 ========================
    /**
     * 将用户缓存中的文件保存到本地磁盘，并完成文档分块、向量化，存入Chroma向量库
     * 文件生命周期说明：
     * 读取 {@link UserFileHolder} 中userId对应的缓存文件，索引成功后主动清理缓存；
     * 索引失败时保留缓存，允许用户重复执行入库操作
     * @param userId 用户唯一标识
     * @return 执行结果文本（用于直接回复用户）
     */
    public String saveAndIndex(String userId) {
        UserFileHolder.FileEntry entry = userFileHolder.get(userId);
        if (entry == null || entry.bytes() == null) {
            return "没有找到待保存的文件，请先发送文件（支持 PDF/Word/TXT），然后再说「把这个存到我的知识库」。";
        }

        String fileName = entry.fileName();
        if (fileName == null || fileName.isBlank()) {
            fileName = "unnamed_" + System.currentTimeMillis() + ".bin";
        }

        try {
            // 原文件名是不可信输入；先拒绝跨平台路径、ADS 和设备名，再创建用户目录。
            fileName = checkedFileName(fileName);
            Path userDir = userDirectory(userId, true);

            // 文件同名冲突处理：存在同名文件则重命名追加时间戳
            Path filePath = checkedChild(userDir, fileName);
            if (Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(filePath)) throw new IOException("不接受符号链接文件");
                String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
                String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
                filePath = checkedChild(userDir, base + "_" + System.currentTimeMillis()
                        + "_" + UUID.randomUUID().toString().substring(0, 8) + ext);
            }
            // CREATE_NEW 防止并发重试覆盖已有文件，也不会跟随同名符号链接写到目录之外。
            Files.write(filePath, entry.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            // 2. 获取预提取完成的文档文本
            String content = entry.textContent();
            if (content == null || content.isBlank()) {
                return "文件「" + fileName + "」已保存，但无法提取文字内容，未索引到知识库";
            }

            // 3. 文档文本分块（复用 TravelKnowledgeChunker）
            TravelKnowledgeChunker chunker = new TravelKnowledgeChunker();
            List<TravelKnowledgeChunker.Chunk> chunks = chunker.chunk(content, fileName);

            if (chunks.isEmpty()) {
                return "文件「" + fileName + "」已保存，但内容为空，未索引到知识库";
            }

            // 4. 向量化 + 存入 Chroma
            List<String> texts = chunks.stream().map(TravelKnowledgeChunker.Chunk::getText).collect(Collectors.toList());
            List<List<Float>> embeddings = embeddingService.generateEmbeddings(texts);

            String collectionName = COLLECTION_PREFIX + sanitize(userId);
            String collectionId = chromaClient.getOrCreateCollection(collectionName);
            if (collectionId == null) {
                return "文件已保存到磁盘，但知识库索引服务暂不可用";
            }

            // 组装Chroma新增文档参数
            List<String> ids = new ArrayList<>();
            List<List<Double>> doubleEmbs = new ArrayList<>();
            List<String> docs = new ArrayList<>();
            List<Map<String, Object>> metadatas = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                List<Float> emb = embeddings.get(i);
                if (emb == null || emb.isEmpty()) continue;

                TravelKnowledgeChunker.Chunk chunk = chunks.get(i);
                // 构建向量唯一ID：uk_安全文件名_序号
                ids.add("uk_" + sanitize(fileName) + "_" + i);
                doubleEmbs.add(embeddingService.toDoubleList(emb));
                docs.add(chunk.getText());

                // 组装元数据，携带用户ID、文件路径、分块原始信息，方便后续过滤、溯源
                Map<String, Object> meta = new HashMap<>(chunk.toMetadata());
                meta.put("userId", userId);
                meta.put("filePath", filePath.toString());
                metadatas.add(meta);
            }

            // 批量写入向量数据库
            chromaClient.addDocuments(collectionId, ids, doubleEmbs, docs, metadatas);

            // 索引成功，清除临时文件缓存
            userFileHolder.remove(userId);
            log.info("📚 [{}] 个人知识库已索引: {} ({} chunks)", userId, fileName, chunks.size());
            return "✅ 文件「" + fileName + "」已保存到你的知识库并完成索引。" + chunks.size() + " 个知识片段已入库，后续对话中我会参考这些资料。";

        } catch (IOException | IllegalArgumentException e) {
            log.warn("[{}] 保存知识库文件失败，类型={}", userId, e.getClass().getSimpleName());
            return "❌ 文件保存失败，请使用普通文件名，不要包含目录、冒号、控制字符或系统保留名称。";
        }
    }

    // ======================== 知识库检索 ========================
    /**
     * 在用户私有知识库中进行向量相似度检索
     * @param userId 用户唯一标识
     * @param query  用户查询文本
     * @param topK   返回最相似片段数量
     * @return 匹配到的文档文本片段列表；异常/无数据返回空集合
     */
    public List<String> searchKnowledge(String userId, String query, int topK) {
        try {
            String collectionName = COLLECTION_PREFIX + sanitize(userId);
            String collectionId = chromaClient.getOrCreateCollection(collectionName);
            if (collectionId == null) return Collections.emptyList();

            // 查询文本向量化
            List<Float> queryEmb = embeddingService.generateEmbedding(query);
            if (queryEmb == null || queryEmb.isEmpty()) return Collections.emptyList();

            // 执行相似度查询
            return chromaClient.query(collectionId, embeddingService.toDoubleList(queryEmb), topK);
        } catch (Exception e) {
            log.warn("[{}] 个人知识库检索失败: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }


    /**
     * 查询用户已上传并持久化到磁盘的知识库文件名称列表
     * @param userId 用户唯一标识
     * @return 用户目录下所有文件名称，无文件/异常返回空集合
     */
    public List<String> listFiles(String userId) {
        try {
            Path userDir = userDirectory(userId, false);
            if (userDir == null) return Collections.emptyList();
            try (var files = Files.list(userDir)) {
                return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
            }
        } catch (IOException | IllegalArgumentException e) {
            return Collections.emptyList();
        }
    }

    // ======================== 知识中心：文档管理 ========================

    /** 知识库文档详情（文件名 + 大小 + 修改时间），按文件名排序 */
    public List<Map<String, Object>> listDetail(String userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Path userDir = userDirectory(userId, false);
            if (userDir == null) return out;
            try (var stream = Files.list(userDir)) {
            for (Path p : stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                try {
                    Map<String, Object> m = new HashMap<>();
                    m.put("fileName", p.getFileName().toString());
                    m.put("size", Files.size(p));
                    m.put("lastModified", Files.getLastModifiedTime(p).toInstant().toString());
                    out.add(m);
                } catch (IOException ignored) {}
            }
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("[{}] 列出知识库详情失败，类型={}", userId, e.getClass().getSimpleName());
        }
        return out;
    }

    /** 查看知识库某文档的内容（格式描述 + 提取文本） */
    public String viewContent(String userId, String fileName) {
        Path file = resolveFile(userId, fileName);
        if (file == null || !Files.exists(file)) return null;
        try {
            byte[] bytes = Files.readAllBytes(file);
            String fmt = fileService.analyze(bytes);
            String txt = fileService.extractContent(bytes);
            return (fmt == null ? "未知格式" : fmt)
                    + (txt != null && !txt.isBlank() ? "\n\n" + txt : "\n（无法提取文字内容）");
        } catch (IOException e) {
            return "读取失败：" + e.getMessage();
        }
    }

    /** 删除知识库文档：删磁盘文件 + 删 Chroma 中该文件的全部向量 */
    public boolean delete(String userId, String fileName) {
        Path file = resolveFile(userId, fileName);
        if (file == null || !Files.exists(file)) return false;
        try {
            Files.deleteIfExists(file);
            String collectionName = COLLECTION_PREFIX + sanitize(userId);
            String collectionId = chromaClient.getOrCreateCollection(collectionName);
            if (collectionId != null) {
                Map<String, Object> where = Map.of("filePath", Map.of("$eq", file.toString()));
                chromaClient.deleteByWhere(collectionId, where);
            }
            log.info("🗑 [{}] 知识库已删除: {}", userId, fileName);
            return true;
        } catch (Exception e) {
            log.warn("[{}] 删除知识库失败: {}", userId, e.getMessage());
            return false;
        }
    }

    /** 校验并解析知识库内文件路径（防路径穿越） */
    private Path resolveFile(String userId, String fileName) {
        try {
            String name = checkedFileName(fileName);
            Path userDir = userDirectory(userId, false);
            if (userDir == null) return null;
            Path candidate = checkedChild(userDir, name);
            return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) ? candidate : null;
        } catch (IOException | IllegalArgumentException invalid) { return null; }
    }

    /** 发布目录的 data 可以是合法共享目录链接，但用户子目录必须真实留在知识库根目录中。 */
    private Path userDirectory(String userId, boolean create) throws IOException {
        if (userId == null || userId.isBlank() || userId.length() > 160) throw new IllegalArgumentException("用户标识无效");
        Path root = storageDirectory.toAbsolutePath().normalize();
        if (create) Files.createDirectories(root);
        if (!Files.isDirectory(root)) return null;
        Path realRoot = root.toRealPath();
        Path userDir = realRoot.resolve(sanitize(userId)).normalize();
        if (!userDir.startsWith(realRoot) || userDir.equals(realRoot) || Files.isSymbolicLink(userDir))
            throw new IOException("用户目录无效");
        if (create) Files.createDirectories(userDir);
        if (!Files.isDirectory(userDir, LinkOption.NOFOLLOW_LINKS)) return null;
        Path realUser = userDir.toRealPath();
        if (!realUser.startsWith(realRoot) || realUser.equals(realRoot)) throw new IOException("用户目录越界");
        return realUser;
    }

    private static Path checkedChild(Path userDir, String fileName) throws IOException {
        Path candidate = userDir.resolve(fileName).normalize();
        if (!candidate.startsWith(userDir) || !userDir.equals(candidate.getParent())) throw new IOException("文件目录越界");
        return candidate;
    }

    /** 保留普通中文文件名，不把危险路径静默改成另一个文件名。 */
    private static String checkedFileName(String name) {
        if (name == null || name.isBlank() || name.length() > 240 || name.equals(".") || name.equals("..")
                || !name.equals(name.strip()) || name.endsWith(".") || name.chars().anyMatch(Character::isISOControl)
                || name.matches(".*[\\\\/:<>\"|?*].*")
                || name.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")) {
            throw new IllegalArgumentException("文件名无效");
        }
        return name;
    }

    /**
     * 名称清洗工具方法
     * Chroma集合名称、向量ID仅支持字母、数字、下划线、横杠，非法字符统一替换为下划线，防止API报错
     * @param name 原始userId/文件名
     * @return 清洗后安全字符串
     */
    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
