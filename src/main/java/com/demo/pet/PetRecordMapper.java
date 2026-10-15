package com.demo.pet;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface PetRecordMapper extends BaseMapper<PetRecordEntity> {

    @Select("SELECT * FROM pet_records WHERE user_id=#{userId} AND pet_name=#{petName} " +
            "AND record_type=#{recordType} ORDER BY record_date DESC, id DESC")
    List<PetRecordEntity> findByUserAndPet(@Param("userId") String userId,
                                           @Param("petName") String petName,
                                           @Param("recordType") String recordType);

    @Select("SELECT * FROM pet_records WHERE user_id=#{userId} AND pet_name=#{petName} " +
            "AND record_type=#{recordType} ORDER BY record_date DESC, id DESC LIMIT 1")
    PetRecordEntity findLatest(@Param("userId") String userId,
                               @Param("petName") String petName,
                               @Param("recordType") String recordType);

    /** 按 name（类别）汇总金额，用于花费统计 */
    @Select("SELECT name, SUM(amount) AS total FROM pet_records WHERE user_id=#{userId} " +
            "AND pet_name=#{petName} AND record_type=#{recordType} AND record_date >= #{fromDate} " +
            "GROUP BY name")
    List<Map<String, Object>> sumByName(@Param("userId") String userId,
                                        @Param("petName") String petName,
                                        @Param("recordType") String recordType,
                                        @Param("fromDate") String fromDate);
}
