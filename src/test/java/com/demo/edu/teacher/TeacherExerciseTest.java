package com.demo.edu.teacher;

import com.demo.edu.mapper.EduExerciseMapper;
import com.demo.entity.QuizPaperEntity;
import com.demo.entity.QuizQuestionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 教师自编习题：创建 / 发布 / 修改的权限与内容约束。
 *
 * <p>不用 Spring：{@code BaseMapper} 用 Mockito 打桩（它方法太多，手写桩不现实），
 * 自己的 {@code EduExerciseMapper} 也一并打桩以便捕获调用参数。
 */
class TeacherExerciseTest {

    private static final String CLASS = "class_demo_1";
    private static final String TEACHER = "teacher1";

    private EduExerciseMapper exerciseMapper;
    private TeacherExerciseService service;

    @BeforeEach
    void setUp() {
        exerciseMapper = mock(EduExerciseMapper.class);
        service = new TeacherExerciseService(exerciseMapper);

        // 模拟 @SelectKey 的"插入后回读自增主键"：
        // 用自己的 Mapper 方法就能这样打桩（BaseMapper 的 insert 是 default 方法，桩不了）
        when(exerciseMapper.insertPaper(any(QuizPaperEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, QuizPaperEntity.class).setId(9001L);
            return 1;
        });
        when(exerciseMapper.insertQuestion(any(QuizQuestionEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, QuizQuestionEntity.class).setId(9002L);
            return 1;
        });
    }

    private static Map<String, Object> draft(String stem, String qType, String answer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stem", stem);
        body.put("qType", qType);
        body.put("answer", answer);
        body.put("options", "[\"A. 1\",\"B. 2\"]");
        body.put("knowledgePoint", "一元一次方程");
        return body;
    }

    @Test
    @DisplayName("创建习题：默认直接发布，且 creator/classId 来自参数（不是请求体）")
    void createPublishesByDefault() {
        Map<String, Object> result = service.create(CLASS, TEACHER, draft("1+1=?", "SINGLE", "A"));

        assertTrue(Boolean.TRUE.equals(result.get("success")), String.valueOf(result.get("error")));
        assertEquals(9002L, result.get("questionId"));
        ArgumentCaptor<Long> qid = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> cls = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> creator = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> published = ArgumentCaptor.forClass(Integer.class);
        verify(exerciseMapper).insert(qid.capture(), cls.capture(), creator.capture(),
                anyString(), published.capture(), any());
        assertEquals(9002L, qid.getValue());
        assertEquals(CLASS, cls.getValue());
        assertEquals(TEACHER, creator.getValue());
        assertEquals(1, published.getValue(), "默认应当直接发布");
    }

    @Test
    @DisplayName("创建习题：题干/答案为空 → 400，且不落库")
    void createRejectsBlank() {
        Map<String, Object> body = new HashMap<>();
        body.put("qType", "SINGLE");
        Map<String, Object> result = service.create(CLASS, TEACHER, body);
        assertFalse(Boolean.TRUE.equals(result.get("success")));
        assertEquals(400, result.get("code"));
        verify(exerciseMapper, never()).insertPaper(any(QuizPaperEntity.class));
    }

    @Test
    @DisplayName("简答题本期不开放（自动判分需要接入 LLM，否则会污染学情）")
    void essayIsNotSupportedYet() {
        Map<String, Object> result = service.create(CLASS, TEACHER, draft("谈谈你的看法", "ESSAY", "要点"));
        assertFalse(Boolean.TRUE.equals(result.get("success")));
        assertEquals(400, result.get("code"));
        assertTrue(String.valueOf(result.get("error")).contains("题型"),
                "错误信息要说清是题型问题：" + result.get("error"));
        verify(exerciseMapper, never()).insertPaper(any(QuizPaperEntity.class));
    }

