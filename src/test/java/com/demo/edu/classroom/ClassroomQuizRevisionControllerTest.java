package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClassroomQuizRevisionControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ClassroomQuizRevisionRegistry registry = mock(ClassroomQuizRevisionRegistry.class);

    @Test void internalRegistrationBindsTheSixFieldsAndPublicGetUsesTheLimitedProjection() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ClassroomQuizRevisionController(registry)).build();
        when(registry.register(any())).thenReturn(json.createObjectNode().put("oldPaperId", 1).put("newPaperId", 2));
        var publicResult = json.createObjectNode().put("courseId", "old");
        publicResult.putArray("revisions").addObject().put("sceneId", "s1").put("newCourseId", "new")
                .put("newSceneId", "s2").put("reason", "更正题目").put("createdAt", "2026-10-16T00:00:00Z");
        when(registry.publicRevisions("old")).thenReturn(publicResult);
        mvc.perform(post("/api/internal/quiz-revisions").contentType("application/json").content("""
                {"oldCourseId":"old","oldSceneId":"s1","newCourseId":"new","newSceneId":"s2","reason":"更正题目","requestId":"request-1"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.oldPaperId").value(1));
        var input = ArgumentCaptor.forClass(ClassroomQuizRevisionRegistry.RegisterRequest.class);
        verify(registry).register(input.capture());
        assertEquals(new ClassroomQuizRevisionRegistry.RegisterRequest("old", "s1", "new", "s2", "更正题目", "request-1"), input.getValue());
        mvc.perform(get("/api/classroom/courses/old/quiz-revisions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions[0].newCourseId").value("new"))
                .andExpect(jsonPath("$.revisions[0].oldPaperId").doesNotExist())
                .andExpect(jsonPath("$.revisions[0].requestId").doesNotExist());
    }

    @Test void publishesStableConflictStatusAndThereIsNoPublicRegistrationRoute() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ClassroomQuizRevisionController(registry)).build();
        when(registry.publicRevisions("bad")).thenThrow(new ClassroomException(409, "修订链需要核对"));
        mvc.perform(get("/api/classroom/courses/bad/quiz-revisions")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("修订链需要核对"));
        mvc.perform(post("/api/classroom/courses/old/quiz-revisions").contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
        verify(registry, never()).register(any());
    }
}
