package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.EnglishWordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 英语词库 Mapper — english_words 表
 */
@Mapper
public interface EnglishWordMapper extends BaseMapper<EnglishWordEntity> {
}
