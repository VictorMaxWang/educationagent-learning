package com.demo.edu.teacher;

import com.demo.edu.mapper.EduClassStatMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 班级匿名学情统计：习题正确率、薄弱知识点分布、专题提问占比。
 *
 * <h3>隐私三道闸（与危机看板同一套思路，但更严）</h3>
 * <ol>
 *   <li><b>SQL 层只聚合</b>：{@code EduClassStatMapper} 里全部是 COUNT/SUM/GROUP BY，
 *       没有任何一条查询会投影出 user_id；</li>
 *   <li><b>DTO 白名单</b>：出口字段硬编码在 {@link #ALLOWED_KEYS}，测试用反射钉住，
 *       将来谁往里加字段会立刻红；</li>
 *   <li><b>k-匿名</b>：样本量不足 {@code edu.teacher.min-count-to-show} 时不出数——
 *       一个班的"错题 1 道"配上只有几个人的班级，很容易被反推到具体某个人。</li>
 * </ol>
 *
 * <p>注意一个刻意的取舍：正确率在样本不足时也隐藏。它看起来只是"一个百分比"，
 * 但当全班只做了 1 道题时，"正确率 100%" 等同于"那个学生答对了"。
 */
@Slf4j
@Service
public class EduClassStatService {

    /** 出口字段白名单。加字段前先想清楚它是不是个体数据。 */
    static final Set<String> ALLOWED_KEYS = Set.of(
            "classId", "windowDays", "studentCount",
            "quizAnswered", "quizCorrect", "quizAccuracy", "quizHidden",
            "weakKnowledgePoints", "weakPointsHidden",
            "topicDistribution", "topicTotal", "topicHidden",
            "note", "privacyNote");

    private final EduClassStatMapper statMapper;
    private final EduUserClassMapper userClassMapper;

    @Value("${edu.teacher.min-count-to-show:3}")
    private int minCountToShow;

    public EduClassStatService(EduClassStatMapper statMapper, EduUserClassMapper userClassMapper) {
        this.statMapper = statMapper;
        this.userClassMapper = userClassMapper;
    }

    /**
     * @param classId 班级（<b>由角色门从 token 得到</b>，不是请求参数）
     * @param days    窗口天数，钳制 1~30
     */
    public Map<String, Object> classStat(String classId, int days) {
        int window = Math.max(1, Math.min(days, 30));
        LocalDate fromDate = today().minusDays(window - 1L);
        LocalDateTime fromTs = fromDate.atStartOfDay();
        int threshold = Math.max(1, minCountToShow);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("classId", classId);
        out.put("windowDays", window);
        out.put("studentCount", safe(() -> userClassMapper.countStudentsOfClass(classId), 0));

        // ---- 习题正确率 ----
        int answered = safe(() -> statMapper.quizAnsweredTotal(classId, fromTs), 0);
        int correct = safe(() -> statMapper.quizCorrectTotal(classId, fromTs), 0);
        boolean quizHidden = answered < threshold;
        out.put("quizAnswered", answered);
        out.put("quizCorrect", correct);
        out.put("quizHidden", quizHidden);
        out.put("quizAccuracy", quizHidden ? null : round1(correct * 100.0 / answered));

        // ---- 薄弱知识点分布（错题）----
        List<Map<String, Object>> weak = new ArrayList<>();
        boolean weakHidden = false;
        if (quizHidden) {
            weakHidden = true;
        } else {
            // 分母是错题总数，先算一次（不要放进循环里，否则每个知识点都会重新查库）
            int wrongTotal = Math.max(0, answered - correct);
            try {
                for (Map<String, Object> row : statMapper.weakKnowledgePoints(classId, fromTs)) {
                    int wrong = num(row.get("wrong"));
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("point", String.valueOf(row.get("kp")));
                    item.put("wrong", wrong);
                    item.put("share", wrongTotal == 0 ? 0.0 : round1(wrong * 100.0 / wrongTotal));
                    weak.add(item);
                }
            } catch (Exception e) {
                log.warn("薄弱知识点统计失败：{}", e.toString());
            }
        }
        out.put("weakKnowledgePoints", weak);
        out.put("weakPointsHidden", weakHidden);

        // ---- 专题提问占比 ----
        List<Map<String, Object>> topics = new ArrayList<>();
        int topicTotal = 0;
        try {
            List<Map<String, Object>> rows = statMapper.topicDistribution(classId, fromDate);
            for (Map<String, Object> row : rows) {
                topicTotal += num(row.get("c"));
            }
            boolean topicHidden = topicTotal < threshold;
            out.put("topicHidden", topicHidden);
            out.put("topicTotal", topicHidden ? null : topicTotal);
            if (!topicHidden) {
                for (Map<String, Object> row : rows) {
                    int c = num(row.get("c"));
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("topic", String.valueOf(row.get("topic")));
                    item.put("count", c);
                    item.put("share", topicTotal == 0 ? 0.0 : round1(c * 100.0 / topicTotal));
                    topics.add(item);
                }
            }
        } catch (Exception e) {
            log.warn("专题提问统计失败：{}", e.toString());
            out.put("topicHidden", true);
            out.put("topicTotal", null);
        }
        out.put("topicDistribution", topics);

        out.put("note", quizHidden
                ? "本周作答不足 " + threshold + " 条，正确率与知识点分布按 k-匿名策略隐藏"
                : "统计口径：全部为班级聚合值，不含任何学生明细");
        out.put("privacyNote",
                "本页所有数字都来自聚合查询（COUNT/SUM/GROUP BY），数据库层面不返回学生 ID、"
                        + "姓名或对话内容；样本不足时会隐藏数值。");

        assertWhitelisted(out);
        return out;
    }

    /** 出口自检：任何未列入白名单的字段都是 bug（宁可报错也不悄悄漏出去）。 */
    private static void assertWhitelisted(Map<String, Object> out) {
        for (String key : out.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalStateException("班级统计响应出现未授权字段：" + key);
            }
        }
    }

    private static LocalDate today() {
        return LocalDate.now();
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static int safe(java.util.function.IntSupplier supplier, int fallback) {
        try {
            return supplier.getAsInt();
        } catch (Exception e) {
            log.warn("班级统计项查询失败（按 {} 计）：{}", fallback, e.toString());
            return fallback;
        }
    }
}
