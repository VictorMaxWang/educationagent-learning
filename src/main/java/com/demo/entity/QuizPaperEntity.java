package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 刷题试卷实体 — 映射 quiz_paper 表
 * <p>
 * 一次上传文件解析出的整卷题目。
 */
@TableName("quiz_paper")
public class QuizPaperEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String title;
    private String sourceFileName;
    private String fileType;
    private Integer questionCount;
    private Integer deleted;    // 0=正常 1=已删除（软删，保留错题记录）
    private LocalDateTime createdAt;

    public QuizPaperEntity() {}

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSourceFileName() { return sourceFileName; }
    public void setSourceFileName(String sourceFileName) { this.sourceFileName = sourceFileName; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public Integer getQuestionCount() { return questionCount; }
    public void setQuestionCount(Integer questionCount) { this.questionCount = questionCount; }

    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
