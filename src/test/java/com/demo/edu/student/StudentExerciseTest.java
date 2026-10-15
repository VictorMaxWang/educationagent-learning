package com.demo.edu.student;

import com.demo.edu.knowledge.EduKnowledgeAccessProperties;
import com.demo.edu.knowledge.EduKnowledgeAccessService;
import com.demo.edu.mapper.EduDomainAccessMapper;
import com.demo.edu.mapper.EduExerciseMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 学生侧「班级作业」：只读本班、不含答案、重做覆盖、客观题判分。
 *
 * <p>其中 {@link #listQueryNeverProjectsTheAnswer()} 是一条<b>结构性</b>测试：
 * 直接读 Mapper 注解里的 SQL 文本，断言列表查询<b>不投影</b> {@code answer}/{@code explanation}——
 * 这比"断言返回体里没有答案"更强，因为它守的是"根本没查出来"。
 */
class StudentExerciseTest {

    private static final String CLASS = "class_demo_1";
    private static final String STUDENT = "stu_demo_01";

    private EduExerciseMapper exerciseMapper;
    private StudentExerciseService service;

    @BeforeEach
    void setUp() {
        exerciseMapper = mock(EduExerciseMapper.class);

        // 班级解析复用知识库访问校验那套（精确优先 + 去后缀），这里用桩模拟 user_demo_1 属于演示班
        Map<String, String> mapping = new HashMap<>();
        mapping.put(STUDENT, CLASS);
        EduUserClassMapper userClassMapper = new EduUserClassMapper() {
            @Override
            public String selectClassIdByUserId(String userId) {
                return mapping.get(userId);
            }

            @Override
            public int countStudentsOfClass(String classId) {
                return mapping.size();
            }
        };
        EduDomainAccessMapper domainAccessMapper = new EduDomainAccessMapper() {
            @Override
            public Integer selectEnabled(String classId, String domainId) {
                return null;
            }

            @Override
            public int upsert(String classId, String domainId, int enabled) {
                return 1;
            }

            @Override
            public List<Map<String, Object>> listByClass(String classId) {
                return List.of();
            }
        };
        EduKnowledgeAccessService access = new EduKnowledgeAccessService(
                new EduKnowledgeAccessProperties(), userClassMapper, domainAccessMapper);
        service = new StudentExerciseService(exerciseMapper, access);
    }

    private static Map<String, Object> question(String qType, String answer, String explanation) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("questionId", 9002L);
        q.put("paperId", 9001L);
        q.put("qType", qType);
        q.put("answer", answer);
        q.put("explanation", explanation);
        return q;
    }

    @Test
    @DisplayName("列表查询的 SQL 不投影标准答案与解析（结构性断言：根本没查出来）")
    void listQueryNeverProjectsTheAnswer() throws Exception {
        Method m = EduExerciseMapper.class.getMethod("listPublishedForClass", String.class);
        String sql = String.join(" ", m.getAnnotation(Select.class).value());

        assertFalse(sql.contains("q.answer"), "列表 SQL 不该查标准答案：" + sql);
        assertFalse(sql.toLowerCase().contains("explanation"), "列表 SQL 不该查解析：" + sql);
        assertTrue(sql.contains("e.class_id = #{classId}"), "班级必须是 where 条件：" + sql);
        assertTrue(sql.contains("e.published = 1"), "只查已发布：" + sql);
    }

    @Test
    @DisplayName("未识别到班级 → 403（不返回任何题目）")
    void listWithoutClassIsRejected() {
        Map<String, Object> result = service.list("someone_without_class");
        assertFalse(Boolean.TRUE.equals(result.get("success")));
        assertEquals(403, result.get("code"));
        verify(exerciseMapper, never()).listPublishedForClass(anyString());
    }

    @Test
    @DisplayName("列表：班级由服务端解析（学生无法指定看哪个班）")
    void listResolvesClassServerSide() {
        when(exerciseMapper.listPublishedForClass(CLASS)).thenReturn(List.of(Map.of("questionId", 1)));
        Map<String, Object> result = service.list(STUDENT);
        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertEquals(CLASS, result.get("classId"));
        verify(exerciseMapper).listPublishedForClass(CLASS);
    }

    @Test
    @DisplayName("提交：不是发布给自己班的题 → 403（班级在 where 里，查不到）")
    void submitRejectsQuestionOfAnotherClass() {
        when(exerciseMapper.findPublishedForJudge(eq(CLASS), anyLong())).thenReturn(null);
        Map<String, Object> result = service.submit(STUDENT, 9002L, "A");
        assertFalse(Boolean.TRUE.equals(result.get("success")));
        assertEquals(403, result.get("code"));
        verify(exerciseMapper, never()).insertAnswer(anyString(), any(), anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("提交：首次作答插入记录，判分结果与答案一起返回")
    void submitInsertsOnFirstAttempt() {
        when(exerciseMapper.findPublishedForJudge(eq(CLASS), anyLong()))
                .thenReturn(question("SINGLE", "A", "解析文本"));
        when(exerciseMapper.updateAnswer(anyString(), anyLong(), anyString(), anyInt())).thenReturn(0);

        Map<String, Object> result = service.submit(STUDENT, 9002L, " a ");

        assertTrue(Boolean.TRUE.equals(result.get("success")), String.valueOf(result.get("error")));
        assertEquals(true, result.get("correct"), "大小写与空白不该影响判分");
        assertEquals("A", result.get("standardAnswer"), "交卷后才给答案");
        assertEquals("解析文本", result.get("explanation"));
        verify(exerciseMapper).insertAnswer(eq(STUDENT), eq(9001L), eq(9002L), eq(" a "), eq(1));
    }

    @Test
    @DisplayName("提交：重做是覆盖而不是新增（否则反复重做会把班级正确率刷歪）")
    void submitOverwritesOnRetry() {
        when(exerciseMapper.findPublishedForJudge(eq(CLASS), anyLong()))
                .thenReturn(question("SINGLE", "A", null));
        when(exerciseMapper.updateAnswer(anyString(), anyLong(), anyString(), anyInt())).thenReturn(1);

        Map<String, Object> result = service.submit(STUDENT, 9002L, "B");
        assertEquals(false, result.get("correct"));
        ArgumentCaptor<Integer> flag = ArgumentCaptor.forClass(Integer.class);
        verify(exerciseMapper).updateAnswer(eq(STUDENT), eq(9002L), eq("B"), flag.capture());
        assertEquals(0, flag.getValue(), "答错要落 is_correct=0");
        verify(exerciseMapper, never()).insertAnswer(anyString(), any(), anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("提交：答案为空 → 400")
    void submitRejectsBlankAnswer() {
        Map<String, Object> result = service.submit(STUDENT, 9002L, "   ");
        assertFalse(Boolean.TRUE.equals(result.get("success")));
        assertEquals(400, result.get("code"));
    }

    @Test
    @DisplayName("客观题判分：单选/判断忽略大小写与标点，多选题与顺序无关")
    void gradingRules() {
        assertTrue(StudentExerciseService.isCorrect("SINGLE", "A", "a"));
        assertTrue(StudentExerciseService.isCorrect("JUDGE", "对", " 对 "));
        assertTrue(StudentExerciseService.isCorrect("SINGLE", "B", "B."));
        assertTrue(StudentExerciseService.isCorrect("MULTI", "AB", "ba"));
        assertTrue(StudentExerciseService.isCorrect("MULTI", "A,B", "BA"));
        assertTrue(StudentExerciseService.isCorrect("MULTI", "ABD", "D、A、B"));
        assertFalse(StudentExerciseService.isCorrect("MULTI", "AB", "A"), "少选算错");
        assertFalse(StudentExerciseService.isCorrect("MULTI", "AB", "ABC"), "多选算错");
        assertFalse(StudentExerciseService.isCorrect("SINGLE", "A", "C"));
        assertFalse(StudentExerciseService.isCorrect("SINGLE", "A", null));
    }
}
