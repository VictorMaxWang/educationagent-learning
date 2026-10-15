package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.TravelRestaurantFavoriteEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 旅行·收藏餐馆 Mapper。
 */
@Mapper
public interface TravelRestaurantFavoriteMapper extends BaseMapper<TravelRestaurantFavoriteEntity> {

    @Select("SELECT * FROM travel_restaurant_favorite WHERE travel_plan_id = #{travelPlanId} AND poi_id = #{poiId} LIMIT 1")
    TravelRestaurantFavoriteEntity findByPlanAndPoi(@Param("travelPlanId") String travelPlanId,
                                                    @Param("poiId") String poiId);

    @Select("SELECT * FROM travel_restaurant_favorite WHERE travel_plan_id = #{travelPlanId} ORDER BY create_time DESC")
    List<TravelRestaurantFavoriteEntity> findByPlan(@Param("travelPlanId") String travelPlanId);
}
