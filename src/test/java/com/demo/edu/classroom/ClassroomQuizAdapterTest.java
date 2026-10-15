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
        adapter.submit(scene,List.of(new QuizService.AnswerSubmission(1,"C,A")));
        verify(quiz).submit(eq("classroom"),eq(9L),eq(List.of(new QuizService.AnswerSubmission(1,"AC"))));
        assertThrows(ClassroomException.class,() -> adapter.submit(scene,List.of(new QuizService.AnswerSubmission(1,"Z"))));
        assertThrows(ClassroomException.class,() -> adapter.submit(scene,List.of(new QuizService.AnswerSubmission(1,"A"),new QuizService.AnswerSubmission(1,"C"))));
        ObjectNode projected=ClassroomCourseService.publicCourse(json.createObjectNode().set("scenes",json.createArrayNode().add(scene)));
        assertFalse(projected.path("scenes").get(0).path("content").path("questions").get(0).has("answer"));
        assertFalse(projected.path("scenes").get(0).path("content").path("questions").get(0).has("analysis"));
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
