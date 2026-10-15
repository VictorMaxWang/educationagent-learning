package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.RagEvalReportEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * RAG 评估结果 Mapper。
 *
 * <p>批次级聚合指标不落库，由这里的 AVG() GROUP BY run_id 现算，避免与明细行不一致。</p>
 *
 * <p>注意：聚合方法的返回列都起了<b>无下划线的驼峰别名</b>，因为项目开启了
 * map-underscore-to-camel-case，带下划线的别名会被二次转换；无下划线别名则原样保留。</p>
 */
@Mapper
public interface RagEvalReportMapper extends BaseMapper<RagEvalReportEntity> {

    /**
     * 单个批次的聚合指标。
     *
     * <p>均值只由 SUCCESS 行贡献：FAILED/INTERRUPTED 行的指标列是 NULL，
     * MySQL 的 AVG() 会自动跳过 —— 「没算出来」不会被当成 0 分拉低均值。</p>
     *
     * <p>同时返回每个指标的<b>样本数</b>：20 条只成功 3 条时，
     * 只有均值会掩盖 85% 的失败率，必须让 nXxx / total 可见。</p>
     */
    @Select("SELECT run_id AS runId, " +
            "       MIN(collection) AS collection, " +
            "       COUNT(*) AS total, " +
            "       SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount, " +
            "       SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount, " +
            "       SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END) AS runningCount, " +
            "       SUM(CASE WHEN status = 'INTERRUPTED' THEN 1 ELSE 0 END) AS interruptedCount, " +
            "       AVG(context_recall) AS avgContextRecall, " +
            "       AVG(faithfulness) AS avgFaithfulness, " +
            "       AVG(factual_correctness) AS avgFactualCorrectness, " +
            "       COUNT(context_recall) AS nContextRecall, " +
            "       COUNT(faithfulness) AS nFaithfulness, " +
            "       COUNT(factual_correctness) AS nFactualCorrectness, " +
            "       MIN(create_time) AS startedAt, " +
            "       MAX(create_time) AS finishedAt " +
            "FROM rag_eval_report WHERE run_id = #{runId} GROUP BY run_id")
    Map<String, Object> aggregateByRun(@Param("runId") String runId);

    /** 最近若干批次的聚合指标（按最后写入时间倒序）；字段口径同 aggregateByRun */
    @Select("SELECT run_id AS runId, " +
            "       MIN(collection) AS collection, " +
            "       COUNT(*) AS total, " +
            "       SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount, " +
            "       SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount, " +
            "       AVG(context_recall) AS avgContextRecall, " +
            "       AVG(faithfulness) AS avgFaithfulness, " +
            "       AVG(factual_correctness) AS avgFactualCorrectness, " +
            "       COUNT(context_recall) AS nContextRecall, " +
            "       COUNT(faithfulness) AS nFaithfulness, " +
            "       COUNT(factual_correctness) AS nFactualCorrectness, " +
            "       MIN(create_time) AS startedAt, " +
            "       MAX(create_time) AS finishedAt " +
            "FROM rag_eval_report GROUP BY run_id ORDER BY MAX(create_time) DESC LIMIT #{limit}")
    List<Map<String, Object>> listRecentRuns(@Param("limit") int limit);

    /** 取某批次明细 */
    @Select("SELECT * FROM rag_eval_report WHERE run_id = #{runId} ORDER BY id LIMIT #{limit}")
    List<RagEvalReportEntity> findByRunId(@Param("runId") String runId, @Param("limit") int limit);

    /** 取某批次中需要重跑的用例（非 SUCCESS） */
    @Select("SELECT * FROM rag_eval_report WHERE run_id = #{runId} AND status <> 'SUCCESS' ORDER BY id")
    List<RagEvalReportEntity> findRetryable(@Param("runId") String runId);

    /**
     * 把上次进程遗留的 RUNNING 行标记为中断。
     *
     * <p>启动时调用。否则进程被 kill 后这些行会永远挂在 RUNNING，
     * 让报告看起来像「评估卡住了」。</p>
     */
    @Update("UPDATE rag_eval_report SET status = 'INTERRUPTED', error_msg = '服务重启导致评估中断' " +
            "WHERE status = 'RUNNING'")
    int markRunningAsInterrupted();
}
