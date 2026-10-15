package com.demo.service.tools.holder;

import com.demo.service.chart.ChartData;
import com.demo.service.preprocessing.FileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户文件临时缓存。
 * <p>
 * 微信文件消息在 MessagePreprocessor 中下载、分析后缓存于此。
 * LLM 决定调 saveToMyKnowledge 时取出使用。
 * <p>
 * 改动：不再 getAndClear，改为显式 remove 删除。
 * get 只读不删，支持跨轮次多次尝试。
 * 超过 5 分钟的旧文件自动过期。
 */
@Slf4j
@Component
public class UserFileHolder {

    /** 文件过期时间（毫秒） */
    private static final long FILE_TTL_MS = 5 * 60 * 1000;

    @Resource
    private FileService fileService;

    /** userId → FileEntry */
    private final ConcurrentHashMap<String, FileEntry> files = new ConcurrentHashMap<>();

    /**
     * 缓存用户最新上传的文件。覆盖旧条目。
     */
    public void put(String userId, byte[] bytes, String fileName) {
        if (bytes == null || userId == null) return;

        String textContent = null;
        List<ChartData> sheetData = null;
        try {
            textContent = fileService.extractContent(bytes);
            // Excel 文件额外解析结构化数据
            if (isExcelFile(fileName)) {
                sheetData = fileService.extractExcel(bytes);
            }
        } catch (Exception e) {
            log.warn("[{}] 文件内容提取失败（不影响保存）: {}", userId, e.getMessage());
        }

        files.put(userId, new FileEntry(bytes, fileName != null ? fileName : "unknown.bin",
                textContent, System.currentTimeMillis(), sheetData));
        log.info("📁 [{}] 文件已缓存: {} ({} bytes, 5min 过期)",
                userId, fileName, bytes.length);
    }

    private boolean isExcelFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    /**
     * 读取文件缓存（不删除，支持跨轮次重试）。
     * 如果文件已过期返回 null。
     */
    public FileEntry get(String userId) {
        if (userId == null) return null;
        FileEntry entry = files.get(userId);
        if (entry == null) return null;

        // 过期检查
        if (System.currentTimeMillis() - entry.timestamp() > FILE_TTL_MS) {
            files.remove(userId);
            log.info("📁 [{}] 文件缓存已过期: {}", userId, entry.fileName());
            return null;
        }

        return entry;
    }

    /**
     * 确认文件已成功处理，从缓存中移除。
     * 仅在 saveAndIndex 成功后调用。
     */
    public void remove(String userId) {
        if (userId == null) return;
        FileEntry entry = files.remove(userId);
        if (entry != null) {
            log.info("📁 [{}] 文件已消费并从缓存移除: {}", userId, entry.fileName());
        }
    }

    /** 检查是否有未过期的待处理文件 */
    public boolean hasPending(String userId) {
        return get(userId) != null;
    }

    /** 清空指定用户的缓存（用于重置） */
    public void clear(String userId) {
        if (userId != null) files.remove(userId);
    }

    public record FileEntry(byte[] bytes, String fileName, String textContent, long timestamp, List<ChartData> sheetData) {}
}
