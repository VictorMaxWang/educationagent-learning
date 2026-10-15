package com.demo.agent;

import com.demo.service.tools.WebPageReaderService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 网页内容读取器
 * Agent 接收到用户发送的链接时自动调用此工具
 * 抓取网页标题和正文，返回纯文本供 LLM 总结/回答
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebPageReaderTool {

    private final WebPageReaderService webPageReaderService;

    @Tool("读取指定网址的页面内容并提取可读文本（标题+正文）。"
            + "当用户发送一个链接/网址时，必须调用此工具读取内容，"
            + "读取后基于提取的内容回答用户问题或总结文章。"
            + "支持任意 HTTP/HTTPS 网页，自动清理广告和导航干扰。")
    public String readWebPage(
            @P("网页链接 URL，例如：https://example.com/article/123")
            String url) {
        log.info("🧰 Agent 调用网页读取工具，URL：{}", url);
        String result = webPageReaderService.fetchPageContent(url);
        log.info("🧰 网页读取工具返回，长度：{} 字符", result.length());
        return result;
    }
}
