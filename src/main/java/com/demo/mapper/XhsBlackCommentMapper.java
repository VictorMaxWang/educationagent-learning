package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.XhsBlackCommentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 小红书黑评去重 Mapper。
 */
@Mapper
public interface XhsBlackCommentMapper extends BaseMapper<XhsBlackCommentEntity> {

    /** 按评论 ID 查询（去重判断用） */
    @Select("SELECT * FROM xhs_black_comments WHERE comment_id = #{commentId} LIMIT 1")
    XhsBlackCommentEntity findByCommentId(@Param("commentId") String commentId);
}
