package com.demo.fileconvert;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 临时文件管理器 —— UUID 命名 + 日期分目录 + 安全删除 + 路径越界防护。
 * <p>
 * 目录结构：
 * <pre>
 * {tempRoot}/
 *   ├── office/{yyyy-MM-dd}/{uuid}.{ext}    ← 文档转换源文件/输出目录
 *   └── image/{yyyy-MM-dd}/{uuid}.{ext}     ← 图片转换源文件/输出目录
 * </pre>
 */
@Slf4j
@Component
public class TempFileManager {

    @Resource
    private FileConvertProperties props;

    /**
     * 写入源文件临时文件（UUID 命名 + 日期分目录）。
     *
     * @param category 分类目录名，如 "office" / "image"
     * @param ext      文件扩展名（不含点）
     * @param bytes    文件内容
     * @return 写入后的文件路径
     */
    public Path writeTempFile(String category, String ext, byte[] bytes) throws IOException {
        Path dir = categoryDir(category);
        Files.createDirectories(dir);
        Path file = dir.resolve(uuidName(ext));
        Files.write(file, bytes);
        return file;
    }

    /** 生成一个转换输出目录（同样按日期分目录），并确保存在 */
    public Path outputDir(String category) throws IOException {
        Path dir = categoryDir(category);
        Files.createDirectories(dir);
        return dir;
    }

    /** 安全删除文件（存在才删，失败仅告警不抛出） */
    public void safeDelete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("临时文件删除失败: {} ({})", path, e.getMessage());
        }
    }

    /** 分类目录：{tempRoot}/{category}/{yyyy-MM-dd} */
    private Path categoryDir(String category) {
        return Paths.get(props.getTempRoot(), category, LocalDate.now().toString());
    }

    /** UUID 短命名 + 扩展名 */
    private String uuidName(String ext) {
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return uuid + "." + ConvertFormat.normalize(ext);
    }
}
