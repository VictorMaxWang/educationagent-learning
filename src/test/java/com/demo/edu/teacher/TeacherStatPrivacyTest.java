package com.demo.edu.teacher;

import com.demo.edu.mapper.EduClassStatMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 班级学情统计的隐私隔离测试（答辩可现场跑）。
 *
 * <h3>测的是"拿不到"，不是"承诺不看"</h3>
 * <ol>
 *   <li>响应 key 恰好等于白名单——将来谁加了个 {@code userId} 字段会立刻红；</li>
 *   <li>序列化后的 JSON 里搜不到任何个体标识子串；</li>
 *   <li>样本不足时按 k-匿名隐藏（正确率也藏：全班只做 1 道题时，"100%" 就等于那个学生答对了）。</li>
 * </ol>
 */
class TeacherStatPrivacyTest {

    private static final Set<String> ALLOWED_KEYS = EduClassStatService.ALLOWED_KEYS;

    /**
     * 禁用字段名——按<b>标识符形状</b>列，不是按话题词列。
     *
     * <p>踩过两次坑：一开始拿"学生/姓名"这类话题词去扫，结果
     * ① {@code privacyNote} 正文里"不返回学生 ID、姓名"这句话被当成泄露；
     * ② {@code studentCount}（班级人数，正经的聚合值）被当成个体标识。
     * 真正要防的是"能指向某个人的字段"，所以列的是 userId / studentName / phone 这类形状。
     */
    private static final List<String> FORBIDDEN_FIELD_NAMES = List.of(
            "userid", "user_id", "uid", "sessionid", "session_id",
            "studentname", "studentid", "realname", "username", "phone", "email", "idcard");

    /** 假统计 Mapper：answered/correct/topicTotal 可配。 */
    private static EduClassStatMapper fakeStatMapper(int answered, int correct, int topicTotal) {
        int wrong = Math.max(0, answered - correct);
        return new EduClassStatMapper() {
            @Override
            public int quizAnsweredTotal(String classId, LocalDateTime from) {
                return answered;
            }

            @Override
            public int quizCorrectTotal(String classId, LocalDateTime from) {
                return correct;
            }

            @Override
            public List<Map<String, Object>> weakKnowledgePoints(String classId, LocalDateTime from) {
                List<Map<String, Object>> rows = new ArrayList<>();
                if (wrong == 0) return rows;
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("kp", "青春期身体变化");
                a.put("wrong", Math.max(1, wrong * 2 / 3));
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("kp", "情绪的识别与表达");
                b.put("wrong", wrong - (int) a.get("wrong"));
                rows.add(a);
                if ((int) b.get("wrong") > 0) rows.add(b);
                return rows;
            }

            @Override
            public List<Map<String, Object>> topicDistribution(String classId, LocalDate from) {
                List<Map<String, Object>> rows = new ArrayList<>();
                if (topicTotal <= 0) return rows;
                if (topicTotal == 1) {
                    Map<String, Object> only = new LinkedHashMap<>();
                    only.put("topic", "lifesafety");
                    only.put("c", 1);
                    rows.add(only);
                    return rows;
                }
                int first = topicTotal - topicTotal / 3;
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("topic", "lifesafety");
                a.put("c", first);
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("topic", "sexedu");
                b.put("c", topicTotal - first);
                rows.add(a);
                rows.add(b);
                return rows;
            }
        };
    }

