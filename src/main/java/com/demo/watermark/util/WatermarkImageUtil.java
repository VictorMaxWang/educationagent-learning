package com.demo.watermark.util;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 图片格式转换与流处理工具。
 * <p>
 * 所有处理基于 byte[] 内存流，减少磁盘 IO。
 * 支持 JPG/PNG/BMP/WebP/GIF 主流格式的编解码。
 * </p>
 */
@Slf4j
public final class WatermarkImageUtil {

    private WatermarkImageUtil() {
        // 工具类禁止实例化
    }

    /** 支持的图片格式 */
    public static final String FORMAT_JPG = "JPEG";
    public static final String FORMAT_PNG = "PNG";
    public static final String FORMAT_BMP = "BMP";
    public static final String FORMAT_GIF = "GIF";

    /**
     * 将 byte[] 解码为 BufferedImage。
     *
     * @param imageBytes 图片二进制数据
     * @return BufferedImage，解码失败返回 null
     */
    public static BufferedImage decode(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("【水印图片工具】解码失败：输入数据为空");
            return null;
        }
        try (ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes)) {
            BufferedImage image = ImageIO.read(bis);
            if (image == null) {
                log.warn("【水印图片工具】解码失败：不支持的图片格式，size={}KB", imageBytes.length / 1024);
                return null;
            }
            log.debug("【水印图片工具】解码成功，尺寸={}x{}, size={}KB",
                    image.getWidth(), image.getHeight(), imageBytes.length / 1024);
            return image;
        } catch (IOException e) {
            log.error("【水印图片工具】解码异常", e);
            return null;
        }
    }

    /**
     * 将 BufferedImage 编码为指定格式的 byte[]。
     *
     * @param image  图片对象
     * @param format 输出格式（"JPEG", "PNG", "BMP"）
     * @return 编码后的二进制数据，失败返回 null
     */
    public static byte[] encode(BufferedImage image, String format) {
        if (image == null) {
            log.warn("【水印图片工具】编码失败：图片对象为空");
            return null;
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            // JPEG 不支持 alpha 通道，需转换为 RGB
            BufferedImage toWrite = image;
            if ("JPEG".equalsIgnoreCase(format) && image.getType() == BufferedImage.TYPE_INT_ARGB) {
                toWrite = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                toWrite.createGraphics().drawImage(image, 0, 0, null);
                toWrite.createGraphics().dispose();
            }
            boolean written = ImageIO.write(toWrite, format, bos);
            if (!written) {
                log.error("【水印图片工具】编码失败：无合适的 ImageWriter，format={}", format);
                return null;
            }
            byte[] result = bos.toByteArray();
            if (result.length == 0) {
                log.error("【水印图片工具】编码失败：输出为空，format={}", format);
                return null;
            }
            log.debug("【水印图片工具】编码成功，format={}, size={}KB", format, result.length / 1024);
            return result;
        } catch (IOException e) {
            log.error("【水印图片工具】编码异常，format={}", format, e);
            return null;
        }
    }

    /**
     * 检测图片格式（基于文件头魔数）。
     *
     * @param imageBytes 图片二进制数据
     * @return 格式名称（"JPEG", "PNG", "GIF", "BMP"），无法识别返回 "PNG"
     */
    public static String detectFormat(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length < 4) {
            return FORMAT_PNG;
        }
        int b0 = imageBytes[0] & 0xFF;
        int b1 = imageBytes[1] & 0xFF;
        int b2 = imageBytes[2] & 0xFF;
        int b3 = imageBytes[3] & 0xFF;

        // JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return FORMAT_JPG;
        }
        // PNG: 89 50 4E 47
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
            return FORMAT_PNG;
        }
        // GIF: 47 49 46 38
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) {
            return FORMAT_GIF;
        }
        // BMP: 42 4D
        if (b0 == 0x42 && b1 == 0x4D) {
            return FORMAT_BMP;
        }
        return FORMAT_PNG;
    }

    /**
     * 释放 BufferedImage 内存（主动 flush，辅助 GC）。
     *
     * @param image 要释放的图片对象
     */
    public static void flush(BufferedImage image) {
        if (image != null) {
            image.flush();
            log.debug("【水印图片工具】BufferedImage 已释放");
        }
    }
}
