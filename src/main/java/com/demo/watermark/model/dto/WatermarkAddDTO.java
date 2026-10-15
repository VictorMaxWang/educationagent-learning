package com.demo.watermark.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 加水印请求 DTO。
 * 包含水印文字、样式参数及用户偏好信息。
 */
@Data
@Builder
public class WatermarkAddDTO {

    /** 水印文字内容 */
    private String text;

    /** 水印颜色（#RRGGBB 格式） */
    private String color;

    /** 字号 */
    private int fontSize;

    /** 透明度（0.0=完全透明, 1.0=完全不透明） */
    private float opacity;

    /** 水印位置：top-left / top-right / bottom-left / bottom-right / center / tiled */
    private String position;

    /** 旋转角度（0-360） */
    private double rotation;

    /** 是否平铺水印 */
    private boolean tiled;

    /** 是否隐形水印（LSB隐写） */
    private boolean invisible;

    /** 输出图片压缩质量（0.0-1.0） */
    private float outputQuality;

    /** 用户微信 openId */
    private String userId;

    /**
     * 创建默认水印配置。
     */
    public static WatermarkAddDTO createDefault(String userId) {
        return WatermarkAddDTO.builder()
                .text("Copyright")
                .color("#FFFFFF")
                .fontSize(36)
                .opacity(0.5f)
                .position("bottom-right")
                .rotation(0)
                .tiled(false)
                .outputQuality(0.92f)
                .userId(userId)
                .build();
    }
}
