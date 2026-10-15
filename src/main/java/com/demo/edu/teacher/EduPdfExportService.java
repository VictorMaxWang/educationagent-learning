package com.demo.edu.teacher;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一段文本导出成中文 PDF（教师端导出备课素材）。
 *
 * <h3>中文 PDF 的坑：不内嵌字体就是一堆方框</h3>
 * PDFBox 默认的 Helvetica 没有中文字形，直接写中文会抛
 * {@code IllegalArgumentException: No glyph for U+4E2D}（或渲染成方框）。
 * 必须加载含中文字形的字体并内嵌，且必须用 {@code PDType0Font}（Type0 复合字体），
 * 老的 {@code PDType1Font} 只支持单字节编码。
 *
 * <p>字体按"本机 Windows 常见字体"逐个尝试：纯 TTF 优先（加载路径最短），
 * 再退到 TTC 字体集合（{@code msyh.ttc} / {@code simsun.ttc} 在 Win10+ 上更常见）。
 * 全都找不到时抛明确错误，让调用方如实告诉用户"PDF 导出不可用，请改用 Word 导出"——
 * 而不是生成一份全是方框的 PDF 让人以为成功了。
 *
 * <p>排版只做到"折行 + 分页"：足够把备课素材另存一份 PDF。
 * 复杂排版（图文混排、表格样式）应该交给 Word 或前端打印。
 */
@Slf4j
@Service
public class EduPdfExportService {

    /** 纯 TTF 候选（优先：加载最简单）。 */
    private static final String[] TTF_CANDIDATES = {
            "C:/Windows/Fonts/simhei.ttf",   // 黑体
            "C:/Windows/Fonts/simkai.ttf",   // 楷体
            "C:/Windows/Fonts/deng.ttf"      // 等线
    };

    /** TTC 字体集合候选（Win10+ 上中文字体常以 TTC 形式存在）。 */
    private static final String[] TTC_CANDIDATES = {
            "C:/Windows/Fonts/msyh.ttc",     // 微软雅黑
            "C:/Windows/Fonts/simsun.ttc"    // 宋体
    };

    private static final float MARGIN = 56f;
    private static final float TITLE_SIZE = 16f;
    private static final float BODY_SIZE = 11.5f;
    private static final float LEADING = 19f;

    /** 每行最多多少字符（中文近似等宽，按字符数硬折行够用）。 */
    private static final int MAX_CHARS_PER_LINE = 38;

    /**
     * 生成 PDF。
     *
     * @return PDF 字节流
     * @throws IllegalStateException 找不到可用中文字体，或写入失败
     */
    public byte[] toPdf(String title, String body) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont font = loadCjkFont(doc);
            List<String> lines = wrap(body);
            float pageHeight = PDRectangle.A4.getHeight();
            float bottom = MARGIN;

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = pageHeight - MARGIN;
            try {
                y = writeLine(cs, font, title, TITLE_SIZE, y);
                y -= LEADING * 0.6f;

                for (String line : lines) {
                    if (y < bottom) {                 // 换页
                        cs.close();
                        page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        cs = new PDPageContentStream(doc, page);
                        y = pageHeight - MARGIN;
                    }
                    if (!line.isEmpty()) {
                        y = writeLine(cs, font, line, BODY_SIZE, y);
                    } else {
                        y -= LEADING;
                    }
                }
            } finally {
                cs.close();
            }

            doc.save(out);
            return out.toByteArray();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("PDF 生成失败：" + e.getMessage(), e);
        }
    }

    private static float writeLine(PDPageContentStream cs, PDFont font, String text, float size, float y)
            throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(MARGIN, y);
        cs.showText(text);
        cs.endText();
        return y - LEADING;
    }

    /** 按字符数硬折行（中文近似等宽），保留原有换行。 */
    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        String clean = safe(text);
        for (String raw : clean.split("\n", -1)) {
            if (raw.isEmpty()) {
                out.add("");
                continue;
            }
            int start = 0;
            while (start < raw.length()) {
                int end = Math.min(raw.length(), start + MAX_CHARS_PER_LINE);
                out.add(raw.substring(start, end));
                start = end;
            }
        }
        return out;
    }

    /** 去掉 PDF 基本字体不支持的字符（\r、制表符、NUL），避免 showText 抛异常。 */
    private static String safe(String text) {
        if (text == null) return "";
        return text.replace("\r", "").replace("\t", "    ").replace("\u0000", "");
    }

    /** 加载含中文字形的字体：纯 TTF 优先，再退 TTC；都找不到抛明确错误。 */
    private static PDFont loadCjkFont(PDDocument doc) {
        for (String path : TTF_CANDIDATES) {
            File f = new File(path);
            if (!f.isFile()) continue;
            try {
                PDFont font = PDType0Font.load(doc, f);
                log.info("📄 PDF 导出字体：{}", path);
                return font;
            } catch (Exception e) {
                log.warn("加载字体失败 {}：{}", path, e.toString());
            }
        }
        for (String path : TTC_CANDIDATES) {
            File f = new File(path);
            if (!f.isFile()) continue;
            try {
                // TTC 是字体集合：取集合里的第一个字体
                org.apache.fontbox.ttf.TrueTypeCollection collection =
                        new org.apache.fontbox.ttf.TrueTypeCollection(f);
                final PDFont[] holder = new PDFont[1];
                collection.processAllFonts(ttf -> {
                    if (holder[0] == null) {
                        try {
                            holder[0] = PDType0Font.load(doc, ttf, true);
                        } catch (Exception e) {
                            log.warn("TTC 子字体加载失败：{}", e.toString());
                        }
                    }
                });
                if (holder[0] != null) {
                    log.info("📄 PDF 导出字体（TTC）：{}", path);
                    return holder[0];
                }
            } catch (Exception e) {
                log.warn("加载 TTC 字体失败 {}：{}", path, e.toString());
            }
        }
        throw new IllegalStateException(
                "未找到可用的中文字体（已尝试 " + String.join("、", TTF_CANDIDATES)
                        + " 与 " + String.join("、", TTC_CANDIDATES)
                        + "）。请改用 Word 导出，或在本机安装上述字体之一");
    }
}
