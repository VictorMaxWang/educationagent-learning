package com.demo.study.quiz;

import com.demo.entity.QuizAnswerEntity;
import com.demo.entity.QuizQuestionEntity;
import com.demo.mapper.QuizAnswerMapper;
import com.demo.mapper.QuizQuestionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 已确认测试接缝：真实QuizService重做入口，历史答卷只追加，不能用修改旧成绩来移出错题。 */
class QuizAppendOnlyHistoryTest {
    @Test void aCorrectRedoPreservesEveryOriginalAnswerAndAppendsTheNewAttempt() {
        var questions = mock(QuizQuestionMapper.class);
        var answers = mock(QuizAnswerMapper.class);
        var service = new QuizService();
        ReflectionTestUtils.setField(service, "questionMapper", questions);
        ReflectionTestUtils.setField(service, "answerMapper", answers);
        var question = new QuizQuestionEntity();
        question.setId(11L); question.setPaperId(7L); question.setQuestionNo(1);
        question.setQType("fill"); question.setStem("8×3的结果是多少？"); question.setAnswer("24"); question.setHasAnswer(1);
        var first = new QuizAnswerEntity("classroom", 7L, 11L, "11", 0); first.setId(41L);
        first.setAnsweredAt(LocalDateTime.of(2026, 10, 16, 12, 0));
        var second = new QuizAnswerEntity("classroom", 7L, 11L, "23", 0); second.setId(42L);
        second.setAnsweredAt(LocalDateTime.of(2026, 10, 16, 12, 1));
        when(questions.selectById(11L)).thenReturn(question);
        when(questions.selectList(any())).thenReturn(List.of(question));
        when(answers.selectList(any())).thenReturn(List.of(first, second));
        when(answers.insert(any(QuizAnswerEntity.class))).thenAnswer(call -> {
            ((QuizAnswerEntity) call.getArgument(0)).setId(43L); return 1;
        });

        var result = service.mistakeRedo("classroom", 7L, 11L, "24");
        assertTrue(result.correct()); assertTrue(result.removed());
        assertEquals(0, first.getIsCorrect()); assertEquals(0, second.getIsCorrect());
        assertEquals("11", first.getUserAnswer()); assertEquals("23", second.getUserAnswer());
        assertEquals(LocalDateTime.of(2026, 10, 16, 12, 0), first.getAnsweredAt());
        var appended = ArgumentCaptor.forClass(QuizAnswerEntity.class);
        verify(answers).insert(appended.capture());
        assertEquals("24", appended.getValue().getUserAnswer()); assertEquals(1, appended.getValue().getIsCorrect());
        verify(answers, never()).updateById(any(QuizAnswerEntity.class));
        verify(answers, never()).delete(any());
    }
}
