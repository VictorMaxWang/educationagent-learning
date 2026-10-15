package com.demo.pet;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 宠物定时推送 Mapper
 */
@Mapper
public interface PetScheduleMapper extends BaseMapper<PetScheduleEntity> {

    /** 查询用户所有定时任务 */
    @Select("SELECT * FROM pet_schedules WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<PetScheduleEntity> findByUserId(@Param("userId") String userId);

    /** 查询用户某宠物的定时任务 */
    @Select("SELECT * FROM pet_schedules WHERE user_id = #{userId} AND pet_name = #{petName} ORDER BY created_at DESC")
    List<PetScheduleEntity> findByUserIdAndPetName(@Param("userId") String userId,
                                                    @Param("petName") String petName);

    /** 查询所有启用的定时任务（启动恢复用） */
    @Select("SELECT * FROM pet_schedules WHERE enabled = 1")
    List<PetScheduleEntity> findAllEnabled();
}
