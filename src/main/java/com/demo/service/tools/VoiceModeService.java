package com.demo.service.tools;

import com.demo.entity.UserSettingsEntity;
import com.demo.mapper.UserSettingsMapper;
import com.demo.cache.ThreeTierCache;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 语音模式状态管理（三级缓存：内存 → Redis → MySQL）
 * 优先级：单次语音指令 > 全局语音模式 > 默认文字回复
 * 单次语音标记存 L1 内存，全局模式走三级缓存
 */
@Slf4j
@Service
public class VoiceModeService {

    @Resource
    private UserSettingsMapper userSettingsMapper;

    @Resource
    private ThreeTierCache<UserSettingsEntity> userSettingsCache;

    /** 单次语音标记（一次性消费，不持久化） */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> singleFlags = new java.util.concurrent.ConcurrentHashMap<>();

    public void enableGlobal(String userId) {
        UserSettingsEntity settings = getOrCreate(userId);
        settings.setVoiceMode("global");
        userSettingsMapper.updateById(settings);      // L3 MySQL
        userSettingsCache.put(userId, settings);       // L1+L2
        log.info("🔊 [{}] 全局语音模式 ON", userId);
    }

    public void setSingle(String userId) {
        singleFlags.put(userId, true);
        log.info("🔊 [{}] 单次语音标记", userId);
    }

    public void disable(String userId) {
        UserSettingsEntity settings = getOrCreate(userId);
        settings.setVoiceMode("off");
        userSettingsMapper.updateById(settings);
        userSettingsCache.put(userId, settings);
        log.info("🔊 [{}] 语音模式 OFF", userId);
    }

    /**
     * 判断是否发语音：单次标记 > L1内存 > L2 Redis > L3 MySQL
     */
    public boolean shouldSendVoice(String userId) {
        if (Boolean.TRUE.equals(singleFlags.remove(userId))) {
            return true;
        }
        return isGlobalEnabled(userId);
    }

    /** 全局语音模式是否开启（供桌面端听筒开关读取，不触发单次标记） */
    public boolean isGlobalEnabled(String userId) {
        UserSettingsEntity settings = userSettingsCache.get(userId,
                () -> userSettingsMapper.findByUserId(userId));
        return settings != null && "global".equals(settings.getVoiceMode());
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
