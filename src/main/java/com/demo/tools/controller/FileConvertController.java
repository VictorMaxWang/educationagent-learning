package com.demo.tools.controller;
import com.demo.controller.DesktopFileStore;

import com.demo.fileconvert.FileConvertFacade;
import com.demo.fileconvert.exception.ConvertProcessException;
import com.demo.fileconvert.exception.FileFormatNotSupportException;
import com.demo.fileconvert.exception.ForbiddenExecFileException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Map;

/**
 * 文件格式转换接口（桌面端 · 学习栏目子功能）。
 * <p>
 * 流程：前端先走 {@code POST /api/desktop/file/upload} 上传文件拿 fileId，
 * 再调 {@code POST /api/desktop/fileconvert/convert} 携带 fileId + targetFormat，
 * 转换成功后返回 base64 产物，前端触发浏览器下载。
 * <p>
 * 复用 {@link FileConvertFacade}，与微信预检 / Agent 工具共用同一套安全校验、格式规则与转换引擎。
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop/fileconvert")
public class FileConvertController {

    @Resource
    private FileConvertFacade fileConvertFacade;

    @Resource
    private DesktopFileStore fileStore;

    /** 转换请求体 */
    public record ConvertRequest(String userId, String fileId, String targetFormat) {}

    /** 从 fileId 取文件字节 → 转换 → 返回 base64 产物 */
    @PostMapping("/convert")
    public ResponseEntity<?> convert(@RequestBody ConvertRequest req) {
        String targetFormat = req.targetFormat() == null ? "" : req.targetFormat().trim().toLowerCase();
        if (targetFormat.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请指定目标格式，如 pdf、docx、png"));
        }

        DesktopFileStore.FileEntry entry = fileStore.get(req.fileId());
        if (entry == null || entry.bytes() == null || entry.bytes().length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不存在或已过期，请重新上传"));
        }

        try {
            FileConvertFacade.ConvertResult result = fileConvertFacade.convert(
                    entry.bytes(), entry.fileName(), targetFormat);
            // 转换成功后不消费文件：允许同一文件尝试多个目标格式（重新选文件会生成新 fileId）
            log.info("✅ [{}] 桌面端文件转换成功: {} → {} ({}KB)",
                    req.userId() == null ? "desktop" : req.userId(),
                    entry.fileName(), result.fileName(), result.bytes().length / 1024);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "fileName", result.fileName(),
                    "size", result.bytes().length,
                    "base64", Base64.getEncoder().encodeToString(result.bytes())));
        } catch (ForbiddenExecFileException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (FileFormatNotSupportException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (ConvertProcessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("❌ 桌面端文件转换异常", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "转换失败：" + e.getMessage()));
        }
    }
}
