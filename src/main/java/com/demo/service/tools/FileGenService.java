package com.demo.service.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 办公文档生成服务 —— 用 POI 生成 docx/xlsx/csv/txt 并保存到 D 盘固定沙箱目录。
 * <p>
 * 产物经 {@code FileGenTool} 写入 {@link com.demo.fileconvert.FileConvertOutputHolder} 后，
 * 由发送层自动发给用户（微信 client.sendFile / 桌面 {file} 事件），本服务不直接发送。
 */
@Slf4j
@Service
public class FileGenService {

    /** 生成目录（固定沙箱，免验证码），可通过 file.gen.dir 覆盖 */
    @Value("${file.gen.dir:D:\\机器人生成文件}")
    private String genDir;

    /** 防止 LLM 生成超大表/超大正文撑爆内存 */
    private static final int MAX_ROWS = 5000;
    private static final int MAX_COLS = 50;
    private static final int MAX_CONTENT_LEN = 100_000;

    /** 支持生成的格式 */
    public enum GenFormat {
        DOCX("docx"), XLSX("xlsx"), CSV("csv"), TXT("txt");
        final String ext;
        GenFormat(String ext) { this.ext = ext; }

        /** 归一化格式名；不合法抛 IllegalArgumentException */
        static GenFormat normalize(String format) {
            if (format == null || format.isBlank()) {
                throw new IllegalArgumentException("请指定文件格式：word/excel/csv/txt");
            }
            String f = format.trim().toLowerCase(Locale.ROOT);
            return switch (f) {
                case "word", "docx", "文档", "word文档", "word文档格式" -> DOCX;
                case "excel", "xlsx", "表格", "excel表格", "电子表格" -> XLSX;
                case "csv" -> CSV;
                case "txt", "text", "文本", "纯文本" -> TXT;
                // 本项目只用 OOXML 引擎，旧格式明确拒绝
                case "doc" -> throw new IllegalArgumentException("暂不支持 .doc，请用 word（.docx）");
                case "xls" -> throw new IllegalArgumentException("暂不支持 .xls，请用 excel（.xlsx）");
                default -> throw new IllegalArgumentException(
                        "不支持的格式：" + format + "，仅支持 word/docx、excel/xlsx、csv、txt");
            };
        }
    }

    /** 表格数据（列名 + 数据行） */
    public record TableData(List<String> columns, List<List<String>> rows) {}

    /** 生成结果：字节 + 文件名 + 保存绝对路径 */
    public record GenResult(byte[] bytes, String fileName, String absolutePath) {}

    /**
     * 生成文档并保存到 D 盘沙箱目录。
     *
     * @param format        格式（word/docx/excel/xlsx/csv/txt 或中文别名）
     * @param title         标题（Word 大标题 / Excel sheet 名），可空
     * @param content       正文（Word/TXT 多段用换行分隔；Excel/CSV 可省略），可空
     * @param tableDataJson 表格数据 JSON（{"columns":[...],"rows":[[...]]}），Excel/CSV 用，Word 可内嵌表格，可空
     * @param fileName      输出文件名（不含扩展名），可空自动生成
     * @return 生成结果
     */
    public GenResult generate(String format, String title, String content,
                              String tableDataJson, String fileName) {
        GenFormat fmt = GenFormat.normalize(format);
        TableData table = parseTableData(tableDataJson);
        byte[] bytes = switch (fmt) {
            case DOCX -> buildDocx(title, content, table);
            case XLSX -> buildXlsx(title, content, table);
            case CSV -> buildCsv(content, table);
            case TXT -> buildTxt(title, content);
        };
        String safeName = sanitizeFileName(fileName, title, fmt.ext);
        Path abs = saveToDisk(bytes, safeName);
        log.info("💾 文档已生成并保存: {} ({}KB)", abs, bytes.length / 1024);
        return new GenResult(bytes, abs.getFileName().toString(), abs.toString());
    }

    // ======================== docx ========================

