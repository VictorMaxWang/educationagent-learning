package com.demo.edu.teacher;

import com.demo.chroma.doc.DocKnowledgeAssistant;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.controller.DesktopFileStore;
import com.demo.edu.mapper.EduResourceMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 校本资源上传：把教师上传的文件落进校本知识库目录并<b>强制重建索引</b>。
 *
 * <h3>两个必须显式处理的坑</h3>
 * <ol>
 *   <li><b>索引器只认 txt / pdf / docx</b>（{@code DocKnowledgeIndexer.SUPPORTED_EXT}）：
 *       {@code .md} 会被静默跳过——老师传了个 md 讲义，界面上显示"上传成功"，
 *       检索却永远没有它。这里把 md 自动转存为同名 .txt；其它不支持的扩展名直接拒收并说清楚。</li>
 *   <li><b>上传后必须强制重建</b>：索引器在 {@code state == DONE} 时早退，
 *       启动之后新增的文件永远不会被扫到（文件躺在目录里、指纹里没有它、集合里也没有它，
 *       状态却还是 DONE）。所以这里必须调 {@code registry.reindex("school")}。</li>
 * </ol>
 *
 * <p>重建是同步的，所以返回时可以如实报告入库结果（DONE / FAILED），
 * 而不是让老师对着一个"好像成功了"的界面猜。
 */
@Slf4j
@Service
public class SchoolResourceService {

    private static final String DOMAIN = "school";
    private static final String LIB_ID = "school";
    private static final Set<String> ACCEPTED_EXT = Set.of("txt", "pdf", "docx");
    private static final Path TARGET_DIR = Paths.get("data", "school-knowledge", LIB_ID);
    /** 侧车元数据映射：让每个 chunk 带上 classId（检索时按班过滤的源头）。 */
    private static final Path META_MAP = Paths.get("data", "school-knowledge", ".meta-map.json");

    @Resource
    private DesktopFileStore fileStore;
    @Resource
    private DocKnowledgeRegistry docKnowledgeRegistry;
    @Resource
    private EduResourceMapper resourceMapper;

    /** 业务指标埋点（可选，缺失时自动跳过）。 */
    @Resource
    private com.demo.metrics.BusinessMetrics businessMetrics;

    /**
     * 从已上传的临时文件（DesktopFileStore）落进校本知识库。
     *
     * @param classId 上传者归属班级（来自角色门，不是请求参数）
     * @param fileId  由 {@code POST /api/desktop/file/upload} 返回的临时文件 id
     */
    public Map<String, Object> uploadFromStore(String classId, String fileId) {
        Map<String, Object> out = new LinkedHashMap<>();
        DesktopFileStore.FileEntry entry = fileStore.get(fileId);
        if (entry == null) {
            out.put("success", false);
            out.put("error", "临时文件不存在或已过期，请重新上传");
            return out;
        }

        String rawName = safeFileName(entry.fileName());
        if (rawName.isBlank()) {
            out.put("success", false);
            out.put("error", "文件名不合法");
            return out;
        }
        String ext = ext(rawName);
        if ("md".equals(ext)) {
            // md 不在索引器的支持列表里：转成 txt，而不是"上传成功但检索不到"
            String txtName = rawName.substring(0, rawName.length() - 3) + ".txt";
            if (!write(txtName, entry.bytes())) {
                out.put("success", false);
                out.put("error", "写入校本资源目录失败");
                return out;
            }
            log.info("📚 校本资源：{} 是 .md，已自动转存为 {}", rawName, txtName);
            rawName = txtName;
        } else if (!ACCEPTED_EXT.contains(ext)) {
            out.put("success", false);
            out.put("error", "只支持 txt / pdf / docx（当前：" + (ext.isEmpty() ? "无扩展名" : ext)
                    + "）。md 会自动转成 txt，其它格式请先转换");
            return out;
        } else if (!write(rawName, entry.bytes())) {
            out.put("success", false);
            out.put("error", "写入校本资源目录失败");
            return out;
        }

        // ★ 给文件打上 classId（写进侧车映射，索引器会把它写进 chunk 元数据）
        //   这是"按班隔离"的源头：检索端据此用 where 条件过滤，别的班根本召不到这份材料。
        tagClass(LIB_ID + "/" + rawName, classId);

        // ★ 必须强制重建：索引器在 DONE 状态会早退，新文件不会被自动扫到
        boolean reindexed = docKnowledgeRegistry.reindex(DOMAIN);
        DocKnowledgeAssistant assistant = docKnowledgeRegistry.getAssistant(DOMAIN);
        String state = assistant == null ? "NOT_REGISTERED" : assistant.health().get("indexing").toString();

        Integer chunks = null; // 索引器自己回读校验并打印条数，这里不重复猜
        try {
            resourceMapper.insert(classId, rawName, LIB_ID, chunks);
        } catch (Exception e) {
            log.warn("校本资源元数据落库失败（不影响入库）：{}", e.toString());
        }

        boolean ok = reindexed && "DONE".equals(state);
        out.put("success", ok);
        out.put("fileName", rawName);
        out.put("libId", LIB_ID);
        out.put("indexState", state);
        out.put("reindexed", reindexed);
        if (!ok) {
            out.put("error", "文件已保存，但索引未完成（state=" + state
                    + "）。请查看后端日志中 [school] 子库的重建结果，或稍后重试");
        } else if (businessMetrics != null) {
            businessMetrics.recordTeacherKnowledgeUploaded();
        }
        log.info("📚 校本资源上传：{} → 入库状态={}", rawName, state);
        return out;
    }

