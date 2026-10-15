package com.demo.session.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 会话消息图片存储 — AI 生成图片落盘 {@code data/images/}，并提供 URL 生成与读取。
 * <p>
 * 设计：图片以文件形式存磁盘，消息表只存相对 URL（{@code /chat/images/xxx.png}），
 * 避免把 base64 塞进 MySQL 导致数据库膨胀；刷新页面后前端仍可经该 URL 重新加载图片。
 * 与 data/charts、data/traces 同级，相对工作目录。
 */
@Slf4j
@Component
public class SessionImageStore {

    /** 存储根目录（相对工作目录） */
    private static final Path IMAGE_DIR = Path.of("data/images");

    /** 仅允许读取我们生成的 UUID 文件名（32 位 hex 或含 '-' 的 UUID）+ 已知扩展名，防路径穿越 */
    private static final Pattern SAFE_NAME = Pattern.compile("^[0-9a-fA-F-]{32,}\\.(png|jpe?g|gif|bmp)$");

    /**
     * 保存图片字节 → 返回可访问 URL（相对路径）。
     *
     * @param bytes 图片字节
     * @param mime  图片 MIME（image/png、image/jpeg 等），用于确定扩展名
     * @return 相对 URL（如 /chat/images/xxx.png）；保存失败返回 null
     */
    public String save(byte[] bytes, String mime) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            Files.createDirectories(IMAGE_DIR);
            String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extOf(mime);
            Files.write(IMAGE_DIR.resolve(fileName), bytes);
            log.info("🖼️ 会话图片已落盘: data/images/{} ({}KB)", fileName, bytes.length / 1024);
            return "/chat/images/" + fileName;
        } catch (IOException e) {
            log.warn("保存会话图片失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 读取图片字节。文件名必须匹配安全格式，防止目录穿越；文件不存在或非法返回 null。
     */
    public byte[] load(String fileName) {
        if (fileName == null || !SAFE_NAME.matcher(fileName).matches()) {
            log.warn("非法图片文件名: {}", fileName);
            return null;
        }
        try {
            Path path = IMAGE_DIR.resolve(fileName).normalize();
            if (!path.startsWith(IMAGE_DIR)) return null; // 防御：禁止越出目录
            if (!Files.exists(path)) return null;
            return Files.readAllBytes(path);
        } catch (IOException e) {
            log.warn("读取会话图片失败: {}", e.getMessage());
            return null;
        }
    }

    /** 根据文件名扩展名推断 Content-Type */
    public String contentType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        int dot = fileName.lastIndexOf('.');
        String ext = (dot >= 0 ? fileName.substring(dot + 1) : "").toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }

    /** MIME → 文件扩展名（未知默认 png） */
    private String extOf(String mime) {
        if (mime == null) return "png";
        return switch (mime.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            default -> "png";
        };
    }
}
