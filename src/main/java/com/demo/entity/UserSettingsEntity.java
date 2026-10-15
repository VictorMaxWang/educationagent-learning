package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户设置实体 — 映射 user_settings 表
 * 合并替代 VoiceModeService / VoiceTypeService / ChatModeService 的 ConcurrentHashMap
 */
@TableName("user_settings")
public class UserSettingsEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String voiceMode;
    private String voiceType;
    private String chatMode;
    private String mimicSample;
    /** 日报推送开关：true=启用, false=关闭 */
    private Boolean reportEnabled;
    /** 日报推送时间 HH:mm，如 "08:00" */
    private String reportTime;
    /** 日报天气城市 */
    private String reportCity;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public UserSettingsEntity() {}

    public UserSettingsEntity(String userId) {
        this.userId = userId;
        this.voiceMode = "off";
        this.voiceType = "101007";
        this.chatMode = "normal";
    }

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getVoiceMode() { return voiceMode; }
    public void setVoiceMode(String voiceMode) { this.voiceMode = voiceMode; }

    public String getVoiceType() { return voiceType; }
    public void setVoiceType(String voiceType) { this.voiceType = voiceType; }

    public String getChatMode() { return chatMode; }
    public void setChatMode(String chatMode) { this.chatMode = chatMode; }

    public String getMimicSample() { return mimicSample; }
    public void setMimicSample(String mimicSample) { this.mimicSample = mimicSample; }

    public Boolean getReportEnabled() { return reportEnabled; }
    public void setReportEnabled(Boolean reportEnabled) { this.reportEnabled = reportEnabled; }

    public String getReportTime() { return reportTime; }
    public void setReportTime(String reportTime) { this.reportTime = reportTime; }

    public String getReportCity() { return reportCity; }
    public void setReportCity(String reportCity) { this.reportCity = reportCity; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
