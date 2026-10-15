package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.QuizAnswerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 做题记录 Mapper（错题本数据源）
 */
@Mapper
public interface QuizAnswerMapper extends BaseMapper<QuizAnswerEntity> {

    /**
     * 按知识点聚合用户答题统计。
     * 返回字段：kp(知识点), total(答题数), correct(正确数)
     */
    @Select("SELECT q.knowledge_point AS kp, COUNT(*) AS total, " +
            "SUM(CASE WHEN a.is_correct = 1 THEN 1 ELSE 0 END) AS correct " +
            "FROM quiz_answer a JOIN quiz_question q ON a.question_id = q.id " +
            "WHERE a.user_id = #{userId} AND q.knowledge_point IS NOT NULL AND q.knowledge_point <> '' " +
            "GROUP BY q.knowledge_point")
    List<Map<String, Object>> selectKnowledgeStats(@Param("userId") String userId);
}
