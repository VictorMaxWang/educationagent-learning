package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.QuizAnswerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 做题记录 Mapper（错题本数据源）
 */
@Mapper
public interface QuizAnswerMapper extends BaseMapper<QuizAnswerEntity> {

    /**
     * 按知识点聚合用户答题统计。
     * 先按试卷和知识点聚合，业务层过滤未发布试卷后再合并。
     * total保留全部历史尝试；confirmed仅0/1，pending单列未知，不能把未知计入正确率分母。
     */
    @Select("SELECT a.paper_id AS paperId,q.knowledge_point AS kp, COUNT(*) AS total, " +
            "SUM(CASE WHEN a.is_correct = 1 THEN 1 ELSE 0 END) AS correct, " +
            "SUM(CASE WHEN a.is_correct IN (0,1) THEN 1 ELSE 0 END) AS confirmed, " +
            "SUM(CASE WHEN a.is_correct IN (0,1) THEN 0 ELSE 1 END) AS pending " +
            "FROM quiz_answer a JOIN quiz_question q ON a.question_id = q.id " +
            "WHERE a.user_id = #{userId} AND q.knowledge_point IS NOT NULL AND q.knowledge_point <> '' " +
            "GROUP BY a.paper_id,q.knowledge_point")
    List<Map<String, Object>> selectKnowledgeStats(@Param("userId") String userId);

    /**
     * 历史作答只读；是否仍在错题队列由独立状态决定，不改写旧答卷。
     * 两表可能继承不同排序规则；各自绑定同一userId，避免跨表文本列比较触发MySQL 1267。
     */
    @Select("SELECT a.* FROM quiz_answer a WHERE a.user_id=#{userId} AND a.is_correct=0 " +
            "AND NOT EXISTS(SELECT 1 FROM quiz_mistake_resolution r WHERE r.user_id=#{userId} " +
            "AND r.question_id=a.question_id AND r.resolved=1) ORDER BY a.answered_at DESC,a.id DESC")
    List<QuizAnswerEntity> selectUnresolvedMistakes(@Param("userId") String userId);

    /** 请求开始序列比响应返回顺序可靠；晚到的旧评阅不能重新打开较新请求已经解决的错题。 */
    @Insert("INSERT INTO quiz_mistake_resolution(user_id,question_id,resolved,request_sequence,resolution_answer_id,resolution_kind) " +
            "VALUES(#{userId},#{questionId},#{resolved},#{sequence},#{answerId},#{kind}) ON DUPLICATE KEY UPDATE " +
            "resolved=CASE WHEN VALUES(request_sequence)>=request_sequence THEN VALUES(resolved) ELSE resolved END, " +
            "resolution_answer_id=CASE WHEN VALUES(request_sequence)>=request_sequence THEN VALUES(resolution_answer_id) ELSE resolution_answer_id END, " +
            "resolution_kind=CASE WHEN VALUES(request_sequence)>=request_sequence THEN VALUES(resolution_kind) ELSE resolution_kind END, " +
            "updated_at=CASE WHEN VALUES(request_sequence)>=request_sequence THEN CURRENT_TIMESTAMP(3) ELSE updated_at END, " +
            "request_sequence=GREATEST(request_sequence,VALUES(request_sequence))")
    int recordResolution(@Param("userId") String userId, @Param("questionId") Long questionId,
                         @Param("resolved") boolean resolved, @Param("sequence") long sequence,
                         @Param("answerId") Long answerId, @Param("kind") String kind);

    @Select("SELECT resolved FROM quiz_mistake_resolution WHERE user_id=#{userId} AND question_id=#{questionId}")
    Integer isMistakeResolved(@Param("userId") String userId,@Param("questionId") Long questionId);

    @Select("SELECT id,user_id AS userId,request_id AS requestId,paper_id AS paperId,operation,payload_fingerprint AS payloadFingerprint," +
            "question_fingerprint AS questionFingerprint,state,result_json AS resultJson,last_evaluation_id AS lastEvaluationId " +
            "FROM quiz_attempt_request WHERE user_id=#{userId} AND request_id=#{requestId}")
    Map<String,Object> findAttemptRequest(@Param("userId") String userId, @Param("requestId") String requestId);

    @Insert("INSERT IGNORE INTO quiz_attempt_request(user_id,request_id,paper_id,operation,payload_fingerprint,question_fingerprint,state) " +
            "VALUES(#{userId},#{requestId},#{paperId},#{operation},#{payloadFingerprint},#{questionFingerprint},'prepared')")
    int createAttemptRequest(@Param("userId") String userId, @Param("requestId") String requestId,
                             @Param("paperId") Long paperId, @Param("operation") String operation,
                             @Param("payloadFingerprint") String payloadFingerprint, @Param("questionFingerprint") String questionFingerprint);

    /** 先耐久声明一次模型调用意图；重启遗留的evaluating不能再次自动取得调用权。 */
    @Update("UPDATE quiz_attempt_request SET state='evaluating',last_evaluation_id=#{evaluationId},updated_at=CURRENT_TIMESTAMP(3) " +
            "WHERE id=#{id} AND state='prepared'")
    int claimAttemptEvaluation(@Param("id") long id,@Param("evaluationId") String evaluationId);

    /** 固定评阅先独立保存，评分事务失败时仍可复用；只有当前claim拥有者可以写结果。 */
    @Update("UPDATE quiz_attempt_request SET state=#{state},result_json=#{resultJson},last_evaluation_id=#{evaluationId},updated_at=CURRENT_TIMESTAMP(3) " +
            "WHERE id=#{id} AND ((state='prepared' AND last_evaluation_id IS NULL) OR (state='evaluating' AND last_evaluation_id=#{evaluationId}))")
    int recordAttemptEvaluation(@Param("id") long id,@Param("state") String state,@Param("resultJson") String resultJson,@Param("evaluationId") String evaluationId);

    @Update("UPDATE quiz_attempt_request SET state='unknown',updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND state='evaluating' AND last_evaluation_id=#{evaluationId}")
    int markAttemptEvaluationUnknown(@Param("id") long id,@Param("evaluationId") String evaluationId);

    /** 已确认回执不可被未知或晚到结果降级；写入与答卷追加使用同一事务。 */
    @Update("UPDATE quiz_attempt_request SET state=#{state},result_json=#{resultJson},last_evaluation_id=#{evaluationId}," +
            "updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND state<>'complete'")
    int finishAttemptRequest(@Param("id") long id, @Param("state") String state,
                             @Param("resultJson") String resultJson, @Param("evaluationId") String evaluationId);
}
