package com.demo.watermark.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 去水印请求 DTO。
 * 包含水印区域信息及降级策略参数。
 */
@Data
@Builder
public class WatermarkRemoveDTO {

    /** 水印区域类型：corner=角落水印, tiled=平铺半透明水印, auto=自动识别 */
    private String regionType;

    /** 水印位置（corner 模式）：top-left / top-right / bottom-left / bottom-right */
    private String position;

    /** 用户微信 openId */
    private String userId;

    /** 是否启用 AI 修复（主方案） */
    private boolean aiEnabled;

    /** 是否启用 OpenCV 离线降级 */
    private boolean opencvFallbackEnabled;

    /**
     * 创建默认去水印配置（自动识别 + AI优先 + OpenCV降级）。
     */
    public static WatermarkRemoveDTO createDefault(String userId) {
        return WatermarkRemoveDTO.builder()
                .regionType("auto")
                .position(null)
                .userId(userId)
                .aiEnabled(true)
                .opencvFallbackEnabled(true)
                .build();
    }
}
