package com.demo.agent;

import com.demo.service.tools.DoubaoSearchService;
import com.demo.service.tools.WebSearchService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 网页搜索（双源对比）
 * <p>
 * 同时调用百度千帆 + 豆包搜索两个后端，把两方结果合并返回给 LLM，
 * 由 LLM 对比、筛选、判定正确信息后输出最终答案。
 * 两源互相印证，不一致时 LLM 需说明差异，提升实时信息回答准确率。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool {

    private final WebSearchService webSearchService;
    private final DoubaoSearchService doubaoSearchService;

    @Tool("搜索互联网获取实时/最新信息。当用户询问实时新闻、最新事件、"
            + "近期热点、验证事实、或任何 LLM 无法确定的知识时，必须调用此工具。"
            + "工具会同时返回【百度】与【豆包】两个搜索来源的结果，请对比两方："
            + "① 两方一致的结论优先采用；② 不一致时指出差异并选择更权威/更新的来源；"
            + "③ 剔除无效或明显错误信息。最终基于对比后的可靠信息回答用户。"
            + "【仅限非车票类实时信息】禁止用于查询火车票、高铁、动车、余票、时刻表。" )
    public String searchWeb(
            @P("搜索关键词，应简洁准确，例如：'2026年世界杯赛程'、'今天比特币价格'、'最新AI新闻'")
            String query) {
        log.info("🧰 Agent 调用搜索工具，关键词：{}", query);

        // 双源查询：百度千帆 + 豆包搜索（各取 5 条）
        String baidu = webSearchService.search(query, 5);
        String doubao = doubaoSearchService.search(query, 5);

        // 合并返回，带来源标注，供 LLM 对比筛选
        StringBuilder sb = new StringBuilder("【搜索汇总】关键词：").append(query).append("\n\n");
        sb.append("========== 【来源一：百度千帆】 ==========\n").append(baidu).append("\n\n");
        sb.append("========== 【来源二：豆包搜索】 ==========\n").append(doubao);
        sb.append("\n\n请对比以上两个来源的结果，筛选出有效、正确的信息后回答用户。");

        String result = sb.toString();
        log.info("🧰 搜索工具返回（双源合并），长度：{} 字符", result.length());
        return result;
    }
}
