package com.demo.service.tools;

import com.demo.entity.UserSettingsEntity;
import com.demo.mapper.UserSettingsMapper;
import com.demo.cache.ThreeTierCache;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 音色状态管理（三级缓存：内存 → Redis → MySQL）
 */
@Slf4j
@Service
public class VoiceTypeService {

    public static final String DEFAULT_VOICE_TYPE = "101007";

    @Resource
    private UserSettingsMapper userSettingsMapper;

    @Resource
    private ThreeTierCache<UserSettingsEntity> userSettingsCache;

    public String getVoiceType(String userId) {
        UserSettingsEntity settings = userSettingsCache.get(userId,
                () -> userSettingsMapper.findByUserId(userId));
        if (settings == null) return DEFAULT_VOICE_TYPE;
        String voiceType = settings.getVoiceType();
        return (voiceType != null && !voiceType.isBlank()) ? voiceType : DEFAULT_VOICE_TYPE;
    }

    public void setVoiceType(String userId, String voiceType) {
        UserSettingsEntity settings = getOrCreate(userId);
        settings.setVoiceType(voiceType);
        userSettingsMapper.updateById(settings);       // L3 MySQL
        userSettingsCache.put(userId, settings);        // L1+L2
        log.info("🎙️ [{}] 音色切换至 {}", userId, voiceType);
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
