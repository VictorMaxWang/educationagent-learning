package com.demo.edu.classroom;

import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.entity.QuizAnswerEntity;
import com.demo.entity.QuizQuestionEntity;
import com.demo.mapper.QuizAnswerMapper;
import com.demo.mapper.QuizQuestionMapper;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 未确定整组不进入写事务；固定结果确认后才在真实数据库短事务内统一追加。 */
class ClassroomQuizTransactionTest {
    @Test void uncertainWholeGroupRollsBackAndSameAnswersCanBeRetried() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:quiz_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE answer_audit(no INT, correct INT)");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var questionMapper=mock(QuizQuestionMapper.class);var answerMapper=mock(QuizAnswerMapper.class);var model=mock(OpenAiChatModel.class);
        var service=new QuizService();
        ReflectionTestUtils.setField(service,"questionMapper",questionMapper);ReflectionTestUtils.setField(service,"answerMapper",answerMapper);ReflectionTestUtils.setField(service,"deepseekChatModel",model);
        var choice=new QuizQuestionEntity();choice.setId(1L);choice.setPaperId(9L);choice.setQuestionNo(1);choice.setQType("judge");choice.setAnswer("对");
        var shortQuestion=new QuizQuestionEntity();shortQuestion.setId(2L);shortQuestion.setPaperId(9L);shortQuestion.setQuestionNo(2);shortQuestion.setQType("short");shortQuestion.setAnswer("比较的整体大小相同");
        when(questionMapper.selectList(any())).thenReturn(List.of(choice,shortQuestion));
        when(answerMapper.insert(any(QuizAnswerEntity.class))).thenAnswer(call->{
            QuizAnswerEntity answer=call.getArgument(0);jdbc.update("INSERT INTO answer_audit VALUES(?,?)",answer.getQuestionId(),answer.getIsCorrect());return 1;
        });
        var unknown=mock(ChatResponse.class,RETURNS_DEEP_STUBS);when(unknown.aiMessage().text()).thenReturn("[{\"no\":2,\"correct\":null}]");
        var confirmed=mock(ChatResponse.class,RETURNS_DEEP_STUBS);when(confirmed.aiMessage().text()).thenReturn("[{\"no\":2,\"correct\":true,\"correctAnswer\":\"比较的整体大小相同\",\"explanation\":\"表达了相同基准\"}]");
        when(model.chat(any(ChatRequest.class))).thenReturn(unknown,confirmed);
        var json=new ObjectMapper();var adapter=new ClassroomQuizAdapter(mock(ClassroomCourseMapper.class),service,json);
        ObjectNode scene=(ObjectNode)json.readTree("""
            {"quizId":9,"content":{"questions":[{"no":1,"type":"judge"},{"no":2,"type":"short_answer"}]}}
            """);
        var answers=List.of(new QuizService.AnswerSubmission(1,"对"),new QuizService.AnswerSubmission(2,"两块菜地得一样大"));
        var first=adapter.prepare(scene,answers,"same-request");
        assertThrows(ClassroomException.class,()->adapter.evaluate(first));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM answer_audit",Integer.class));
        var unknownReceipt=assertThrows(ClassroomException.class,()->adapter.evaluate(adapter.prepare(scene,answers,"same-request")));
        assertEquals("EVALUATION_UNKNOWN",unknownReceipt.code());verify(model,times(1)).chat(any(ChatRequest.class));
        var retry=adapter.prepare(scene,answers,"explicit-new-review");var evaluated=adapter.evaluate(retry);
        var success=tx.execute(status->adapter.commit(scene,retry,evaluated));assertNotNull(success);assertEquals(2,success.score());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM answer_audit",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM answer_audit WHERE correct=-1",Integer.class));
    }
}
