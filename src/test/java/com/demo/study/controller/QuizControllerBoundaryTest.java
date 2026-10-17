package com.demo.study.controller;

import com.demo.controller.DesktopFileStore;
import com.demo.edu.classroom.ClassroomException;
import com.demo.study.quiz.QuizException;
import com.demo.study.quiz.QuizService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 通过真实MVC异常分派覆盖catch-all与@ExceptionHandler的顺序，不能只直接调用handler证明HTTP状态。 */
class QuizControllerBoundaryTest {
    private static final String REVISION="本练习已有修订版，请前往课程 new-course 的页面 new-scene 作答";
    private final QuizService quizzes=mock(QuizService.class);
    private final DesktopFileStore files=mock(DesktopFileStore.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        QuizController controller=new QuizController();
        ReflectionTestUtils.setField(controller,"quizService",quizzes);
        ReflectionTestUtils.setField(controller,"fileStore",files);
        mvc=MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void supersededImmediateGradeIs409RatherThanCatchAll500()throws Exception {
        when(quizzes.gradeOne(5L,1,"B")).thenThrow(new ClassroomException(409,REVISION));
        mvc.perform(post("/api/desktop/quiz/grade-one").contentType("application/json")
                .content("{\"quizId\":5,\"no\":1,\"answer\":\"B\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value(REVISION))
                .andExpect(jsonPath("$.correct").doesNotExist());
        verify(quizzes,times(1)).gradeOne(5L,1,"B");
    }

    @Test void supersededSubmissionIs409RatherThanCatchAll500()throws Exception {
        when(quizzes.submit(eq("classroom"),eq(5L),anyList())).thenThrow(new ClassroomException(409,REVISION));
        mvc.perform(post("/api/desktop/quiz/submit").contentType("application/json")
                .content("{\"quizId\":5,\"userId\":\"classroom\",\"answers\":[{\"no\":1,\"answer\":\"B\"}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value(REVISION))
                .andExpect(jsonPath("$.score").doesNotExist());
        verify(quizzes,times(1)).submit(eq("classroom"),eq(5L),anyList());
    }

    @Test void supersededMistakeRedoUsesTheSame409Handler()throws Exception {
        when(quizzes.mistakeRedo("classroom",5L,8L,"B")).thenThrow(new ClassroomException(409,REVISION));
        mvc.perform(post("/api/desktop/quiz/mistake-redo").contentType("application/json")
                .content("{\"quizId\":5,\"questionId\":8,\"userId\":\"classroom\",\"answer\":\"B\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value(REVISION))
                .andExpect(jsonPath("$.removed").doesNotExist());
        verify(quizzes,times(1)).mistakeRedo("classroom",5L,8L,"B");
    }

    @Test void successfulGradingAndExistingQuizValidationKeepTheirStatuses()throws Exception {
        when(quizzes.gradeOne(6L,1,"A")).thenReturn(new QuizService.GradeOneResult(1,true,true,"A","计算一致"));
        mvc.perform(post("/api/desktop/quiz/grade-one").contentType("application/json")
                .content("{\"quizId\":6,\"no\":1,\"answer\":\"A\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.correct").value(true));
        when(quizzes.gradeOne(6L,2,"A")).thenThrow(new QuizException("题目不存在"));
        mvc.perform(post("/api/desktop/quiz/grade-one").contentType("application/json")
                .content("{\"quizId\":6,\"no\":2,\"answer\":\"A\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("题目不存在"));
    }

    @Test void otherTypedBoundaryStatusesArePreservedToo()throws Exception {
        when(quizzes.gradeOne(6L,1,"A")).thenThrow(new ClassroomException(503,"核对服务暂不可用"));
        mvc.perform(post("/api/desktop/quiz/grade-one").contentType("application/json")
                .content("{\"quizId\":6,\"no\":1,\"answer\":\"A\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("核对服务暂不可用"));
    }

    @Test void uncertainDurableAttemptRetainsMachineReadableCodeWithoutInventingScore()throws Exception {
        when(quizzes.submit(eq("classroom"),eq(5L),anyList(),eq("unknown-attempt")))
                .thenThrow(new ClassroomException(409,"本次评阅结果尚未确认", "EVALUATION_UNKNOWN"));
        mvc.perform(post("/api/desktop/quiz/submit").contentType("application/json")
                .content("{\"quizId\":5,\"userId\":\"classroom\",\"requestId\":\"unknown-attempt\",\"answers\":[{\"no\":1,\"answer\":\"B\"}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVALUATION_UNKNOWN"))
                .andExpect(jsonPath("$.error").value("本次评阅结果尚未确认")).andExpect(jsonPath("$.score").doesNotExist());
        verify(quizzes,times(1)).submit(eq("classroom"),eq(5L),anyList(),eq("unknown-attempt"));
    }

    @Test void generationCatchAllAlsoPreservesTypedBoundariesAndDoesNotConsumeTheFile()throws Exception {
        when(files.get("source-file")).thenReturn(new DesktopFileStore.FileEntry(new byte[]{1},"题目.txt","text/plain"));
        when(quizzes.generate(eq("classroom"),any(byte[].class),eq("题目.txt"),eq("txt")))
                .thenThrow(new ClassroomException(422,"资料尚不能用于出题"));
        mvc.perform(post("/api/desktop/quiz/generate").contentType("application/json")
                .content("{\"userId\":\"classroom\",\"fileId\":\"source-file\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("资料尚不能用于出题"));
        verify(files,never()).remove(anyString());
    }
}
