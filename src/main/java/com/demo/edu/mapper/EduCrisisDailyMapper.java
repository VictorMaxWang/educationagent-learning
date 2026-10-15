package com.demo.edu.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 危机信号<b>聚合</b>计数（按班级按日）。
 *
 * <p><b>这张表里没有 user_id、没有会话 id、没有时间点、没有原句。</b>
 * 也就是说：即使有人拿到整个数据库，也无法从中还原"是哪个学生"。
 * 教师端的隐私隔离因此不依赖"接口不投影"（那种做法只要有人改一句 SQL 就破功），
 * 而是依赖<b>个体数据根本不存在</b>。
 *
 * <p>代价是诚实的：不能审计"谁发的"、重启会丢内存去重（见 CrisisCountRecorder）。
 */
@Mapper
public interface EduCrisisDailyMapper {

    /**
     * 当日同类信号 +1，并把当日最高风险等级抬到 {@code max(max_level, level)}。
     *
     * @param level 本次信号的风险等级（1 无望 / 2 直接意念 / 3 方法性表述）
     */
    @Insert("INSERT INTO edu_crisis_daily(class_id, stat_date, signal_type, cnt, max_level) "
            + "VALUES(#{classId}, #{statDate}, #{signalType}, 1, #{level}) "
            + "ON DUPLICATE KEY UPDATE cnt = cnt + 1, max_level = GREATEST(max_level, #{level})")
    int increment(@Param("classId") String classId,
                  @Param("statDate") LocalDate statDate,
                  @Param("signalType") String signalType,
                  @Param("level") int level);

    /**
     * 只抬高当日风险等级，<b>不动计数</b>。
     *
     * <p>用途：同一个学生同一天先说了"活着没意思"（1 级，已计 1 条），
     * 后来又说了"我想割腕"（3 级）——计数仍然是 1（是同一个孩子），但等级必须升到 3，
     * 否则教师端看到的关注度会低于真实情况。这正是"按学生按日去重"带来的一个必须补的漏洞。
     */
    @Update("UPDATE edu_crisis_daily SET max_level = GREATEST(max_level, #{level}) "
            + "WHERE class_id = #{classId} AND stat_date = #{statDate} AND signal_type = #{signalType}")
    int raiseLevel(@Param("classId") String classId,
                   @Param("statDate") LocalDate statDate,
                   @Param("signalType") String signalType,
                   @Param("level") int level);

    /** 近 N 天的逐日合计（日期 + 计数 + 当日最高等级）。 */
    @Select("SELECT stat_date AS d, COALESCE(SUM(cnt), 0) AS c, COALESCE(MAX(max_level), 1) AS l "
            + "FROM edu_crisis_daily "
            + "WHERE class_id = #{classId} AND stat_date >= #{from} "
            + "GROUP BY stat_date ORDER BY stat_date")
    List<Map<String, Object>> dailyCounts(@Param("classId") String classId,
                                          @Param("from") LocalDate from);

    /** 近 N 天合计计数（k-匿名判定用）。 */
    @Select("SELECT COALESCE(SUM(cnt), 0) FROM edu_crisis_daily "
            + "WHERE class_id = #{classId} AND stat_date >= #{from}")
    int totalSince(@Param("classId") String classId, @Param("from") LocalDate from);
}
