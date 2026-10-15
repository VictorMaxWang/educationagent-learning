package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 刷题题目实体 — 映射 quiz_question 表
 */
@TableName("quiz_question")
public class QuizQuestionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long paperId;
    private Integer questionNo;
    private String qType;
    private String stem;
    private String options;         // JSON 数组字符串，如 ["A. xx","B. xx"]
    private String answer;          // 标准答案（可空=文件未提供）
    private String explanation;     // 解析（可空）
    private String knowledgePoint;  // 知识点标签（LLM 打标）
    private Integer hasAnswer;      // 0/1
    private Integer passageNo;      // 所属材料组号（听力/阅读），null=独立题
    private LocalDateTime createdAt;

    public QuizQuestionEntity() {}

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }

    public Integer getQuestionNo() { return questionNo; }
    public void setQuestionNo(Integer questionNo) { this.questionNo = questionNo; }

    public String getQType() { return qType; }
    public void setQType(String qType) { this.qType = qType; }

    public String getStem() { return stem; }
    public void setStem(String stem) { this.stem = stem; }

    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public String getKnowledgePoint() { return knowledgePoint; }
    public void setKnowledgePoint(String knowledgePoint) { this.knowledgePoint = knowledgePoint; }

    public Integer getHasAnswer() { return hasAnswer; }
    public void setHasAnswer(Integer hasAnswer) { this.hasAnswer = hasAnswer; }

    public Integer getPassageNo() { return passageNo; }
    public void setPassageNo(Integer passageNo) { this.passageNo = passageNo; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
