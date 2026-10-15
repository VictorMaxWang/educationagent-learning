package com.demo.edu.teacher;

import com.demo.edu.mapper.EduCrisisDailyMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 教师端隐私隔离测试 —— <b>答辩时可以现场跑的那一条</b>。
 *
 * <p>它不测"我们承诺不看学生数据"，而是测<b>结构上取不到</b>：
 * <ol>
 *   <li>把聚合响应序列化成 JSON，断言<b>不含</b> userId / sessionId / uid / user_ 等任何个体标识子串；</li>
 *   <li>断言响应的 key 恰好等于白名单——将来谁往 DTO 里加了字段，这条会立刻红；</li>
 *   <li>断言 k-匿名生效：合计不足阈值时不出数。</li>
 * </ol>
 *
 * <p>不引入 Spring 上下文（那会去开 Lucene 索引，可能抢写锁弄坏正在运行的后端），
 * 用匿名实现替换两个 Mapper；{@code minCountToShow} 是 {@code @Value} 注入的，
 * 测试里直接反射赋值——比为了一个阈值把整个上下文拉起来划算得多。
 */
class EduPrivacyTest {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "classId", "windowDays", "studentCount", "total", "suppressed", "maxLevel", "note",
            "days", "privacyNote");

    private static final List<String> FORBIDDEN_SUBSTRINGS = List.of(
            "userId", "user_id", "sessionId", "session_id", "uid", "user_");

    private EduTeacherAggregateService service(int total, int perDay) {
        return service(total, perDay, 3);
    }

    private EduTeacherAggregateService service(int total, int perDay, int minCountToShow) {
        EduCrisisDailyMapper crisisMapper = new EduCrisisDailyMapper() {
            @Override
            public int increment(String classId, LocalDate statDate, String signalType, int level) {
                return 1;
            }

            @Override
            public int raiseLevel(String classId, LocalDate statDate, String signalType, int level) {
                return 1;
            }

            @Override
            public List<Map<String, Object>> dailyCounts(String classId, LocalDate from) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("d", LocalDate.now().toString());
                row.put("c", perDay);
                row.put("l", 3); // 当日最高等级（3 = 方法性表述）
                return List.of(row);
            }

            @Override
            public int totalSince(String classId, LocalDate from) {
                return total;
            }
        };
        EduUserClassMapper userClassMapper = new EduUserClassMapper() {
            @Override
            public String selectClassIdByUserId(String userId) {
                return "class_demo_1";
            }

            @Override
            public int countStudentsOfClass(String classId) {
                return 6;
            }
        };
        EduTeacherAggregateService svc = new EduTeacherAggregateService(crisisMapper, userClassMapper);
        setField(svc, "minCountToShow", minCountToShow);
        return svc;
    }

    private static void setField(Object target, String name, int value) {
        try {
            Field f = EduTeacherAggregateService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("设置测试字段失败：" + name, e);
        }
    }

    @Test
    @DisplayName("响应体序列化后不含任何学生个体标识")
    void responseCarriesNoStudentIdentifier() throws Exception {
        Map<String, Object> summary = service(5, 3).crisisSummary("class_demo_1", 7);
        String json = new ObjectMapper().writeValueAsString(summary);

        for (String forbidden : FORBIDDEN_SUBSTRINGS) {
            assertFalse(json.contains(forbidden),
                    "教师端响应里出现了个体标识「" + forbidden + "」：" + json);
        }
        // 教师端只能看到班级级数字
        assertTrue(json.contains("class_demo_1"));
    }

    @Test
    @DisplayName("响应的 key 恰好等于白名单（加字段即红）")
    void responseKeysAreWhitelisted() {
        Map<String, Object> summary = service(5, 3).crisisSummary("class_demo_1", 7);
        assertEquals(ALLOWED_KEYS, summary.keySet(),
                "教师端 DTO 的字段集变了——请确认新字段不是个体数据");

        Object days = summary.get("days");
        assertTrue(days instanceof List<?>);
        for (Object o : (List<?>) days) {
            Map<?, ?> day = (Map<?, ?>) o;
            assertTrue(Set.of("date", "count", "hidden", "reason", "level").containsAll(day.keySet()),
                    "逐日项出现未预期字段：" + day.keySet());
        }
    }

    @Test
    @DisplayName("k-匿名：合计不足阈值时隐藏数值")
    void suppressesSmallAggregates() {
        Map<String, Object> summary = service(2, 2, 3).crisisSummary("class_demo_1", 7);
        assertEquals(Boolean.TRUE, summary.get("suppressed"));
        assertNull(summary.get("total"), "不足 3 条时不应给出合计数字");
        assertTrue(String.valueOf(summary.get("note")).contains("样本不足"));

        // 计数藏起来，但等级照常给出：等级无法反推到具体某个学生，
        // 而"本班出现过 3 级信号"恰是教师最需要知道的（关怀义务）。
        // 这条断言就是在钉住这个取舍。
        assertEquals(3, summary.get("maxLevel"), "等级不参与 k-匿名隐藏");
        List<?> days = (List<?>) summary.get("days");
        assertEquals(1, days.size(), "逐日条目仍然给出（只是计数为空）");
        Map<?, ?> day = (Map<?, ?>) days.get(0);
        assertNull(day.get("count"), "计数按 k-匿名隐藏");
        assertEquals(Boolean.TRUE, day.get("hidden"));
        assertEquals(3, day.get("level"), "等级照常给出");
    }

    @Test
    @DisplayName("达到阈值时给出合计，但单日不足 2 条仍不展示")
    void hidesSingleEventDays() {
        Map<String, Object> big = service(5, 3).crisisSummary("class_demo_1", 7);
        assertEquals(Boolean.FALSE, big.get("suppressed"));
        assertEquals(5, big.get("total"));

        Map<String, Object> single = service(5, 1).crisisSummary("class_demo_1", 7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) single.get("days");
        assertEquals(1, days.size());
        assertEquals(Boolean.TRUE, days.get(0).get("hidden"), "单日 1 条不展示，否则可能被反推到个人");
        assertNull(days.get(0).get("count"));
    }

    @Test
    @DisplayName("窗口天数被钳制，数据库异常时降级为不可用而不是抛出去")
    void clampsWindowAndDegradesGracefully() {
        Map<String, Object> summary = service(5, 3).crisisSummary("class_demo_1", 9999);
        assertEquals(30, summary.get("windowDays"));

        EduTeacherAggregateService broken = new EduTeacherAggregateService(
                new EduCrisisDailyMapper() {
                    @Override
                    public int increment(String classId, LocalDate statDate, String signalType, int level) {
                        return 1;
                    }

                    @Override
                    public int raiseLevel(String classId, LocalDate statDate, String signalType, int level) {
                        return 1;
                    }

                    @Override
                    public List<Map<String, Object>> dailyCounts(String classId, LocalDate from) {
                        return List.of();
                    }

                    @Override
                    public int totalSince(String classId, LocalDate from) {
                        throw new IllegalStateException("表不存在");
                    }
                },
                new EduUserClassMapper() {
                    @Override
                    public String selectClassIdByUserId(String userId) {
                        return "class_demo_1";
                    }

                    @Override
                    public int countStudentsOfClass(String classId) {
                        return 6;
                    }
                });
        Map<String, Object> degraded = broken.crisisSummary("class_demo_1", 7);
        assertEquals(Boolean.TRUE, degraded.get("suppressed"));
        assertTrue(String.valueOf(degraded.get("note")).contains("不可用"));
    }
}
