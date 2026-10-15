package com.demo.agent;

import com.demo.service.tools.WebFetchService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 网页精读（web_fetch）。
 * <p>
 * 与 web_search 分离：web_search 只返回搜索摘要列表（标题/摘要/url），
 * 本工具输入具体 URL，下载 HTML 并经 Readability 风格算法清洗噪声，返回干净正文。
 * <p>
 * 典型链路：用户提问 → web_search 得到候选链接 → LLM 挑值得精读的 url → web_fetch 逐一抓正文 → 结合摘要回答。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebFetchTool {

    private final WebFetchService webFetchService;

    @Tool("网页精读：输入具体 URL，下载网页 HTML 并经 Readability 风格算法清洗噪声（导航/广告/侧边栏/页脚），返回干净正文。"
            + "适用：搜索引擎（web_search）结果里挑出的值得深读的文章链接、用户给出的具体网页，需要理解文章完整内容或提取正文细节时。"
            + "区别于 web_search（只返回标题/摘要/url 列表，不抓正文）。")
    public String webFetch(
            @P("要精读的网页链接 URL，例如：https://example.com/article/123")
            String url) {
        log.info("🔍 Agent 调用网页精读工具，URL：{}", url);
        String result = webFetchService.fetchCleanContent(url);
        log.info("🔍 网页精读返回，长度：{} 字符", result.length());
        return result;
    }
}
