package com.demo.pet;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 宠物定时推送任务实体 — 映射 pet_schedules 表
 */
@TableName("pet_schedules")
public class PetScheduleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String petName;
    private String timeExpr;
    private String cronExpr;
    private String action;
    private Integer enabled;
    private LocalDateTime createdAt;

    public PetScheduleEntity() {}

    public PetScheduleEntity(String userId, String petName, String timeExpr,
                             String cronExpr, String action) {
        this.userId = userId;
        this.petName = petName;
        this.timeExpr = timeExpr;
        this.cronExpr = cronExpr;
        this.action = action;
        this.enabled = 1;
    }

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPetName() { return petName; }
    public void setPetName(String petName) { this.petName = petName; }

    public String getTimeExpr() { return timeExpr; }
    public void setTimeExpr(String timeExpr) { this.timeExpr = timeExpr; }

    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
