package com.demo.edu.teacher;

import com.demo.edu.mapper.EduCrisisDailyMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师端<b>只读聚合</b>统计。
 *
 * <h3>隐私隔离靠三层，缺一层都不成立</h3>
 * <ol>
 *   <li><b>数据层</b>：危机计数表 {@code edu_crisis_daily} 只有 {@code (class_id, stat_date, signal_type, cnt)}，
 *       没有 user_id / session_id / 原句——个体记录根本不存在，不是"存了不查"；</li>
 *   <li><b>接口层</b>：本服务只做 {@code GROUP BY}，响应体的 key 在白名单里硬编码，
 *       连 {@code userId} 这个字段名都不会出现在 JSON 里（有 EduPrivacyTest 用反射断言钉住）；</li>
 *   <li><b>呈现层</b>：k-匿名——合计不足 {@code edu.teacher.min-count-to-show} 条则不出数，
 *       单日不足 2 条的那一天也不出数（否则"某天恰好 1 条"在只有一个人的场景下等于指名道姓）。</li>
 * </ol>
 *
 * <p>第 3 层容易被当成"过度设计"，但在乡村学校的真实规模下（一个班可能只有十几个人、
 * 一次只统计一周），"1 条"这种数字的指向性非常强。宁可显示"样本不足"，也不显示 1。
 */
@Slf4j
@Service
public class EduTeacherAggregateService {

    /** 单日最小可展示条数：低于它就不出数（配合合计的 k-匿名）。 */
    private static final int MIN_PER_DAY = 2;

    private final EduCrisisDailyMapper crisisMapper;
    private final EduUserClassMapper userClassMapper;

    @Value("${edu.teacher.min-count-to-show:3}")
    private int minCountToShow;

    public EduTeacherAggregateService(EduCrisisDailyMapper crisisMapper, EduUserClassMapper userClassMapper) {
        this.crisisMapper = crisisMapper;
        this.userClassMapper = userClassMapper;
    }

    /**
     * 班级危机信号脱敏汇总。
     *
     * @param classId 班级（<b>由角色门从口令映射得到</b>，不是请求参数）
     * @param days    窗口天数，钳制到 1~30
     */
    public Map<String, Object> crisisSummary(String classId, int days) {
        int window = Math.max(1, Math.min(days, 30));
        LocalDate from = LocalDate.now().minusDays(window - 1L);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("classId", classId);
        out.put("windowDays", window);
        out.put("studentCount", userClassMapper.countStudentsOfClass(classId));

        int total;
        try {
            total = crisisMapper.totalSince(classId, from);
        } catch (Exception e) {
            log.warn("危机聚合查询失败：{}", e.toString());
            out.put("total", null);
            out.put("suppressed", true);
            out.put("days", List.of());
            out.put("note", "统计不可用（数据库查询失败），请检查 edu_crisis_daily 表是否已建");
            return out;
        }

        // ---- 逐日：日期 + 计数（可隐藏）+ 当日最高等级（始终展示）----
        //
        // 这里有个刻意的隐私取舍：**计数可以隐藏，等级不隐藏**。
        // 理由是两者的可推断性不同——
        //   · "本班今天有 1 条" 会把范围缩到"某一个学生"，接近指名；
        //   · "本班今天出现过 3 级信号" 只说明这个班的关注度，教师无法据此找到任何一个具体学生。
        // 而"班里存在高风险信号"恰恰是教师最需要知道的事（关怀义务），藏掉它反而更糟。
        // 所以：合计与单日计数仍按 k-匿名隐藏，等级照常给出。
        boolean enough = total >= Math.max(1, minCountToShow);
        out.put("total", enough ? total : null);
        out.put("suppressed", !enough);

        int maxLevel = 0;
        List<Map<String, Object>> daily = new ArrayList<>();
        try {
            for (Map<String, Object> row : crisisMapper.dailyCounts(classId, from)) {
                int c = row.get("c") == null ? 0 : ((Number) row.get("c")).intValue();
                int dayLevel = row.get("l") == null ? 0 : ((Number) row.get("l")).intValue();
                maxLevel = Math.max(maxLevel, dayLevel);

                Map<String, Object> day = new LinkedHashMap<>();
                day.put("date", String.valueOf(row.get("d")));
                day.put("level", dayLevel);
                if (enough && c >= MIN_PER_DAY) {
                    day.put("count", c);
                    day.put("hidden", false);
                } else {
                    day.put("count", null);
                    day.put("hidden", true);
                    day.put("reason", enough
                            ? "单日不足 " + MIN_PER_DAY + " 条，不展示计数（等级照常给出）"
                            : "合计不足 " + Math.max(1, minCountToShow) + " 条，不展示计数（等级照常给出）");
                }
                daily.add(day);
            }
        } catch (Exception e) {
            log.warn("危机逐日数据查询失败：{}", e.toString());
        }
        out.put("maxLevel", maxLevel);
        out.put("days", daily);
        out.put("note", enough
                ? "计数已按「学生+日期」去重：一个学生一天最多计 1 次；该表不含任何个人标识，"
                        + "系统无法也不提供「谁发的」"
                : "样本不足 " + Math.max(1, minCountToShow) + " 条，合计与单日计数按 k-匿名隐藏；"
                        + "等级照常展示（等级无法反推到具体学生）");
        out.put("privacyNote",
                "教师端只能看到班级聚合：接口不接收班级参数（由口令决定），响应里没有学生标识字段，"
                        + "数据库里也没有个体记录。");
        return out;
    }
}
