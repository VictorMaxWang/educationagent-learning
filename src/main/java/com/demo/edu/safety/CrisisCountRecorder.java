package com.demo.edu.safety;

import com.demo.edu.mapper.EduCrisisDailyMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 危机信号计数：把"发生过一次危机引导"写进 <b>按班级按日的聚合表</b>。
 *
 * <h3>为什么个体记录压根不落库</h3>
 * 另一种做法是存事件表（带 user_id），教师接口只做 GROUP BY 投影。
 * 那种做法的安全性挂在"接口别写错"上——任何一次 SQL 改动、任何一次库泄露都会破功，
 * 而隐私承诺不该依赖这种脆弱前提。所以这里只存聚合：
 * {@code (class_id, stat_date, signal_type, cnt)}，个体信息在进程内存里用完即弃。
 *
 * <h3>去重语义</h3>
 * 同一个学生、同一天只计一次——否则一个孩子连发十条"我想死"（或换十种说法），
 * 教师端看到的是"本班 10 条高风险"，把<b>一个学生的严重程度</b>夸大成<b>十个学生</b>，
 * 这比不计数更误导。去重键是 {@code sha256(userId|日期)}：
 * 它是单向的，内存里也不留明文 userId。
 *
 * <p>去重集合是进程内的，<b>重启会丢</b>（重启后同一学生当天可能再计一次）。
 * 原型阶段可接受，换成 Redis 集合即可修（见方案 Limitations）。
 */
@Slf4j
@Component
public class CrisisCountRecorder {

    private final EduCrisisDailyMapper crisisMapper;
    private final EduUserClassMapper userClassMapper;

    /** 业务指标埋点（可选，缺失时自动跳过）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.demo.metrics.BusinessMetrics businessMetrics;

    /**
     * 落库的信号类别。
     *
     * <p><b>刻意只保留一个粗粒度类别</b>，而不是把命中的具体词写进去：
     * 具体词本身就是孩子原话的一部分（"割腕"与"不想活"透露的东西差很多）。
     * 教师端需要的是"本班今天有 N 个孩子出现危机信号"，而不是这些孩子说了什么。
     */
    private static final String SIGNAL_TYPE = "self_harm";

    /**
     * 当日已计数的学生 → 该学生当日已记录的最高等级（key 是 sha256 摘要，不含明文）。
     *
     * <p>存等级而不只是一个"见过"标记，是为了支持<b>等级只升不降</b>：
     * 同一学生当天先说"活着没意思"（1 级）后说"想割腕"（3 级），计数仍是 1，
     * 但等级要抬到 3。
     */
    private final Map<String, Integer> seenToday = new ConcurrentHashMap<>();
    private volatile LocalDate seenDay = LocalDate.now();

    public CrisisCountRecorder(EduCrisisDailyMapper crisisMapper, EduUserClassMapper userClassMapper) {
        this.crisisMapper = crisisMapper;
        this.userClassMapper = userClassMapper;
    }

    /**
     * 记一次危机信号（含风险等级）。
     *
     * <p>该方法<b>永不抛异常、永不阻塞</b>：它是旁路统计，绝不能因为数据库不可用
     * 就让一个需要帮助的孩子拿不到热线号码。
     *
     * @param userId 用户标识（用于查班级 + 去重，不落库）
     * @param level  风险等级 1/2/3（见 {@code CrisisSignalDetector}），越界会被钳到 1~3
     */
    public void record(String userId, int level) {
        try {
            if (userId == null || userId.isBlank()) {
                log.info("ℹ️ 危机信号未计数：无用户标识");
                return;
            }
            String classId = userClassMapper.selectClassIdByUserId(userId);
            if (classId == null || classId.isBlank()) {
                log.warn("ℹ️ 危机信号未计数：该用户未归属任何班级（edu_user_class 无记录）。"
                        + "学生端仍已收到引导话术，此处仅跳过统计");
                return;
            }
            int lv = Math.max(1, Math.min(3, level));
            LocalDate today = LocalDate.now();
            rollIfNewDay(today);

            // ★ 去重键只含「学生 + 日期」：一个学生一天不管说了几种说法、说了几次，
            //   教师端都只看到 1。若按命中的具体词去重，同一个孩子换三种说法就变成 3 条，
            //   把「一个学生的严重程度」误读成「三个学生」——比不计数更误导。
            String dedupKey = sha256(userId + "|" + today);
            Integer previous = seenToday.putIfAbsent(dedupKey, lv);

            if (previous == null) {
                crisisMapper.increment(classId, today, SIGNAL_TYPE, lv);
                log.warn("📊 危机信号计数 +1 → 班级={} 日期={} 等级={}（该表不含任何个人标识）",
                        classId, today, lv);
                if (businessMetrics != null) {
                    businessMetrics.recordClassWarn(1);
                }
                return;
            }

            if (lv > previous) {
                // 同一学生当日等级上调：计数不动，等级抬高。
                // 少了这一步，"先低落、后说出方法性表述"会被按 1 级统计，教师端关注度偏低。
                seenToday.put(dedupKey, lv);
                crisisMapper.raiseLevel(classId, today, SIGNAL_TYPE, lv);
                log.warn("⬆️ 同一学生当日风险等级上调 {} → {}（班级={} 日期={}，计数保持 1）",
                        previous, lv, classId, today);
                return;
            }
            log.info("ℹ️ 危机信号已计数过（同一学生同日），本次不重复累计");
        } catch (Exception e) {
            log.warn("危机信号计数失败（不影响学生端引导）：{}", e.toString());
        }
    }

    private void rollIfNewDay(LocalDate today) {
        if (!today.equals(seenDay)) {
            synchronized (this) {
                if (!today.equals(seenDay)) {
                    seenToday.clear();
                    seenDay = today;
                }
            }
        }
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 拿不到摘要就退回 hashCode：去重精度下降，但不影响主流程
            return String.valueOf(raw.hashCode());
        }
    }

    /** 供健康快照/单测查看当日去重集合大小。 */
    public int todayDedupSize() {
        return seenToday.size();
    }
}
