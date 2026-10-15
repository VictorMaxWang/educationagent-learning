package com.demo.service.tools;

import com.demo.entity.UserSettingsEntity;
import com.demo.mapper.UserSettingsMapper;
import com.demo.cache.ThreeTierCache;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对话模式状态管理（三级缓存：内存 → Redis → MySQL）
 * 五种模式：normal / expert / concise / mimic / plain（通俗释义）
 */
@Slf4j
@Service
public class ChatModeService {

    @Resource
    private UserSettingsMapper userSettingsMapper;

    @Resource
    private ThreeTierCache<UserSettingsEntity> userSettingsCache;

    public void setMode(String userId, String mode) {
        UserSettingsEntity settings = getOrCreate(userId);
        settings.setChatMode(mode);
        settings.setMimicSample(null);
        userSettingsMapper.updateById(settings);       // L3 MySQL
        userSettingsCache.put(userId, settings);        // L1+L2
        log.info("💬 [{}] 对话模式切换至：{}", userId, mode);
    }

    public void setMimicMode(String userId, String sampleText) {
        UserSettingsEntity settings = getOrCreate(userId);
        settings.setChatMode("mimic");
        settings.setMimicSample(sampleText);
        userSettingsMapper.updateById(settings);
        userSettingsCache.put(userId, settings);
        log.info("💬 [{}] 模仿模式已设置，样本长度：{} 字", userId, sampleText.length());
    }

    public String getMode(String userId) {
        UserSettingsEntity settings = userSettingsCache.get(userId,
                () -> userSettingsMapper.findByUserId(userId));
        if (settings == null) return "normal";
        String mode = settings.getChatMode();
        return (mode != null && !mode.isBlank()) ? mode : "normal";
    }

    public String getMimicSample(String userId) {
        UserSettingsEntity settings = userSettingsCache.get(userId,
                () -> userSettingsMapper.findByUserId(userId));
        if (settings == null) return null;
        return "mimic".equals(settings.getChatMode()) ? settings.getMimicSample() : null;
    }

    public void reset(String userId) {
        UserSettingsEntity settings = getOrCreate(userId);
        settings.setChatMode("normal");
        settings.setMimicSample(null);
        userSettingsMapper.updateById(settings);
        userSettingsCache.put(userId, settings);
        log.info("💬 [{}] 对话模式已重置为 normal", userId);
    }

    public String getModeInstruction(String userId) {
        String mode = getMode(userId);
        return switch (mode) {
            case "expert" -> "\n\n【当前模式：专家模式】请用专业、深入的风格回答。"
                    + "使用领域术语，引用数据和原理，给出详细分析。\n"
                    + "不要简化，不要回避复杂度。回答越专业越好。";
            case "concise" -> "\n\n【当前模式：精简模式】请用极其简洁的风格回答。"
                    + "控制在 30 字以内，只说结论，不说过程、不解释、不道歉。"
                    + "像电报一样简短。";
            // 通俗释义模式：为"看不懂书面语"的学生设计（乡村场景里这是真问题——教材语言
            // 与学生的日常语言差距很大）。要点写在提示词里而不是靠模型自觉：
            // 先给大白话结论 → 再打比方 → 术语当场解释 → 限定篇幅。
            case "plain" -> "\n\n【当前模式：通俗释义模式】请用中小学生能听懂的话解释。"
                    + "要求：① 先用一句大白话给结论；② 再用一个生活里的比喻或例子说明；"
                    + "③ 出现专业名词时当场用括号解释它是什么意思；"
                    + "④ 不堆术语、不写长句、不加参考文献；⑤ 篇幅 150~300 字。"
                    + "语气像耐心的学长学姐，不要像教科书。";
            case "mimic" -> {
                String sample = getMimicSample(userId);
                if (sample != null) {
                    yield "\n\n【当前模式：模仿模式】请严格模仿以下文本的风格、语气、句式来回答：\n"
                            + "--- 风格样本开始 ---\n" + sample + "\n--- 风格样本结束 ---\n"
                            + "注意：模仿的是表达风格（用词、句式、语气、节奏），而不是回答的内容本身。";
                }
                yield "";
            }
            default -> "";
        };
    }

    private UserSettingsEntity getOrCreate(String userId) {
        UserSettingsEntity settings = userSettingsCache.get(userId,
                () -> userSettingsMapper.findByUserId(userId));
        if (settings != null) return settings;

        UserSettingsEntity entity = new UserSettingsEntity(userId);
        userSettingsMapper.insert(entity);
        userSettingsCache.put(userId, entity);
        return entity;
    }
}
