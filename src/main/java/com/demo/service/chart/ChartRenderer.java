package com.demo.service.chart;

import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.LineAndShapeRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DefaultPieDataset;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * JFreeChart 图表渲染服务。
 * <p>
 * 接收 ChartData → 渲染 → PNG byte[]
 * 纯 Java 内存渲染，零外部依赖。
 */
@Slf4j
@Service
public class ChartRenderer {

    private static final int IMG_WIDTH = 1200;
    private static final int IMG_HEIGHT = 900;
    private static final Font TITLE_FONT = new Font("Microsoft YaHei", Font.BOLD, 24);
    private static final Font LABEL_FONT = new Font("Microsoft YaHei", Font.PLAIN, 14);
    private static final Font AXIS_FONT = new Font("Microsoft YaHei", Font.PLAIN, 13);

    /**
     * 渲染图表为 PNG 字节数组。
     *
     * @param data      Excel 结构化数据
     * @param chartType bar / line / pie
     * @param title     图表标题
     * @param xCol      X轴列名
     * @param yCols     Y轴列名列表
     * @return PNG 字节数组
     */
    public byte[] render(ChartData data, String chartType, String title,
                         String xCol, List<String> yCols) {
        int xIdx = indexOfHeader(data.getHeaders(), xCol);
        if (xIdx < 0) throw new IllegalArgumentException("找不到列: " + xCol);

        List<Integer> yIdxs = yCols.isEmpty()
                ? findNumericColumns(data, xIdx)
                : yCols.stream()
                    .map(c -> indexOfHeader(data.getHeaders(), c))
                    .filter(i -> i >= 0)
                    .toList();

        if (yIdxs.isEmpty()) throw new IllegalArgumentException("没有数值列");

        if (title == null || title.isBlank()) title = data.getSheetName();

        // === 诊断：打印数据 ===
        System.out.println("\n📊 图表数据诊断:");
        System.out.println("  sheet=" + data.getSheetName() + " 行数=" + data.rowCount());
        System.out.println("  表头=" + data.getHeaders());
        System.out.println("  xCol=" + xCol + " xIdx=" + xIdx + " yIdxs=" + yIdxs);
        String[] xVals = data.getStringColumn(xIdx);
        System.out.println("  X轴数据(" + xVals.length + "): " + String.join(",", xVals));
        for (int yi : yIdxs) {
            double[] vals = data.getNumericColumn(yi);
            System.out.println("  Y列[" + data.getHeaders().get(yi) + "](" + vals.length + "): " + java.util.Arrays.toString(vals));
        }

        JFreeChart chart = switch (chartType != null ? chartType : "bar") {
            case "line" -> createLineChart(data, title, xIdx, yIdxs);
            case "pie" -> createPieChart(data, title, xIdx, yIdxs.get(0));
            default -> createBarChart(data, title, xIdx, yIdxs);
        };

        byte[] png = toPng(chart);

        // 保存到本地并打印控制台
        saveAndPrint(png, chartType, title);

        return png;
    }

    // ======================== 图表创建 ========================

