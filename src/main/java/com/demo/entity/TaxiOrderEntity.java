package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 打车订单实体 — 映射 taxi_orders 表
 * 每次成功创建打车订单后写入，用于订单追溯和日志审计
 */
@TableName("taxi_orders")
public class TaxiOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String origin;
    private String destination;
    private String carType;
    private String estimatePrice;
    private String orderNo;
    private String status;
    private String rawResponse;
    private LocalDateTime createdAt;

    public TaxiOrderEntity() {}

    public TaxiOrderEntity(String userId, String origin, String destination,
                           String carType, String estimatePrice) {
        this.userId = userId;
        this.origin = origin;
        this.destination = destination;
        this.carType = carType;
        this.estimatePrice = estimatePrice;
        this.status = "created";
    }

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getCarType() { return carType; }
    public void setCarType(String carType) { this.carType = carType; }

    public String getEstimatePrice() { return estimatePrice; }
    public void setEstimatePrice(String estimatePrice) { this.estimatePrice = estimatePrice; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
