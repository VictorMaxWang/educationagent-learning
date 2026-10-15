package com.demo.service.tools;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 网页精读服务（web_fetch 后端）。
 * <p>
 * 与 {@link WebPageReaderService} 的区别：
 * 用 <b>Readability 风格评分算法</b>提取正文——对候选段落按文本长度/句数/链接密度计分，
 * 向上累加到祖先容器，选得分最高的容器作为正文，而非简单取第一个 article/content 节点。
 * 能更好剔除导航、侧边栏、相关阅读等噪声。
 */
@Slf4j
@Service
public class WebFetchService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_CONTENT_LENGTH = 6_000;

    /**
     * 抓取 URL 并返回 Readability 清洗后的干净正文。
     * @param url 目标网页 URL
     * @return 标题 + 正文纯文本
     */
    public String fetchCleanContent(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "错误：URL 不能为空";
        }
        String normalizedUrl = url.trim();
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "https://" + normalizedUrl;
        }
        try {
            log.info("🔍 [WebFetch] 抓取网页：{}", normalizedUrl);
            Document doc = Jsoup.connect(normalizedUrl)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .get();
            String title = doc.title();

            removeNoise(doc);
            Element article = extractArticle(doc);
            String text = article != null ? cleanWinner(article).text().trim() : "";
            if (text.isEmpty() && doc.body() != null) {
                text = doc.body().text().trim();
            }
            if (text.isEmpty()) {
                return "⚠️ 未能提取到有效正文，页面可能依赖 JavaScript 渲染。";
            }
            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH) + "\n\n...（内容过长已截断）";
            }
            log.info("🔍 [WebFetch] 提取完成，正文 {} 字符", text.length());
            return "📰 " + title + "\n\n" + text;
        } catch (SocketTimeoutException e) {
            log.warn("🔍 [WebFetch] 超时：{}", normalizedUrl);
            return "⚠️ 网页访问超时，该网站响应较慢，请稍后重试。";
        } catch (UnknownHostException e) {
            log.warn("🔍 [WebFetch] 域名无法解析：{}", normalizedUrl);
            return "⚠️ 无法解析该网址，请检查链接是否正确。";
        } catch (Exception e) {
            log.warn("🔍 [WebFetch] 抓取失败：{} - {}", normalizedUrl, e.getMessage());
            return "⚠️ 无法读取该网页，原因：" + e.getMessage();
        }
    }

    /** 移除导航/广告/脚本/页脚等噪声节点 */
    private void removeNoise(Document doc) {
        doc.select("script, style, nav, header, footer, iframe, noscript, form, select, button, aside").remove();
        doc.select("[class*=ad], [class*=sidebar], [class*=nav], [class*=footer], [class*=header], "
                + "[class*=menu], [class*=banner], [class*=toolbar], [class*=widget], [class*=comment], "
                + "[class*=recommend], [class*=share]").remove();
        doc.select("[id*=ad], [id*=sidebar], [id*=nav], [id*=footer], [id*=header], [id*=menu]").remove();
        doc.select("[style*=display:none], [style*=display: none], [hidden]").remove();
    }

    /**
     * Readability 风格正文提取：
     * 候选段落计分 → 按 0.6 衰减向上累加到祖先 → 大容器额外计分 → 选总分最高节点。
     */
    private Element extractArticle(Document doc) {
        Map<Element, Double> scores = new IdentityHashMap<>();

        // 1. 段落类候选计分并向上累加
        for (Element el : doc.select("p, pre, td, blockquote, li")) {
            double s = scoreNode(el);
            if (s <= 0) continue;
            Element ancestor = el;
            double factor = 1.0;
            for (int i = 0; i < 4 && ancestor != null; i++) {
                scores.merge(ancestor, s * factor, Double::sum);
                ancestor = ancestor.parent();
                factor *= 0.6; // 越往上权重越低，避免噪声容器被无谓抬分
            }
        }

        // 2. 含较多段落的大容器直接计分（article/main 等）
        for (Element el : doc.select("div, section, article, main")) {
            if (el.select("p").size() >= 3) {
                scores.merge(el, scoreNode(el) * 1.5, Double::sum);
            }
        }

        // 3. 选最高分
        Element best = null;
        double bestScore = 0;
        for (Map.Entry<Element, Double> e : scores.entrySet()) {
            if (e.getValue() > bestScore) {
                bestScore = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** 节点得分：长度加分（封顶）+ 句数加分，乘 1-链接密度 */
    private double scoreNode(Element el) {
        String text = el.text().trim();
        int len = text.length();
        if (len < 20) return 0;
        double lengthBonus = Math.min(len / 100.0, 10);
        int sentences = text.split("[。.!?！？]").length;
        double density = 1.0 - linkDensity(el);
        return (lengthBonus + sentences * 0.5) * density;
    }

    /** 链接文本占总文本比例（导航/列表类节点趋近 1） */
    private double linkDensity(Element el) {
        String allText = el.text();
        if (allText.isBlank()) return 1;
        int linkLen = 0;
        for (Element a : el.select("a")) {
            linkLen += a.text().length();
        }
        return Math.min(1.0, (double) linkLen / allText.length());
    }

    /** 在选中的正文容器内，剔除链接密集/过短的碎片节点后再取文本 */
    private Element cleanWinner(Element article) {
        for (Element child : article.select("div, section, ul, nav, p")) {
            String t = child.text().trim();
            if (t.isEmpty()) continue;
            if ((linkDensity(child) > 0.6 && t.length() > 30) || (t.length() < 12 && child.select("a, img").size() > 0)) {
                child.remove();
            }
        }
        return article;
    }
}
