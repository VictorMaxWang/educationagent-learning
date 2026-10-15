package com.demo.fileconvert;

import com.demo.fileconvert.engine.ImageConvertService;
import com.demo.fileconvert.engine.OfficeConvertService;
import com.demo.fileconvert.exception.ConvertProcessException;
import com.demo.fileconvert.exception.FileFormatNotSupportException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 文件转换门面 —— 编排「安全检测 → 格式校验 → 引擎分发 → 转换 → 产物管理」全链路。
 * <p>
 * 微信预检 / Agent 工具 / 桌面聊天流共用此门面，保证各通道行为一致。
 */
@Slf4j
@Service
public class FileConvertFacade {

    private final FileSecurityChecker securityChecker;
    private final OfficeConvertService officeConvertService;
    private final ImageConvertService imageConvertService;

    public FileConvertFacade(FileSecurityChecker securityChecker,
                             OfficeConvertService officeConvertService,
                             ImageConvertService imageConvertService) {
        this.securityChecker = securityChecker;
        this.officeConvertService = officeConvertService;
        this.imageConvertService = imageConvertService;
    }

    /**
     * 执行文件转换
     * @param sourceBytes  源文件字节
     * @param fileName     源文件名（用于扩展名判断与产物命名，可为 null）
     * @param targetFormat 目标格式（扩展名，如 "pdf"）
     * @return 转换结果（字节 + 产物文件名）
     */
    public ConvertResult convert(byte[] sourceBytes, String fileName, String targetFormat) {
        // 1. 接入层安全检测 → 返回规范化源扩展名
        String sourceExt = securityChecker.check(fileName, sourceBytes);

        // 2. 目标格式校验
        String targetExt = ConvertFormat.normalize(targetFormat);
        if (targetExt.isEmpty()) {
            throw new FileFormatNotSupportException("请指定目标格式，如 pdf、docx、txt、jpg、png");
        }
        if (!ConvertFormat.supports(sourceExt, targetExt)) {
            throw new FileFormatNotSupportException("不支持从 ." + sourceExt + " 转为 ." + targetExt
                    + "（" + ConvertFormat.categoryName(sourceExt) + "类格式互转）");
        }

        log.info("🔄 转换开始: {} (.{}) → .{}", fileName, sourceExt, targetExt);

        // 3. 分发到对应引擎
        byte[] resultBytes;
        try {
            if (ConvertFormat.Engine.LIBREOFFICE == ConvertFormat.engineFor(sourceExt)) {
                resultBytes = officeConvertService.convert(sourceBytes, sourceExt, targetExt);
            } else {
                resultBytes = imageConvertService.convert(sourceBytes, sourceExt, targetExt);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ConvertProcessException("转换执行失败：" + e.getMessage(), e);
        }

        // 4. 产物命名（产物字节直接回传调用方，不再落盘供下载）
        String newName = renameTo(targetExt, fileName);

        log.info("✅ 转换完成: {} → {} ({}KB)", fileName, newName, resultBytes.length / 1024);
        return new ConvertResult(resultBytes, newName);
    }

    /** 转换结果 */
    public record ConvertResult(byte[] bytes, String fileName) {}

    /** 将源文件名后缀替换为目标后缀；无扩展名的源文件名直接补后缀 */
    private String renameTo(String targetExt, String fileName) {
        if (fileName == null || fileName.isBlank()) return "converted." + targetExt;
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + "." + targetExt;
    }
}