    private JFreeChart createBarChart(ChartData data, String title, int xIdx, List<Integer> yIdxs) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        for (int yi : yIdxs) {
            String seriesName = data.getHeaders().get(yi);
            double[] vals = data.getNumericColumn(yi);
            String[] labels = data.getStringColumn(xIdx);
            for (int i = 0; i < vals.length; i++) {
                ds.addValue(vals[i], seriesName, labels[i]);
            }
        }
        JFreeChart chart = ChartFactory.createBarChart(title, "", "数值", ds,
                PlotOrientation.VERTICAL, yIdxs.size() > 1, true, false);
        styleCategoryPlot(chart);
        return chart;
    }

    private JFreeChart createLineChart(ChartData data, String title, int xIdx, List<Integer> yIdxs) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        for (int yi : yIdxs) {
            String seriesName = data.getHeaders().get(yi);
            double[] vals = data.getNumericColumn(yi);
            String[] labels = data.getStringColumn(xIdx);
            for (int i = 0; i < vals.length; i++) {
                ds.addValue(vals[i], seriesName, labels[i]);
            }
        }
        JFreeChart chart = ChartFactory.createLineChart(title, "", "数值", ds,
                PlotOrientation.VERTICAL, yIdxs.size() > 1, true, false);
        styleCategoryPlot(chart);
        // 折线图显示数据点
        CategoryPlot plot = chart.getCategoryPlot();
        if (plot.getRenderer() instanceof LineAndShapeRenderer r) {
            r.setDefaultShapesVisible(true);
            r.setDefaultShapesFilled(true);
        }
        return chart;
    }

    private JFreeChart createPieChart(ChartData data, String title, int nameIdx, int valIdx) {
        DefaultPieDataset ds = new DefaultPieDataset();
        String[] names = data.getStringColumn(nameIdx);
        double[] vals = data.getNumericColumn(valIdx);
        for (int i = 0; i < names.length; i++) {
            ds.setValue(names[i], vals[i]);
        }
        JFreeChart chart = ChartFactory.createPieChart(title, ds, true, true, false);
        PiePlot plot = (PiePlot) chart.getPlot();
        plot.setLabelFont(LABEL_FONT);
        plot.setLabelBackgroundPaint(new Color(255, 255, 255, 200));
        return chart;
    }

    // ======================== 样式 ========================

    private void styleCategoryPlot(JFreeChart chart) {
        chart.setTitle(new TextTitle(chart.getTitle().getText(), TITLE_FONT));
        chart.setBackgroundPaint(Color.WHITE);
        chart.removeLegend(); // 单系列不显示图例

        CategoryPlot plot = chart.getCategoryPlot();
        plot.setBackgroundPaint(new Color(248, 248, 248));
        plot.setDomainGridlinePaint(new Color(220, 220, 220));
        plot.setRangeGridlinePaint(new Color(220, 220, 220));

        // X轴标签
        CategoryAxis domainAxis = plot.getDomainAxis();
        domainAxis.setLabelFont(AXIS_FONT);
        domainAxis.setTickLabelFont(LABEL_FONT);
        domainAxis.setCategoryLabelPositions(CategoryLabelPositions.UP_45);

        // Y轴
        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setLabelFont(AXIS_FONT);
        rangeAxis.setTickLabelFont(LABEL_FONT);

        // 柱状图颜色
        if (plot.getRenderer() instanceof BarRenderer r) {
            r.setDefaultItemLabelsVisible(false);
        }
    }

    // ======================== 输出 ========================

    /** 保存到本地目录并打印控制台链接 */
    private void saveAndPrint(byte[] png, String chartType, String title) {
        try {
            Path dir = Path.of("data/charts");
            Files.createDirectories(dir);
            String fileId = UUID.randomUUID().toString().replace("-", "");
            Path filePath = dir.resolve(fileId + ".png");
            Files.write(filePath, png);

            System.out.println("\n========================================");
            System.out.println("📊 图表已生成！");
            System.out.println("   🔗 本地路径: " + filePath.toAbsolutePath());
            System.out.println("   📁 类型: " + (chartType != null ? chartType : "bar"));
            System.out.println("   📌 标题: " + (title != null ? title : ""));
            System.out.println("   💾 大小: " + png.length / 1024 + "KB");
            System.out.println("========================================\n");
        } catch (Exception e) {
            log.warn("保存图表到本地失败: {}", e.getMessage());
        }
    }

    private byte[] toPng(JFreeChart chart) {
        try {
            BufferedImage img = chart.createBufferedImage(IMG_WIDTH, IMG_HEIGHT);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            byte[] bytes = baos.toByteArray();
            log.info("📊 图表渲染完成: {}×{}, {}KB", IMG_WIDTH, IMG_HEIGHT, bytes.length / 1024);
            return bytes;
        } catch (Exception e) {
            throw new RuntimeException("图表渲染失败: " + e.getMessage());
        }
    }

    // ======================== 工具 ========================

    private int indexOfHeader(List<String> headers, String name) {
        if (name == null) return -1;
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).equalsIgnoreCase(name)) return i;
        }
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).contains(name) || name.contains(headers.get(i))) return i;
        }
        return -1;
    }

    private List<Integer> findNumericColumns(ChartData data, int excludeIdx) {
        for (int i = 0; i < data.getHeaders().size(); i++) {
            if (i != excludeIdx) return List.of(i);
        }
        return List.of();
    }
}
