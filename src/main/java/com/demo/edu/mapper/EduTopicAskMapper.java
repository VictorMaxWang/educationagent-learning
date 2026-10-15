package com.demo.edu.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 专题提问计数（供"生命安全与健康教育 / 青春期性教育专题提问占比"）。
 *
 * <p>表里只有 班级 + 日期 + 专题 + 计数，<b>没有 user_id、没有提问原文</b>。
 * 统计口径是"提问条数"而非"人数"——不需要用户维度，也就不可能被反推到某个学生。
 */
@Mapper
public interface EduTopicAskMapper {

    /** 当日该班该专题 +1。 */
    @Insert("INSERT INTO edu_topic_ask(class_id, stat_date, topic, cnt) "
            + "VALUES(#{classId}, #{statDate}, #{topic}, 1) "
            + "ON DUPLICATE KEY UPDATE cnt = cnt + 1")
    int increment(@Param("classId") String classId,
                  @Param("statDate") LocalDate statDate,
                  @Param("topic") String topic);
}
