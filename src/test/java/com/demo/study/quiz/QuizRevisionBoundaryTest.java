package com.demo.study.quiz;

import com.demo.edu.classroom.ClassroomException;
import com.demo.edu.classroom.ClassroomQuizRevisionRegistry;
import com.demo.entity.QuizAnswerEntity;
import com.demo.entity.QuizPaperEntity;
import com.demo.entity.QuizQuestionEntity;
import com.demo.mapper.QuizAnswerMapper;
import com.demo.mapper.QuizPaperMapper;
import com.demo.mapper.QuizQuestionMapper;
import com.demo.study.controller.QuizController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 旧卷三条判分入口统一拒绝；错题仅增加修订投影，不改历史题面、答案或成绩。 */
class QuizRevisionBoundaryTest {
    @Test void supersededPaperStopsImmediateGradingSubmissionAndRedoBeforeReadingOldKey() {
        var service=new QuizService();var revisions=mock(ClassroomQuizRevisionRegistry.class);
        var questions=mock(QuizQuestionMapper.class);var answers=mock(QuizAnswerMapper.class);
        ReflectionTestUtils.setField(service,"quizRevisions",revisions);ReflectionTestUtils.setField(service,"questionMapper",questions);ReflectionTestUtils.setField(service,"answerMapper",answers);
        doThrow(new ClassroomException(409,"本练习已有修订版")).when(revisions).assertScorable(7L);
        assertEquals(409,assertThrows(ClassroomException.class,()->service.gradeOne(7L,1,"A")).status());
        assertEquals(409,assertThrows(ClassroomException.class,()->service.submit("classroom",7L,List.of(new QuizService.AnswerSubmission(1,"A")))).status());
        assertEquals(409,assertThrows(ClassroomException.class,()->service.mistakeRedo("classroom",7L,11L,"A")).status());
        verifyNoInteractions(questions,answers);verify(revisions,times(3)).assertScorable(7L);
        assertEquals(409,new QuizController().classroomBoundary(new ClassroomException(409,"已修订")).getStatusCode().value());
    }

    @Test void mistakeAddsOnlyRevisionNavigationAndKeepsTheOriginalAnswerRecord() {
        var service=new QuizService();var revisions=mock(ClassroomQuizRevisionRegistry.class);var questions=mock(QuizQuestionMapper.class);
        var answers=mock(QuizAnswerMapper.class);var papers=mock(QuizPaperMapper.class);
        ReflectionTestUtils.setField(service,"quizRevisions",revisions);ReflectionTestUtils.setField(service,"questionMapper",questions);
        ReflectionTestUtils.setField(service,"answerMapper",answers);ReflectionTestUtils.setField(service,"paperMapper",papers);
        var old=new QuizAnswerEntity("classroom",7L,11L,"A",0);old.setId(42L);
        var q=new QuizQuestionEntity();q.setId(11L);q.setPaperId(7L);q.setQuestionNo(1);q.setQType("single");q.setStem("旧题干");q.setOptions("[\"A. 48\",\"B. 240\"]");q.setAnswer("B");q.setExplanation("旧解析");
        var paper=new QuizPaperEntity();paper.setId(7L);paper.setTitle("原课程");
        when(answers.selectUnresolvedMistakes("classroom")).thenReturn(List.of(old));when(questions.selectBatchIds(any())).thenReturn(List.of(q));when(papers.selectBatchIds(any())).thenReturn(List.of(paper));
        var navigation=new ObjectMapper().createObjectNode().put("newCourseId","new-course").put("newSceneId","new-scene").put("reason","题目已修订").put("createdAt","2026-10-16T00:00:00Z");
        when(revisions.revisionForPaper(7L)).thenReturn(navigation);
        var result=service.mistakes("classroom").getFirst();assertEquals("旧题干",result.stem());assertEquals("A",result.userAnswer());assertEquals("B",result.correctAnswer());
        assertEquals("new-course",result.revision().path("newCourseId").asText());assertEquals(0,old.getIsCorrect());
        navigation.put("newCourseId","mutated");assertEquals("new-course",result.revision().path("newCourseId").asText());
        verify(answers,never()).insert(any(QuizAnswerEntity.class));verify(answers,never()).updateById(any(QuizAnswerEntity.class));
    }
}
