package com.demo.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.service.tools.BaiduOcrService;
import com.demo.service.tools.InvoiceVerifyService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 桌面端票据 OCR 接口。
 * <p>
 * 前端上传图片 Data URL（base64）→ 后端调百度智能云通用票据识别 → 返回结构化票面字段。
 * 密钥只存后端（{@link BaiduOcrService}），前端不接触任何百度凭证。
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop")
public class DesktopOcrController {

    @Resource
    private BaiduOcrService baiduOcrService;

    @Resource
    private InvoiceVerifyService invoiceVerifyService;

    /** 导出目录（机器人生成文件目录，file.gen.dir 配置，默认 D:\机器人生成文件） */
    @Value("${file.gen.dir:D:\\机器人生成文件}")
    private String genDir;

    /**
     * 识别票据（自动识别票种）。
     * body: {@code {"image": "data:image/png;base64,xxxxx", "type": "auto"}}
     * type: auto（默认，先试数电票接口，无结构化字段再回退通用票据）| invoice | electronic
     * 返回: {@code {"code":200,"msg":"识别成功","type":"electronic|invoice","data":<百度原始JSON>}}
     */
    @PostMapping("/ocr/invoice")
    public Map<String, Object> ocrInvoice(@RequestBody(required = false) Map<String, String> body) {
        String image = body != null ? body.getOrDefault("image", "") : "";
        String type = body != null ? body.getOrDefault("type", "auto") : "auto";
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            byte[] imageBytes = decodeDataUrl(image);
            if (imageBytes == null || imageBytes.length == 0) {
                result.put("code", 400);
                result.put("msg", "图片数据为空");
                return result;
            }

            // PDF：百度 OCR 对 PDF 输入只认首页 → 本地逐页渲染成图片，每页识别后合并
            if (isPdf(imageBytes)) {
                return ocrPdf(imageBytes, type);
            }

            result.putAll(ocrImage(imageBytes, type));
            return result;
        } catch (Exception e) {
            log.error("🧾 票据识别失败", e);
            result.put("code", 500);
            result.put("msg", "票据识别失败：" + e.getMessage());
            return result;
        }
    }

    /** 单张图片识别：非显式通用票据先试数电票接口，无结构化字段再回退通用票据 */
    private Map<String, Object> ocrImage(byte[] imageBytes, String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        String ocrJson = null;
        String usedType = "invoice";

        // 非显式通用票据 → 先试数电票接口（现代发票多数为全电发票）
        if (!"invoice".equals(type)) {
            String eJson = baiduOcrService.recognizeElectronicInvoice(imageBytes);
            JSONObject eObj = JSONObject.parseObject(eJson);
            if (hasUsableResult(eObj)) {
                ocrJson = eJson;
                usedType = "electronic";
            } else if ("electronic".equals(type)) {
                // 明确指定数电票接口：即使无结构化字段也原样返回，方便排查
                ocrJson = eJson;
                usedType = "electronic";
            }
            // auto 且数电票接口无结果 → 回退通用票据
        }

        // 回退 / 显式通用票据
        if (ocrJson == null) {
            ocrJson = baiduOcrService.recognizeInvoice(imageBytes);
            usedType = "invoice";
        }

        JSONObject data = JSONObject.parseObject(ocrJson);
        // 数电票接口可能把日期读成"年月日"标签 → 通用OCR兜底提取真实日期
        ensureInvoiceDate(data, imageBytes);

        result.put("code", 200);
        result.put("msg", "识别成功");
        result.put("type", usedType);
        result.put("data", data);
        return result;
    }

    /**
     * 多页 PDF 识别：逐页渲染成图片 → 每页走 {@link #ocrImage} → 合并 words_result。
     * 解决百度 OCR 对 PDF 输入只认首页、多页发票后半段字段（税号/大写金额/备注）丢失的问题。
     */
    private Map<String, Object> ocrPdf(byte[] pdfBytes, String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<byte[]> pages = renderPdfPages(pdfBytes);
            if (pages.isEmpty()) {
                result.put("code", 500);
                result.put("msg", "PDF 无法渲染页面");
                return result;
            }

            JSONObject merged = new JSONObject();
            JSONObject mergedWords = new JSONObject();
            String usedType = "invoice";
            int okPages = 0;
            byte[] firstPage = null;

            for (byte[] pageImg : pages) {
                Map<String, Object> page = ocrImage(pageImg, type);
                if (page != null && page.get("code") instanceof Integer ci && ci == 200
                        && page.get("data") instanceof JSONObject d) {
                    JSONObject words = d.getJSONObject("words_result");
                    if (words != null) mergedWords.putAll(words);
                    usedType = String.valueOf(page.get("type"));
                    if (firstPage == null) firstPage = pageImg;
                    okPages++;
                }
            }

            if (okPages == 0) {
                result.put("code", 500);
                result.put("msg", "PDF 识别失败，未解析到票面字段");
                return result;
            }

            merged.put("words_result", mergedWords);
            if (firstPage != null) ensureInvoiceDate(merged, firstPage);

            result.put("code", 200);
            result.put("msg", "识别成功");
            result.put("type", usedType);
            result.put("data", merged);
            return result;
        } catch (Exception e) {
            log.error("🧾 PDF 票据识别失败", e);
            result.put("code", 500);
            result.put("msg", "PDF 票据识别失败：" + e.getMessage());
            return result;
        }
    }

    /** 用 PDFBox 把 PDF 逐页渲染成 PNG（200 DPI，保证清晰度） */
    private List<byte[]> renderPdfPages(byte[] pdfBytes) throws IOException {
        List<byte[]> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int n = doc.getNumberOfPages();
            for (int i = 0; i < n; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, 200);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                pages.add(out.toByteArray());
                img.flush();
            }
        }
        return pages;
    }

    /** 魔数检测：文件头是否为 %PDF */
    private static boolean isPdf(byte[] bytes) {
        if (bytes == null || bytes.length < 5) return false;
        return bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    /**
     * 票据核验。
     * body: {@code {"params": {"invoice_no": "...", "invoice_code": "...", "invoice_date": "...", "check_code": "...", "amount": "..."}}}
     * 后端转发第三方查验接口，返回核验结果。
     */
    @PostMapping("/ocr/verify")
    public Map<String, Object> verifyInvoice(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body != null && body.get("params") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() != null) {
                    params.put(k, String.valueOf(e.getValue()));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();

        // ========== 数电票兼容（翔云接口对普票强制校验码，数电票无校验码区块）==========
        String invNo = params.getOrDefault("invoiceNumber", "");
        boolean isDianShuPiao = invNo.length() == 20; // 数电票=20位发票号码

        // 发票代码：数电票无代码，传空字符串（避免"-"占位触发长度校验）
        String invoiceCode = params.getOrDefault("invoiceCode", "");
        if (isDianShuPiao || "-".equals(invoiceCode)) {
            params.put("invoiceCode", "");
        }

        // 校验码：数电票无校验码 → 用发票号码后6位（网OCR文档：全电发票号码后6位）；传统普票缺失 → 提示手动补充
        String checkCode = params.getOrDefault("checkCode", "");
        if (isDianShuPiao && (checkCode.isBlank() || "-".equals(checkCode))) {
            params.put("checkCode", invNo.length() >= 6 ? invNo.substring(invNo.length() - 6) : "000000");
        } else if (!isDianShuPiao && (checkCode.isBlank() || "-".equals(checkCode))) {
            result.put("code", -30008);
            result.put("msg", "普通发票校验码识别失败，请手动补充后重试");
            return result;
        }

        try {
            String json = invoiceVerifyService.verify(params);
            JSONObject data = JSONObject.parseObject(json);
            result.put("code", 200);
            result.put("msg", "核验完成");
            result.put("data", data);
            return result;
        } catch (Exception e) {
            log.error("🔍 票据核验失败", e);
            result.put("code", 500);
            result.put("msg", "核验失败：" + e.getMessage());
            return result;
        }
    }

    /**
     * 数电票日期兜底：电子发票接口常把日期字段读成"年月日"标签（发票上其实有日期）。
     * 此时调通用文字识别拿整图文字，正则提取真实日期（如 2026年07月15日）回填。
     */
    private void ensureInvoiceDate(JSONObject data, byte[] imageBytes) {
        if (data == null || imageBytes == null) return;
        JSONObject words = data.getJSONObject("words_result");
        if (words == null) return;
        // 已有有效日期（非"年月日"标签）则不处理
        for (String key : new String[]{"InvoiceDate", "开票日期", "invoice_date"}) {
            String s = valueStr(words.get(key));
            if (s != null && !s.isBlank() && !s.contains("年月日")) return;
        }
        try {
            String generalJson = baiduOcrService.recognizeGeneral(imageBytes);
            JSONObject g = JSONObject.parseObject(generalJson);
            JSONArray arr = g.getJSONArray("words_result");
            if (arr == null) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                if (item != null && item.getString("words") != null) {
                    sb.append(item.getString("words")).append("\n");
                }
            }
            Matcher m = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日").matcher(sb.toString());
            if (m.find()) {
                String iso = m.group(1) + "-" + pad2(m.group(2)) + "-" + pad2(m.group(3));
                words.put("InvoiceDate", iso);
                log.info("📅 通用OCR兜底提取开票日期: {}", iso);
            } else {
                log.warn("📅 通用OCR未匹配到日期格式");
            }
        } catch (Exception e) {
            log.warn("📅 通用OCR提取日期失败: {}", e.getMessage());
        }
    }

    /** 读取字段值：兼容字符串和 {word:"xxx"} 对象 */
    private static String valueStr(Object v) {
        if (v == null) return null;
        if (v instanceof Map<?, ?> m) return String.valueOf(m.get("word"));
        return String.valueOf(v);
    }

    private static String pad2(String s) {
        return s.length() < 2 ? "0" + s : s;
    }

    /** 判断百度返回是否含可用的结构化字段（无 error_code 且 words_result 非空） */
    private static boolean hasUsableResult(JSONObject json) {
        if (json == null) return false;
        if (json.containsKey("error_code")) return false;
        JSONObject words = json.getJSONObject("words_result");
        return words != null && !words.isEmpty();
    }

    /**
     * 导出发票识别记录为 Excel(.xlsx)。
     * body: {@code {"invoices": [ {发票字段..., "_type":"electronic", "_time":"2026-07-31 12:00"}, ... ]}}
     * 返回 .xlsx 二进制文件流（前端触发浏览器下载）。
     */
    @PostMapping("/ocr/export-excel")
    public Map<String, Object> exportExcel(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> invoices = new ArrayList<>();
        if (body != null && body.get("invoices") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    invoices.add(castToStrMap(m));
                }
            }
        }

        // 兜底去重：按发票号码去重，同一张发票只保留一条（无号码的记录不参与去重，直接保留）
        List<Map<String, Object>> deduped = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> inv : invoices) {
            String num = getStr(inv, "发票号码", "发票编号", "InvoiceNum", "InvoiceCode", "invoice_num");
            if ("-".equals(num) || seen.add(num)) {
                deduped.add(inv);
            }
        }
        log.info("🧾 导出Excel：收到 {} 条，去重后 {} 条，首条={}", invoices.size(), deduped.size(),
                deduped.isEmpty() ? "无" : deduped.get(0));

        try {
            byte[] xlsx = buildInvoiceExcel(deduped);

            // 写入机器人生成文件目录（file.gen.dir，默认 D:\机器人生成文件）
            String fileName = "发票识别记录_" + timestampNow() + ".xlsx";
            Path dir = Paths.get(genDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.write(file, xlsx);

            result.put("code", 200);
            result.put("msg", "导出成功");
            result.put("fileName", fileName);
            result.put("filePath", file.toAbsolutePath().toString());
            log.info("🧾 发票记录已导出：{}", file.toAbsolutePath());
            return result;
        } catch (Exception e) {
            log.error("🧾 导出 Excel 失败", e);
            result.put("code", 500);
            result.put("msg", "导出失败：" + e.getMessage());
            return result;
        }
    }

    /** 导出文件名时间戳：yyyy-MM-dd_HH-mm-ss */
    private static String timestampNow() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
    }

    /** 已映射到固定列的字段名集合（用于识别剩余字段并入"其他字段"列） */
    private static final java.util.Set<String> MAPPED_FIELDS = java.util.Set.of(
            "_type", "_time",
            "发票号码", "发票编号", "InvoiceNum", "InvoiceCode", "invoice_num",
            "开票日期", "InvoiceDate", "invoice_date",
            "价税合计", "价税合计(小写)", "合计金额",
            "AmountInFiguers", "AmountInFigures", "AmountInWords", "amount_in_figures",
            "销售方名称", "销售方", "IndustrySort", "SellerName", "seller_name",
            "销售方识别号", "销售方统一社会信用代码", "SellerTaxId", "SellerCreditCode", "seller_tax_id",
            "购买方名称", "购买方", "PurchaserName", "purchaser_name",
            "购买方识别号", "购买方统一社会信用代码", "PurchaserTaxId", "PurchaserCreditCode", "purchaser_tax_id",
            "校验码", "CheckCode", "check_code");

    /** 用 POI XSSFWorkbook 生成带表头的发票记录 Excel */
    private static byte[] buildInvoiceExcel(List<Map<String, Object>> invoices) throws Exception {
        String[] headers = {"序号", "发票号码", "开票日期", "价税合计", "销售方名称",
                "销售方税号", "购买方名称", "购买方税号", "校验码", "识别接口", "识别时间", "其他字段"};

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("发票识别记录");

            // 表头
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            // 数据行
            int rowIdx = 1;
            for (int i = 0; i < invoices.size(); i++) {
                Map<String, Object> inv = invoices.get(i);
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(i + 1);

                // 诊断：打印第 1 行实际提取到的单元格值，验证 getStr 是否取到
                if (i == 0) {
                    log.info("🧾 Excel第1行提取值: 发票号码=[{}], 价税合计=[{}], 销售方=[{}], 识别时间=[{}]",
                            getStr(inv, "发票号码", "发票编号", "InvoiceNum", "InvoiceCode", "invoice_num"),
                            getStr(inv, "价税合计", "价税合计(小写)", "合计金额",
                                    "AmountInFiguers", "AmountInFigures", "amount_in_figures", "AmountInWords"),
                            cleanLabel(getStr(inv, "销售方名称", "销售方", "IndustrySort", "SellerName", "seller_name")),
                            getStr(inv, "_time"));
                }
                // 字段名兼容：中文 key（通用票据接口）+ 英文/拼音 key（数电票 electronic_invoice 接口）
                setCell(row, 1, getStr(inv, "发票号码", "发票编号", "InvoiceNum", "InvoiceCode", "invoice_num"));
                setCell(row, 2, cleanLabel(getStr(inv, "开票日期", "InvoiceDate", "invoice_date")));
                // 注意百度字段拼写错误：AmountInFiguers（多一个 u）；AmountInWords 是大写，优先取小写
                setCell(row, 3, getStr(inv, "价税合计", "价税合计(小写)", "合计金额",
                        "AmountInFiguers", "AmountInFigures", "amount_in_figures", "AmountInWords"));
                setCell(row, 4, cleanLabel(getStr(inv, "销售方名称", "销售方", "IndustrySort", "SellerName", "seller_name")));
                setCell(row, 5, getStr(inv, "销售方识别号", "销售方统一社会信用代码", "SellerTaxId", "SellerCreditCode", "seller_tax_id"));
                setCell(row, 6, cleanLabel(getStr(inv, "购买方名称", "购买方", "PurchaserName", "purchaser_name")));
                setCell(row, 7, getStr(inv, "购买方识别号", "购买方统一社会信用代码", "PurchaserTaxId", "PurchaserCreditCode", "purchaser_tax_id"));
                setCell(row, 8, getStr(inv, "校验码", "CheckCode", "check_code"));
                setCell(row, 9, "electronic".equals(getStr(inv, "_type")) ? "数电票" : "通用票据");
                setCell(row, 10, getStr(inv, "_time"));

                // 兜底：未映射到固定列的字段全部并入"其他字段"列，确保数据不丢
                StringBuilder extra = new StringBuilder();
                for (Map.Entry<String, Object> e : inv.entrySet()) {
                    String k = e.getKey();
                    if (k == null || MAPPED_FIELDS.contains(k)) continue;
                    Object v = e.getValue();
                    if (v instanceof Map<?, ?> m && m.get("word") != null) {
                        v = m.get("word");
                    }
                    if (v != null && !String.valueOf(v).isBlank()) {
                        if (extra.length() > 0) extra.append("\n");
                        extra.append(k).append("=").append(v);
                    }
                }
                setCell(row, 11, extra.toString());
            }

            // 自动列宽
            for (int i = 0; i < headers.length; i++) {
                sheet.setColumnWidth(i, 18 * 256);
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 安全取值：值可能是 {word:"xxx"} 对象或字符串 */
    private static String getStr(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v == null) continue;
            if (v instanceof Map<?, ?> m && m.get("word") != null) {
                return String.valueOf(m.get("word"));
            }
            String s = String.valueOf(v);
            if (!s.isBlank() && !"null".equals(s)) return s;
        }
        return "-";
    }

    private static void setCell(Row row, int idx, String val) {
        Cell cell = row.createCell(idx);
        cell.setCellValue(val != null ? val : "-");
    }

    /** 剥离百度识别值里的标签前缀（如 "名称:海鸿达..." → "海鸿达..."），并过滤纯标签占位文案 */
    private static String cleanLabel(String v) {
        if (v == null || v.isBlank() || "-".equals(v)) return "-";
        String cleaned = v.replaceFirst("^名称[:：]\\s*", "").trim();
        // 百度没识别出实际值时返回的是标签占位文案（如 年月日 / 社会信用代码/纳税人识别号）
        if (cleaned.matches("(?:年月日|名称|购买方|销售方|购买方名称|销售方名称|"
                + "社会信用代码|纳税人识别号|购买方纳税人识别号|销售方纳税人识别号|"
                + "社会信用代码/纳税人识别号|购买方社会信用代码|销售方社会信用代码)")) {
            return "-";
        }
        return cleaned;
    }

    /** 把任意 Map 的 key 转成字符串 key 的 Map（fastjson 反序列化嵌套结构安全处理） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToStrMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() instanceof String k) {
                out.put(k, e.getValue());
            }
        }
        return out;
    }

    /** 解析 Data URL（data:image/png;base64,xxxxx）→ 图片字节数组 */
    private static byte[] decodeDataUrl(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return null;
            return Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
