package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.UserSettingsEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户设置 Mapper
 */
@Mapper
public interface UserSettingsMapper extends BaseMapper<UserSettingsEntity> {

    /**
     * 根据 userId 查找设置
     */
    @Select("SELECT * FROM user_settings WHERE user_id = #{userId}")
    UserSettingsEntity findByUserId(@Param("userId") String userId);

    /**
     * 查找所有启用了日报推送的用户设置
     */
    @Select("SELECT * FROM user_settings WHERE report_enabled = 1")
    List<UserSettingsEntity> findAllWithReportEnabled();
}
