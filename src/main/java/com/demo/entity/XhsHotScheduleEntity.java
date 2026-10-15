package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 小红书热门视频定时发布配置实体 — 映射 xhs_hot_schedule 表（单行配置）。
 */
@TableName("xhs_hot_schedule")
public class XhsHotScheduleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 定时发布开关 1开/0关 */
    private Integer enabled;
    /** 星期几，逗号分隔，1=周一..7=周日，如 "1,3,5" */
    private String days;
    /** 发布时刻 HH:mm */
    private String time;
    /** 最多取几个热门视频 */
    private Integer maxVideos;
    /** 发布话题标签（空格分隔） */
    private String tags;
    private LocalDateTime updatedAt;

    public XhsHotScheduleEntity() {}

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public String getDays() { return days; }
    public void setDays(String days) { this.days = days; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public Integer getMaxVideos() { return maxVideos; }
    public void setMaxVideos(Integer maxVideos) { this.maxVideos = maxVideos; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
