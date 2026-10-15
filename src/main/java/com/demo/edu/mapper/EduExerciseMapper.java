package com.demo.edu.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 教师自编习题（{@code edu_exercise}）与它的聚合统计。
 *
 * <p>题目内容在 {@code quiz_question} 里，本 Mapper 只管"谁发给哪个班、发了没有、何时截止"，
 * 以及教师端的**聚合**答题统计。
 *
 * <p>隐私口径与教育域其余部分一致：教师端的 SQL **只做 COUNT/SUM/GROUP BY**，
 * 不投影 `user_id`。唯一会读到学生维度的地方是"这道题有没有人作答"（{@link #countAnswers}），
 * 它只返回一个计数，拿不到是谁。
 */
@Mapper
public interface EduExerciseMapper {

    /**
     * 建"作业容器"（{@code quiz_paper}）并回读自增主键。
     *
     * <p>为什么不用 MyBatis-Plus 的 {@code BaseMapper.insert}：它是 <b>default 方法</b>
     * （内部再调别的方法），单测里 {@code when(mock.insert(...))} 会真的执行方法体、
     * 污染 Mockito 的 matcher 栈，根本没法定桩。用自己的显式 SQL + {@code @SelectKey}
     * 反而更好测，也和本包其余 Mapper 的风格一致。
     */
    @Insert("INSERT INTO quiz_paper(user_id, title, file_type, question_count, deleted) "
            + "VALUES (#{userId}, #{title}, 'teacher-authored', 1, 0)")
    @SelectKey(statement = "SELECT LAST_INSERT_ID()", keyProperty = "id", before = false, resultType = Long.class)
    int insertPaper(com.demo.entity.QuizPaperEntity paper);

    /** 建题目本体（{@code quiz_question}）并回读自增主键。 */
    @Insert("INSERT INTO quiz_question(paper_id, question_no, q_type, stem, options, answer, explanation, "
            + "knowledge_point, has_answer) "
            + "VALUES (#{paperId}, #{questionNo}, #{qType}, #{stem}, #{options}, #{answer}, #{explanation}, "
            + "#{knowledgePoint}, #{hasAnswer})")
    @SelectKey(statement = "SELECT LAST_INSERT_ID()", keyProperty = "id", before = false, resultType = Long.class)
    int insertQuestion(com.demo.entity.QuizQuestionEntity question);

    /**
     * 局部更新题目内容：<b>传 null 表示"不改该字段"</b>（用 {@code COALESCE} 表达）。
     *
     * <p>这样服务层只传要改的字段即可，不必先读一遍再整体覆盖。
     * 副作用是该写法无法把字段"改回 null"——本题型下不需要这个能力，故接受。
     */
    @Update("UPDATE quiz_question SET "
            + "stem = COALESCE(#{stem}, stem), "
            + "options = COALESCE(#{options}, options), "
            + "answer = COALESCE(#{answer}, answer), "
            + "explanation = COALESCE(#{explanation}, explanation) "
            + "WHERE id = #{id}")
    int updateQuestionContent(@Param("id") Long id,
                              @Param("stem") String stem,
                              @Param("options") String options,
                              @Param("answer") String answer,
                              @Param("explanation") String explanation);

    @Insert("INSERT INTO edu_exercise(question_id, class_id, creator, knowledge_point, published, expire_time) "
            + "VALUES (#{questionId}, #{classId}, #{creator}, #{knowledgePoint}, #{published}, #{expireTime})")
    int insert(@Param("questionId") Long questionId,
               @Param("classId") String classId,
               @Param("creator") String creator,
               @Param("knowledgePoint") String knowledgePoint,
               @Param("published") int published,
               @Param("expireTime") LocalDateTime expireTime);

    /** 取一条习题记录（校验归属用）。 */
    @Select("SELECT question_id AS questionId, class_id AS classId, creator, knowledge_point AS knowledgePoint, "
            + "published, expire_time AS expireTime, create_time AS createTime "
            + "FROM edu_exercise WHERE question_id = #{questionId}")
    Map<String, Object> selectByQuestionId(@Param("questionId") Long questionId);

    /** 本班（且本人创建）的习题 + 聚合答题统计。 */
    @Select("SELECT q.id AS questionId, q.q_type AS qType, q.stem, q.knowledge_point AS knowledgePoint, "
            + "e.published, e.expire_time AS expireTime, e.creator, "
            + "COUNT(a.id) AS answeredCount, COALESCE(SUM(a.is_correct), 0) AS correctCount "
            + "FROM edu_exercise e "
            + "JOIN quiz_question q ON q.id = e.question_id "
            + "LEFT JOIN quiz_answer a ON a.question_id = e.question_id "
            + "WHERE e.class_id = #{classId} AND e.creator = #{creator} "
            + "GROUP BY q.id, q.q_type, q.stem, q.knowledge_point, e.published, e.expire_time, e.creator "
            + "ORDER BY e.create_time DESC LIMIT 200")
    List<Map<String, Object>> listByClassAndCreator(@Param("classId") String classId,
                                                    @Param("creator") String creator);

    /** 该题是否已有作答（决定题干/答案能不能改）。 */
    @Select("SELECT COUNT(*) FROM quiz_answer WHERE question_id = #{questionId}")
    int countAnswers(@Param("questionId") Long questionId);

    // ======================== 学生端 ========================

    /**
     * 学生可见的已发布习题。
     *
     * <p><b>刻意不投影 {@code answer} 与 {@code explanation}</b>——
     * 答题前把答案发给学生毫无意义（这是列表接口，不是交卷后的对答案）。
     * 这两个字段只在 {@link #findPublishedForJudge} 里读取，且只用于服务端判分。
     */
    @Select("SELECT q.id AS questionId, q.q_type AS qType, q.stem, q.options, "
            + "q.knowledge_point AS knowledgePoint, e.expire_time AS expireTime "
            + "FROM edu_exercise e "
            + "JOIN quiz_question q ON q.id = e.question_id "
            + "WHERE e.class_id = #{classId} AND e.published = 1 "
            + "AND (e.expire_time IS NULL OR e.expire_time > NOW()) "
            + "ORDER BY e.create_time ASC LIMIT 200")
    List<Map<String, Object>> listPublishedForClass(@Param("classId") String classId);

    /**
     * 取一道**已发布且属于该班**的习题（含标准答案，仅服务端判分用）。
     * 班级是 where 条件的一部分——所以学生提交别班的题会查不到，直接拒绝。
     */
    @Select("SELECT q.id AS questionId, q.paper_id AS paperId, q.q_type AS qType, q.stem, "
            + "q.answer, q.explanation "
            + "FROM edu_exercise e "
            + "JOIN quiz_question q ON q.id = e.question_id "
            + "WHERE e.class_id = #{classId} AND e.published = 1 AND e.question_id = #{questionId} "
            + "AND (e.expire_time IS NULL OR e.expire_time > NOW()) LIMIT 1")
    Map<String, Object> findPublishedForJudge(@Param("classId") String classId,
                                              @Param("questionId") Long questionId);

    /**
     * 覆盖同一学生同一题的既有作答（存在时更新，不存在时插入）。
     *
     * <p>为什么必须覆盖而不是追加：`quiz_answer` 没有 (user, question) 唯一键，
     * 学生反复重做会累积多条记录，而学情正确率是按**条数**算的——
     * 一个人把错题重做五遍，班级正确率就会被刷歪。
     * 所以教师布置的作业按"一生一题一次"计（重做即覆盖）。
     */
    @Update("UPDATE quiz_answer SET user_answer = #{answer}, is_correct = #{isCorrect}, answered_at = NOW() "
            + "WHERE user_id = #{userId} AND question_id = #{questionId}")
    int updateAnswer(@Param("userId") String userId,
                     @Param("questionId") Long questionId,
                     @Param("answer") String answer,
                     @Param("isCorrect") int isCorrect);

    /**
     * 首次作答写入 {@code quiz_answer}（与刷题模块同一张表 → 学情大盘与错题本自动联动）。
     */
    @Insert("INSERT INTO quiz_answer(user_id, paper_id, question_id, user_answer, is_correct) "
            + "VALUES (#{userId}, #{paperId}, #{questionId}, #{answer}, #{isCorrect})")
    int insertAnswer(@Param("userId") String userId,
                     @Param("paperId") Long paperId,
                     @Param("questionId") Long questionId,
                     @Param("answer") String answer,
                     @Param("isCorrect") int isCorrect);

    // ======================== 教师端维护 ========================

    @Update("UPDATE edu_exercise SET published = #{published}, expire_time = #{expireTime} "
            + "WHERE question_id = #{questionId} AND class_id = #{classId}")
    int updatePublish(@Param("questionId") Long questionId,
                      @Param("classId") String classId,
                      @Param("published") int published,
                      @Param("expireTime") LocalDateTime expireTime);

    /** 只允许改"元信息"（解析/知识点）——题干与答案的改动由服务层按"是否已有人作答"另行把关。 */
    @Update("UPDATE edu_exercise SET knowledge_point = #{knowledgePoint} "
            + "WHERE question_id = #{questionId} AND class_id = #{classId}")
    int updateKnowledgePoint(@Param("questionId") Long questionId,
                             @Param("classId") String classId,
                             @Param("knowledgePoint") String knowledgePoint);
}
