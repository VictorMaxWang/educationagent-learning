package com.demo.watermark;

import com.demo.watermark.model.dto.WatermarkAddDTO;
import com.demo.watermark.model.dto.WatermarkRemoveDTO;
import com.demo.watermark.service.ImageWatermarkService;
import com.demo.watermark.service.impl.Java2DWatermarkRemoveImpl;
import com.demo.watermark.service.impl.LocalImageWatermarkAddImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 图片水印门面服务（桌面端）。
 * <ul>
 *   <li>加水印：纯 Java Graphics2D（文字/颜色/字号/透明度/位置/平铺/旋转）</li>
 *   <li>去水印：纯 Java2D 局部邻域加权填充（零外部依赖，参考实现已去掉 OpenCV 依赖）</li>
 * </ul>
 * 全部基于 byte[] 内存流，不落盘、不改原图。
 */
@Slf4j
@Service
public class WatermarkService {

    private final ImageWatermarkService addService;
    private final ImageWatermarkService removeService;

    public WatermarkService() {
        // 默认参数（前端 DTO 可覆盖；空值回退默认）
        this.addService = new LocalImageWatermarkAddImpl(
                "Copyright", "#FFFFFF", 36, 0.5f,
                "bottom-right", 0, false, 0.92f);
        this.removeService = new Java2DWatermarkRemoveImpl();
        log.info("💧 水印服务就绪：加水印(Graphics2D) + 去水印(Java2D 邻域填充)");
    }

    /** 加水印：成功返回新图片二进制，失败返回 null */
    public byte[] addWatermark(byte[] imageBytes, WatermarkAddDTO dto) {
        return addService.addWatermark(imageBytes, dto);
    }

    /** 去水印：成功返回新图片二进制，失败返回 null */
    public byte[] removeWatermark(byte[] imageBytes, WatermarkRemoveDTO dto) {
        return removeService.removeWatermark(imageBytes, dto);
    }
}
