package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 英语词库实体 — 映射 english_words 表。
 * 墨墨背单词联动：AI 生成 / 有道查词 / 本地复习状态。
 */
@TableName("english_words")
public class EnglishWordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String word;
    private String phonetic;
    private String meaning;
    private String partOfSpeech;
    private String example;
    private String status;      // new / mastered / weak / forgotten
    private String bookType;    // cet4 / cet6 / kaoyan / ielts
    private String source;      // ai / youdao / local
    private Integer inNotebook; // 生词本：0/1
    private Integer reviewLevel; // 艾宾浩斯复习等级 0-7
    private Long nextReviewAt;   // 下次复习时间（epoch 毫秒）
    private Long lastReviewedAt; // 上次复习时间（epoch 毫秒）
    private LocalDateTime createdAt;

    public EnglishWordEntity() {}

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }

    public String getPhonetic() { return phonetic; }
    public void setPhonetic(String phonetic) { this.phonetic = phonetic; }

    public String getMeaning() { return meaning; }
    public void setMeaning(String meaning) { this.meaning = meaning; }

    public String getPartOfSpeech() { return partOfSpeech; }
    public void setPartOfSpeech(String partOfSpeech) { this.partOfSpeech = partOfSpeech; }

    public String getExample() { return example; }
    public void setExample(String example) { this.example = example; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getBookType() { return bookType; }
    public void setBookType(String bookType) { this.bookType = bookType; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Integer getInNotebook() { return inNotebook; }
    public void setInNotebook(Integer inNotebook) { this.inNotebook = inNotebook; }

    public Integer getReviewLevel() { return reviewLevel; }
    public void setReviewLevel(Integer reviewLevel) { this.reviewLevel = reviewLevel; }

    public Long getNextReviewAt() { return nextReviewAt; }
    public void setNextReviewAt(Long nextReviewAt) { this.nextReviewAt = nextReviewAt; }

    public Long getLastReviewedAt() { return lastReviewedAt; }
    public void setLastReviewedAt(Long lastReviewedAt) { this.lastReviewedAt = lastReviewedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
