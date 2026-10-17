package com.demo.edu.mapper;

import com.demo.entity.QuizPaperEntity;
import com.demo.entity.QuizQuestionEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 课堂状态和题库同库写入，事务保证题目与课堂关联同时可见。 */
@Mapper
public interface ClassroomCourseMapper {
    @Select("SELECT document FROM edu_learning_course WHERE id=#{id}")
    String find(@Param("id") String id);

    @Select("SELECT document FROM edu_learning_course ORDER BY updated_at DESC LIMIT 100")
    List<String> list();

    /** 首页只读摘要，一次SQL完成投影；不传输整门课HTML、图片、对话再逐项删除。 */
    @Select("SELECT JSON_OBJECT('id',id,'title',JSON_UNQUOTE(JSON_EXTRACT(document,'$.title')),"
            + "'topic',JSON_UNQUOTE(JSON_EXTRACT(document,'$.topic')),'grade',JSON_EXTRACT(document,'$.grade'),"
            + "'subject',JSON_UNQUOTE(JSON_EXTRACT(document,'$.subject')),'domain',JSON_UNQUOTE(JSON_EXTRACT(document,'$.domain')),"
            + "'direction',JSON_UNQUOTE(JSON_EXTRACT(document,'$.direction')),'status',JSON_UNQUOTE(JSON_EXTRACT(document,'$.status')),"
            + "'createdAt',JSON_UNQUOTE(JSON_EXTRACT(document,'$.createdAt')),'updatedAt',JSON_UNQUOTE(JSON_EXTRACT(document,'$.updatedAt')),"
            + "'revision',COALESCE(JSON_EXTRACT(document,'$.revision'),0),"
            + "'sceneCount',COALESCE(JSON_LENGTH(JSON_EXTRACT(document,'$.scenes')),0),"
            + "'outlineCount',COALESCE(JSON_LENGTH(JSON_EXTRACT(document,'$.outlines')),0)) "
            + "FROM edu_learning_course ORDER BY updated_at DESC LIMIT 100")
    List<String> listSummaries();

    @Insert("INSERT INTO edu_learning_course(id,document,updated_at) VALUES(#{id},#{document},CURRENT_TIMESTAMP(3)) " +
            "ON DUPLICATE KEY UPDATE document=VALUES(document),updated_at=CURRENT_TIMESTAMP(3)")
    int save(@Param("id") String id, @Param("document") String document);

    @Insert("INSERT INTO quiz_paper(user_id,title,source_file_name,file_type,question_count,deleted) " +
            "VALUES(#{userId},#{title},#{sourceFileName},'classroom',#{questionCount},0)")
    @SelectKey(statement="SELECT LAST_INSERT_ID()", keyProperty="id", before=false, resultType=Long.class)
    int insertPaper(QuizPaperEntity paper);

    @Insert("INSERT INTO quiz_question(paper_id,question_no,q_type,stem,options,answer,explanation,knowledge_point,has_answer) " +
            "VALUES(#{paperId},#{questionNo},#{qType},#{stem},#{options},#{answer},#{explanation},#{knowledgePoint},1)")
    @SelectKey(statement="SELECT LAST_INSERT_ID()", keyProperty="id", before=false, resultType=Long.class)
    int insertQuestion(QuizQuestionEntity question);
}
