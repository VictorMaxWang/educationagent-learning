package com.demo.watermark.service.impl;

import com.demo.watermark.model.dto.WatermarkAddDTO;
import com.demo.watermark.service.ImageWatermarkService;
import com.demo.watermark.model.dto.WatermarkRemoveDTO;
import com.demo.watermark.util.WatermarkImageUtil;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * 本地图片加水印实现。
 * <p>
 * 通过 Graphics2D + AlphaComposite 做像素混合绘制水印图层，
 * 支持文字水印、全屏平铺水印、角落固定水印、自定义透明度/字号/颜色/旋转角度。
 * 不修改原图，输出全新像素矩阵。
 * </p>
 */
@Slf4j
public class LocalImageWatermarkAddImpl implements ImageWatermarkService {

    private final String defaultText;
    private final String defaultColor;
    private final int defaultFontSize;
    private final float defaultOpacity;
    private final String defaultPosition;
    private final double defaultRotation;
    private final boolean defaultTiled;
    private final float outputQuality;

    public LocalImageWatermarkAddImpl(String defaultText, String defaultColor,
                                       int defaultFontSize, float defaultOpacity,
                                       String defaultPosition, double defaultRotation,
                                       boolean defaultTiled, float outputQuality) {
        this.defaultText = defaultText;
        this.defaultColor = defaultColor;
        this.defaultFontSize = defaultFontSize;
        this.defaultOpacity = defaultOpacity;
        this.defaultPosition = defaultPosition;
        this.defaultRotation = defaultRotation;
        this.defaultTiled = defaultTiled;
        this.outputQuality = outputQuality;
    }

