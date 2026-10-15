package com.demo.edu;

import com.demo.edu.mapper.EduCrisisDailyMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import com.demo.edu.safety.CrisisCountRecorder;
import com.demo.edu.safety.CrisisGuard;
import com.demo.edu.safety.CrisisGuardProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 危机兜底行为测试（不依赖 Spring / 数据库 / LLM）。
 *
 * <p>这里守的是整个教育域最不能出错的一条路径：<b>enforce 模式下命中就必须短路</b>。
 * 它是本项目最典型的"静默失效"高危点——模式配错时页面和日志都看不出区别，
 * 只有一个说要自杀的孩子会得到一段模型自由生成的文字。
 */
class CrisisGuardTest {

    private CrisisGuardProperties props;
    private AtomicInteger increments;
    /** 最近一次 increment 写入的等级 */
    private final AtomicInteger lastLevel = new AtomicInteger();
    /** 最近一次 raiseLevel 写入的等级（0 表示没被调用过） */
    private final AtomicInteger levelRaised = new AtomicInteger();
    private CrisisGuard guard;

    @BeforeEach
    void setUp() {
        props = new CrisisGuardProperties();
        increments = new AtomicInteger();
        guard = buildGuard(props, "class_demo_1");
    }

    private CrisisGuard buildGuard(CrisisGuardProperties properties, String classId) {
        EduUserClassMapper userClassMapper = new EduUserClassMapper() {
            @Override
            public String selectClassIdByUserId(String userId) {
                return classId;
            }

            @Override
            public int countStudentsOfClass(String classId) {
                return 6;
            }
        };
        EduCrisisDailyMapper crisisMapper = new EduCrisisDailyMapper() {
            @Override
            public int increment(String classId, LocalDate statDate, String signalType, int level) {
                increments.incrementAndGet();
                lastLevel.set(level);
                return 1;
            }

            @Override
            public int raiseLevel(String classId, LocalDate statDate, String signalType, int level) {
                levelRaised.set(level);
                return 1;
            }

            @Override
            public List<Map<String, Object>> dailyCounts(String classId, LocalDate from) {
                return List.of();
            }

            @Override
            public int totalSince(String classId, LocalDate from) {
                return 0;
            }
        };
        CrisisCountRecorder recorder = new CrisisCountRecorder(crisisMapper, userClassMapper);
        return new CrisisGuard(properties, recorder);
    }

    @Test
    @DisplayName("enforce：命中即返回固定话术，且话术含两条热线")
    void enforceReturnsScript() {
        Optional<String> reply = guard.check("user_demo", "我不想活了");
        assertTrue(reply.isPresent(), "enforce 模式下必须短路");
        assertTrue(reply.get().contains("12356"), "话术必须含全国心理援助热线");
        assertTrue(reply.get().contains("12355"), "话术必须含共青团青少年服务热线");
        assertTrue(reply.get().contains("成年人"), "话术必须引导告诉可信任的成年人");
        assertEquals(1, increments.get(), "应记一次计数");
    }

    @Test
    @DisplayName("去重：同一学生同一天同一类型只计一次（避免把一个人夸大成十个人）")
    void deduplicatesPerUserPerDay() {
        guard.check("user_demo", "我想死");
        guard.check("user_demo", "活着没意思");
        guard.check("user_demo", "我不想活了");
        assertEquals(1, increments.get(), "同一学生当日应只累计一次");
    }

    @Test
    @DisplayName("log 模式：照常走 LLM（返回 empty），且不计数")
    void logModeDoesNotReplaceOrCount() {
        props.setMode("log");
        assertTrue(guard.check("user_demo", "我想死").isEmpty(), "log 模式不该替换回复");
        assertEquals(0, increments.get(),
                "log 模式并没有向学生发出危机引导，因此不该计数（计数语义是「已引导」）");
    }

    @Test
    @DisplayName("总开关关闭：完全不动")
    void disabledDoesNothing() {
        props.setEnabled(false);
        assertTrue(guard.check("user_demo", "我想死").isEmpty());
        assertEquals(0, increments.get());
    }

    @Test
    @DisplayName("风险等级随词表分档：方法性表述=3、直接意念=2、无望=1")
    void recordsRiskLevel() {
        guard.check("user_a", "我想死");
        assertEquals(2, lastLevel.get(), "『想死』是直接意念 → 2 级");

        guard.check("user_b", "活着没意思");
        assertEquals(1, lastLevel.get(), "『活着没意思』是无望感 → 1 级");

        guard.check("user_c", "我想割腕");
        assertEquals(3, lastLevel.get(), "『割腕』是方法性表述 → 3 级");
    }

    @Test
    @DisplayName("同一学生当日等级只升不降：先低落、后说出方法性表述 → 计数不变但等级上调")
    void raisesLevelWithoutDoubleCounting() {
        guard.check("user_demo", "活着没意思");   // 1 级，计 1 条
        assertEquals(1, increments.get());
        assertEquals(0, levelRaised.get(), "第一次是计数，不该走 raiseLevel");

        guard.check("user_demo", "我想割腕");     // 3 级，同一学生同一天
        assertEquals(1, increments.get(), "等级更高也不能变成第 2 条——那是同一个孩子");
        assertEquals(3, levelRaised.get(), "等级必须抬到 3，否则教师端看到的关注度低于真实情况");

        guard.check("user_demo", "活着好累");     // 1 级，低于已记录的 3 级
        assertEquals(1, increments.get());
        assertEquals(3, levelRaised.get(), "低等级不能把已记录的等级压回去");
    }

    @Test
    @DisplayName("普通内容不命中")
    void normalTextPassesThrough() {
        assertTrue(guard.check("user_demo", "今天杭州多少度").isEmpty());
        assertTrue(guard.check("user_demo", "《活着》这本书讲什么").isEmpty());
        assertEquals(0, increments.get());
    }

    @Test
    @DisplayName("学生未归属班级：仍必须给出引导话术，只是不计数")
    void stillRepliesWhenNoClassMapping() {
        CrisisGuard orphan = buildGuard(props, null);
        Optional<String> reply = orphan.check("user_unknown", "我想死");
        assertTrue(reply.isPresent(), "统计不能成为给不给热线的前提");
        assertTrue(reply.get().contains("12356"));
    }
}
