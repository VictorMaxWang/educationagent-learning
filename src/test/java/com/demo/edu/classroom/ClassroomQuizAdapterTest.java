package com.demo.edu.classroom;

import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.entity.QuizPaperEntity;
import com.demo.entity.QuizQuestionEntity;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClassroomQuizAdapterTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void remapsArbitraryKeysAndCanonicalizesMultiAnswerOrder() throws Exception {
        var mapper=mock(ClassroomCourseMapper.class); var quiz=mock(QuizService.class);
        var adapter=new ClassroomQuizAdapter(mapper,quiz,json);
        when(mapper.insertPaper(any())).thenAnswer(i -> { ((QuizPaperEntity)i.getArgument(0)).setId(9L); return 1; });
        ObjectNode course=json.createObjectNode().put("id","c").put("title","分数").put("topic","分数");
        ObjectNode scene=(ObjectNode)json.readTree("""
          {"id":"quiz-1","type":"quiz","title":"小测","content":{"type":"quiz","questions":[
          {"id":"q1","type":"multiple","question":"选出偶数","options":[{"label":"2","value":"two"},{"label":"3","value":"three"},{"label":"4","value":"four"}],"answer":["four","two"],"analysis":"偶数可被2整除"}]}}
          """);
        adapter.attach(course,scene); adapter.attach(course,scene);
        ArgumentCaptor<QuizQuestionEntity> capture=ArgumentCaptor.forClass(QuizQuestionEntity.class);
        verify(mapper,times(1)).insertQuestion(capture.capture());
        assertEquals("AC",capture.getValue().getAnswer()); assertEquals("multi",capture.getValue().getQType());
        assertEquals("classroom",capturePaper(mapper).getUserId());
        assertEquals("A",scene.path("content").path("questions").get(0).path("options").get(0).path("value").asText());
        when(quiz.submit(anyString(),anyLong(),anyList())).thenReturn(new QuizService.SubmitResult(9L,1,1,List.of(new QuizService.ResultItem(1,true,"AC","AC","解析"))));
        adapter.submit(scene,List.of(new QuizService.AnswerSubmission(1,"C,A")));
        verify(quiz).submit(eq("classroom"),eq(9L),eq(List.of(new QuizService.AnswerSubmission(1,"AC"))));
        assertThrows(ClassroomException.class,() -> adapter.submit(scene,List.of(new QuizService.AnswerSubmission(1,"Z"))));
        assertThrows(ClassroomException.class,() -> adapter.submit(scene,List.of(new QuizService.AnswerSubmission(1,"A"),new QuizService.AnswerSubmission(1,"C"))));
        ObjectNode projected=ClassroomCourseService.publicCourse(json.createObjectNode().set("scenes",json.createArrayNode().add(scene)));
        assertFalse(projected.path("scenes").get(0).path("content").path("questions").get(0).has("answer"));
        assertFalse(projected.path("scenes").get(0).path("content").path("questions").get(0).has("analysis"));
    }
    @Test void mapsJudgementFillAndShortWithoutRewritingTextAsChoiceKeys() throws Exception {
        var mapper=mock(ClassroomCourseMapper.class); var quiz=mock(QuizService.class);
        var adapter=new ClassroomQuizAdapter(mapper,quiz,json);
        when(mapper.insertPaper(any())).thenAnswer(i -> { ((QuizPaperEntity)i.getArgument(0)).setId(10L); return 1; });
        ObjectNode scene=(ObjectNode)json.readTree("""
          {"id":"quiz-2","type":"quiz","title":"混合题","content":{"type":"quiz","questions":[
          {"id":"j","type":"judge","question":"同整体才能比较面积？","answer":true},
          {"id":"f","type":"fill","question":"长方形面积","answer":24},
          {"id":"s","type":"short_answer","question":"为何先统一整体？","answer":"比较的基准需要相同","gradingPoints":["同样大小的整体","比较基准一致"]}]}}
          """);
        adapter.attach(json.createObjectNode().put("topic","分数"),scene);
        ArgumentCaptor<QuizQuestionEntity> capture=ArgumentCaptor.forClass(QuizQuestionEntity.class);
        verify(mapper,times(3)).insertQuestion(capture.capture());
        assertEquals(List.of("judge","fill","short"),capture.getAllValues().stream().map(QuizQuestionEntity::getQType).toList());
        assertEquals(List.of("对","24","比较的基准需要相同"),capture.getAllValues().stream().map(QuizQuestionEntity::getAnswer).toList());
        assertTrue(capture.getAllValues().get(2).getExplanation().contains("同样大小的整体"));
        assertEquals("24",scene.path("content").path("questions").get(1).path("answer").asText());
        when(quiz.submit(anyString(),anyLong(),anyList())).thenReturn(new QuizService.SubmitResult(10L,2,3,List.of(
            new QuizService.ResultItem(1,true,"对","对",""),new QuizService.ResultItem(2,true,"24","24",""),new QuizService.ResultItem(3,null,"基准相同","都是一样大",""))));
        var answers=List.of(new QuizService.AnswerSubmission(1,"对"),new QuizService.AnswerSubmission(2,"24"),new QuizService.AnswerSubmission(3,"都是一样大"));
        assertEquals(502,assertThrows(ClassroomException.class,()->adapter.submit(scene,answers)).status());
        // 判分失败后题库插入由课程外层事务回滚；此处保持原答案供同一提交 ID 再试。
        verify(quiz).submit(eq("classroom"),eq(10L),eq(answers));
    }
    @Test void invalidAnswerReferencesFailBeforeAnyWrite() throws Exception {
        var mapper=mock(ClassroomCourseMapper.class);
        var adapter=new ClassroomQuizAdapter(mapper,mock(QuizService.class),json);
        ObjectNode scene=(ObjectNode)json.readTree("""
          {"type":"quiz","content":{"questions":[{"id":"q","type":"single","question":"问题","options":[{"label":"甲","value":"A"},{"label":"乙","value":"B"}],"answer":["C"]}]}}
          """);
        assertThrows(ClassroomException.class,() -> adapter.attach(json.createObjectNode(),scene));
        verifyNoInteractions(mapper);
    }
    private QuizPaperEntity capturePaper(ClassroomCourseMapper mapper) {
        ArgumentCaptor<QuizPaperEntity> captor=ArgumentCaptor.forClass(QuizPaperEntity.class);
        verify(mapper).insertPaper(captor.capture()); return captor.getValue();
    }
}
