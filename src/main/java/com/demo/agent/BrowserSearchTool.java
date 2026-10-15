package com.demo.agent;

import com.demo.service.tools.browser.BrowserSearchService;
import com.demo.service.tools.browser.BrowserSearchService.SearchResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LangChain4j Agent 工具 —— 模拟人工搜索操作。
 * 在指定网页的搜索框中模拟真人逐字输入、停顿确认、触发搜索的完整流程。
 *
 * 适用场景：
 * - 用户要求在某个网站搜索内容
 * - 用户提供 URL 和关键词让机器人帮忙搜索
 * - 批量在搜索引擎中执行搜索操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserSearchTool {

    private final BrowserSearchService browserSearchService;

    @Tool("在指定网页的搜索框中模拟真人打字搜索。"
            + "模拟真人点击搜索框→逐字符输入→停顿思考→触发搜索的完整流程。"
            + "当用户要求在网页中搜索内容、帮我搜索、在XX网站搜XX时调用此工具。"
            + "必须提供目标网页URL、搜索关键词、搜索框的CSS选择器。"
            + "搜索触发方式默认按回车键，如需点击搜索按钮则提供按钮选择器。")
    public String simulateSearch(
            @P("目标网页地址，如：https://www.baidu.com、https://www.google.com") String url,
            @P("搜索关键词，输入到搜索框的内容，如：今日天气、Spring Boot教程") String keyword,
            @P("搜索框的CSS选择器或XPath，如：#kw（百度）、input[name='q']（Google）、//input[@name='wd']") String searchBoxSelector,
            @P(value = "搜索按钮的CSS选择器或XPath（可选），不填则按回车键提交。如：#su（百度）、button[type='submit']", required = false) String submitSelector) {

        log.info("🔍 Agent 调用模拟搜索工具，URL：{}，关键词：{}", url, keyword);

        if (url == null || url.isBlank()) {
            return "❌ 搜索操作失败：请提供目标网页地址（URL）。";
        }
        if (keyword == null || keyword.isBlank()) {
            return "❌ 搜索操作失败：请提供要搜索的关键词。";
        }
        if (searchBoxSelector == null || searchBoxSelector.isBlank()) {
            return "❌ 搜索操作失败：请提供搜索框的CSS选择器（如：#kw、input[name='q']）。";
        }

        SearchResult result = browserSearchService.simulateSearch(
                url, keyword, searchBoxSelector,
                submitSelector != null && !submitSelector.isBlank() ? submitSelector : null);

        log.info("🔍 模拟搜索完成，成功：{}", result.success());

        StringBuilder response = new StringBuilder();
        if (result.success()) {
            response.append("✅ 搜索操作完成！\n");
            response.append("已模拟真人在「").append(url).append("」搜索「").append(keyword).append("」\n\n");
        } else {
            response.append("❌ 搜索操作失败：").append(result.message()).append("\n\n");
        }

        // 附加操作摘要
        response.append("📋 操作日志摘要：\n");
        List<String> logs = result.logs();
        int start = Math.max(0, logs.size() - 6);
        for (int i = start; i < logs.size(); i++) {
            response.append("  ").append(logs.get(i)).append("\n");
        }
        if (logs.size() > 6) {
            response.append("  ... 共 ").append(logs.size()).append(" 条日志\n");
        }

        return response.toString().trim();
    }
}
