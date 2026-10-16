package com.demo.edu.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 全站共享成长历史只读映射；写入由受控运维脚本执行，不开放匿名写接口。 */
@Mapper
public interface GrowthConversationMapper {
    @Select("SELECT messages FROM edu_growth_conversation WHERE group_id=#{group} AND grade=#{grade}")
    String find(@Param("group") String group, @Param("grade") int grade);
}
