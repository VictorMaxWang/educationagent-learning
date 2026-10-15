package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.QuizPassageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 刷题材料组 Mapper — quiz_passage 表
 */
@Mapper
public interface QuizPassageMapper extends BaseMapper<QuizPassageEntity> {
}
