package com.demo.edu.teacher;

import com.demo.edu.auth.EduContext;
import com.demo.edu.mapper.EduFeedbackMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 教师错误反馈测试：<b>系统不会自动把对话内容写进反馈表</b>。
 *
 * <h3>为什么这条要测</h3>
 * "教师反馈"看着中性，但它是最容易变成"悄悄收集学生对话"的口子：
 * 只要有人顺手把原始问答一起塞进请求体或表里，隐私承诺（不存对话）就破了。
 * 所以这里用假 Mapper<b>捕获真正落库的三个字段</b>，并故意在请求体里塞入
 * {@code content} / {@code conversation} / {@code userId} 等字段，断言它们一个都没被转发。
 *
 * <p>另外验证：班级只能来自 token（请求体里塞 classId 无效）、超长内容截断、空内容不落库。
 */
class TeacherFeedbackTest {

    /** 捕获真正传进 Mapper 的参数，供断言。 */
    private static class CapturingMapper implements EduFeedbackMapper {
        final List<String[]> captured = new ArrayList<>();

        @Override
        public int insert(String classId, String libId, String message) {
            captured.add(new String[]{classId, libId, message});
            return 1;
        }

        @Override
        public List<Map<String, Object>> listByClass(String classId) {
            return List.of();
        }
    }

    private CapturingMapper mapper;
    private EduTeacherController controller;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new CapturingMapper();
        controller = new EduTeacherController();
        setField(controller, "feedbackMapper", mapper);
        setField(controller, "businessMetrics", null); // 埋点缺失时也必须能跑
        EduContext.setClassId("class_demo_1");
    }

    @AfterEach
    void tearDown() {
        EduContext.clear();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = EduTeacherController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("只落库教师手写的三个字段，请求体里多塞的对话内容一律不转发")
    void onlyStoresTeacherProvidedFields() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "关于『青春期心理变化』的回答引用了不适龄材料");
        body.put("libId", "sexedu");
        // 下面这些是"如果有人顺手把对话也带上"的情形——必须被无视
        body.put("content", "学生：我最近很烦。AI：青春期情绪波动是正常的……");
        body.put("conversation", "整段原始对话都在这里");
        body.put("userId", "user_demo_1");
        body.put("classId", "class_别的班");

        Map<String, Object> resp = controller.feedback(body);

        assertEquals(200, resp.get("code"));
        assertEquals(1, mapper.captured.size(), "应只插入一条");
        String[] row = mapper.captured.get(0);
        assertEquals("class_demo_1", row[0], "班级只能来自 token（EduContext），请求体里的 classId 必须被无视");
        assertEquals("sexedu", row[1]);
        assertEquals("关于『青春期心理变化』的回答引用了不适龄材料", row[2]);
        for (String part : row) {
            if (part == null) continue;
            assertTrue(!part.contains("整段原始对话") && !part.contains("学生：我最近很烦"),
                    "反馈表里出现了对话原文：" + part);
        }
    }

    @Test
    @DisplayName("超长反馈截断到 1000 字（列宽上限），不因超长把插入打挂")
    void truncatesLongMessage() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "很".repeat(2500));
        Map<String, Object> resp = controller.feedback(body);
        assertEquals(200, resp.get("code"));
        assertEquals(1000, mapper.captured.get(0)[2].length());
    }

    @Test
    @DisplayName("空反馈不落库")
    void rejectsEmptyMessage() {
        Map<String, Object> resp = controller.feedback(new LinkedHashMap<>());
        assertEquals(400, resp.get("code"));
        assertTrue(mapper.captured.isEmpty(), "空反馈不该产生任何记录");
    }

    @Test
    @DisplayName("未通过鉴权（无班级上下文）时不落库")
    void rejectsWithoutClassContext() {
        EduContext.clear();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "随便写的反馈");
        Map<String, Object> resp = controller.feedback(body);
        assertEquals(401, resp.get("code"));
        assertTrue(mapper.captured.isEmpty());
        assertNotNull(resp.get("msg"));
    }
}
