package com.demo.fileconvert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * 临时文件定时清理 —— 每 30 分钟清理超过过期时间的临时文件。
 * <p>
 * 清理范围为 {@code file.convert.temp-root} 下所有文件（含转换源文件与输出产物），
 * 过期时间由 {@code file.convert.clean-expire-minutes} 配置（默认 60 分钟）。
 */
@Slf4j
@Component
public class ConvertCleanupScheduler {

    private final FileConvertProperties props;

    public ConvertCleanupScheduler(FileConvertProperties props) {
        this.props = props;
    }

    @Scheduled(fixedRate = 30 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void cleanup() {
        String root = props.getTempRoot();
        if (root == null || root.isBlank()) return;
        Path base = Paths.get(root);
        if (!Files.exists(base)) return;

        long expireMinutes = Math.max(1, props.getCleanExpireMinutes());
        long cutoff = System.currentTimeMillis() - expireMinutes * 60_000L;
        int removed = 0;

        try (Stream<Path> walk = Files.walk(base)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        Files.deleteIfExists(p);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // 单个文件清理失败不影响整体
                }
            }
        } catch (IOException e) {
            log.warn("🧹 临时文件清理遍历失败: {}", e.getMessage());
        }

        if (removed > 0) {
            log.info("🧹 转换临时文件清理完成：删除 {} 个过期文件（> {} 分钟）", removed, expireMinutes);
        }
    }
}
