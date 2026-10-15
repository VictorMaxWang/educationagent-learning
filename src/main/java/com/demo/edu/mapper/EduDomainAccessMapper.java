package com.demo.edu.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 班级 × 知识库 访问开关。
 *
 * <p><b>语义是「默认开放」</b>：查询返回 {@code null} 表示表里没有该组合的记录，
 * 也就是"开放"（与"专题库默认对本班学生开放"一致）；只有教师关闭过才会有 {@code enabled=0} 的记录。
 *
 * <p>这样设计的好处：新增班级、新增专题库都不需要预先铺数据，
 * 而"关闭"这个动作是显式的、可审计的（谁什么时候关的，updated_at 记着）。
 */
@Mapper
public interface EduDomainAccessMapper {

    /** 该班对该领域的开关状态；{@code null} = 无记录 = 默认开放。 */
    @Select("SELECT enabled FROM edu_class_domain_access "
            + "WHERE class_id = #{classId} AND domain_id = #{domainId} LIMIT 1")
    Integer selectEnabled(@Param("classId") String classId, @Param("domainId") String domainId);

    /** 记录/更新开关（幂等）。 */
    @Insert("INSERT INTO edu_class_domain_access(class_id, domain_id, enabled) "
            + "VALUES(#{classId}, #{domainId}, #{enabled}) "
            + "ON DUPLICATE KEY UPDATE enabled = #{enabled}")
    int upsert(@Param("classId") String classId,
               @Param("domainId") String domainId,
               @Param("enabled") int enabled);

    /** 该班所有显式设置过的开关（只含"关过"的记录，默认开放的不会出现在这里）。 */
    @Select("SELECT domain_id AS domainId, enabled, updated_at AS updatedAt "
            + "FROM edu_class_domain_access WHERE class_id = #{classId} ORDER BY domain_id")
    List<Map<String, Object>> listByClass(@Param("classId") String classId);
}
