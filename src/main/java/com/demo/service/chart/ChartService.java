package com.demo.service.chart;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ECharts HTML 拼装服务。
 * <p>
 * 接收 ChartData → 生成 ECharts HTML 字符串 → 供 Playwright 截图。
 */
@Slf4j
@Service
public class ChartService {

    /** 预览数据表格 */
    public String preview(ChartData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 ").append(data.getSheetName()).append("\n");
        sb.append("列：").append(String.join(" | ", data.getHeaders())).append("\n");
        sb.append("行数：").append(data.rowCount()).append("\n");
        for (int i = 0; i < Math.min(data.rowCount(), 10); i++) {
            List<Object> row = data.getRows().get(i);
            sb.append("  ").append(i + 1).append(". ");
            for (int j = 0; j < Math.min(row.size(), data.colCount()); j++) {
                Object v = row.get(j);
                sb.append(data.getHeaders().get(j)).append("=").append(v).append("  ");
            }
            sb.append("\n");
        }
        if (data.rowCount() > 10) sb.append("  ... 共 ").append(data.rowCount()).append(" 行\n");
        return sb.toString();
    }

    /**
     * 生成 ECharts HTML 字符串（供 Playwright 截图使用）。
     */
    public String buildHtml(ChartData data, String chartType, String title,
                            String xCol, List<String> yCols) {
        int xIdx = indexOfHeader(data.getHeaders(), xCol);
        if (xIdx < 0) return "<html><body>❌ 找不到列：" + xCol + "</body></html>";

        List<Integer> yIdxs = yCols.stream()
                .map(c -> indexOfHeader(data.getHeaders(), c))
                .filter(i -> i >= 0)
                .toList();
        if (yIdxs.isEmpty()) {
            for (int i = 0; i < data.getHeaders().size(); i++) {
                if (i != xIdx) { yIdxs = List.of(i); break; }
            }
        }
        if (yIdxs.isEmpty()) return "<html><body>❌ 没有数值列</body></html>";

        if (title == null || title.isBlank()) title = data.getSheetName();

        return switch (chartType != null ? chartType : "bar") {
            case "line" -> buildLineHtml(data, title, xIdx, yIdxs);
            case "pie" -> buildPieHtml(data, title, xIdx, yIdxs.get(0));
            default -> buildBarHtml(data, title, xIdx, yIdxs);
        };
    }

    // ======================== HTML 构建 ========================

    private String buildBarHtml(ChartData data, String title, int xIdx, List<Integer> yIdxs) {
        String[] xData = data.getStringColumn(xIdx);
        String series = yIdxs.stream()
                .map(i -> {
                    String name = data.getHeaders().get(i);
                    double[] vals = data.getNumericColumn(i);
                    return "{name:\"" + escape(name) + "\",type:\"bar\",data:[" + join(vals) + "]}";
                })
                .collect(Collectors.joining(","));
        return wrapHtml(title, "category", xData, series, null);
    }

    private String buildLineHtml(ChartData data, String title, int xIdx, List<Integer> yIdxs) {
        String[] xData = data.getStringColumn(xIdx);
        String series = yIdxs.stream()
                .map(i -> {
                    String name = data.getHeaders().get(i);
                    double[] vals = data.getNumericColumn(i);
                    return "{name:\"" + escape(name) + "\",type:\"line\",data:[" + join(vals) + "]}";
                })
                .collect(Collectors.joining(","));
        return wrapHtml(title, "category", xData, series, null);
    }

    private String buildPieHtml(ChartData data, String title, int nameIdx, int valIdx) {
        String[] names = data.getStringColumn(nameIdx);
        double[] vals = data.getNumericColumn(valIdx);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{name:\"").append(escape(names[i])).append("\",value:").append(vals[i]).append("}");
        }
        sb.append("]");
        return wrapHtml(title, "pie", null, null, sb.toString());
    }

    private String wrapHtml(String title, String xType, String[] xData, String series, String pieData) {
        String xAxisJson = xData != null
                ? "xAxis:{type:'" + xType + "',data:" + JSON.toJSONString(xData) + "},"
                : "";
        String seriesJson = pieData != null
                ? "type:'pie',data:" + pieData
                : series;
        String legend = pieData != null ? "" : "legend:{data:[" + extractSeriesNames(series) + "]},";

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
<title>%s</title>
<style>
*{margin:0;padding:0}html,body{width:100%%;height:100%%;background:#fff}
#c{width:100%%;height:100%%}
#error{display:none;padding:40px 20px;text-align:center;color:#999;font-size:14px}
</style>
</head>
<body>
<div id="c"></div>
<div id="error">图表加载中...</div>
<script>
(function(){
    var c, timer;
    function init(){
        c=echarts.init(document.getElementById('c'));
        c.setOption({
            title:{text:'%s',left:'center',textStyle:{fontSize:16}},
            tooltip:{trigger:'axis'},
            grid:{left:'10%%',right:'10%%',bottom:'15%%',top:'20%%'},
            %s
            %s
            series:[{%s}]
        });
        c.resize();
        document.getElementById('error').style.display='none';
    }
    function loadECharts(){
        var s=document.createElement('script');
        s.src='https://cdn.bootcdn.net/ajax/libs/echarts/5.5.0/echarts.min.js';
        s.onload=init;
        s.onerror=function(){
            var s2=document.createElement('script');
            s2.src='https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js';
            s2.onload=init;
            s2.onerror=function(){
                document.getElementById('error').textContent='⚠️ 加载失败';
                document.getElementById('error').style.display='block';
            };
            document.head.appendChild(s2);
        };
        document.head.appendChild(s);
    }
    loadECharts();
    window.addEventListener('resize',function(){
        clearTimeout(timer);
        timer=setTimeout(function(){if(c)c.resize();},200);
    });
})();
</script>
</body>
</html>""".formatted(
                escape(title), escape(title),
                legend,
                xAxisJson,
                seriesJson
        );
    }

    // ======================== 工具 ========================

    private int indexOfHeader(List<String> headers, String name) {
        if (name == null) return -1;
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).equals(name)) return i;
        }
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).contains(name) || name.contains(headers.get(i))) return i;
        }
        return -1;
    }

    private static String join(double[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append((long) arr[i] == arr[i] ? String.valueOf((long) arr[i]) : String.valueOf(arr[i]));
        }
        return sb.toString();
    }

    private static String extractSeriesNames(String seriesJson) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"name\":\"([^\"]+)\"").matcher(seriesJson);
        while (m.find()) {
            if (sb.length() > 0) sb.append(",");
            sb.append("\"").append(m.group(1)).append("\"");
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
