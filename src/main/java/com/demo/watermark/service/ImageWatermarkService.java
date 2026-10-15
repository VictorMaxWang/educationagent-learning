package com.demo.watermark.service;

import com.demo.watermark.model.dto.WatermarkAddDTO;
import com.demo.watermark.model.dto.WatermarkRemoveDTO;

/**
 * 图片水印统一门面接口。
 * <p>
 * 提供加水印和去水印两大核心能力。
 * 所有方法基于 byte[] 内存流处理，不修改原始图片二进制。
 * </p>
 */
public interface ImageWatermarkService {

    /**
     * 为图片添加水印。
     * <p>
     * 读取图片二进制 → 解码为 BufferedImage → Graphics2D 像素绘制水印图层 →
     * 生成全新像素矩阵 → 编码为新图片二进制。不修改原图。
     * </p>
     *
     * @param imageBytes 原始图片二进制数据
     * @param dto        水印参数配置
     * @return 加水印后的新图片二进制数据，失败返回 null
     */
    byte[] addWatermark(byte[] imageBytes, WatermarkAddDTO dto);

    /**
     * 服务是否可用（可用于判断是否跳过该策略）。
     * 默认返回 true，不可用的实现类（如 NoOp 占位）应返回 false。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 为图片去除水印。
     * <p>
     * 双方案降级策略：
     * 1. 主方案：DashScope 通义万相图像修复（优先执行）
     * 2. 降级方案：OpenCV INPAINT_TELEA 算法（网络异常/限流/超时自动切换）
     * </p>
     *
     * @param imageBytes 带水印的图片二进制数据
     * @param dto        去水印参数配置
     * @return 去水印后的新图片二进制数据，失败返回 null
     */
    byte[] removeWatermark(byte[] imageBytes, WatermarkRemoveDTO dto);
}
