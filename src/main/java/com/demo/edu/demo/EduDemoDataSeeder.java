package com.demo.edu.demo;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * <b>原型演示样本</b>注入器：给教师工作台造一个班的匿名学情数据。
 *
 * <h3>这些数据的性质（务必如实标注）</h3>
 * 它们是<b>原型演示样本</b>，用于验证工作台功能与接口，<b>不是任何真实学校的学生数据</b>。
 * 正式落地后应改为从学校平台导入真实（且已匿名的）班级数据。
 * 论文/申报材料里请照这句话写——评委接受演示样本，但不接受把样本说成真实数据。
 *
 * <h3>为什么造在这几张表里</h3>
 * 工作台的学情**不是单独存一份"知识档案"，而是从既有表聚合出来的**：
 * <ul>
 *   <li>正确率 / 薄弱知识点 ← {@code quiz_answer} join {@code quiz_question.knowledge_point}</li>
 *   <li>专题提问占比 ← {@code edu_topic_ask}</li>
 *   <li>情绪预警 ← {@code edu_crisis_daily}（无 user_id 的聚合计数）</li>
 *   <li>班级人数 ← {@code edu_user_class}</li>
 * </ul>
 * 所以样本必须落在这些表里，否则页面照样显示"样本不足"。
 *
 * <h3>为什么用 Java 生成而不是静态 SQL</h3>
 * <b>静态 SQL 的日期是写死的</b>——过几天再演示，"近 7 天窗口"里就查不到数据，
 * 学情/预警/专题占比会一起塌掉。这里所有日期都相对 {@code LocalDate.now()} 生成，
 * 任何时候重启都落在窗口内。
 *
 * <h3>隐私口径（与产品的隐私设计一致）</h3>
 * 学生标识是<b>不透明字符串</b>（{@code stu_demo_01}），没有任何姓名、手机号、身份证；
 * 危机预警<b>只写聚合计数</b>，不写 user_id、不写原句——与本项目"不存原始对话"的设计同源。
 *
 * <h3>开关与幂等</h3>
 * {@code edu.demo.seed-enabled=true}（默认）才注入；用固定主键 + {@code INSERT IGNORE}，
 * 重复启动不会累积。**正式部署前置为 false。**
 */
@Slf4j
@Component
public class EduDemoDataSeeder {

    /** 演示班级（与 edu_teacher / edu_class 的种子保持一致）。 */
    private static final String CLASS_ID = "class_demo_1";
    private static final String CLASS_NAME = "乡村中学初一(2)班（原型演示样本）";
    private static final int STUDENT_COUNT = 20;

    /** 题库：知识点标签就是"薄弱知识点分布"的分组依据。 */
    private static final String[] KNOWLEDGE_POINTS = {
            "青春期生理变化", "生命价值与意义", "情绪调节", "防校园欺凌",
            "一元一次方程", "英语一般过去时", "自我保护与求助", "英语现在完成时"
    };

    private final JdbcTemplate jdbc;

    @Value("${edu.demo.seed-enabled:true}")
    private boolean seedEnabled;

    public EduDemoDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void seed() {
        if (!seedEnabled) {
            log.info("🧪 演示样本注入已关闭（edu.demo.seed-enabled=false）—— "
                    + "工作台将只显示真实数据（可能为空）");
            return;
        }
        try {
            if (!tablesReady()) {
                log.warn("🧪 演示样本跳过：edu_* 表尚未就绪（schema.sql 还没执行完？）");
                return;
            }
            // 不用 VALUES()（MySQL 8.0.20+ 已废弃），直接把参数传两次
            jdbc.update("INSERT INTO edu_class(class_id, class_name) VALUES (?, ?) "
                            + "ON DUPLICATE KEY UPDATE class_name = ?",
                    CLASS_ID, CLASS_NAME, CLASS_NAME);

            seedStudents();
            seedQuestions();
            seedAnswers();
            seedTopicAsks();
            seedWarnings();

            log.warn("""

                    ══════════════ 【演示样本】已注入 ══════════════
                     班级：{}（{} 名模拟学生：stu_demo_01 … stu_demo_{}）
                     内容：题目 {} 道 / 作答记录 / 近 7 天专题提问 / 情绪预警
                     性质：**原型演示样本**，用于验证工作台功能，不是真实学校数据；
                           正式落地后改为从学校平台导入真实匿名班级数据。
                     关闭：edu.demo.seed-enabled=false
                    ═══════════════════════════════════════════════""",
                    CLASS_NAME, STUDENT_COUNT, String.format("%02d", STUDENT_COUNT),
                    KNOWLEDGE_POINTS.length);
        } catch (Exception e) {
            // 演示数据不是功能依赖：注入失败不能让应用起不来
            log.warn("🧪 演示样本注入失败（不影响其它功能）：{}", e.toString());
        }
    }

    private boolean tablesReady() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM edu_user_class", Integer.class);
            jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 20 名虚拟学生：只有不透明标识，没有任何个人信息。 */
    private void seedStudents() {
        int inserted = 0;
        for (int i = 1; i <= STUDENT_COUNT; i++) {
            inserted += jdbc.update(
                    "INSERT IGNORE INTO edu_user_class(user_id, class_id, role) VALUES (?, ?, 'student')",
                    String.format("stu_demo_%02d", i), CLASS_ID);
        }
        if (inserted > 0) {
            log.info("🧪 演示样本：新增 {} 名模拟学生", inserted);
        }
    }

