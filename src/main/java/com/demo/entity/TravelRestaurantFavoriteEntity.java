package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 旅行·收藏餐馆实体 — 映射 travel_restaurant_favorite 表。
 */
@TableName("travel_restaurant_favorite")
public class TravelRestaurantFavoriteEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 行程方案 ID */
    private String travelPlanId;
    /** 高德 POI ID */
    private String poiId;
    private String name;
    private String lon;
    private String lat;
    private Integer avgCost;
    private Double rating;
    private LocalDateTime createTime;

    public TravelRestaurantFavoriteEntity() {}

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTravelPlanId() { return travelPlanId; }
    public void setTravelPlanId(String travelPlanId) { this.travelPlanId = travelPlanId; }

    public String getPoiId() { return poiId; }
    public void setPoiId(String poiId) { this.poiId = poiId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getLon() { return lon; }
    public void setLon(String lon) { this.lon = lon; }

    public String getLat() { return lat; }
    public void setLat(String lat) { this.lat = lat; }

    public Integer getAvgCost() { return avgCost; }
    public void setAvgCost(Integer avgCost) { this.avgCost = avgCost; }

    public Double getRating() { return rating; }
    public void setRating(Double rating) { this.rating = rating; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
