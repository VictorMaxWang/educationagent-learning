package com.demo.pet;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宠物档案实体 — 映射 pets 表
 */
@TableName("pets")
public class PetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String petName;
    private String species;
    private String breed;
    private String gender;
    private LocalDate birthDate;
    private Double weightKg;
    private String photoUrl;
    private String illnesses;
    private String features;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public PetEntity() {}

    public PetEntity(String userId, String petName) {
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

    public String getSpecies() { return species; }
    public void setSpecies(String species) { this.species = species; }

    public String getBreed() { return breed; }
    public void setBreed(String breed) { this.breed = breed; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }

    public Double getWeightKg() { return weightKg; }
    public void setWeightKg(Double weightKg) { this.weightKg = weightKg; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public String getIllnesses() { return illnesses; }
    public void setIllnesses(String illnesses) { this.illnesses = illnesses; }

    public String getFeatures() { return features; }
    public void setFeatures(String features) { this.features = features; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
