package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.QuizKnowledgeFavEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识点收藏 Mapper
 */
@Mapper
public interface QuizKnowledgeFavMapper extends BaseMapper<QuizKnowledgeFavEntity> {
}
