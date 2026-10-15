package com.demo.service.tools;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

/**
 * 网页内容读取后端服务
 * 使用 Jsoup 抓取目标 URL 的 HTML，提取标题和正文
 * 自动清除广告、导航栏、脚本等干扰内容
 * 返回纯文本格式给 LLM 处理
 */
@Slf4j
@Service
public class WebPageReaderService {

    /** 用户代理，模拟真实浏览器访问 */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 连接超时时间（毫秒） */
    private static final int TIMEOUT_MS = 15_000;

    /** 返回内容最大长度（字符数） */
    private static final int MAX_CONTENT_LENGTH = 4_000;

    /**
     * 抓取并提取网页可读内容
     * @param url 目标网页 URL
     * @return 格式化后的网页内容（标题 + 正文摘要）
     */
    public String fetchPageContent(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "错误：URL 不能为空";
        }

        // 自动补全协议头
        String normalizedUrl = url.trim();
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "https://" + normalizedUrl;
        }

        try {
            log.info("📄 开始抓取网页：{}", normalizedUrl);

            // 连接并获取文档,开始爬取网页
            Document doc = Jsoup.connect(normalizedUrl)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .get();

            // 获取页面标题
            String title = doc.title();
            log.info("📄 页面标题：{}", title);

            // 移除干扰元素
            removeNoiseElements(doc);

            // 提取正文
            String mainContent = extractMainContent(doc);

            if (mainContent.isEmpty()) {
                return "⚠️ 未能提取到该网页的有效文本内容，页面可能包含大量图片或需要 JavaScript 渲染。";
            }

            // 截断过长的内容
            if (mainContent.length() > MAX_CONTENT_LENGTH) {
                mainContent = mainContent.substring(0, MAX_CONTENT_LENGTH) + "\n\n...（内容过长已截断）";
            }

            // 组装结果
            StringBuilder result = new StringBuilder();
            result.append("📰 ").append(title).append("\n\n");
            result.append(mainContent);

            log.info("📄 网页提取完成，共 {} 字符", mainContent.length());
            return result.toString();

        } catch (java.net.SocketTimeoutException e) {
            log.warn("📄 网页抓取超时：{}", normalizedUrl);
            return "⚠️ 网页访问超时，该网站响应较慢，请稍后重试。";
        } catch (java.net.UnknownHostException e) {
            log.warn("📄 域名无法解析：{}", normalizedUrl);
            return "⚠️ 无法解析该网址，请检查链接是否正确。";
        } catch (Exception e) {
            log.warn("📄 网页抓取失败：{} - {}", normalizedUrl, e.getMessage());
            return "⚠️ 无法读取该网页，原因：" + e.getMessage();
        }
    }

    /**
     * 移除页面中的干扰元素（广告、导航、脚本、样式等）
     */
    private void removeNoiseElements(Document doc) {
        // 移除不需要的标签
        doc.select("script, style, nav, header, footer, iframe, noscript").remove();
        // 移除常见广告/侧边栏 class 和 id
        doc.select("[class*=ad], [class*=sidebar], [class*=nav], [class*=footer], [class*=header], [class*=menu], [class*=banner]").remove();
        doc.select("[id*=ad], [id*=sidebar], [id*=nav], [id*=footer], [id*=header], [id*=menu]").remove();
        doc.select("[class*=toolbar], [class*=widget], [class*=comment], [class*=recommend]").remove();
        // 移除隐藏元素
        doc.select("[style*=display:none], [style*=display: none], [hidden]").remove();
    }

    /**
     * 提取页面正文内容
     * 优先使用 <article>、<main> 等语义标签
     * 回退到 body 全文
     */
    private String extractMainContent(Document doc) {
        // 尝试语义标签
        Element article = doc.selectFirst("article");
        if (article != null) {
            String text = article.text().trim();
            if (text.length() > 50) return text;
        }

        Element main = doc.selectFirst("main");
        if (main != null) {
            String text = main.text().trim();
            if (text.length() > 50) return text;
        }

        Element content = doc.selectFirst("[class*=content], [class*=article], [class*=post], [id*=content], [id*=article]");
        if (content != null) {
            String text = content.text().trim();
            if (text.length() > 50) return text;
        }

        // 回退到 body 全文
        Element body = doc.body();
        if (body != null) {
            return body.text().trim();
        }

        return "";
    }
}
