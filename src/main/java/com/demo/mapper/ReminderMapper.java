package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.ReminderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 定时提醒 Mapper
 */
@Mapper
public interface ReminderMapper extends BaseMapper<ReminderEntity> {

    /**
     * 查找所有未执行且触发时间已到的提醒（用于启动恢复时立即触发）
     */
    @Select("SELECT * FROM reminders WHERE executed = #{executed} AND trigger_at <= #{triggerAt}")
    List<ReminderEntity> findOverdue(@Param("executed") int executed, @Param("triggerAt") long triggerAt);

    /**
     * 查找所有未执行且触发时间在未来的提醒（用于启动恢复时重新调度）
     */
    @Select("SELECT * FROM reminders WHERE executed = #{executed} AND trigger_at > #{triggerAt}")
    List<ReminderEntity> findPending(@Param("executed") int executed, @Param("triggerAt") long triggerAt);
}
