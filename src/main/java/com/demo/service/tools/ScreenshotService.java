package com.demo.service.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 网页截图服务 —— 调用本机 Chrome/Edge headless（零 Java 依赖）
 * <p>
 * 普通截图：1280x900 视口。
 * 长截图（fullPage）：设置超高窗口让页面渲染全部内容，一次截取完整页面。
 */
@Slf4j
@Service
public class ScreenshotService {

    private static final String[] BROWSER_PATHS = {
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            "chrome",
            "msedge"
    };

    @Value("${screenshot.viewport.width:1280}")
    private int viewportWidth;

    /** 长截图最大窗口高度（视口拉高到足以容纳整个页面） */
    @Value("${screenshot.max.height:2000}")
    private int maxHeight;

    @Value("${screenshot.page.timeout:30}")
    private int pageTimeoutSec;

    private String browserPath;

    private String findBrowser() {
        if (browserPath != null) return browserPath;
        for (String path : BROWSER_PATHS) {
            if (Files.exists(Path.of(path))) {
                browserPath = path;
                log.info("✅ 找到浏览器: {}", path);
                return browserPath;
            }
        }
        log.warn("⚠️ 未找到 Chrome 或 Edge");
        return null;
    }

    /**
     * @param url      目标网页地址
     * @param fullPage true=长截图（含所有滚动内容），false=仅可视区域
     */
    public byte[] capture(String url, boolean fullPage) {
        if (url == null || url.isBlank()) return null;

        String browser = findBrowser();
        if (browser == null) return null;

        String normalizedUrl = url.trim();
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "https://" + normalizedUrl;
        }

        // 长截图：超高视口让页面渲染全部内容；普通截图：900px 视口
        int height = fullPage ? maxHeight : 900;
        log.info("📸 {} | url={} | height={}px",
                fullPage ? "长截图" : "截图", normalizedUrl, height);

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("chrome_screenshot_");
            Path outputFile = tempDir.resolve("screenshot.png");

            List<String> cmd = new ArrayList<>();
            cmd.add(browser);
            cmd.add("--headless=new");
            cmd.add("--disable-gpu");
            cmd.add("--no-sandbox");
            cmd.add("--disable-dev-shm-usage");
            cmd.add("--hide-scrollbars");
            cmd.add("--screenshot=" + outputFile.toAbsolutePath());
            cmd.add("--window-size=" + viewportWidth + "," + height);
            // 虚拟时间预算：让 JS 有足够时间渲染延迟加载内容
            cmd.add("--virtual-time-budget=" + (pageTimeoutSec * 1000));
            cmd.add("--force-device-scale-factor=1");
            cmd.add(normalizedUrl);

            log.debug("执行: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(pageTimeoutSec + 15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("⏰ 截图超时 ({}s)", pageTimeoutSec);
                return null;
            }

            if (process.exitValue() != 0) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) log.debug("Browser: {}", line);
                }
                log.error("❌ 浏览器退出码: {}", process.exitValue());
                return null;
            }

            if (!Files.exists(outputFile) || Files.size(outputFile) == 0) {
                log.error("❌ 未生成截图文件");
                return null;
            }

            byte[] result = Files.readAllBytes(outputFile);

            // 长截图：裁剪掉底部空白区域
            if (fullPage && result.length > 0) {
                result = cropBottomWhitespace(result);
            }

            log.info("✅ 截图完成 | {}KB | {}×{}px",
                    result.length / 1024, viewportWidth, height);
            return result;

        } catch (Exception e) {
            log.error("❌ 截图失败: {}", e.getMessage(), e);
            return null;
        } finally {
            if (tempDir != null) cleanup(tempDir);
        }
    }

    /**
     * 裁剪底部空白：检测背景色，从底部向上扫描找内容边界。
     */
    private byte[] cropBottomWhitespace(byte[] pngBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (img == null) return pngBytes;

            int w = img.getWidth();
            int h = img.getHeight();

            // 1. 采样底部区域得到背景色
            int bgR = 255, bgG = 255, bgB = 255;
            int sampleY = Math.max(h - 10, 0);
            long sumR = 0, sumG = 0, sumB = 0;
            int samples = 0;
            for (int x = 0; x < w; x += 5) {
                int rgb = img.getRGB(x, sampleY);
                sumR += (rgb >> 16) & 0xFF;
                sumG += (rgb >> 8) & 0xFF;
                sumB += rgb & 0xFF;
                samples++;
            }
            if (samples > 0) {
                bgR = (int) (sumR / samples);
                bgG = (int) (sumG / samples);
                bgB = (int) (sumB / samples);
            }

            // 2. 从底向上扫描
            int contentEnd = h - 1;
            int tolerance = 15; // 颜色容差

            for (int y = h - 1; y >= 50; y--) {
                int diffCount = 0;
                for (int x = 0; x < w; x += 5) {
                    int rgb = img.getRGB(x, y);
                    int dr = Math.abs(((rgb >> 16) & 0xFF) - bgR);
                    int dg = Math.abs(((rgb >> 8) & 0xFF) - bgG);
                    int db = Math.abs((rgb & 0xFF) - bgB);
                    if (dr > tolerance || dg > tolerance || db > tolerance) {
                        diffCount++;
                    }
                }
                // 超过 5% 的采样像素不是背景色 → 这行有内容
                if (diffCount > w / 100) {
                    contentEnd = Math.min(y + 10, h - 1);
                    break;
                }
            }

            if (contentEnd >= h - 20) return pngBytes; // 没什么可裁的

            BufferedImage cropped = img.getSubimage(0, 0, w, contentEnd + 1);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(cropped, "png", baos);

            byte[] result = baos.toByteArray();
            log.info("✂️ 裁剪: {}→{}px | bg=({},{},{}) | {}→{}KB",
                    h, cropped.getHeight(), bgR, bgG, bgB,
                    pngBytes.length / 1024, result.length / 1024);
            return result;

        } catch (Exception e) {
            log.debug("裁剪失败: {}", e.getMessage());
            return pngBytes;
        }
    }

    private void cleanup(Path dir) {
        try {
            try (var files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            }
        } catch (IOException ignored) {}
    }
}
