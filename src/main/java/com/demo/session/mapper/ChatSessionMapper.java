package com.demo.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.session.entity.ChatSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 会话元数据 Mapper（chat_session 表）
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionEntity> {

    /**
     * 查询某用户的全部会话，按更新时间倒序（最近活跃的排最前）
     */
    @Select("SELECT * FROM chat_session WHERE user_id = #{userId} ORDER BY update_time DESC")
    List<ChatSessionEntity> selectByUserId(@Param("userId") String userId);
}
