package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.McpCallLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * MCP 调用日志 Mapper
 */
@Mapper
public interface McpCallLogMapper extends BaseMapper<McpCallLogEntity> {
}
