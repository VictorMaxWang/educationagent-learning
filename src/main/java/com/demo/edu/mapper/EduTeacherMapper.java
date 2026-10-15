package com.demo.edu.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 教师账号。
 *
 * <p>只用于<b>登录校验</b>：查出来的 {@code passwordHash} 仅在本进程内参与 PBKDF2 比对，
 * 绝不返回给调用方、绝不进日志、绝不进任何接口响应。
 */
@Mapper
public interface EduTeacherMapper {

    @Select("SELECT id, username, password_hash AS passwordHash, class_id AS classId "
            + "FROM edu_teacher WHERE username = #{username} LIMIT 1")
    Map<String, Object> selectByUsername(@Param("username") String username);

    @Insert("INSERT INTO edu_teacher(username, password_hash, class_id) "
            + "VALUES(#{username}, #{passwordHash}, #{classId})")
    int insert(@Param("username") String username,
               @Param("passwordHash") String passwordHash,
               @Param("classId") String classId);

    /** 班级名（教师登录后展示"当前身份：初一2班"）。 */
    @Select("SELECT class_name FROM edu_class WHERE class_id = #{classId} LIMIT 1")
    String selectClassName(@Param("classId") String classId);

    @Select("SELECT COUNT(*) FROM edu_teacher")
    int countAll();
}