    /**
     * 题目：主键固定（9xxx），保证重复执行幂等。
     * {@code knowledge_point} 是薄弱知识点分布的分组依据，也是演示的"故事线"。
     */
    private void seedQuestions() {
        for (int i = 0; i < KNOWLEDGE_POINTS.length; i++) {
            jdbc.update("INSERT IGNORE INTO quiz_question"
                            + "(id, paper_id, question_no, q_type, stem, options, answer, explanation, knowledge_point, has_answer) "
                            + "VALUES (?, 90001, ?, 'single', ?, '[]', 'A', '演示样本', ?, 1)",
                    9001 + i, i + 1, "【演示样本】关于「" + KNOWLEDGE_POINTS[i] + "」的题目",
                    KNOWLEDGE_POINTS[i]);
        }
    }

    /**
     * 作答记录：让"薄弱知识点分布"有故事可讲——情绪调节 / 防校园欺凌 / 英语一般过去时错得最多，
     * 青春期生理变化与生命价值掌握较好。正确率整体约 75%。
     */
    private void seedAnswers() {
        int answered = 0;
        for (int s = 1; s <= STUDENT_COUNT; s++) {
            String userId = String.format("stu_demo_%02d", s);
            for (int q = 0; q < KNOWLEDGE_POINTS.length; q++) {
                // 每人只做其中一部分题，避免数据过于规整（更像真实分布）
                if ((s + q) % 3 == 0) continue;
                boolean weak = isWeakPoint(KNOWLEDGE_POINTS[q]);
                // 薄弱项：约 1/3 答对；其它：约 85% 答对。用确定性规则（不用随机）保证可复现
                boolean correct = weak ? (s % 3 == 0) : (s % 7 != 0);
                // 时间戳在 Java 里算好再传：不用 `NOW() - INTERVAL ? DAY`，避免占位符在
                // INTERVAL 表达式里的驱动兼容性问题
                java.time.LocalDateTime answeredAt =
                        LocalDate.now().minusDays((s + q) % 7).atTime(19, 30);
                jdbc.update("INSERT IGNORE INTO quiz_answer"
                                + "(id, user_id, paper_id, question_id, user_answer, is_correct, answered_at) "
                                + "VALUES (?, ?, 90001, ?, ?, ?, ?)",
                        900000 + s * 100 + q, userId, 9001 + q,
                        correct ? "A" : "B", correct ? 1 : 0,
                        java.sql.Timestamp.valueOf(answeredAt));
                answered++;
            }
        }
        log.info("🧪 演示样本：作答记录 {} 条", answered);
    }

    private static boolean isWeakPoint(String point) {
        return point.contains("情绪") || point.contains("欺凌") || point.contains("过去时");
    }

    /** 近 7 天的专题提问分布：生命安全与健康教育 > 青春期性教育 > 校本资源。 */
    private void seedTopicAsks() {
        String[][] plan = {
                {"lifesafety", "3"}, {"sexedu", "2"}, {"school", "1"}
        };
        for (int dayOffset = 0; dayOffset < 7; dayOffset++) {
            LocalDate date = LocalDate.now().minusDays(dayOffset);
            for (String[] item : plan) {
                // 每天的量略有起伏，避免一张完全均匀的图
                int cnt = Integer.parseInt(item[1]) + ((dayOffset % 3 == 0) ? 1 : 0);
                jdbc.update("INSERT INTO edu_topic_ask(class_id, stat_date, topic, cnt) VALUES (?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE cnt = GREATEST(cnt, ?)",
                        CLASS_ID, date, item[0], cnt, cnt);
            }
        }
        log.info("🧪 演示样本：近 7 天专题提问已生成");
    }

    /**
     * 情绪预警聚合计数：近 7 天合计 5 条（**≥3 条才不会触发 k-匿名隐藏**），
     * 其中一天最高等级为 3（演示"等级不隐藏、计数可能隐藏"这条设计）。
     *
     * <p>注意：这张表里<b>没有 user_id、没有原句</b>——演示样本也遵守这个约束。
     */
    private void seedWarnings() {
        int[][] plan = {
                {1, 2, 1}, {2, 1, 2}, {4, 2, 3}
        };
        for (int[] row : plan) {
            jdbc.update("INSERT INTO edu_crisis_daily(class_id, stat_date, signal_type, cnt, max_level) "
                            + "VALUES (?, ?, 'self_harm', ?, ?) "
                            + "ON DUPLICATE KEY UPDATE cnt = GREATEST(cnt, ?), max_level = GREATEST(max_level, ?)",
                    CLASS_ID, LocalDate.now().minusDays(row[0]), row[1], row[2], row[1], row[2]);
        }
        log.info("🧪 演示样本：情绪预警聚合计数已生成（合计 5 条 / 最高 3 级）");
    }

    /** 供健康面板展示注入状态。 */
    public List<String> describe() {
        return List.of(
                "edu.demo.seed-enabled=" + seedEnabled,
                "班级 " + CLASS_ID + "：" + STUDENT_COUNT + " 名模拟学生 + 8 个知识点标签",
                "性质：原型演示样本，非真实学校数据");
    }
}
