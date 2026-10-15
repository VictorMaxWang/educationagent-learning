package com.demo.service.preprocessing;

import com.demo.service.chart.ChartData;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 文件格式识别服务
 * 基于文件魔数（Magic Number / File Signature）判断文件格式
 * 纯本地运算，无需调用任何外部 API，毫秒级返回
 * 支持常见文档与压缩包格式，并可提取 docx/xlsx/txt 文件内容
 */
@Slf4j
@Service
public class FileService {

    // 内容提取最大字符数（防止文件太大撑爆上下文）
    private static final int MAX_CONTENT_LENGTH = 2000;

    /**
     * 识别文件格式类型
     * @param fileBytes 文件原始二进制字节数组
     * @return 格式描述文本，如"PDF 文档"、"Word 文档"，失败返回 null
     */
    public String analyze(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("文件字节为空，无法识别");
            return null;
        }

        FileType type = detect(fileBytes);
        log.info("📄 文件格式识别完成：{} ({})", type.description, type.mime);

        if ("unknown".equals(type.category)) {
            log.warn("无法识别的文件格式");
            return null;
        }

        return type.description;
    }

    /**
     * 提取文件文字内容（默认最大长度）
     * @param fileBytes 文件原始二进制字节数组
     * @return 提取的文字内容，不支持的格式或失败返回 null
     */
    public String extractContent(byte[] fileBytes) {
        return extractContent(fileBytes, MAX_CONTENT_LENGTH);
    }

    /**
     * 提取文件文字内容（可指定最大长度）
     * <p>刷题解析等场景需要更长正文时使用更大的 maxLength。
     *
     * @param fileBytes 文件原始二进制字节数组
     * @param maxLength 内容截断上限（字符数）
     * @return 提取的文字内容，不支持的格式或失败返回 null
     */
    public String extractContent(byte[] fileBytes, int maxLength) {
        if (fileBytes == null || fileBytes.length == 0) return null;

        try {
            FileType type = detect(fileBytes);

            switch (type.mime) {
                case "text/plain":
                case "text/plain; charset=utf-8":
                case "text/plain; charset=utf-16":
                    return extractFromTxt(fileBytes, maxLength);
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
                    return extractFromZipXml(fileBytes, "word/document.xml", "w:t", maxLength);
                case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
                    return extractFromZipXml(fileBytes, "xl/sharedStrings.xml", "t", maxLength);
                case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
                    return extractFromZipXml(fileBytes, "ppt/slides/slide1.xml", "a:t", maxLength);
                case "application/pdf":
                    return extractFromPdf(fileBytes, maxLength);
                case "application/msword":
                    return extractFromOldOffice(fileBytes, maxLength);
                case "application/zip":
                case "application/x-rar-compressed":
                case "application/x-7z-compressed":
                    return extractFromArchive(fileBytes, type.mime);
                default:
                    log.debug("不支持的内容提取格式：{}", type.mime);
                    return null;
            }
        } catch (Exception e) {
            log.warn("内容提取失败：{}", e.getMessage());
            return null;
        }
    }

    // ========== 内容提取实现 ==========

    /**
     * 提取纯文本文件内容
     */
    private String extractFromTxt(byte[] bytes, int maxLength) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return truncate(text.trim(), maxLength);
    }

    /**
     * 提取 PDF 文件文字内容（使用 PDFBox）
     */
    private String extractFromPdf(byte[] bytes, int maxLength) {
        log.info("📄 开始提取 PDF 内容（PDFBox）");
        try (var doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            return truncate(text.trim(), maxLength);
        } catch (Exception e) {
            log.warn("PDF 内容提取失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取旧版 Office 文档内容（.doc/.xls/.ppt）
     * 通过枚举各个提取器自动匹配格式，不依赖精确魔数区分
     */
    private String extractFromOldOffice(byte[] bytes, int maxLength) {
        // 尝试 Word 提取器
        try (var in = new ByteArrayInputStream(bytes);
             var extractor = new WordExtractor(in)) {
            String text = extractor.getText();
            if (text != null && !text.isBlank()) {
                log.info("📄 旧版 Word 内容提取成功");
                return truncate(text.trim(), maxLength);
            }
        } catch (Exception e) {
            log.debug("不是旧版 Word 格式：{}", e.getMessage());
        }

        // 尝试 Excel 提取器
        try (var wb = new HSSFWorkbook(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                var sheet = wb.getSheetAt(i);
                if (sheet == null) continue;
                for (var row : sheet) {
                    for (var cell : row) {
                        String val = switch (cell.getCellType()) {
                            case STRING -> cell.getStringCellValue();
                            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
                            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                            default -> "";
                        };
                        if (!val.isBlank()) sb.append(val).append(' ');
                    }
                    sb.append('\n');
                }
            }
            if (!sb.isEmpty()) {
                log.info("📄 旧版 Excel 内容提取成功");
                return truncate(sb.toString().trim(), maxLength);
            }
        } catch (Exception e) {
            log.debug("不是旧版 Excel 格式：{}", e.getMessage());
        }

        // 尝试 PPT 提取器（遍历所有幻灯片获取文本）
        try (var in = new ByteArrayInputStream(bytes);
             var slideShow = new org.apache.poi.hslf.usermodel.HSLFSlideShow(in)) {
            var slides = slideShow.getSlides();
            StringBuilder sb = new StringBuilder();
            for (var slide : slides) {
                for (var shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.hslf.usermodel.HSLFTextShape textShape) {
                        String t = textShape.getText();
                        if (t != null && !t.isBlank()) sb.append(t).append('\n');
                    }
                }
            }
            if (!sb.isEmpty()) {
                log.info("📄 旧版 PPT 内容提取成功");
                return truncate(sb.toString().trim(), maxLength);
            }
        } catch (Exception e) {
            log.debug("不是旧版 PPT 格式：{}", e.getMessage());
        }

        return null;
    }

    /**
     * 从 ZIP 包中解压指定 XML 文件，提取指定标签内的文本
     * 用于 docx（word/document.xml → w:t）、xlsx（xl/sharedStrings.xml → t）、pptx（ppt/slides/*.xml → a:t）
     */
    private String extractFromZipXml(byte[] bytes, String xmlPath, String textTag, int maxLength) {
        StringBuilder content = new StringBuilder();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            boolean found = false;

            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(xmlPath)) {
                    found = true;
                    extractTextFromXml(zis, textTag, content);
                    break;
                }
                zis.closeEntry();
            }

            // 如果是 xlsx/pptx，有多个 sheet/slide，遍历所有
            if (!found && xmlPath.contains("slide1")) {
                // pptx：尝试所有 slide
                try (ZipInputStream retry = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    ZipEntry e;
                    while ((e = retry.getNextEntry()) != null) {
                        if (e.getName().startsWith("ppt/slides/slide") && e.getName().endsWith(".xml")) {
                            extractTextFromXml(retry, "a:t", content);
                            content.append("\n");
                        }
                        retry.closeEntry();
                    }
                }
            }

            if (!found && xmlPath.contains("sharedStrings")) {
                // xlsx：尝试遍历工作表提取
                try (ZipInputStream retry = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    ZipEntry e;
                    StringBuilder temp = new StringBuilder();
                    while ((e = retry.getNextEntry()) != null) {
                        if (e.getName().startsWith("xl/worksheets/sheet") && e.getName().endsWith(".xml")) {
                            temp.setLength(0);
                            extractTextFromXml(retry, "t", temp);
                            if (!temp.isEmpty()) {
                                content.append(" [sheet] ").append(temp);
                            }
                        }
                        retry.closeEntry();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("ZIP 解析失败：{}", e.getMessage());
        }

        if (content.isEmpty()) return null;
        return truncate(content.toString().trim(), maxLength);
    }

    /**
     * 提取 ZIP 压缩包内容
     * 优先使用 ZipInputStream 快速解析，
     * 中文编码异常时降级 ZipFile + GBK/UTF-8 重试
     * RAR/7z 暂不支持内容提取
     */
    private String extractFromArchive(byte[] bytes, String mime) {
        if ("application/x-rar-compressed".equals(mime)) {
            return "📦 RAR 压缩包，暂不支持提取内部文件内容";
        }
        if ("application/x-7z-compressed".equals(mime)) {
            return "📦 7Z 压缩包，暂不支持提取内部文件内容";
        }

        try {
            // 优先：ZipInputStream 快速解析（UTF-8 编码）
            String result = tryParseWithZipStream(bytes);
            if (result != null) return result;

            // 降级：中文编码异常 → 临时文件 + ZipFile + GBK/UTF-8 重试
            log.warn("📦 ZipInputStream 解析失败，尝试 GBK/UTF-8 编码降级...");
            return tryParseWithZipFileFallback(bytes);

        } catch (java.util.zip.ZipException e) {
            log.warn("📦 ZIP 格式异常：{}", e.getMessage());
            String msg = e.getMessage();
            if (msg != null && (msg.contains("truncated") || msg.contains("EOF"))) {
                return "📦 ZIP 压缩包不完整：文件可能未传输完成或被截断，请重新发送";
            }
            return "📦 ZIP 压缩包格式异常：文件可能已损坏，请检查后重试";
        } catch (Exception e) {
            log.warn("📦 ZIP 解析异常：{}", e.getMessage());
            return "📦 ZIP 压缩包解析失败：文件损坏或格式不兼容，请重新压缩后发送";
        }
    }

    /** 使用 ZipInputStream（UTF-8）快速解析，成功返回文本，报编码异常返回 null */
    private String tryParseWithZipStream(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        boolean hasContent = false;

        try (var zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                long size = entry.getSize();
                appendEntryInfo(result, name, size);
                hasContent = true;
                // ZipInputStream 模式：读取整个条目字节，再根据扩展名提取内容
                byte[] entryBytes = readZipEntryData(zis);
                if (entryBytes != null && entryBytes.length > 0) {
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";
                    String subContent = extractEntryContent(entryBytes, name, ext);
                    if (subContent != null) {
                        appendFileContent(result, name, subContent);
                    }
                }
                zis.closeEntry();
            }
        } catch (IllegalArgumentException e) {
            // "malformed input" —— 中文编码问题，返回 null 走降级
            return null;
        }

        if (!hasContent) return "📦 ZIP 压缩包（空包或仅包含文件夹）";
        return truncate(result.toString().trim(), MAX_CONTENT_LENGTH * 2);
    }

    /** 编码降级：临时文件 + ZipFile 先 GBK 后 UTF-8 重试 */
    private String tryParseWithZipFileFallback(byte[] bytes) throws Exception {
        Path tempFile = Files.createTempFile("wechat_zip_", ".zip");
        try {
            Files.write(tempFile, bytes);

            // 优先 GBK
            String r = tryParseZipWithCharset(tempFile, Charset.forName("GBK"));
            if (r != null) return r;

            // 降级 UTF-8
            log.warn("📦 GBK 降级失败，尝试 UTF-8...");
            r = tryParseZipWithCharset(tempFile, StandardCharsets.UTF_8);
            if (r != null) return r;

            return "📦 ZIP 压缩包解析失败：文件名编码不兼容，请尝试在电脑上重新压缩后发送";
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
        }
    }

    /** 使用 ZipFile + 指定编码解析，递归提取子文件内容 */
    private String tryParseZipWithCharset(Path zipFile, Charset charset) {
        try (var zf = new ZipFile(zipFile.toFile(), charset)) {
            StringBuilder result = new StringBuilder();
            boolean hasContent = false;
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                long size = entry.getSize();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";
                appendEntryInfo(result, name, size);
                hasContent = true;

                try (InputStream in = zf.getInputStream(entry)) {
                    byte[] entryBytes = readZipEntryData(in);
                    if (entryBytes == null || entryBytes.length == 0) continue;

                    // 根据文件类型提取内容
                    String subContent = extractEntryContent(entryBytes, name, ext);
                    if (subContent != null) {
                        appendFileContent(result, name, subContent);
                    }
                } catch (Exception e) {
                    log.debug("📦 跳过 ZIP 内文件 [{}]：{}", name, e.getMessage());
                }
            }
            if (!hasContent) return "📦 ZIP 压缩包（空包或仅包含文件夹）";
            return truncate(result.toString().trim(), MAX_CONTENT_LENGTH * 2);
        } catch (IllegalArgumentException e) {
            return null;
        } catch (Exception e) {
            log.warn("📦 降级解析异常（{}）：{}", charset.name(), e.getMessage());
            return null;
        }
    }

    /**
     * 根据文件类型提取 ZIP 条目内容
     * 文本 → 直接读；PDF → PDFBox 提取；Office(ZIP) → 递归解析；
     * 图片 → 识别格式+尺寸；其他 → 标记不可提取
     */
    private String extractEntryContent(byte[] entryBytes, String name, String ext) {
        // 文本类：直接 UTF-8 读
        if (isReadableTextFile(name)) {
            String text = new String(entryBytes, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        }

        // PDF：调用已有提取方法
        if (".pdf".equals(ext)) {
            try {
                return extractFromPdf(entryBytes, MAX_CONTENT_LENGTH);
            } catch (Exception e) {
                log.debug("📄 ZIP 内 PDF 提取失败 [{}]：{}", name, e.getMessage());
                return "[PDF 文档，内容提取失败]";
            }
        }

        // Office 文档（docx/xlsx/pptx）：它们本身就是 ZIP，调用已有解析
        if (".docx".equals(ext)) {
            try {
                return extractFromZipXml(entryBytes, "word/document.xml", "w:t", MAX_CONTENT_LENGTH);
            } catch (Exception e) {
                return "[Word 文档，内容提取失败]";
            }
        }
        if (".xlsx".equals(ext)) {
            try {
                // xlsx：先读 sharedStrings，再遍历所有 sheet
                String ss = extractFromZipXml(entryBytes, "xl/sharedStrings.xml", "t", MAX_CONTENT_LENGTH);
                if (ss == null) ss = "";
                String sheets = extractFromZipXml(entryBytes, "xl/worksheets/sheet1.xml", "t", MAX_CONTENT_LENGTH);
                return (ss + " " + sheets).trim();
            } catch (Exception e) {
                return "[Excel 表格，内容提取失败]";
            }
        }
        if (".pptx".equals(ext)) {
            try {
                return extractFromZipXml(entryBytes, "ppt/slides/slide1.xml", "a:t", MAX_CONTENT_LENGTH);
            } catch (Exception e) {
                return "[PPT 演示文稿，内容提取失败]";
            }
        }
        if (".ppt".equals(ext) || ".doc".equals(ext) || ".xls".equals(ext)) {
            try {
                return extractFromOldOffice(entryBytes, MAX_CONTENT_LENGTH);
            } catch (Exception e) {
                return "[旧版 Office 文档，内容提取失败]";
            }
        }

        // 图片：识别格式
        if (isImageFile(ext)) {
            String dims = tryReadImageDimension(entryBytes);
            if (dims != null) {
                return "[图片 " + ext.toUpperCase() + " " + dims + "]";
            }
            return "[图片 " + ext.toUpperCase() + "]";
        }

        return null; // 不支持的格式，不产生内容
    }

    /** 判断是否为图片扩展名 */
    private boolean isImageFile(String ext) {
        return ".jpg".equals(ext) || ".jpeg".equals(ext) || ".png".equals(ext)
                || ".gif".equals(ext) || ".bmp".equals(ext) || ".webp".equals(ext);
    }

    /** 尝试读取图片尺寸（仅常见格式） */
    private String tryReadImageDimension(byte[] bytes) {
        try {
            if (bytes.length < 24) return null;
            // JPEG：0xFF 0xD8 0xFF，尺寸在 0xFF 0xC0 段
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
                for (int i = 2; i < bytes.length - 8; i++) {
                    if ((bytes[i] & 0xFF) == 0xFF && (bytes[i+1] & 0xFF) == 0xC0) {
                        int h = ((bytes[i+5] & 0xFF) << 8) | (bytes[i+6] & 0xFF);
                        int w = ((bytes[i+7] & 0xFF) << 8) | (bytes[i+8] & 0xFF);
                        return w + "×" + h;
                    }
                }
            }
            // PNG：IHDR 头，宽高在字节 16-23
            if ((bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50) {
                int w = ((bytes[16] & 0xFF) << 24) | ((bytes[17] & 0xFF) << 16)
                        | ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF);
                int h = ((bytes[20] & 0xFF) << 24) | ((bytes[21] & 0xFF) << 16)
                        | ((bytes[22] & 0xFF) << 8) | (bytes[23] & 0xFF);
                return w + "×" + h;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 添加 ZIP 条目内容（字符串形式）到结果
     */
    private void appendFileContent(StringBuilder sb, String name, String content) {
        if (content == null || content.isBlank()) return;
        sb.append("  ── ").append(name).append(" 内容 ──\n");
        sb.append(truncate(content, 500)).append("\n\n");
    }

    /** 读取 ZIP 条目字节内容（流式） */
    private byte[] readZipEntryData(InputStream in) {
        try {
            int maxRead = 1024 * 200;
            byte[] buf = new byte[maxRead];
            int total = 0, read;
            while ((read = in.read(buf, total, maxRead - total)) > 0) {
                total += read;
                if (total >= maxRead) break;
            }
            if (total == 0) return null;
            byte[] result = new byte[total];
            System.arraycopy(buf, 0, result, 0, total);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /** 添加文件条目信息到结果 */
    private void appendEntryInfo(StringBuilder sb, String name, long size) {
        String sizeStr = size >= 0 ? formatSize(size) : "?";
        sb.append("  📄 ").append(name).append(" (").append(sizeStr).append(")\n");
    }


    /** 判断文件是否为可读文本文件 */
    private boolean isReadableTextFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".java")
                || lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".ts")
                || lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".html")
                || lower.endsWith(".css") || lower.endsWith(".yml") || lower.endsWith(".yaml")
                || lower.endsWith(".properties") || lower.endsWith(".csv")
                || lower.endsWith(".sh") || lower.endsWith(".bat") || lower.endsWith(".cmd")
                || lower.endsWith(".log") || lower.endsWith(".ini") || lower.endsWith(".cfg")
                || lower.endsWith(".sql") || lower.endsWith(".gradle") || lower.endsWith(".toml");
    }

    /** 文件大小友好格式化 */
    private String formatSize(long bytes) {
        if (bytes <= 0) return "?";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /** 带自定义最大长度的截断 */
    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...（内容过长，已截断）";
    }

    /**
     * 使用 StAX 解析 XML 流，提取指定标签内的文本
     */
    private void extractTextFromXml(InputStream xml, String tagName, StringBuilder out) {
        try {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
            XMLStreamReader reader = factory.createXMLStreamReader(xml, "UTF-8");

            boolean inTarget = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (reader.getLocalName().equals(tagName)) {
                        inTarget = true;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    if (inTarget) {
                        String text = reader.getText().trim();
                        if (!text.isEmpty()) {
                            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                                out.append(' ');
                            }
                            out.append(text);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (reader.getLocalName().equals(tagName)) {
                        inTarget = false;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            log.warn("XML 解析失败：{}", e.getMessage());
        }
    }

    /**
     * 文本截断（使用默认最大长度）
     */
    private static String truncate(String text) {
        return truncate(text, MAX_CONTENT_LENGTH);
    }

    // ========== 文件类型内部结构 ==========

    private record FileType(String mime, String description, String category) {}

    // ========== 魔数检测核心 ==========

    private FileType detect(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return new FileType("application/octet-stream", "未知文件", "unknown");
        }

        // ===== 图片 =====
        if (match(bytes, 0xFF, 0xD8, 0xFF))
            return new FileType("image/jpeg", "JPEG 图片", "image");
        if (match(bytes, 0x89, 0x50, 0x4E, 0x47))
            return new FileType("image/png", "PNG 图片", "image");
        if (match(bytes, 0x47, 0x49, 0x46)) {
            if (bytes[3] == 0x38)
                return new FileType("image/gif", "GIF 动图", "image");
        }
        if (match(bytes, 0x52, 0x49, 0x46, 0x46)) {
            if (bytes.length >= 12 && matchOffset(bytes, 8, 0x57, 0x45, 0x42, 0x50))
                return new FileType("image/webp", "WebP 图片", "image");
        }
        if (match(bytes, 0x42, 0x4D))
            return new FileType("image/bmp", "BMP 位图", "image");
        if (match(bytes, 0x00, 0x00, 0x01, 0x00))
            return new FileType("image/x-icon", "ICO 图标", "image");
        if (match(bytes, 0x66, 0x74, 0x79, 0x70)) {
            if (bytes.length >= 12) {
                String brand = new String(bytes, 4, 8, java.nio.charset.StandardCharsets.US_ASCII);
                if (brand.contains("heic") || brand.contains("heix") || brand.contains("mif1"))
                    return new FileType("image/heic", "HEIC 图片", "image");
            }
        }

        // ===== 音频 =====
        if (match(bytes, 0x23, 0x21, 0x41, 0x4D, 0x52))
            return new FileType("audio/amr", "AMR 语音", "audio");
        if (match(bytes, 0x49, 0x44, 0x33))
            return new FileType("audio/mpeg", "MP3 音频", "audio");
        if (match(bytes, 0xFF, 0xFB) || match(bytes, 0xFF, 0xF3) || match(bytes, 0xFF, 0xF2))
            return new FileType("audio/mpeg", "MP3 音频", "audio");
        if (match(bytes, 0x4F, 0x67, 0x67, 0x53))
            return new FileType("audio/ogg", "OGG 音频", "audio");
        if (match(bytes, 0x66, 0x4C, 0x61, 0x43))
            return new FileType("audio/flac", "FLAC 无损音频", "audio");
        if (match(bytes, 0x52, 0x49, 0x46, 0x46)) {
            if (bytes.length >= 12 && matchOffset(bytes, 8, 0x57, 0x41, 0x56, 0x45))
                return new FileType("audio/wav", "WAV 音频", "audio");
        }

        // ===== 视频 =====
        if (match(bytes, 0x1A, 0x45, 0xDF, 0xA3))
            return new FileType("video/webm", "WebM 视频", "video");
        if (match(bytes, 0x2E, 0x52, 0x4D, 0x46))
            return new FileType("video/rmvb", "RMVB 视频", "video");
        if (match(bytes, 0x46, 0x4C, 0x56, 0x01))
            return new FileType("video/flv", "FLV 视频", "video");

        // ===== 文档 =====
        if (match(bytes, 0x25, 0x50, 0x44, 0x46))
            return new FileType("application/pdf", "PDF 文档", "document");
        if (match(bytes, 0x50, 0x4B, 0x03, 0x04)) {
            boolean isDocx = bytes.length > 30 && containsSubstring(bytes, "word/");
            boolean isXlsx = bytes.length > 30 && containsSubstring(bytes, "xl/");
            boolean isPptx = bytes.length > 30 && containsSubstring(bytes, "ppt/");
            if (isDocx) return new FileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word 文档", "document");
            if (isXlsx) return new FileType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel 表格", "document");
            if (isPptx) return new FileType("application/vnd.openxmlformats-officedocument.presentationml.presentation", "PPT 演示文稿", "document");
            return new FileType("application/zip", "ZIP 压缩包", "archive");
        }
        if (match(bytes, 0x50, 0x4B, 0x05, 0x06))
            return new FileType("application/zip", "ZIP 压缩包", "archive");
        if (match(bytes, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07))
            return new FileType("application/x-rar-compressed", "RAR 压缩包", "archive");
        if (match(bytes, 0x1F, 0x8B))
            return new FileType("application/gzip", "GZIP 压缩包", "archive");
        if (match(bytes, 0x42, 0x5A, 0x68))
            return new FileType("application/x-bzip2", "BZ2 压缩包", "archive");
        if (match(bytes, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C))
            return new FileType("application/x-7z-compressed", "7Z 压缩包", "archive");
        if (match(bytes, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00))
            return new FileType("application/x-xz", "XZ 压缩包", "archive");

        // ===== Office 97-2003 旧版格式 =====
        if (match(bytes, 0xD0, 0xCF, 0x11, 0xE0))
            return new FileType("application/msword", "Office 文档（旧版）", "document");

        // ===== 文本 =====
        if (match(bytes, 0xEF, 0xBB, 0xBF))
            return new FileType("text/plain; charset=utf-8", "UTF-8 文本文件", "document");
        if (match(bytes, 0xFF, 0xFE))
            return new FileType("text/plain; charset=utf-16", "文本文件", "document");
        if (isPlainText(bytes))
            return new FileType("text/plain", "文本文件", "document");

        return new FileType("application/octet-stream", "未知文件类型", "unknown");
    }

    // ========== 魔数匹配工具方法 ==========

    private static boolean match(byte[] bytes, int... magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if ((bytes[i] & 0xFF) != magic[i]) return false;
        }
        return true;
    }

    private static boolean matchOffset(byte[] bytes, int offset, int... magic) {
        if (bytes.length < offset + magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if ((bytes[offset + i] & 0xFF) != magic[i]) return false;
        }
        return true;
    }

    private static boolean isPlainText(byte[] bytes) {
        int checkLen = Math.min(bytes.length, 32);
        for (int i = 0; i < checkLen; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0 || b > 0x7E) return false;
        }
        return true;
    }

    private static boolean containsSubstring(byte[] bytes, String sub) {
        byte[] subBytes = sub.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= bytes.length - subBytes.length; i++) {
            for (int j = 0; j < subBytes.length; j++) {
                if (bytes[i + j] != subBytes[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // ======================== Excel 结构化解析（POI） ========================

    /**
     * 使用 Apache POI 解析 Excel 文件，返回行列结构化数据。
     * .xlsx 用 XSSFWorkbook，.xls 用 HSSFWorkbook，统一返回值格式。
     */
    public List<ChartData> extractExcel(byte[] bytes) {
        List<ChartData> result = new ArrayList<>();
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            if (bytes.length > 4 && bytes[0] == (byte)0x50 && bytes[1] == (byte)0x4B) {
                // .xlsx — ZIP 魔数
                try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(in)) {
                    for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                        var sheet = wb.getSheetAt(i);
                        if (sheet == null) continue;
                        ChartData data = readSheet(sheet.getSheetName(), sheet);
                        if (data != null) result.add(data);
                    }
                }
            } else {
                // .xls — OLE2 魔数
                try (var wb = new HSSFWorkbook(in)) {
                    for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                        var sheet = wb.getSheetAt(i);
                        if (sheet == null) continue;
                        ChartData data = readSheetHSSF(sheet.getSheetName(), sheet);
                        if (data != null) result.add(data);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Excel 结构化解析失败: {}", e.getMessage());
        }
        if (!result.isEmpty()) {
            log.info("📊 Excel 结构化解析完成: {} sheets", result.size());
        }
        return result;
    }

    /** 读取 XSSFSheet（.xlsx） */
    private ChartData readSheet(String name, org.apache.poi.xssf.usermodel.XSSFSheet sheet) {
        List<String> headers = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            var row = sheet.getRow(i);
            if (row == null) continue;
            List<Object> cells = new ArrayList<>();
            for (int j = 0; j < row.getLastCellNum(); j++) {
                var cell = row.getCell(j);
                Object val = readCellValue(cell);
                cells.add(val != null ? val : "");
            }
            if (i == 0) {
                headers.addAll(cells.stream().map(Object::toString).toList());
            } else {
                rows.add(cells);
            }
        }
        return headers.isEmpty() || rows.isEmpty() ? null : new ChartData(name, headers, rows);
    }

    /** 读取 HSSFSheet（.xls） */
    private ChartData readSheetHSSF(String name, org.apache.poi.hssf.usermodel.HSSFSheet sheet) {
        List<String> headers = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();
        for (int i = firstRow; i <= lastRow; i++) {
            var row = sheet.getRow(i);
            if (row == null) continue;
            List<Object> cells = new ArrayList<>();
            for (int j = 0; j < row.getLastCellNum(); j++) {
                var cell = row.getCell(j);
                Object val = readCellValue(cell);
                cells.add(val != null ? val : "");
            }
            if (i == firstRow) {
                headers.addAll(cells.stream().map(Object::toString).toList());
            } else {
                rows.add(cells);
            }
        }
        return headers.isEmpty() || rows.isEmpty() ? null : new ChartData(name, headers, rows);
    }

    /** 统一读取单元格值（支持 String/Number/Boolean/Date/Formula） */
    private Object readCellValue(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield (long) val;
                }
                yield val;
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> {
                try {
                    yield cell.getNumericCellValue();
                } catch (Exception e) {
                    try {
                        yield cell.getStringCellValue();
                    } catch (Exception e2) {
                        yield cell.getCellFormula();
                    }
                }
            }
            default -> "";
        };
    }
}
