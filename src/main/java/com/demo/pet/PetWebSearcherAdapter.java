package com.demo.pet;

import com.demo.service.tools.WebSearchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 宠物诊断联网搜索 SPI 实现 — 对接现有百度千帆网页搜索服务。
 * 由 PetDiagnoseService 通过 ObjectProvider 自动注入，未配置时诊断跳过搜索。
 */
@Slf4j
@Component
public class PetWebSearcherAdapter implements PetDiagnoseService.PetWebSearcher {

    @Resource
    private WebSearchService webSearchService;

    @Override
    public String search(String query, int count) {
        try {
            return webSearchService.search(query, count);
        } catch (Exception e) {
            log.warn("🐾 宠物诊断联网搜索失败: {}", e.getMessage());
            return "（联网搜索失败: " + e.getMessage() + "）";
        }
    }
}
