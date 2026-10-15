package com.demo.edu.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 校本资源元数据。
 *
 * <p>表里只有"哪个班传了什么文件"，<b>没有任何学生数据</b>；
 * 文件本体落 {@code data/school-knowledge/school/}，内容进 Chroma。
 * {@code class_id} 在这里的语义是"上传者归属"，不是"只有该班可见"
 * （校本资源按校内共享设计，见 {@code SchoolResourceConfig} 的说明）。
 */
@Mapper
public interface EduResourceMapper {

    @Insert("INSERT INTO edu_resource(class_id, file_name, lib_id, chunks) "
            + "VALUES(#{classId}, #{fileName}, #{libId}, #{chunks})")
    int insert(@Param("classId") String classId,
               @Param("fileName") String fileName,
               @Param("libId") String libId,
               @Param("chunks") Integer chunks);

    /** 该班上传过的资源（最多 50 条，新的在前）。 */
    @Select("SELECT id, file_name AS fileName, lib_id AS libId, chunks, uploaded_at AS uploadedAt "
            + "FROM edu_resource WHERE class_id = #{classId} ORDER BY uploaded_at DESC LIMIT 50")
    List<Map<String, Object>> listByClass(@Param("classId") String classId);
}
