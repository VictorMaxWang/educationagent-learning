package com.demo.pet;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宠物通用健康记录实体 — 映射 pet_records 表
 * 通过 record_type 区分 7 类记录：
 *   weight(体重) / vaccine(疫苗) / deworm(驱虫) / medication(用药)
 *   growth(成长) / expense(花费) / symptom(症状)
 *
 * 通用列映射约定：
 *   record_date  记录日期（体重=记录日 / 疫苗=接种日 / 驱虫=驱虫日 / 用药=开始日 / 成长=事件日 / 花费=花费日 / 症状=症状日）
 *   name         疫苗名 / 驱虫类型(体内体外) / 药名 / 事件类型 / 费用类别 / 症状
 *   amount       体重(kg) / 每天次数 / 金额
 *   text1        备注 / 药品名 / 用量 / 内容
 *   text2        症状严重度(轻/中/重)
 *   next_date    疫苗、驱虫下次日期
 *   end_date     用药结束日期
 *   active       用药是否进行中(1/0)
 *
 * 提供 typed 读写方法，让业务代码保持语义化（如 getWeightKg()/getVaccineName()）。
 */
@TableName("pet_records")
public class PetRecordEntity {

    public static final String TYPE_WEIGHT = "weight";
    public static final String TYPE_VACCINE = "vaccine";
    public static final String TYPE_DEWORM = "deworm";
    public static final String TYPE_MEDICATION = "medication";
    public static final String TYPE_GROWTH = "growth";
    public static final String TYPE_EXPENSE = "expense";
    public static final String TYPE_SYMPTOM = "symptom";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String petName;
    private String recordType;
    private LocalDate recordDate;
    private String name;
    private BigDecimal amount;
    private String text1;
    private String text2;
    private LocalDate nextDate;
    private LocalDate endDate;
    private Integer active;
    private LocalDateTime createdAt;

    public PetRecordEntity() {}

    // ==================== 类型化构造工厂 ====================

    /** 体重记录：amount=体重kg，text1=备注 */
    public static PetRecordEntity weight(String userId, String petName, double weightKg,
                                          LocalDate date, String note) {
        PetRecordEntity r = base(userId, petName, TYPE_WEIGHT, date);
        r.amount = BigDecimal.valueOf(weightKg);
        r.text1 = note;
        return r;
    }

    /** 疫苗记录：name=疫苗名，nextDate=下次接种 */
    public static PetRecordEntity vaccine(String userId, String petName, String vaccineName,
                                           LocalDate vaccDate, LocalDate nextDate) {
        PetRecordEntity r = base(userId, petName, TYPE_VACCINE, vaccDate);
        r.name = vaccineName;
        r.nextDate = nextDate;
        return r;
    }

    /** 驱虫记录：name=体内/体外，text1=药品名，nextDate=下次 */
    public static PetRecordEntity deworm(String userId, String petName, String dewormType,
                                          LocalDate dewormDate, LocalDate nextDate, String drugName) {
        PetRecordEntity r = base(userId, petName, TYPE_DEWORM, dewormDate);
        r.name = dewormType;
        r.text1 = drugName;
        r.nextDate = nextDate;
        return r;
    }

    /** 用药记录：name=药名，amount=每天次数，text1=用量，recordDate=开始日，endDate=结束日 */
    public static PetRecordEntity medication(String userId, String petName, String drugName,
                                              LocalDate startDate, LocalDate endDate,
                                              int timesPerDay, String dosage) {
        PetRecordEntity r = base(userId, petName, TYPE_MEDICATION, startDate);
        r.name = drugName;
        r.amount = BigDecimal.valueOf(timesPerDay);
        r.text1 = dosage;
        r.endDate = endDate;
        r.active = 1;
        return r;
    }

    /** 成长记录：name=事件类型，text1=内容 */
    public static PetRecordEntity growth(String userId, String petName, String eventType,
                                          LocalDate eventDate, String content) {
        PetRecordEntity r = base(userId, petName, TYPE_GROWTH, eventDate);
        r.name = eventType;
        r.text1 = content;
        return r;
    }

    /** 花费记录：name=类别，amount=金额，text1=备注 */
    public static PetRecordEntity expense(String userId, String petName, String category,
                                           LocalDate expenseDate, BigDecimal amount, String note) {
        PetRecordEntity r = base(userId, petName, TYPE_EXPENSE, expenseDate);
        r.name = category;
        r.amount = amount;
        r.text1 = note;
        return r;
    }

    /** 症状记录：name=症状，text1=备注，text2=严重度(轻/中/重) */
    public static PetRecordEntity symptom(String userId, String petName, String symptom,
                                           LocalDate symptomDate, String note, String severity) {
        PetRecordEntity r = base(userId, petName, TYPE_SYMPTOM, symptomDate);
        r.name = symptom;
        r.text1 = note;
        r.text2 = severity;
        return r;
    }

    private static PetRecordEntity base(String userId, String petName, String recordType, LocalDate date) {
        PetRecordEntity r = new PetRecordEntity();
        r.userId = userId;
        r.petName = petName;
        r.recordType = recordType;
        r.recordDate = date;
        return r;
    }

    // ==================== 类型化读取方法 ====================

    public Double getWeightKg() { return amount != null ? amount.doubleValue() : null; }
    public String getVaccineName() { return name; }
    public LocalDate getVaccDate() { return recordDate; }
    public String getDewormType() { return name; }
    public LocalDate getDewormDate() { return recordDate; }
    public String getDrugName() { return text1; }
    public LocalDate getStartDate() { return recordDate; }
    public int getTimesPerDay() { return amount != null ? amount.intValue() : 1; }
    public String getDosage() { return text1; }
    public String getNote() { return text1; }
    public LocalDate getEventDate() { return recordDate; }
    public String getEventType() { return name; }
    public String getContent() { return text1; }
    public LocalDate getExpenseDate() { return recordDate; }
    public String getCategory() { return name; }
    public LocalDate getSymptomDate() { return recordDate; }
    public String getSymptom() { return name; }
    public String getSeverity() { return text2; }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getPetName() { return petName; }
    public void setPetName(String petName) { this.petName = petName; }
    public String getRecordType() { return recordType; }
    public void setRecordType(String recordType) { this.recordType = recordType; }
    public LocalDate getRecordDate() { return recordDate; }
    public void setRecordDate(LocalDate recordDate) { this.recordDate = recordDate; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getText1() { return text1; }
    public void setText1(String text1) { this.text1 = text1; }
    public String getText2() { return text2; }
    public void setText2(String text2) { this.text2 = text2; }
    public LocalDate getNextDate() { return nextDate; }
    public void setNextDate(LocalDate nextDate) { this.nextDate = nextDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public Integer getActive() { return active; }
    public void setActive(Integer active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
