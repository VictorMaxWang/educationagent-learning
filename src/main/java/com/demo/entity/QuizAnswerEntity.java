package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 做题记录实体 — 映射 quiz_answer 表
 * <p>
 * 错题本数据源：查 is_correct=0 的记录即为错题。
 */
@TableName("quiz_answer")
public class QuizAnswerEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long paperId;
    private Long questionId;
    private String userAnswer;
    private Integer isCorrect;      // 0=错误, 1=正确
    private LocalDateTime answeredAt;

    public QuizAnswerEntity() {}

    public QuizAnswerEntity(String userId, Long paperId, Long questionId, String userAnswer, int isCorrect) {
        this.userId = userId;
        this.paperId = paperId;
        this.questionId = questionId;
        this.userAnswer = userAnswer;
        this.isCorrect = isCorrect;
    }

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }

    public String getUserAnswer() { return userAnswer; }
    public void setUserAnswer(String userAnswer) { this.userAnswer = userAnswer; }

    public Integer getIsCorrect() { return isCorrect; }
    public void setIsCorrect(Integer isCorrect) { this.isCorrect = isCorrect; }

    public LocalDateTime getAnsweredAt() { return answeredAt; }
    public void setAnsweredAt(LocalDateTime answeredAt) { this.answeredAt = answeredAt; }
}
