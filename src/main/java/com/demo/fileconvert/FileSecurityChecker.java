package com.demo.fileconvert;

import com.demo.fileconvert.exception.FileFormatNotSupportException;
import com.demo.fileconvert.exception.ForbiddenExecFileException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 接入层安全防护（四层防御的第一层）。
 * <p>
 * 校验链路：后缀解析 → 双重后缀检测 → 可执行黑名单 → MZ 魔数 → 关键词扫描 → 音视频拦截 → 白名单分发。
 * 所有黑名单均为硬编码，不可通过配置修改放行。
 * <p>
 * 仅基于文件名 + 字节数组，不依赖 IO，在最外层拦截恶意文件。
 */
@Slf4j
@Component
public class FileSecurityChecker {

    /** 可执行文件黑名单（硬编码） */
    private static final Set<String> EXEC_BLACKLIST = Set.of(
            "exe", "cmd", "bat", "ps1", "sh", "com", "msc", "reg", "vbs", "msi");

    /** 音视频黑名单（暂不支持转换） */
    private static final Set<String> AUDIO_VIDEO = Set.of(
            "mp4", "avi", "mov", "flv", "mkv", "wmv", "rmvb", "webm",
            "mp3", "wav", "flac", "aac", "wma", "ogg", "amr", "m4a");

    /** 恶意关键词扫描 */
    private static final String[] MALICIOUS_KEYWORDS = {
            "cmd.exe", "powershell", "eval(", "system(", "CreateObject",
            "wscript", "shell.execute", "rundll32"
    };

    /**
     * 安全校验入口。通过后返回规范化扩展名（小写、无点），任一步违规直接抛异常。
     *
     * @param fileName 原始文件名（可为 null）
     * @param bytes    文件字节（可为 null，用于魔数检测）
     * @return 规范化扩展名，如 "pdf"、"xlsx"
     */
    public String check(String fileName, byte[] bytes) {
        String rawName = fileName != null ? fileName.trim() : "";
        String ext = ConvertFormat.normalize(extractExt(rawName));

        // 1. 双重后缀检测：xxx.png.exe 伪装
        if (hasDoubleSuffix(rawName)) {
            throw new ForbiddenExecFileException("安全限制：可执行文件伪装（双重后缀）→ " + rawName);
        }

        // 2. 可执行黑名单
        if (EXEC_BLACKLIST.contains(ext)) {
            throw new ForbiddenExecFileException("安全限制：可执行文件被拦截（." + ext + "）");
        }

        // 3. MZ 魔数：绕过后缀伪装的 PE 文件头
        if (bytes != null && bytes.length >= 2 && (bytes[0] & 0xFF) == 0x4D && (bytes[1] & 0xFF) == 0x5A) {
            throw new ForbiddenExecFileException("安全限制：检测到 PE 可执行文件头（MZ），文件被拦截");
        }

        // 4. 关键词扫描
        String lowerName = rawName.toLowerCase();
        for (String kw : MALICIOUS_KEYWORDS) {
            if (lowerName.contains(kw)) {
                throw new ForbiddenExecFileException("安全限制：文件名含恶意特征关键词 → " + rawName);
            }
        }

        // 5. 音视频拦截（音视频暂不支持转换，归为"格式不支持"）
        if (AUDIO_VIDEO.contains(ext)) {
            throw new FileFormatNotSupportException("音视频文件（." + ext + "）暂不支持转换");
        }

        // 6. 白名单校验
        if (!ConvertFormat.isSupportedInput(ext)) {
            throw new FileFormatNotSupportException("不支持的文件格式：." + (ext.isEmpty() ? "未知" : ext));
        }

        log.debug("🔒 安全校验通过: {} → .{}", rawName.isEmpty() ? "(无名)" : rawName, ext);
        return ext;
    }

    /** 提取文件扩展名（最后一个点之后），无点返回空串 */
    private String extractExt(String fileName) {
        if (fileName == null || fileName.isBlank()) return "";
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) return "";
        return fileName.substring(idx + 1);
    }

    /**
     * 双重后缀检测：文件名结尾是可执行后缀，且其前还包含其他后缀点。
     * 如 "photo.png.exe"（结尾 exe + 前面还有 .png）、"evil.sh"（只有一层后缀 → 交黑名单处理）。
     */
    private boolean hasDoubleSuffix(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        String ext = extractExt(lower);
        if (!EXEC_BLACKLIST.contains(ext)) return false;
        // 去掉最后的后缀后，剩余部分是否还含点（即还有第二层后缀）
        int lastDot = lower.lastIndexOf('.');
        if (lastDot <= 0) return false;
        return lower.substring(0, lastDot).contains(".");
    }
}
