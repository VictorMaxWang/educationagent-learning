package com.demo.pet;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 宠物诊断记录 Mapper
 */
@Mapper
public interface PetDiagnosisMapper extends BaseMapper<PetDiagnosisEntity> {

    /** 按用户+宠物名查诊断历史 */
    @Select("SELECT * FROM pet_diagnoses WHERE user_id = #{userId} AND pet_name = #{petName} ORDER BY created_at DESC")
    List<PetDiagnosisEntity> findByUserIdAndPetName(@Param("userId") String userId,
                                                     @Param("petName") String petName);

    /** 按用户查所有诊断记录 */
    @Select("SELECT * FROM pet_diagnoses WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<PetDiagnosisEntity> findByUserId(@Param("userId") String userId);
}
