package com.demo.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.session.entity.SessionMessageEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 会话消息 Mapper（chat_message 表）
 */
@Mapper
public interface SessionMessageMapper extends BaseMapper<SessionMessageEntity> {

    /**
     * 查询会话全部消息（升序，供缓存/全量加载）
     */
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY create_time ASC")
    List<SessionMessageEntity> selectBySessionAsc(@Param("sessionId") String sessionId);

    /**
     * 统计会话消息总数（分页用）
     */
    @Select("SELECT COUNT(*) FROM chat_message WHERE session_id = #{sessionId}")
    long countBySession(@Param("sessionId") String sessionId);

    /**
     * 分页查询会话消息：page=1 为最近一页（create_time DESC + LIMIT，调用方反转回升序展示）
     */
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY create_time DESC LIMIT #{offset}, #{size}")
    List<SessionMessageEntity> selectPageDesc(@Param("sessionId") String sessionId,
                                              @Param("offset") int offset,
                                              @Param("size") int size);

    /**
     * 级联删除会话全部消息
     */
    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
