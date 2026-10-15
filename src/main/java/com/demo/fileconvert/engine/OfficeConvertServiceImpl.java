package com.demo.fileconvert.engine;

import com.demo.fileconvert.FileConvertProperties;
import com.demo.fileconvert.TempFileManager;
import com.demo.fileconvert.exception.ConvertProcessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * LibreOffice 文档转换实现。
 * <p>
 * 安全参数：--headless --norestore --nolockcheck --nologo。
 * 优先使用真实二进制 soffice.bin（Git Bash 下 soffice.exe 包装器会挂起，ProcessBuilder 下以 .bin 最稳）。
 * 所有参数走 List，无 shell 注入面；超时强制销毁由 {@link CommandRunner} 统一处理。
 */
@Slf4j
@Service
public class OfficeConvertServiceImpl implements OfficeConvertService {

    @Resource
    private FileConvertProperties props;

    @Resource
    private TempFileManager tempFileManager;

    @Override
    public byte[] convert(byte[] sourceBytes, String sourceExt, String targetExt) throws Exception {
        FileConvertProperties.LibreOffice lo = props.getLibreoffice();
        String soffice = resolveSoffice(lo);
        if (soffice == null) {
            throw new ConvertProcessException("未找到 LibreOffice（soffice），请检查 file.convert.libreoffice.path 配置");
        }

        Path src = tempFileManager.writeTempFile("office", sourceExt, sourceBytes);
        Path outDir = tempFileManager.outputDir("office");
        try {
            // md 目标：LibreOffice 无 md 过滤器，用 txt 过滤器产出纯文本后改后缀（符合文档"md 转出为纯文本"的说明）
            String filter = "md".equals(targetExt) ? "txt" : targetExt;
            // txt 导出：强制 UTF-8 编码。LibreOffice 默认按系统编码（中文 Windows=GBK）输出，
            // 导出的 .md/.txt 按 UTF-8 打开会是乱码。outputExt 仍是 "txt"，findOutput 按扩展名匹配。
            String convertTo = "txt".equals(filter) ? "txt:Text (encoded):UTF8" : filter;
            List<String> cmd = new ArrayList<>(List.of(
                    soffice,
                    "--headless", "--norestore", "--nolockcheck", "--nologo"));
            // PDF 源：LibreOffice 默认把 PDF 当成 Draw 绘图文档打开，Draw 没有 docx/txt/rtf 等导出过滤器，
            // 导致 PDF→Word 等转换报 "no export filter"。强制用 writer_pdf_import 让 PDF 以 Writer 文档打开，才能导出。
            if ("pdf".equalsIgnoreCase(sourceExt)) {
                cmd.add("--infilter=writer_pdf_import");
            }
            cmd.addAll(List.of(
                    "--convert-to", convertTo,
                    "--outdir", outDir.toString(),
                    src.toString()));
            log.info("🔄 LibreOffice 转换: {} → {} | {}", sourceExt, targetExt, soffice);
            runWithRetry(cmd, lo.getTimeout());

            Path out = findOutput(outDir, src.getFileName().toString(), filter);
            if (out == null) {
                throw new ConvertProcessException("LibreOffice 未产出转换文件（" + targetExt + "）");
            }
            byte[] result = Files.readAllBytes(out);
            tempFileManager.safeDelete(out);
            return result;
        } finally {
            if (props.isCleanTemp()) {
                tempFileManager.safeDelete(src);
            }
        }
    }

    /**
     * 执行 LibreOffice 转换，首次失败自动重试一次。
     * 首次失败通常由用户 profile 初始化/锁竞争引起（冷启动、与 GUI 实例并发），
     * 短暂等待后重跑一次即可成功。
     */
    private void runWithRetry(List<String> cmd, long timeoutMs) {
        try {
            CommandRunner.run(cmd, timeoutMs, "LibreOffice");
        } catch (ConvertProcessException first) {
            log.warn("🔄 LibreOffice 首次转换失败，1.5s 后重试一次: {}", first.getMessage());
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw first;
            }
            CommandRunner.run(cmd, timeoutMs, "LibreOffice");
        }
    }

    /**
     * 解析 soffice 可执行文件路径：
     * 优先 {path}/program/soffice.bin（真实二进制），其次 soffice.exe，最后退回 PATH 中的 cmd。
     */
    private String resolveSoffice(FileConvertProperties.LibreOffice lo) {
        String base = lo.getPath();
        if (base != null && !base.isBlank()) {
            Path bin = Paths.get(base, "program", "soffice.bin");
            if (Files.exists(bin)) return bin.toString();
            Path exe = Paths.get(base, "program", "soffice.exe");
            if (Files.exists(exe)) return exe.toString();
        }
        return lo.getCmd();
    }

    /**
     * 在输出目录中查找转换产物：文件名必须精确等于 {源basename}.{输出扩展名}。
     * 源文件与输出在同一目录（都是 {uuid}.{ext}），若用 startsWith(base+".") 会把源文件也匹配进去，
     * 导致读回源字节当作"转换产物"（转出的 .pdf 打开却是 .docx）。必须精确匹配输出扩展名。
     * md→txt 时 filterExt="txt"（LibreOffice 用 txt 过滤器，产物后缀是 .txt，随后 facade 再改名 .md）。
     */
    private Path findOutput(Path outDir, String srcName, String filterExt) throws Exception {
        String base = srcName.substring(0, srcName.lastIndexOf('.'));
        String expected = base + "." + filterExt;
        try (Stream<Path> stream = Files.list(outDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(expected))
                    .findFirst()
                    .orElse(null);
        }
    }
}
