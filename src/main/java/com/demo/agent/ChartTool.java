package com.demo.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.service.chart.ChartData;
import com.demo.service.chart.ChartRenderer;
import com.demo.service.chart.ChartService;
import com.demo.service.tools.holder.UserFileHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j Agent 工具 —— 图表生成。
 * <p>
 * Excel → JFreeChart 渲染 → PNG → 微信发图
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartTool {

    private final ChartRenderer chartRenderer;
    private final ChartService chartService;
    private final UserFileHolder userFileHolder;

    @Getter
    private byte[] lastChartImage;

    @Tool("生成图表。从用户最近上传的 Excel 中取数据，或直接根据传入的数据生成图表图片。"
            + "支持图表类型：bar(柱状图)、line(折线图)、pie(饼图)。不指定类型时默认 bar。"
            + "用户说「生成图表」「画个图」「可视化」「柱状图」「折线图」「饼图」时调用。"
            + "若用户没上传 Excel 但描述了数据，直接通过 tableData 参数传入数据生成图表，无需文件。")
    public String generateChart(
            @P(value = "图表类型：bar=柱状图，line=折线图，pie=饼图", required = false) String chartType,
            @P(value = "图表标题", required = false) String title,
            @P(value = "X轴/分类列名，如：月份、地区、部门", required = false) String xColumn,
            @P(value = "Y轴/数值列名，多列用逗号分隔，如：销售额,利润", required = false) String yColumns,
            @P(value = "数据 JSON（可选，直接传入数据画图，无需上传文件）：{\"columns\":[\"年份\",\"金牌数\"],\"rows\":[[\"2016\",70],[\"2020\",88]]}", required = false) String tableData) {

        String userId = com.demo.util.VoiceContext.getUserId();
        if (userId == null) userId = "default";

        // 数据来源：优先用 LLM 传入的 tableData，否则回退读用户上传的 Excel（或刚生成的 xlsx）
        ChartData data;
        if (tableData != null && !tableData.isBlank()) {
            try {
                data = parseTableData(tableData, title);
            } catch (Exception e) {
                log.warn("[{}] 图表数据解析失败: {}", userId, tableData, e);
                return "⛔ 数据解析失败，请按 {\"columns\":[\"列1\",\"列2\"],\"rows\":[[\"值1\",\"值2\"]]} 格式提供：" + e.getMessage();
            }
        } else {
            UserFileHolder.FileEntry entry = userFileHolder.get(userId);
            if (entry == null) {
                return "⚠️ 没有已上传的 Excel 文件，可以直接把数据发给我（例如：2020年88枚金牌），或先让我生成 Excel 数据表再画图。";
            }
            List<ChartData> sheets = entry.sheetData();
            if (sheets == null || sheets.isEmpty()) {
                return "⚠️ 文件没有结构化数据，请确认发送的是 Excel 文件。";
            }
            data = sheets.get(0);
        }

        log.info("📊 [{}] 生成图表: type={} title={}", userId, chartType, title);

        if (title == null || title.isBlank()) title = data.getSheetName();

        List<String> yColList = yColumns != null && !yColumns.isBlank()
                ? List.of(yColumns.split("[,，]"))
                : List.of();

        try {
            byte[] png = chartRenderer.render(data, chartType, title, xColumn, yColList);
            lastChartImage = png;
            log.info("📊 图表已生成: {}KB", png.length / 1024);
            return "✅ 图表已生成，正在发送图片...";
        } catch (Exception e) {
            log.error("❌ 图表生成失败", e);
            return "❌ 图表生成失败：" + e.getMessage();
        }
    }

    /** 把 LLM 传入的 tableData JSON 解析为 ChartData（容忍 markdown 围栏、自动补列名） */
    private ChartData parseTableData(String tableData, String title) {
        String cleaned = tableData.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        JSONObject obj = JSON.parseObject(cleaned);
        if (obj == null) throw new IllegalArgumentException("不是合法的 JSON 对象");
        List<String> columns = obj.getJSONArray("columns") == null
                ? new ArrayList<>() : obj.getJSONArray("columns").toJavaList(String.class);
        List<List<Object>> rows = new ArrayList<>();
        JSONArray arr = obj.getJSONArray("rows");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                JSONArray rowArr = arr.getJSONArray(i); // 每行必须是 JSON 数组
                List<Object> row = new ArrayList<>();
                for (int j = 0; j < rowArr.size(); j++) row.add(rowArr.get(j));
                rows.add(row);
            }
        }
        if (columns.isEmpty() && rows.isEmpty()) {
            throw new IllegalArgumentException("数据为空");
        }
        // 只有行没列名 → 自动补「列1」「列2」…
        if (columns.isEmpty()) {
            int maxCols = rows.stream().mapToInt(List::size).max().orElse(0);
            for (int i = 1; i <= maxCols; i++) columns.add("列" + i);
        }
        String sheetName = (title != null && !title.isBlank()) ? title : "数据";
        return new ChartData(sheetName, columns, rows);
    }

    @Tool("查看已上传 Excel 的数据预览，包括列名、行数和前 10 行数据。帮助用户确认要选哪些列来生成图表。")
    public String previewExcel() {
        String userId = com.demo.util.VoiceContext.getUserId();
        if (userId == null) userId = "default";

        UserFileHolder.FileEntry entry = userFileHolder.get(userId);
        if (entry == null) return "⚠️ 没有找到已上传的文件。如需画图可直接在 generateChart 的 tableData 参数传入数据。";

        List<ChartData> sheets = entry.sheetData();
        if (sheets == null || sheets.isEmpty()) {
            String text = entry.textContent();
            if (text != null && !text.isBlank()) return "📄 文件内容：\n" + text;
            return "⚠️ 文件没有可读内容";
        }

        StringBuilder sb = new StringBuilder("📊 文件：").append(entry.fileName()).append("\n");
        for (ChartData sheet : sheets) {
            sb.append(chartService.preview(sheet)).append("\n");
        }
        return sb.toString();
    }

    public void clear() {
        this.lastChartImage = null;
    }
}
