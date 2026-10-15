package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * RAG 周度评估调度配置实体 — 映射 rag_eval_schedule 表（单行配置）。
 */
@TableName("rag_eval_schedule")
public class RagEvalScheduleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 定时评估开关 1开/0关 */
    private Integer enabled;
    /** 星期几，逗号分隔，1=周一..7=周日，如 "1" 或 "1,4" */
    private String days;
    /** 评估时刻 HH:mm */
    private String time;
    /** 评估目标集合 */
    private String collection;
    /** 每轮用例数 */
    private Integer sampleSize;
    /** 检索条数，上限 20 */
    private Integer topK;
    private LocalDateTime updatedAt;

    public RagEvalScheduleEntity() {}

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public String getDays() { return days; }
    public void setDays(String days) { this.days = days; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }

    public Integer getSampleSize() { return sampleSize; }
    public void setSampleSize(Integer sampleSize) { this.sampleSize = sampleSize; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
