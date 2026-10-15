package com.demo.pet;

import com.demo.service.preprocessing.VisionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 宠物图片识别 SPI 实现 — 对接现有阿里云 Qwen-VL 视觉识别服务。
 * 由 PetDiagnoseService 通过 ObjectProvider 自动注入，未配置时诊断自动降级。
 */
@Slf4j
@Component
@Primary
public class PetImageRecognizerAdapter implements PetDiagnoseService.PetImageRecognizer {

    @Resource
    private VisionService visionService;

    @Override
    public String describe(byte[] imageBytes) {
        try {
            return visionService.describe(imageBytes);
        } catch (Exception e) {
            log.warn("🐾 宠物图片识别失败: {}", e.getMessage());
            return null;
        }
    }
}
