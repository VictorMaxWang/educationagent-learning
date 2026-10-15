package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 小红书黑评去重实体 — 映射 xhs_black_comments 表。
 * 记录已提醒过的黑评 comment_id，避免重复发邮件。
 */
@TableName("xhs_black_comments")
public class XhsBlackCommentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 笔记 ID */
    private String noteId;
    /** 评论 ID（唯一去重键） */
    private String commentId;
    /** 评论内容 */
    private String content;
    private LocalDateTime reportedAt;

    public XhsBlackCommentEntity() {}

    public XhsBlackCommentEntity(String noteId, String commentId, String content) {
        this.noteId = noteId;
        this.commentId = commentId;
        this.content = content;
    }

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNoteId() { return noteId; }
    public void setNoteId(String noteId) { this.noteId = noteId; }

    public String getCommentId() { return commentId; }
    public void setCommentId(String commentId) { this.commentId = commentId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getReportedAt() { return reportedAt; }
    public void setReportedAt(LocalDateTime reportedAt) { this.reportedAt = reportedAt; }
}
