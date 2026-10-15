package com.demo.watermark.service.impl;

import com.demo.watermark.model.dto.WatermarkAddDTO;
import com.demo.watermark.model.dto.WatermarkRemoveDTO;
import com.demo.watermark.service.ImageWatermarkService;
import com.demo.watermark.util.WatermarkImageUtil;
import com.demo.watermark.util.WatermarkMaskUtil;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;

/**
 * 去水印实现 — 纯 Java2D 局部邻域加权填充（零外部依赖）。
 * <p>
 * 由参考实现 OpenCvWatermarkRemoveImpl 的 Java2D 兜底逻辑提取而来，
 * 不依赖 OpenCV 原生库：按掩码标出的水印区域，用周围保留像素加权平均填充，
 * 边缘做 3x3 均值平滑。效果满足常规半透明/角落水印的淡化需求。
 * </p>
 */
@Slf4j
public class Java2DWatermarkRemoveImpl implements ImageWatermarkService {

    @Override
    public byte[] addWatermark(byte[] imageBytes, WatermarkAddDTO dto) {
        // 去水印实现类不处理加水印
        return null;
    }

    @Override
    public byte[] removeWatermark(byte[] imageBytes, WatermarkRemoveDTO dto) {
        if (imageBytes == null || imageBytes.length == 0) return null;
        try {
            BufferedImage source = WatermarkImageUtil.decode(imageBytes);
            if (source == null) return null;

            int w = source.getWidth();
            int h = source.getHeight();

            String regionType = dto != null ? dto.getRegionType() : "auto";
            String position = dto != null ? dto.getPosition() : null;
            BufferedImage mask = WatermarkMaskUtil.generateMask(w, h, regionType, position);

            BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

            // 复制非掩码区域
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((mask.getRGB(x, y) & 0xFF) < 128) {
                        result.setRGB(x, y, source.getRGB(x, y));
                    }
                }
            }

            // 掩码区域局部邻域加权填充
            int radius = Math.max(5, Math.min(w, h) / 100);
            int maxRadius = radius * 3;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((mask.getRGB(x, y) & 0xFF) > 128) {
                        result.setRGB(x, y, sampleNeighborhood(source, mask, x, y, w, h, radius, maxRadius));
                    }
                }
            }

            smoothEdges(result, mask, w, h);

            WatermarkImageUtil.flush(source);
            WatermarkImageUtil.flush(mask);

            String format = WatermarkImageUtil.detectFormat(imageBytes);
            byte[] output = WatermarkImageUtil.encode(result, format);
            WatermarkImageUtil.flush(result);

            log.info("【去水印】Java2D 邻域填充完成，{}x{}", w, h);
            return output;
        } catch (Exception e) {
            log.error("【去水印】Java2D 异常", e);
            return null;
        }
    }

    /** 取水印区域内像素的周围加权平均值 */
    private int sampleNeighborhood(BufferedImage src, BufferedImage mask,
                                   int cx, int cy, int w, int h,
                                   int radius, int maxRadius) {
        float totalWeight = 0, sumR = 0, sumG = 0, sumB = 0;
        int samples = 0;

        for (int r = radius; r <= maxRadius; r += Math.max(1, r / 2)) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                    if ((mask.getRGB(nx, ny) & 0xFF) < 128) {
                        int rgb = src.getRGB(nx, ny);
                        float dist = (float) Math.sqrt(dx * dx + dy * dy);
                        float weight = 1.0f / (dist * dist + 1);
                        sumR += ((rgb >> 16) & 0xFF) * weight;
                        sumG += ((rgb >> 8) & 0xFF) * weight;
                        sumB += (rgb & 0xFF) * weight;
                        totalWeight += weight;
                        samples++;
                    }
                }
            }
            if (samples >= 8) break;
        }

        if (totalWeight > 0) {
            int r = clamp((int) (sumR / totalWeight));
            int g = clamp((int) (sumG / totalWeight));
            int b = clamp((int) (sumB / totalWeight));
            return (r << 16) | (g << 8) | b;
        }
        return 0x808080;
    }

    /** 水印区域边缘 3x3 均值平滑，淡化过渡带 */
    private void smoothEdges(BufferedImage img, BufferedImage mask, int w, int h) {
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                if ((mask.getRGB(x, y) & 0xFF) > 128) {
                    boolean isEdge = false;
                    outer:
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if ((mask.getRGB(x + dx, y + dy) & 0xFF) < 128) {
                                isEdge = true;
                                break outer;
                            }
                        }
                    }
                    if (isEdge) {
                        int sr = 0, sg = 0, sb = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                int rgb = img.getRGB(x + dx, y + dy);
                                sr += (rgb >> 16) & 0xFF;
                                sg += (rgb >> 8) & 0xFF;
                                sb += rgb & 0xFF;
                            }
                        }
                        img.setRGB(x, y,
                                (clamp(sr / 9) << 16) | (clamp(sg / 9) << 8) | clamp(sb / 9));
                    }
                }
            }
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
