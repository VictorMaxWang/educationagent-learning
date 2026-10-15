package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.XhsHotScheduleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 小红书热门视频定时发布配置 Mapper（单行配置，BaseMapper 自带 CRUD）。
 */
@Mapper
public interface XhsHotScheduleMapper extends BaseMapper<XhsHotScheduleEntity> {
}
