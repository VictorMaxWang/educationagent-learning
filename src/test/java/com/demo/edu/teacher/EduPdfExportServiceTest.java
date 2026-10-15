package com.demo.edu.teacher;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中文 PDF 导出测试。
 *
 * <h3>为什么要"回读校验"而不是只断言文件非空</h3>
 * PDFBox 写中文的典型失败不是抛异常，而是<b>把字渲染成方框（豆腐块）</b>或干脆
 * 编码丢失——文件照样生成、照样能打开，只是内容全不可读。
 * 所以这里把生成出来的 PDF 用 {@link PDFTextStripper} 再解析回文本，断言中文原样可读：
 * 这一步同时验证了"字体已内嵌"和"ToUnicode 映射正确"，是唯一能证明导出真的可用的检查。
 */
class EduPdfExportServiceTest {

    @Test
    @DisplayName("生成的中文 PDF 可被解析回原文（证明字体与编码没问题）")
    void chineseTextSurvivesRoundTrip() throws Exception {
        String title = "备课素材：青春期身体变化";
        String body = "第一点：青春期身体变化是正常的。\n"
                + "第二点：如果有疑问，可以问老师或家长。\n"
                + "【资料】依据校本资料整理。";

        byte[] pdf = new EduPdfExportService().toPdf(title, body);

        assertTrue(pdf.length > 800, "PDF 太小，可能没写进内容：" + pdf.length + " 字节");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.ISO_8859_1), "不是合法 PDF 头");

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", "");
            assertTrue(text.contains("备课素材"), "标题中文丢失或被渲染成方框：" + text);
            assertTrue(text.contains("青春期身体变化是正常的"), "正文中文丢失：" + text);
            assertTrue(text.contains("依据校本资料整理"), "正文中文丢失：" + text);
        }
    }

    @Test
    @DisplayName("超长文本能分页且不抛异常")
    void longTextPaginates() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append("第 ").append(i).append(" 行：这是一段用于测试分页的中文内容。\n");
        }
        byte[] pdf = new EduPdfExportService().toPdf("长文本", body.toString());
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() >= 2, "200 行中文应产生多页，实际 " + doc.getNumberOfPages() + " 页");
        }
    }
}
