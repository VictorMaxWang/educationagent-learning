package com.demo.service.chart;

import java.util.List;

/**
 * Excel 解析后的结构化数据集。
 * <p>
 * 每个实例对应一个 sheet，包含表头和数据行。
 */
public class ChartData {

    private final String sheetName;
    private final List<String> headers;
    private final List<List<Object>> rows;

    public ChartData(String sheetName, List<String> headers, List<List<Object>> rows) {
        this.sheetName = sheetName;
        this.headers = headers;
        this.rows = rows;
    }

    public String getSheetName() { return sheetName; }
    public List<String> getHeaders() { return headers; }
    public List<List<Object>> getRows() { return rows; }

    public int rowCount() { return rows.size(); }
    public int colCount() { return headers.size(); }

    /** 将指定列转为数值数组（用于 ECharts series） */
    public double[] getNumericColumn(int colIndex) {
        return rows.stream()
                .mapToDouble(row -> {
                    Object v = colIndex < row.size() ? row.get(colIndex) : null;
                    if (v instanceof Number n) return n.doubleValue();
                    if (v instanceof String s) {
                        try { return Double.parseDouble(s.replaceAll("[^\\d.\\-]", "")); }
                        catch (NumberFormatException e) { return 0; }
                    }
                    return 0;
                })
                .toArray();
    }

    /** 将指定列转为字符串数组（用于 ECharts xAxis） */
    public String[] getStringColumn(int colIndex) {
        return rows.stream()
                .map(row -> {
                    Object v = colIndex < row.size() ? row.get(colIndex) : "";
                    return v != null ? v.toString() : "";
                })
                .toArray(String[]::new);
    }
}
