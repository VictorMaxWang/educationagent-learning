package com.demo.watermark.util;

import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * 水印掩码生成工具。
 * <p>
 * 用于去水印场景：根据用户指定的水印区域生成二值掩码图（白色=水印区域，黑色=保留区域），
 * 作为 AI 图像修复 / OpenCV INPAINT 的输入。
 * </p>
 */
@Slf4j
public final class WatermarkMaskUtil {

    private WatermarkMaskUtil() {
        // 工具类禁止实例化
    }

    /** 角落水印估算占比（保守估计，只覆盖文字水印区域） */
    private static final double CORNER_RATIO_WIDTH = 0.12;
    private static final double CORNER_RATIO_HEIGHT = 0.06;

    /** 边缘 margin（水印通常不会紧贴边缘，留一点边距） */
    private static final int EDGE_MARGIN_X = 15;
    private static final int EDGE_MARGIN_Y = 10;

    /**
     * 生成水印区域掩码图。
     * <p>
     * 掩码为与输入图片相同尺寸的二值图：
     * {@code Color.WHITE} = 水印区域（待修复），{@code Color.BLACK} = 保留区域。
     * </p>
     *
     * @param imageWidth  图片宽度
     * @param imageHeight 图片高度
     * @param regionType  区域类型：corner / tiled / auto
     * @param position    水印位置（corner 模式）：top-left / top-right / bottom-left / bottom-right
     * @return 掩码 BufferedImage，白色区域为需要修复的水印区
     */
    public static BufferedImage generateMask(int imageWidth, int imageHeight,
                                              String regionType, String position) {
        BufferedImage mask = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = mask.createGraphics();

        // 默认全黑（保留全部）
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, imageWidth, imageHeight);

        if ("corner".equalsIgnoreCase(regionType) || "auto".equalsIgnoreCase(regionType)) {
            drawCornerMask(g2d, imageWidth, imageHeight, position);
        } else if ("tiled".equalsIgnoreCase(regionType)) {
            drawTiledMask(g2d, imageWidth, imageHeight);
        }

        g2d.dispose();
        log.debug("【水印掩码】掩码已生成，尺寸={}x{}, regionType={}, position={}",
                imageWidth, imageHeight, regionType, position);
        return mask;
    }

    /**
     * 绘制角落水印掩码（白色矩形区域）。
     */
    private static void drawCornerMask(Graphics2D g2d, int width, int height, String position) {
        int maskW = Math.max(60, (int) (width * CORNER_RATIO_WIDTH));
        int maskH = Math.max(20, (int) (height * CORNER_RATIO_HEIGHT));

        String pos = (position != null) ? position.toLowerCase() : "bottom-right";
        int x, y;
        switch (pos) {
            case "top-left":
                x = EDGE_MARGIN_X;
                y = EDGE_MARGIN_Y;
                break;
            case "top-right":
                x = width - maskW - EDGE_MARGIN_X;
                y = EDGE_MARGIN_Y;
                break;
            case "bottom-left":
                x = EDGE_MARGIN_X;
                y = height - maskH - EDGE_MARGIN_Y;
                break;
            case "bottom-right":
            default:
                x = width - maskW - EDGE_MARGIN_X;
                y = height - maskH - EDGE_MARGIN_Y;
                break;
        }

        // 用半透明渐变掩码替代实心白块：从中心向外渐变，只重点修复水印核心区
        g2d.setColor(Color.WHITE);
        g2d.fillRect(x, y, maskW, maskH);
    }

    /**
     * 绘制平铺半透明水印掩码（网格状白色条纹，模拟平铺水印分布）。
     */
    private static void drawTiledMask(Graphics2D g2d, int width, int height) {
        // 平铺水印：使用小掩码块分布，覆盖可能的文字位置
        int cols = 5;
        int rows = 5;
        int cellW = width / cols;
        int cellH = height / rows;
        g2d.setColor(Color.WHITE);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int cx = col * cellW + cellW / 3;
                int cy = row * cellH + cellH / 3;
                int cw = cellW / 3;
                int ch = Math.max(12, cellH / 6); // 文字水印通常是窄条
                g2d.fillRect(cx, cy, cw, ch);
            }
        }
    }
}
