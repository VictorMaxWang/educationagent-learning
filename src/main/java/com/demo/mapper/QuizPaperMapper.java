package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.QuizPaperEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 刷题试卷 Mapper
 */
@Mapper
public interface QuizPaperMapper extends BaseMapper<QuizPaperEntity> {
}
