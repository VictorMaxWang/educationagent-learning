package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 对话消息 Mapper
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    /**
     * 获取用户最大的轮次序号
     */
    @Select("SELECT COALESCE(MAX(round_num), 0) FROM chat_messages WHERE user_id = #{userId}")
    int findMaxRoundNumByUserId(@Param("userId") String userId);

    /**
     * 删除用户超出保留轮次的旧消息
     */
    @Delete("DELETE FROM chat_messages WHERE user_id = #{userId} AND round_num <= #{maxRoundNum}")
    int deleteOldMessages(@Param("userId") String userId, @Param("maxRoundNum") int maxRoundNum);

    /**
     * 删除用户所有消息
     */
    @Delete("DELETE FROM chat_messages WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") String userId);
}
