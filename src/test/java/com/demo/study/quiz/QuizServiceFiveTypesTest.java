package com.demo.study.quiz;

import com.demo.entity.QuizAnswerEntity;
import com.demo.entity.QuizQuestionEntity;
import com.demo.mapper.QuizAnswerMapper;
import com.demo.mapper.QuizQuestionMapper;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 直接覆盖真实判分服务，避免把 UI 类型渲染成功误当成五题型已打通。 */
class QuizServiceFiveTypesTest {
    final QuizService service=new QuizService();
    final QuizQuestionMapper questions=mock(QuizQuestionMapper.class);
    final QuizAnswerMapper answers=mock(QuizAnswerMapper.class);
    final OpenAiChatModel model=mock(OpenAiChatModel.class);
    QuizServiceFiveTypesTest() {
        ReflectionTestUtils.setField(service,"questionMapper",questions);
        ReflectionTestUtils.setField(service,"answerMapper",answers);
        ReflectionTestUtils.setField(service,"deepseekChatModel",model);
    }
    QuizQuestionEntity q(int no,String type,String answer) {
        var q=new QuizQuestionEntity();q.setId((long)no);q.setPaperId(8L);q.setQuestionNo(no);q.setQType(type);
        q.setStem("为什么整体要一样大");q.setAnswer(answer);q.setExplanation("评分要点：同样大小、比较基准一致");return q;
    }
    void response(String text) {
        var response=mock(ChatResponse.class,RETURNS_DEEP_STUBS);
        when(response.aiMessage().text()).thenReturn(text);
        when(model.chat(any(ChatRequest.class))).thenReturn(response);
    }
    @Test void shortWithReferenceUsesSemanticModelWhileJudgeAndFillUseRules() {
        when(questions.selectList(any())).thenReturn(List.of(q(1,"judge","对"),q(2,"fill","24"),q(3,"short","比较基准需要一致")));
        response("[{\"no\":3,\"correct\":true,\"correctAnswer\":\"比较基准需要一致\",\"explanation\":\"同样大小表达了相同基准\"}]");
        var result=service.submit("classroom",8L,List.of(new QuizService.AnswerSubmission(1,"正确"),new QuizService.AnswerSubmission(2," ２４ "),new QuizService.AnswerSubmission(3,"先让两块地一样大")));
        assertEquals(3,result.score());assertTrue(result.results().get(2).correct());
        var request=ArgumentCaptor.forClass(ChatRequest.class);verify(model).chat(request.capture());
        assertTrue(request.getValue().messages().toString().contains("比较基准一致"));
        verify(answers,times(3)).insert(any(QuizAnswerEntity.class));
    }
    @Test void malformedBooleanAndDuplicateNumbersRemainUnknownInsteadOfWrong() {
        when(questions.selectList(any())).thenReturn(List.of(q(1,"short","参考")));
        for(String text:List.of("[{\"no\":1,\"correct\":\"false\",\"correctAnswer\":\"参考\",\"explanation\":\"解析\"}]","[{\"no\":1,\"correctAnswer\":\"参考\",\"explanation\":\"解析\"}]","[{\"no\":2,\"correct\":false,\"correctAnswer\":\"参考\",\"explanation\":\"解析\"}]","[{\"no\":1,\"correct\":false,\"correctAnswer\":\"参考\",\"explanation\":\"解析\"},{\"no\":1,\"correct\":false,\"correctAnswer\":\"参考\",\"explanation\":\"解析\"}]")) {
            clearInvocations(answers);response(text);
            var result=service.submit("classroom",8L,List.of(new QuizService.AnswerSubmission(1,"我的回答")));
            assertNull(result.results().get(0).correct());
            var inserted=ArgumentCaptor.forClass(QuizAnswerEntity.class);verify(answers).insert(inserted.capture());
            assertEquals(-1,inserted.getValue().getIsCorrect());
        }
    }
    @Test void uncertainRedoDoesNotInsertAnIncorrectAnswerOrRemoveMistake() {
        when(questions.selectById(1L)).thenReturn(q(1,"short","比较基准一致"));
        response("{\"no\":1,\"correct\":null,\"correctAnswer\":\"参考\",\"explanation\":\"暂无法判断\"}");
        var result=service.mistakeRedo("classroom",8L,1L,"一样大");
        assertNull(result.correct());assertFalse(result.removed());verifyNoInteractions(answers);
    }
    @Test void singleAndMultipleSubmissionNumbersAreStrict() {
        when(questions.selectList(any())).thenReturn(List.of(q(1,"single","B"),q(2,"multi","AC")));
        var result=service.submit("classroom",8L,List.of(new QuizService.AnswerSubmission(1,"B"),new QuizService.AnswerSubmission(2,"C,A")));
        assertEquals(2,result.score());verifyNoInteractions(model);
        assertThrows(QuizException.class,()->service.submit("classroom",8L,List.of(new QuizService.AnswerSubmission(1,"B"),new QuizService.AnswerSubmission(1,"A"))));
    }
}
