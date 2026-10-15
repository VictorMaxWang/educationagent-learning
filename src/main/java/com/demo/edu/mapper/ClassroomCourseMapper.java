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
