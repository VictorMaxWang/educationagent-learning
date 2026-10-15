package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.RagEvalScheduleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 周度评估调度配置 Mapper（单行配置，读写走 selectList/insert/updateById）。
 */
@Mapper
public interface RagEvalScheduleMapper extends BaseMapper<RagEvalScheduleEntity> {
}
