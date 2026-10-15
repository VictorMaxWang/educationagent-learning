package com.demo.pet;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 宠物档案 Mapper
 */
@Mapper
public interface PetMapper extends BaseMapper<PetEntity> {

    /** 按用户+宠物名查唯一宠物 */
    @Select("SELECT * FROM pets WHERE user_id = #{userId} AND pet_name = #{petName}")
    PetEntity findByUserIdAndName(@Param("userId") String userId,
                                  @Param("petName") String petName);

    /** 列出用户所有宠物 */
    @Select("SELECT * FROM pets WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<PetEntity> findByUserId(@Param("userId") String userId);
}