    private byte[] buildDocx(String title, String content, TableData table) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // ① 标题：居中、加粗、18pt
            if (hasText(title)) {
                XWPFParagraph p = doc.createParagraph();
                p.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun r = p.createRun();
                r.setText(title.trim());
                r.setBold(true);
                r.setFontSize(18);
                setChineseFont(r, "宋体");
                doc.createParagraph(); // 标题下空一段
            }
            // ② 正文：按行拆段
            if (hasText(content)) {
                String body = content.length() > MAX_CONTENT_LEN ? content.substring(0, MAX_CONTENT_LEN) : content;
                for (String line : body.split("\\r?\\n")) {
                    XWPFParagraph p = doc.createParagraph();
                    XWPFRun r = p.createRun();
                    r.setText(line);
                    r.setFontSize(11);
                    setChineseFont(r, "宋体");
                }
            }
            // ③ 可选内嵌表格：表头 + 数据
            if (table != null && !table.columns().isEmpty()) {
                int cols = Math.max(table.columns().size(),
                        table.rows().stream().mapToInt(List::size).max().orElse(0));
                int dataRows = Math.min(table.rows().size(), MAX_ROWS);
                XWPFTable t = doc.createTable(dataRows + 1, cols);
                // 表头加粗：用已有空段落的 createRun()，避免 setText 后再 createRun 产生重复 run
                XWPFTableRow head = t.getRow(0);
                for (int c = 0; c < table.columns().size(); c++) {
                    XWPFRun hr = head.getCell(c).getParagraphs().get(0).createRun();
                    hr.setBold(true);
                    hr.setText(table.columns().get(c));
                }
                for (int r = 0; r < dataRows; r++) {
                    List<String> row = table.rows().get(r);
                    XWPFTableRow tr = t.getRow(r + 1);
                    for (int c = 0; c < cols; c++) {
                        tr.getCell(c).setText(c < row.size() ? row.get(c) : "");
                    }
                }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Word 文档生成失败：" + e.getMessage(), e);
        }
    }

    /** 同时设置西文与中文字体（中文字体必须走 eastAsia，否则 Word 显示默认字体） */
    private void setChineseFont(XWPFRun run, String font) {
        run.setFontFamily(font);                                 // ascii + hAnsi
        run.setFontFamily(font, XWPFRun.FontCharRange.eastAsia); // 中文
    }

    // ======================== xlsx ========================

    private byte[] buildXlsx(String title, String content, TableData table) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String sheetName = sanitizeSheetName(hasText(title) ? title : "Sheet1");
            XSSFSheet sheet = wb.createSheet(sheetName);

            // 表头样式：加粗 + 灰底
            XSSFCellStyle headStyle = wb.createCellStyle();
            XSSFFont headFont = wb.createFont();
            headFont.setBold(true);
            headStyle.setFont(headFont);
            headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // 组装数据：有表格用表格（首行=表头），否则正文逐行放 A 列
            List<List<String>> data = new ArrayList<>();
            if (table != null && !table.columns().isEmpty()) {
                data.add(table.columns());
                data.addAll(table.rows());
            } else if (hasText(content)) {
                String body = content.length() > MAX_CONTENT_LEN ? content.substring(0, MAX_CONTENT_LEN) : content;
                for (String line : body.split("\\r?\\n")) data.add(List.of(line));
            }
            if (data.isEmpty()) data.add(List.of("")); // 空表占位
            int maxCols = Math.min(MAX_COLS, data.stream().mapToInt(List::size).max().orElse(1));
            int dataRows = Math.min(data.size(), MAX_ROWS + 1);

            for (int i = 0; i < dataRows; i++) {
                XSSFRow row = sheet.createRow(i);
                List<String> vals = data.get(i);
                for (int c = 0; c < maxCols; c++) {
                    XSSFCell cell = row.createCell(c);
                    cell.setCellValue(c < vals.size() ? vals.get(c) : ""); // 统一字符串，避免数字/本地化差异
                    if (i == 0) cell.setCellStyle(headStyle);
                }
            }
            // 列宽：手工估算（headless 服务器禁用 autoSizeColumn，无 AWT 字体度量）
            for (int c = 0; c < maxCols; c++) {
                int w = 8;
                for (int i = 0; i < dataRows; i++) {
                    String v = c < data.get(i).size() ? data.get(i).get(c) : "";
                    w = Math.max(w, v.length());
                }
                sheet.setColumnWidth(c, Math.min(255, w + 2) * 256); // Excel 列宽上限 255 字符
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Excel 生成失败：" + e.getMessage(), e);
        }
    }

    /** sheet 名：去非法字符 \/?*[]: 且 ≤31 字符 */
    private String sanitizeSheetName(String name) {
        String n = name.replaceAll("[\\\\/*?\\[\\]:]", "_");
        if (n.length() > 31) n = n.substring(0, 31);
        return n;
    }

    // ======================== csv ========================

    private byte[] buildCsv(String content, TableData table) {
        StringBuilder sb = new StringBuilder();
        if (table != null && !table.columns().isEmpty()) {
            sb.append(csvLine(table.columns()));
            for (List<String> row : table.rows()) {
                sb.append(csvLine(row));
            }
        } else if (hasText(content)) {
            String body = content.length() > MAX_CONTENT_LEN ? content.substring(0, MAX_CONTENT_LEN) : content;
            for (String line : body.split("\\r?\\n")) sb.append(csvLine(List.of(line)));
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        // UTF-8 BOM 前置，否则 Excel 打开中文按 GBK 猜编码乱码
        ByteBuffer buf = ByteBuffer.allocate(3 + body.length);
        buf.put((byte) 0xEF).put((byte) 0xBB).put((byte) 0xBF).put(body);
        return buf.array();
    }

    private String csvLine(List<String> cells) {
        return cells.stream().map(c -> {
            if (c == null) c = "";
            return (c.indexOf(',') >= 0 || c.indexOf('"') >= 0 || c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0)
                    ? "\"" + c.replace("\"", "\"\"") + "\""
                    : c;
        }).collect(Collectors.joining(",")) + "\r\n"; // Excel 兼容用 CRLF
    }

    // ======================== txt ========================

    private byte[] buildTxt(String title, String content) {
        StringBuilder sb = new StringBuilder();
        if (hasText(title)) sb.append(title.trim()).append("\n\n");
        if (hasText(content)) {
            sb.append(content.length() > MAX_CONTENT_LEN ? content.substring(0, MAX_CONTENT_LEN) : content);
        }
        if (sb.isEmpty()) sb.append("（空文档）");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ======================== 表格数据 JSON 解析 ========================

    private TableData parseTableData(String json) {
        if (json == null || json.isBlank()) return null;
        String cleaned = json.trim();
        // 容忍 LLM 输出的 markdown 代码围栏
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        try {
            JSONObject obj = JSON.parseObject(cleaned);
            if (obj == null) throw new IllegalArgumentException("不是合法的 JSON 对象");
            List<String> columns = obj.getJSONArray("columns") == null
                    ? new ArrayList<>() : obj.getJSONArray("columns").toJavaList(String.class);
            List<List<String>> rows = new ArrayList<>();
            JSONArray arr = obj.getJSONArray("rows");
            if (arr != null) {
                for (int i = 0; i < Math.min(arr.size(), MAX_ROWS); i++) {
                    rows.add(arr.getJSONArray(i).toJavaList(String.class)); // 每行必须是 JSON 数组
                }
            }
            if (columns.isEmpty() && rows.isEmpty()) return null;
            // 只有行没列名 → 自动补「列1」「列2」…
            if (columns.isEmpty()) {
                int maxCols = rows.stream().mapToInt(List::size).max().orElse(0);
                for (int i = 1; i <= maxCols; i++) columns.add("列" + i);
            }
            return new TableData(columns, rows);
        } catch (Exception e) {
            log.warn("表格数据解析失败: {}", json, e);
            throw new IllegalArgumentException(
                    "表格数据解析失败，请按 {\"columns\":[\"列1\",\"列2\"],\"rows\":[[\"值1\",\"值2\"]]} 格式提供：" + e.getMessage());
        }
    }

    // ======================== 文件名净化 + 落盘 ========================

    private String sanitizeFileName(String fileName, String title, String ext) {
        String base = (hasText(fileName) ? fileName : hasText(title) ? title : "生成文件")
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .trim();
        if (base.isBlank()) base = "生成文件";
        if (base.length() > 80) base = base.substring(0, 80);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot); // 去掉可能带的扩展名，统一用格式决定的扩展名
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return base + "_" + ts + "." + ext; // 追加时间戳避免重名覆盖
    }

    private Path saveToDisk(byte[] bytes, String fileName) {
        try {
            Path dir = Path.of(genDir);
            Files.createDirectories(dir); // 幂等创建
            Path file = dir.resolve(fileName);
            Files.write(file, bytes);
            return file.toAbsolutePath();
        } catch (IOException e) {
            throw new IllegalArgumentException("文件保存失败：" + e.getMessage(), e);
        }
    }

    /**
     * 在资源管理器中打开保存目录并高亮指定文件（Windows），让用户决定保留或移动到其他位置。
     * 非阻塞（不等待 explorer 进程退出）；失败仅记日志，不影响主流程。
     */
    public void revealInExplorer(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) return;
        try {
            new ProcessBuilder("explorer.exe", "/select,", absolutePath).start();
            log.info("📂 已在资源管理器中打开并高亮文件: {}", absolutePath);
        } catch (Exception e) {
            log.warn("打开文件夹失败: {}", e.getMessage());
        }
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
