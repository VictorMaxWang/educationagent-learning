package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.TaxiOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 打车订单 Mapper
 */
@Mapper
public interface TaxiOrderMapper extends BaseMapper<TaxiOrderEntity> {

    /**
     * 查询某用户的所有订单（按时间倒序）
     */
    @Select("SELECT * FROM taxi_orders WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<TaxiOrderEntity> findByUserId(@Param("userId") String userId);

    /**
     * 根据订单号查找
     */
    @Select("SELECT * FROM taxi_orders WHERE order_no = #{orderNo}")
    TaxiOrderEntity findByOrderNo(@Param("orderNo") String orderNo);
}