    /** 该班上传过的资源列表。 */
    public List<Map<String, Object>> list(String classId) {
        try {
            return resourceMapper.listByClass(classId);
        } catch (Exception e) {
            log.warn("校本资源列表查询失败：{}", e.toString());
            return List.of();
        }
    }

    // ======================== 内部工具 ========================

    private static boolean write(String fileName, byte[] bytes) {
        try {
            Files.createDirectories(TARGET_DIR);
            Files.write(TARGET_DIR.resolve(fileName), bytes);
            return true;
        } catch (Exception e) {
            log.warn("写入校本资源失败 {}：{}", fileName, e.toString());
            return false;
        }
    }

    /** 去掉路径成分与非法字符，防目录穿越（{@code ../../x} 这类）。 */
    private static String safeFileName(String name) {
        if (name == null) return "";
        String base = Paths.get(name).getFileName() == null ? name : Paths.get(name).getFileName().toString();
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (base.contains("..")) return "";
        return base;
    }

    private static String ext(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 把「文件相对路径 → classId」写进侧车 {@code data/school-knowledge/.meta-map.json}。
     *
     * <p>为什么用侧车而不是塞进文件名：文件名是给教师看的（`七年级备课提要.txt`），
     * 往里塞机器标识既难看又容易被手工改名破坏；侧车是索引器的通用机制
     * （见 {@code DocKnowledgeIndexer.loadMetaMap}），语义由这里定义。
     *
     * <p>写失败只记 WARN：文件照常入库，只是不参与按班隔离——
     * 这比"上传直接失败"更可取（教师至少能用，且日志里说清了）。
     */
    private static void tagClass(String relativePath, String classId) {
        tagClass(META_MAP, relativePath, classId);
    }

    /**
     * 可测版本：写入指定的侧车文件（包可见，供 {@code LocalKnowledgeIsolateTest} 用临时目录验证）。
     *
     * <p>已存在的键会被<b>覆盖</b>（同一个文件名重新上传到另一个班时，归属跟着变，
     * 而不是同时属于两个班——否则一份材料会因为一次改名永久泄漏给两个班）。
     */
    static void tagClass(Path metaMapFile, String relativePath, String classId) {
        try {
            JSONObject root = new JSONObject();
            if (Files.exists(metaMapFile)) {
                JSONObject existing = JSON.parseObject(Files.readString(metaMapFile));
                if (existing != null) root = existing;
            }
            JSONObject meta = new JSONObject();
            meta.put("classId", classId);
            root.put(relativePath, meta);

            if (metaMapFile.getParent() != null) Files.createDirectories(metaMapFile.getParent());
            Files.writeString(metaMapFile, root.toJSONString());
        } catch (Exception e) {
            log.warn("写入 .meta-map.json 失败（该文件将不参与按班隔离）：{}", e.toString());
        }
    }
}
