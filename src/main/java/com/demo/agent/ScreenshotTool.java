package com.demo.agent;

import com.demo.service.tools.ScreenshotService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * LangChain4j Agent 工具 —— 网页截图（本机 Chrome headless）
 * <p>
 * 调用本机 Chrome 命令行 headless 模式截图，零额外 Java 依赖。
 * 支持普通截图和全页长截图。
 * 图片由 WechatMsgListener 调用 {@code wechatClient.sendImage()} 发送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScreenshotTool {

    private static final int MAX_DIMENSION = 1920;
    private static final float JPEG_QUALITY = 0.85f;
    private static final int COMPRESS_THRESHOLD = 500 * 1024;

    private final ScreenshotService screenshotService;

    @Getter
    private byte[] lastScreenshot;

    @Getter
    private String lastScreenshotMime;

    /**
     * 对网址进行网页截图（支持全页长截图）
     */
    @Tool("网页截图。对指定URL进行截图，支持普通截图和全页长截图（包含所有可滚动内容）。"
            + "使用本机 Chrome 浏览器，无需额外配置。"
            + "当用户说'截图'、'网页截图'、'截个图'、'长截图'时调用此工具。"
            + "适用于：网页操作结果视觉确认、保存网页状态、多工具链操作验证。"
            + "例如：'截图 https://www.baidu.com'、'长截图 https://example.com'")
    public String takeScreenshot(
            @P("目标网页URL。必须是完整链接，例如：https://www.example.com")
            String url,
            @P("是否全页长截图。true=包含所有滚动内容的完整页面，false=仅可视区域。默认false。用户说'长截图'时传true")
            String fullPage) {

        log.info("📸 Agent 调用网页截图 | url={} | fullPage={}", url, fullPage);

        if (url == null || url.isBlank()) {
            return "请提供需要截图的网页URL，例如：https://www.baidu.com";
        }

        boolean isFullPage = "true".equalsIgnoreCase(fullPage);

        clear();
        byte[] imageBytes = screenshotService.capture(url, isFullPage);

        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("网页截图失败: url={}", url);
            return "网页截图失败。请检查：\n"
                    + "1. URL 是否正确（需包含 http:// 或 https://）\n"
                    + "2. 本机是否安装了 Chrome 浏览器\n"
                    + "3. 网站是否能正常访问";
        }

        byte[] compressed = compressIfNeeded(imageBytes);
        this.lastScreenshot = compressed;
        this.lastScreenshotMime = (compressed.length < imageBytes.length) ? "image/jpeg" : "image/png";

        log.info("✅ 网页截图完成 | 原始 {}KB → 最终 {}KB",
                imageBytes.length / 1024, compressed.length / 1024);
        return "网页截图已完成，已发送给你，请查收！";
    }

    public void clear() {
        this.lastScreenshot = null;
        this.lastScreenshotMime = null;
    }

    private byte[] compressIfNeeded(byte[] input) {
        if (input.length < COMPRESS_THRESHOLD) return input;

        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(input));
            if (original == null) return input;

            int w = original.getWidth(), h = original.getHeight();
            int nw, nh;
            if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) { nw = w; nh = h; }
            else if (w >= h) { nw = MAX_DIMENSION; nh = h * MAX_DIMENSION / w; }
            else { nh = MAX_DIMENSION; nw = w * MAX_DIMENSION / h; }

            BufferedImage resized = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, nw, nh, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(resized, null, null), param);
            } finally { writer.dispose(); }

            byte[] result = baos.toByteArray();
            log.info("🖼️ 截图压缩: {}×{} → {}×{}, {}KB → {}KB", w, h, nw, nh, input.length / 1024, result.length / 1024);
            return result;
        } catch (Exception e) {
            log.warn("截图压缩失败: {}", e.getMessage());
            return input;
        }
    }
}