    /** 递归收集所有字段名（键），用于"字段名不得是个体标识"的断言。 */
    private static void collectKeys(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.add(String.valueOf(e.getKey()));
                collectKeys(e.getValue(), out);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) collectKeys(item, out);
        }
    }

    private static EduUserClassMapper fakeUserClassMapper() {
        return new EduUserClassMapper() {
            @Override
            public String selectClassIdByUserId(String userId) {
                return "class_demo_1";
            }

            @Override
            public int countStudentsOfClass(String classId) {
                return 12;
            }
        };
    }

    private EduClassStatService service(int answered, int correct) {
        return service(answered, correct, 8);
    }

    private EduClassStatService service(int answered, int correct, int topicTotal) {
        EduClassStatService svc = new EduClassStatService(
                fakeStatMapper(answered, correct, topicTotal), fakeUserClassMapper());
        setField(svc, "minCountToShow", 3);
        return svc;
    }

    private static void setField(Object target, String name, int value) {
        try {
            Field f = EduClassStatService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("设置测试字段失败：" + name, e);
        }
    }

    @Test
    @DisplayName("响应里没有任何学生个体标识（扫字段名 + 扫 id 形状的值）")
    void responseCarriesNoStudentIdentifier() throws Exception {
        Map<String, Object> stat = service(20, 15).classStat("class_demo_1", 7);

        // ① 字段名不得是个体标识。注意只扫"键"——privacyNote 正文里会出现"姓名"这个词
        //    （正是用来声明不泄露它），拿子串去扫整段 JSON 会把自己写的承诺当成泄露。
        Set<String> keys = new java.util.HashSet<>();
        collectKeys(stat, keys);
        for (String key : keys) {
            String lower = key.toLowerCase();
            for (String forbidden : FORBIDDEN_FIELD_NAMES) {
                assertFalse(lower.equals(forbidden) || lower.endsWith(forbidden),
                        "班级统计响应的字段名「" + key + "」像是个体标识，字段集：" + keys);
            }
        }

        // ② 值里不得出现 id 形状的标识（user_xxx / session_xxx 这类）
        String json = new ObjectMapper().writeValueAsString(stat);
        assertFalse(json.contains("user_"), "值里出现了用户标识：" + json);
        assertFalse(json.contains("session_"), "值里出现了会话标识：" + json);
    }

    @Test
    @DisplayName("响应 key 恰好等于白名单（加字段即红）")
    void responseKeysAreWhitelisted() {
        Map<String, Object> stat = service(20, 15).classStat("class_demo_1", 7);
        assertEquals(ALLOWED_KEYS, stat.keySet(), "班级统计 DTO 字段集变了，请确认新字段不是个体数据");
        for (Object item : (List<?>) stat.get("weakKnowledgePoints")) {
            assertTrue(Set.of("point", "wrong", "share").containsAll(((Map<?, ?>) item).keySet()));
        }
        for (Object item : (List<?>) stat.get("topicDistribution")) {
            assertTrue(Set.of("topic", "count", "share").containsAll(((Map<?, ?>) item).keySet()));
        }
    }

    @Test
    @DisplayName("样本充足：正确率与占比可算")
    void computesAggregatesWhenSampleIsEnough() {
        Map<String, Object> stat = service(20, 15).classStat("class_demo_1", 7);
        assertEquals(20, stat.get("quizAnswered"));
        assertEquals(15, stat.get("quizCorrect"));
        assertEquals(75.0, stat.get("quizAccuracy"));
        assertEquals(Boolean.FALSE, stat.get("quizHidden"));
        assertNotNull(stat.get("topicTotal"));
        assertEquals(8, stat.get("topicTotal"));
        assertEquals(Boolean.FALSE, stat.get("topicHidden"));
        assertFalse(((List<?>) stat.get("weakKnowledgePoints")).isEmpty());
    }

    @Test
    @DisplayName("k-匿名：样本不足时隐藏正确率、知识点与专题数值")
    void hidesWhenSampleTooSmall() {
        Map<String, Object> stat = service(1, 1, 1).classStat("class_demo_1", 7);
        // 全班只做了 1 道题时，"正确率 100%" 等同于"那个学生答对了" → 必须藏
        assertNull(stat.get("quizAccuracy"), "样本不足时不能给出正确率");
        assertEquals(Boolean.TRUE, stat.get("quizHidden"));
        assertEquals(Boolean.TRUE, stat.get("weakPointsHidden"));
        assertTrue(((List<?>) stat.get("weakKnowledgePoints")).isEmpty());
        assertEquals(Boolean.TRUE, stat.get("topicHidden"));
        assertNull(stat.get("topicTotal"));
        assertTrue(String.valueOf(stat.get("note")).contains("k-匿名"));
    }

    @Test
    @DisplayName("k-匿名按维度各自判定：刷题样本不足，不影响专题占比照常出图")
    void suppressionIsPerDimension() {
        Map<String, Object> stat = service(1, 1, 10).classStat("class_demo_1", 7);
        assertEquals(Boolean.TRUE, stat.get("quizHidden"), "刷题样本 1 条 → 正确率隐藏");
        assertEquals(Boolean.FALSE, stat.get("topicHidden"), "专题样本 10 条 → 占比照常展示");
        assertNotNull(stat.get("topicTotal"));
        assertFalse(((List<?>) stat.get("topicDistribution")).isEmpty());
    }

    @Test
    @DisplayName("窗口天数被钳制；数据库异常时降级为 0 而不是抛出去")
    void clampsWindowAndDegradesGracefully() {
        assertEquals(30, service(20, 15).classStat("class_demo_1", 9999).get("windowDays"));

        EduClassStatMapper broken = new EduClassStatMapper() {
            @Override
            public int quizAnsweredTotal(String classId, LocalDateTime from) {
                throw new IllegalStateException("表不存在");
            }

            @Override
            public int quizCorrectTotal(String classId, LocalDateTime from) {
                throw new IllegalStateException("表不存在");
            }

            @Override
            public List<Map<String, Object>> weakKnowledgePoints(String classId, LocalDateTime from) {
                throw new IllegalStateException("表不存在");
            }

            @Override
            public List<Map<String, Object>> topicDistribution(String classId, LocalDate from) {
                throw new IllegalStateException("表不存在");
            }
        };
        EduClassStatService svc = new EduClassStatService(broken, fakeUserClassMapper());
        setField(svc, "minCountToShow", 3);
        Map<String, Object> stat = svc.classStat("class_demo_1", 7);
        assertEquals(0, stat.get("quizAnswered"), "查询失败应按 0 计，而不是把 500 抛给教师端");
        assertEquals(Boolean.TRUE, stat.get("quizHidden"));
    }
}
