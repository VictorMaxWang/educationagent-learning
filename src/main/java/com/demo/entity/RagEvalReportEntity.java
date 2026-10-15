package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RAG 评估结果实体 — 映射 rag_eval_report 表。
 *
 * <p><b>粒度是「一个用例一行」</b>而非「一个批次一行」：/api/eval/retry 要求能重跑
 * 失败用例，必须有 case 粒度。批次级聚合指标由 SQL AVG() GROUP BY run_id 现算，不冗余存储。</p>
 */
@TableName("rag_eval_report")
public class RagEvalReportEntity {

    /** 状态：评估进行中 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 状态：三项指标均成功算出 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 状态：该用例评估失败（与「算出来是 0 分」严格区分） */
    public static final String STATUS_FAILED = "FAILED";
    /** 状态：进程重启导致中断 */
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 批次ID，同一次评估的用例共享 */
    private String runId;
    /** Chroma 集合名 */
    private String collection;
    /** 该行使用的检索条数，重跑时复用同一参数以保证可比 */
    private Integer topK;
    /** 测试问题 */
    private String question;
    /** 标准答案 */
    private String groundTruth;
    /** 检索到的 context，JSON 数组 */
    private String retrievedContexts;
    /** 基于 context 现算的答案 */
    private String answer;
    /** 标准答案事实点被 context 覆盖的比例 */
    private BigDecimal contextRecall;
    /** 答案陈述被 context 支撑的比例 */
    private BigDecimal faithfulness;
    /** 答案与标准答案的事实一致度 */
    private BigDecimal factualCorrectness;
    /** 裁判返回的原始 JSON（含各指标理由） */
    private String judgeDetail;
    /** 裁判模型，如 deepseek-chat */
    private String judgeModel;
    /** 裁判 prompt 版本；prompt 一改必须 bump，否则新旧分数不可比 */
    private String promptVersion;
    /** RUNNING / SUCCESS / FAILED / INTERRUPTED */
    private String status;
    /** 失败原因 */
    private String errorMsg;
    /** 该用例耗时(ms) */
    private Long durationMs;
    private LocalDateTime createTime;

    public RagEvalReportEntity() {}

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getGroundTruth() { return groundTruth; }
    public void setGroundTruth(String groundTruth) { this.groundTruth = groundTruth; }

    public String getRetrievedContexts() { return retrievedContexts; }
    public void setRetrievedContexts(String retrievedContexts) { this.retrievedContexts = retrievedContexts; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public BigDecimal getContextRecall() { return contextRecall; }
    public void setContextRecall(BigDecimal contextRecall) { this.contextRecall = contextRecall; }

    public BigDecimal getFaithfulness() { return faithfulness; }
    public void setFaithfulness(BigDecimal faithfulness) { this.faithfulness = faithfulness; }

    public BigDecimal getFactualCorrectness() { return factualCorrectness; }
    public void setFactualCorrectness(BigDecimal factualCorrectness) { this.factualCorrectness = factualCorrectness; }

    public String getJudgeDetail() { return judgeDetail; }
    public void setJudgeDetail(String judgeDetail) { this.judgeDetail = judgeDetail; }

    public String getJudgeModel() { return judgeModel; }
    public void setJudgeModel(String judgeModel) { this.judgeModel = judgeModel; }

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
