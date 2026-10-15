package com.demo.edu.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 学生 → 班级映射（教育域最小数据模型）。
 *
 * <p><b>表里只有 user_id → class_id，没有姓名、学号、花名册。</b>
 * 这是刻意的：教师端要的是"班级聚合"，不是"知道这是谁"；
 * 不存在的字段不可能被泄露，比"存了但接口不查"可靠得多。
 *
 * <p>映射由学校线下导入或演示脚本写入（见 {@code data/edu-demo-seed.sql}），
 * 系统不做自动注册。
 */
@Mapper
public interface EduUserClassMapper {

    /** 查该用户所属班级；未归属任何班级时返回 null（此后一切计数都不写，只打日志）。 */
    @Select("SELECT class_id FROM edu_user_class WHERE user_id = #{userId} LIMIT 1")
    String selectClassIdByUserId(@Param("userId") String userId);

    /** 该班级的学生数（教师端用于说明"聚合口径覆盖多少人"）。 */
    @Select("SELECT COUNT(*) FROM edu_user_class WHERE class_id = #{classId}")
    int countStudentsOfClass(@Param("classId") String classId);
}
