package com.demo.edu.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 学生<b>自己</b>的学习数据统计（周报用）。
 *
 * <p>每条查询都带 {@code user_id = #{userId}} 且 {@code userId} 由服务端从请求里取，
 * <b>只查当前学生自己的行</b>——不存在"传别人的 id 就能看别人数据"的路径，
 * 也不做任何跨用户聚合（跨用户聚合只存在于教师端的脱敏计数，见 EduCrisisDailyMapper）。
 *
 * <p>时间字段口径各不相同，别搞混：{@code chat_session.create_time} / {@code chat_message.create_time}
 * 是 <b>epoch 毫秒</b>（BIGINT），{@code quiz_answer.answered_at} 是 <b>TIMESTAMP</b>。
 */
@Mapper
public interface EduStudentStatsMapper {

    /** 窗口内新建的会话数（epoch 毫秒）。 */
    @Select("SELECT COUNT(*) FROM chat_session WHERE user_id = #{userId} AND create_time >= #{fromMs}")
    int sessionCount(@Param("userId") String userId, @Param("fromMs") long fromMs);

    /** 窗口内的消息数（按角色，epoch 毫秒）。注意 chat_message 没有 user_id，必须 join 会话表。 */
    @Select("SELECT COUNT(*) FROM chat_message m JOIN chat_session s ON m.session_id = s.session_id "
            + "WHERE s.user_id = #{userId} AND m.create_time >= #{fromMs} AND m.role = #{role}")
    int messageCount(@Param("userId") String userId, @Param("fromMs") long fromMs, @Param("role") String role);

    /** 窗口内刷题作答数。 */
    @Select("SELECT COUNT(*) FROM quiz_answer WHERE user_id = #{userId} AND answered_at >= #{from}")
    int quizAnswered(@Param("userId") String userId, @Param("from") LocalDateTime from);

    /** 窗口内答对题数。 */
    @Select("SELECT COALESCE(SUM(is_correct), 0) FROM quiz_answer "
            + "WHERE user_id = #{userId} AND answered_at >= #{from}")
    int quizCorrect(@Param("userId") String userId, @Param("from") LocalDateTime from);
}
