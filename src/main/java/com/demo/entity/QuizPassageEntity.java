package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 刷题材料组实体 — 映射 quiz_passage 表。
 * 一篇听力/一篇阅读 = 一个材料组，下面挂多道题。
 */
@TableName("quiz_passage")
public class QuizPassageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long paperId;
    private Integer passageNo;
    private String passageType;   // listening / reading
    private String title;
    private String content;
    private LocalDateTime createdAt;

    public QuizPassageEntity() {}

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }

    public Integer getPassageNo() { return passageNo; }
    public void setPassageNo(Integer passageNo) { this.passageNo = passageNo; }

    public String getPassageType() { return passageType; }
    public void setPassageType(String passageType) { this.passageType = passageType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