    @Override
    public byte[] addWatermark(byte[] imageBytes, WatermarkAddDTO dto) {
        // 解码图片
        BufferedImage original = WatermarkImageUtil.decode(imageBytes);
        if (original == null) {
            log.error("【加水印】图片解码失败，无法添加水印");
            return null;
        }

        try {
            // 合并默认参数与用户参数
            String text = (dto != null && dto.getText() != null && !dto.getText().isBlank())
                    ? dto.getText() : defaultText;
            Color color = parseColor(dto != null ? dto.getColor() : null);
            int fontSize = (dto != null && dto.getFontSize() > 0) ? dto.getFontSize() : defaultFontSize;
            float opacity = (dto != null && dto.getOpacity() > 0) ? dto.getOpacity() : defaultOpacity;
            String position = (dto != null && dto.getPosition() != null) ? dto.getPosition() : defaultPosition;
            double rotation = (dto != null && dto.getRotation() != 0) ? dto.getRotation() : defaultRotation;
            boolean tiled = (dto != null && dto.isTiled()) || defaultTiled;

            // 创建新图层（带透明通道）
            BufferedImage watermarked = new BufferedImage(
                    original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = watermarked.createGraphics();

            // 启用抗锯齿
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // 先绘制原图
            g2d.drawImage(original, 0, 0, null);

            // 设置水印透明度
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
            g2d.setColor(color);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, fontSize));

            if (tiled) {
                drawTiledWatermark(g2d, text, fontSize, rotation, original.getWidth(), original.getHeight());
            } else {
                drawSingleWatermark(g2d, text, fontSize, rotation, position,
                        original.getWidth(), original.getHeight());
            }

            g2d.dispose();

            // 编码输出
            String format = WatermarkImageUtil.detectFormat(imageBytes);
            byte[] result = WatermarkImageUtil.encode(watermarked, format);

            // 释放内存
            WatermarkImageUtil.flush(original);
            WatermarkImageUtil.flush(watermarked);

            log.info("【加水印】水印添加成功，text={}, position={}, tiled={}, size={}x{}",
                    text, position, tiled, original.getWidth(), original.getHeight());
            return result;

        } catch (Exception e) {
            log.error("【加水印】添加水印异常", e);
            return null;
        } finally {
            WatermarkImageUtil.flush(original);
        }
    }

    @Override
    public byte[] removeWatermark(byte[] imageBytes, WatermarkRemoveDTO dto) {
        // 加水印实现类不处理去水印，由专门的 remove 实现类处理
        log.warn("【加水印】LocalImageWatermarkAddImpl 不支持去水印操作");
        return null;
    }

    // ── 水印绘制方法 ────────────────────────────────────────

    /**
     * 绘制单次水印（角落/居中）。
     */
    private void drawSingleWatermark(Graphics2D g2d, String text, int fontSize,
                                      double rotation, String position, int imgW, int imgH) {
        Font font = new Font("SansSerif", Font.PLAIN, fontSize);
        FontRenderContext frc = g2d.getFontRenderContext();
        Rectangle2D textBounds = font.getStringBounds(text, frc);

        int textW = (int) textBounds.getWidth();
        int textH = (int) textBounds.getHeight();
        int margin = Math.max(imgW, imgH) / 30; // 动态边距

        // 计算水印位置
        int x, y;
        switch (position != null ? position.toLowerCase() : "bottom-right") {
            case "top-left":
                x = margin;
                y = margin + textH;
                break;
            case "top-right":
                x = imgW - textW - margin;
                y = margin + textH;
                break;
            case "bottom-left":
                x = margin;
                y = imgH - margin;
                break;
            case "center":
                x = (imgW - textW) / 2;
                y = (imgH + textH) / 2;
                break;
            case "bottom-right":
            default:
                x = imgW - textW - margin;
                y = imgH - margin;
                break;
        }

        // 旋转处理
        if (rotation != 0) {
            AffineTransform originalTransform = g2d.getTransform();
            // 以文字中心为旋转点
            int cx = x + textW / 2;
            int cy = y - textH / 2;
            g2d.rotate(Math.toRadians(rotation), cx, cy);
            g2d.drawString(text, x, y);
            g2d.setTransform(originalTransform);
        } else {
            g2d.drawString(text, x, y);
        }

        log.debug("【加水印】单次水印位置={}, x={}, y={}, rotation={}°", position, x, y, rotation);
    }

    /**
     * 绘制平铺水印（全屏网格状重复）。
     */
    private void drawTiledWatermark(Graphics2D g2d, String text, int fontSize,
                                     double rotation, int imgW, int imgH) {
        Font font = new Font("SansSerif", Font.PLAIN, fontSize);
        FontRenderContext frc = g2d.getFontRenderContext();
        Rectangle2D textBounds = font.getStringBounds(text, frc);

        int textW = (int) textBounds.getWidth() + 40;
        int textH = (int) textBounds.getHeight() + 40;

        // 网格平铺
        int cols = Math.max(1, imgW / textW);
        int rows = Math.max(1, imgH / textH);
        int cellW = imgW / cols;
        int cellH = imgH / rows;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int x = col * cellW + cellW / 2 - (int) textBounds.getWidth() / 2;
                int y = row * cellH + cellH / 2 + (int) textBounds.getHeight() / 3;

                if (rotation != 0) {
                    AffineTransform originalTransform = g2d.getTransform();
                    int cx = x + (int) textBounds.getWidth() / 2;
                    int cy = y - (int) textBounds.getHeight() / 3;
                    g2d.rotate(Math.toRadians(rotation), cx, cy);
                    g2d.drawString(text, x, y);
                    g2d.setTransform(originalTransform);
                } else {
                    g2d.drawString(text, x, y);
                }
            }
        }
    }

    /**
     * 解析颜色字符串（支持 #RRGGBB / #AARRGGBB 和 Color 常量名）。
     */
    private Color parseColor(String colorStr) {
        if (colorStr == null || colorStr.isBlank()) {
            return Color.decode(defaultColor);
        }
        try {
            String trimmed = colorStr.trim();
            if (trimmed.startsWith("#")) {
                // 处理 #RRGGBB 或 #AARRGGBB
                String hex = trimmed.substring(1);
                if (hex.length() == 6) {
                    return new Color(
                            Integer.parseInt(hex.substring(0, 2), 16),
                            Integer.parseInt(hex.substring(2, 4), 16),
                            Integer.parseInt(hex.substring(4, 6), 16));
                } else if (hex.length() == 8) {
                    return new Color(
                            Integer.parseInt(hex.substring(2, 4), 16),
                            Integer.parseInt(hex.substring(4, 6), 16),
                            Integer.parseInt(hex.substring(6, 8), 16),
                            Integer.parseInt(hex.substring(0, 2), 16));
                }
            }
            return Color.decode(trimmed);
        } catch (Exception e) {
            log.warn("【加水印】颜色解析失败：{}，使用默认颜色", colorStr);
            return Color.decode(defaultColor);
        }
    }
}
