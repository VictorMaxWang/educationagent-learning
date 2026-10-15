package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 旅行·每日行程关联餐馆实体 — 映射 travel_schedule_rest 表。
 */
@TableName("travel_schedule_rest")
public class TravelScheduleRestEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 每日行程 ID */
    private String scheduleId;
    /** 高德 POI ID */
    private String poiId;
    private String name;
    /** lunch / dinner */
    private String timePeriod;
    private String remark;
    private LocalDateTime createTime;

    public TravelScheduleRestEntity() {}

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String scheduleId) { this.scheduleId = scheduleId; }

    public String getPoiId() { return poiId; }
    public void setPoiId(String poiId) { this.poiId = poiId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTimePeriod() { return timePeriod; }
    public void setTimePeriod(String timePeriod) { this.timePeriod = timePeriod; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
