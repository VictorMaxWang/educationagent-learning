package com.demo.edu.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 教师错误反馈：教师对 AI 输出质量的反馈（资料不对、答非所问等）。
 *
 * <p>表里只有"哪个班提了什么"——<b>不含任何学生数据</b>，
 * 所以教师端展示自己的反馈历史不存在隐私问题。
 */
@Mapper
public interface EduFeedbackMapper {

    @Insert("INSERT INTO edu_feedback(class_id, lib_id, message) "
            + "VALUES(#{classId}, #{libId}, #{message})")
    int insert(@Param("classId") String classId,
               @Param("libId") String libId,
               @Param("message") String message);

    @Select("SELECT id, lib_id AS libId, message, created_at AS createdAt "
            + "FROM edu_feedback WHERE class_id = #{classId} ORDER BY created_at DESC LIMIT 50")
    List<Map<String, Object>> listByClass(@Param("classId") String classId);
}