    @Test
    @DisplayName("创建习题：内容命中安全词表 → 拒绝保存（不是发了以后再说）")
    void createRunsContentSafety() {
        Map<String, Object> harmful = draft("如果你不想活了会怎么做", "SINGLE", "A");
        Map<String, Object> result = service.create(CLASS, TEACHER, harmful);

        assertFalse(Boolean.TRUE.equals(result.get("success")));
        assertEquals(400, result.get("code"));
        verify(exerciseMapper, never()).insertPaper(any(QuizPaperEntity.class));
        verify(exerciseMapper, never()).insert(anyLong(), anyString(), anyString(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("发布：别人的题被拒（403），且不写库")
    void publishRejectsOtherTeachersQuestion() {
        when(exerciseMapper.selectByQuestionId(9002L)).thenReturn(row("class_demo_1", "other_teacher", 0));
        Map<String, Object> body = new HashMap<>();
        body.put("questionId", 9002L);

        Map<String, Object> result = service.publish(CLASS, TEACHER, body);
        assertFalse(Boolean.TRUE.equals(result.get("success")));
        assertEquals(403, result.get("code"));
        verify(exerciseMapper, never()).updatePublish(anyLong(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("发布：别的班的题被拒（403）——请求体塞别的 questionId 也没用")
    void publishRejectsOtherClassQuestion() {
        when(exerciseMapper.selectByQuestionId(9002L)).thenReturn(row("class_other", TEACHER, 0));
        Map<String, Object> body = new HashMap<>();
        body.put("questionId", 9002L);

        Map<String, Object> result = service.publish(CLASS, TEACHER, body);
        assertEquals(403, result.get("code"));
        verify(exerciseMapper, never()).updatePublish(anyLong(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("发布：本班的题放行，且只带 published 时保留原有截止时间")
    void publishKeepsExistingExpireTime() {
        LocalDateTime expire = LocalDateTime.now().plusDays(3).withNano(0);
        when(exerciseMapper.selectByQuestionId(9002L)).thenReturn(row(CLASS, TEACHER, 0, expire));
        Map<String, Object> body = new HashMap<>();
        body.put("questionId", 9002L);
        body.put("published", true);

        Map<String, Object> result = service.publish(CLASS, TEACHER, body);
        assertTrue(Boolean.TRUE.equals(result.get("success")));

        ArgumentCaptor<LocalDateTime> expireCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(exerciseMapper).updatePublish(eq(9002L), eq(CLASS), eq(1), expireCaptor.capture());
        assertEquals(expire, expireCaptor.getValue(),
                "只改发布状态不该把原有截止时间清掉——那是\"发布\"这个动作的隐性副作用");
    }

    @Test
    @DisplayName("修改：已有人作答时不许改题干/答案（会与已提交记录对不上）")
    void updateLocksContentOnceAnswered() {
        when(exerciseMapper.selectByQuestionId(9002L)).thenReturn(row(CLASS, TEACHER, 1));
        when(exerciseMapper.countAnswers(9002L)).thenReturn(3);

        Map<String, Object> body = new HashMap<>();
        body.put("questionId", 9002L);
        body.put("stem", "改成另一道题");

        Map<String, Object> result = service.update(CLASS, TEACHER, body);
        assertFalse(Boolean.TRUE.equals(result.get("success")));
        assertEquals(409, result.get("code"));
        assertTrue(String.valueOf(result.get("error")).contains("3"),
                "错误信息应说明已有多少条作答：" + result.get("error"));
        verify(exerciseMapper, never()).updateQuestionContent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("修改：已有人作答时仍可改解析（答案不变，不破坏已有记录）")
    void updateAllowsExplanationWhenAnswered() {
        when(exerciseMapper.selectByQuestionId(9002L)).thenReturn(row(CLASS, TEACHER, 1));
        when(exerciseMapper.countAnswers(9002L)).thenReturn(3);

        Map<String, Object> body = new HashMap<>();
        body.put("questionId", 9002L);
        body.put("explanation", "更清楚的解析");

        Map<String, Object> result = service.update(CLASS, TEACHER, body);
        assertTrue(Boolean.TRUE.equals(result.get("success")), String.valueOf(result.get("error")));
        assertTrue(Boolean.TRUE.equals(result.get("contentLocked")));
        verify(exerciseMapper).updateQuestionContent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("修改：没人作答时可以改题干")
    void updateAllowsContentWhenUnanswered() {
        when(exerciseMapper.selectByQuestionId(9002L)).thenReturn(row(CLASS, TEACHER, 0));
        when(exerciseMapper.countAnswers(9002L)).thenReturn(0);

        Map<String, Object> body = new HashMap<>();
        body.put("questionId", 9002L);
        body.put("stem", "换个说法");

        Map<String, Object> result = service.update(CLASS, TEACHER, body);
        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertFalse(Boolean.TRUE.equals(result.get("contentLocked")));
    }

    private static Map<String, Object> row(String classId, String creator, int published) {
        return row(classId, creator, published, null);
    }

    private static Map<String, Object> row(String classId, String creator, int published, LocalDateTime expire) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("classId", classId);
        row.put("creator", creator);
        row.put("published", published);
        row.put("expireTime", expire);
        return row;
    }
}
