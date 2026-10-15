package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.QuizQuestionFavEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 题目收藏 Mapper
 */
@Mapper
public interface QuizQuestionFavMapper extends BaseMapper<QuizQuestionFavEntity> {
}
