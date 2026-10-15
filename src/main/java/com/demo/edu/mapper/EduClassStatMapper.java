package com.demo.edu.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 班级<b>匿名聚合</b>学情统计。
 *
 * <h3>这个 Mapper 里没有一条查询会返回学生标识</h3>
 * 全部是 {@code COUNT} / {@code SUM} / {@code GROUP BY}，SQL 里<b>不出现 au.user_id 之类的投影</b>。
 * 隐私隔离的第一层就落在这里：不是"查出来再过滤"，而是压根查不出来。
 * 第二层是 DTO 白名单（见 {@code EduClassStatService}），第三层是 k-匿名（样本太小时不出数）。
 *
 * <p>关联口径：{@code quiz_answer.user_id} 与 {@code edu_user_class.user_id} 都是字符串用户标识，
 * 直接 join 即可把一个班的学生作答圈起来。
 */
@Mapper
public interface EduClassStatMapper {

    /** 窗口内本班作答总数。 */
    @Select("SELECT COUNT(*) FROM quiz_answer a "
            + "JOIN edu_user_class u ON a.user_id = u.user_id "
            + "WHERE u.class_id = #{classId} AND a.answered_at >= #{from}")
    int quizAnsweredTotal(@Param("classId") String classId, @Param("from") LocalDateTime from);

    /** 窗口内本班答对总数。 */
    @Select("SELECT COALESCE(SUM(a.is_correct), 0) FROM quiz_answer a "
            + "JOIN edu_user_class u ON a.user_id = u.user_id "
            + "WHERE u.class_id = #{classId} AND a.answered_at >= #{from}")
    int quizCorrectTotal(@Param("classId") String classId, @Param("from") LocalDateTime from);

    /**
     * 薄弱知识点分布：错题按知识点分组。
     *
     * <p>只返回"知识点 + 错题数"，没有任何用户维度——所以这张饼图天然不泄露个人。
     * 知识点取自 {@code quiz_question.knowledge_point}（LLM 打标，可能为空，用"未标注"兜底）。
     */
    @Select("SELECT COALESCE(NULLIF(q.knowledge_point, ''), '未标注') AS kp, COUNT(*) AS wrong "
            + "FROM quiz_answer a "
            + "JOIN edu_user_class u ON a.user_id = u.user_id "
            + "JOIN quiz_question q ON a.question_id = q.id "
            + "WHERE u.class_id = #{classId} AND a.is_correct = 0 AND a.answered_at >= #{from} "
            + "GROUP BY kp ORDER BY wrong DESC LIMIT 10")
    List<Map<String, Object>> weakKnowledgePoints(@Param("classId") String classId,
                                                  @Param("from") LocalDateTime from);

    /** 专题提问分布（来自 edu_topic_ask 这张无个人标识的聚合表）。 */
    @Select("SELECT topic, COALESCE(SUM(cnt), 0) AS c FROM edu_topic_ask "
            + "WHERE class_id = #{classId} AND stat_date >= #{from} "
            + "GROUP BY topic ORDER BY c DESC")
    List<Map<String, Object>> topicDistribution(@Param("classId") String classId,
                                                @Param("from") java.time.LocalDate from);
}
