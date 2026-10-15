package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.QuizQuestionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 刷题题目 Mapper
 */
@Mapper
public interface QuizQuestionMapper extends BaseMapper<QuizQuestionEntity> {
}
