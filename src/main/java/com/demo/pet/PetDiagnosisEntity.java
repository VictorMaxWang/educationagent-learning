package com.demo.pet;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 宠物智能诊断记录实体 — 映射 pet_diagnoses 表
 */
@TableName("pet_diagnoses")
public class PetDiagnosisEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String petName;
    private String imageUrl;
    private String concern;
    private String aiRawText;
    private String diagnosis;
    private String suggestions;
    private String keywords;
    private LocalDateTime createdAt;

    public PetDiagnosisEntity() {}

    public PetDiagnosisEntity(String userId, String petName) {
        this.userId = userId;
        this.petName = petName;
    }

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPetName() { return petName; }
    public void setPetName(String petName) { this.petName = petName; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getConcern() { return concern; }
    public void setConcern(String concern) { this.concern = concern; }

    public String getAiRawText() { return aiRawText; }
    public void setAiRawText(String aiRawText) { this.aiRawText = aiRawText; }

    public String getDiagnosis() { return diagnosis; }
    public void setDiagnosis(String diagnosis) { this.diagnosis = diagnosis; }

    public String getSuggestions() { return suggestions; }
    public void setSuggestions(String suggestions) { this.suggestions = suggestions; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
