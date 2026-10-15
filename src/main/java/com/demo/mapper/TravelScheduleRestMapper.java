package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.TravelScheduleRestEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 旅行·行程关联餐馆 Mapper。
 */
@Mapper
public interface TravelScheduleRestMapper extends BaseMapper<TravelScheduleRestEntity> {

    @Select("SELECT * FROM travel_schedule_rest WHERE schedule_id = #{scheduleId} ORDER BY create_time")
    List<TravelScheduleRestEntity> findByScheduleId(@Param("scheduleId") String scheduleId);
}
