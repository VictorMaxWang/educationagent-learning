package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeAssistant;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.safety.CrisisGuard;
import com.demo.edu.safety.CrisisGuardProperties;
import com.demo.edu.safety.EduContentSafetyJudge;
import com.demo.edu.safety.EduContentSafetyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 无应用启动或外部调用，验证公共包装不会读取或清除客户端指定的旧会话。 */
class ClassroomResourceControllerTest {
    private DocKnowledgeRegistry registry;
    private DocKnowledgeAssistant assistant;
    private EduContentSafetyJudge judge;
    private EduContentSafetyProperties safetyProperties;
    private MockMvc mvc;

    @BeforeEach void setup() {
        registry = mock(DocKnowledgeRegistry.class);
        assistant = mock(DocKnowledgeAssistant.class);
        judge = mock(EduContentSafetyJudge.class);
        when(registry.getAssistant(anyString())).thenReturn(assistant);
        when(assistant.subLibraries()).thenReturn(List.of(Map.of("id", "general", "displayName", "通识资料")));
        when(judge.judge(anyString(), anyList())).thenReturn(new EduContentSafetyJudge.Verdict(true, true, null, null));
        safetyProperties = new EduContentSafetyProperties();
        safetyProperties.setMode("log");
        mvc = MockMvcBuilders.standaloneSetup(new ClassroomResourceController(registry, new CrisisGuardProperties(), judge, safetyProperties)).build();
    }

    @Test void searchIgnoresCallerIdUsesDistinctTemporaryIdsAndClearsEach() throws Exception {
        when(assistant.searchAndAnswer(anyString(), anyString(), eq("general"))).thenReturn(Map.of(
                "success", true, "answer", "有依据的回答", "articles", List.of(Map.of("content", "资料片段",
                        "file", "C:\\private\\source.pdf", "filePath", "private-secret", "distance", 0.2))));
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/classroom/resources/tongshi/search")
                .contentType("application/json").content("{\"uid\":\"desktop_user\",\"question\":\"这是什么意思\",\"lib_id\":\"general\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("有依据的回答"))
                .andExpect(jsonPath("$.data.articles[0].file").value("source.pdf"))
                .andExpect(jsonPath("$.data.articles[0].filePath").doesNotExist());
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(assistant, times(2)).searchAndAnswer(ids.capture(), eq("这是什么意思"), eq("general"));
        assertNotEquals(ids.getAllValues().get(0), ids.getAllValues().get(1));
        for (String id : ids.getAllValues()) {
            assertTrue(id.startsWith("public_resource_"));
            verify(assistant).clearContext(id);
        }
        verify(assistant, never()).clearContext("desktop_user");
    }

    @Test void thrownOrReturnedFailuresStillCleanTheTemporarySessionAndHideDetails() throws Exception {
        when(assistant.searchAndAnswer(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("secret C:/private/config"))
                .thenReturn(Map.of("success", false, "error", "secret C:/private/config"));
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/classroom/resources/psychology/search")
                .contentType("application/json").content("{\"question\":\"课程问题\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.msg").value("知识库暂不可用，请稍后重试"));
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(assistant, times(2)).searchAndAnswer(ids.capture(), anyString(), anyString());
        for (String id : ids.getAllValues()) verify(assistant).clearContext(id);
    }

    @Test void unknownDomainsLibrariesAndInvalidQuestionsNeverReachGeneration() throws Exception {
        mvc.perform(post("/api/classroom/resources/school/search").contentType("application/json")
                .content("{\"question\":\"课程问题\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/classroom/resources/tongshi/search").contentType("application/json")
                .content("{\"question\":\"课程问题\",\"lib_id\":\"private_library\"}")).andExpect(status().isBadRequest());
        for (String body : List.of("{}", "{\"question\":2}", "{\"question\":\" \"}",
                "{\"question\":\"" + "问".repeat(2001) + "\"}", "{\"question\":\"课程问题\",\"lib_id\":\"../private\"}"))
            mvc.perform(post("/api/classroom/resources/tongshi/search").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        verify(assistant, never()).searchAndAnswer(anyString(), anyString(), anyString());
        verifyNoInteractions(judge);
    }

    @Test void validQuestionAtMaximumLengthIsAccepted() throws Exception {
        when(assistant.searchAndAnswer(anyString(), anyString(), anyString())).thenReturn(Map.of("success", true, "answer", "说明"));
        mvc.perform(post("/api/classroom/resources/lifesafety/search").contentType("application/json")
                .content("{\"question\":\"" + "问".repeat(2000) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test void clearContextNeverUsesOrClearsClientUid() throws Exception {
        mvc.perform(post("/api/classroom/resources/sexedu/clear-context").contentType("application/json")
                .content("{\"uid\":\"existing-student\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/api/classroom/resources/tongshi/clear-context")).andExpect(status().isOk());
        verifyNoInteractions(registry, assistant, judge);
    }

    @Test void healthAndLibrariesOnlyReturnPublicFields() throws Exception {
        when(assistant.health()).thenReturn(Map.of("chroma_ok", true, "indexing", "DONE", "active_sessions", 12,
                "apiKey", "secret", "path", "C:/private"));
        when(assistant.subLibraries()).thenReturn(List.of(Map.of("id", "general", "displayName", "通识资料", "path", "C:/private")));
        mvc.perform(get("/api/classroom/resources/tongshi/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chroma_ok").value(true)).andExpect(jsonPath("$.data.indexing").value("DONE"))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist()).andExpect(jsonPath("$.data.path").doesNotExist())
                .andExpect(jsonPath("$.data.active_sessions").doesNotExist());
        mvc.perform(get("/api/classroom/resources/tongshi/libs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("general")).andExpect(jsonPath("$.data[0].path").doesNotExist());
    }

    @Test void safetyShortCircuitDoesNotCreateSessionOrAssociateAStudent() throws Exception {
        mvc.perform(post("/api/classroom/resources/psychology/search").contentType("application/json")
                .content("{\"uid\":\"existing-student\",\"question\":\"我不想活了\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.crisis").value(true))
                .andExpect(jsonPath("$.data.answer").value(CrisisGuard.SCRIPT));
        verifyNoInteractions(registry, assistant, judge);
    }

    @Test void unsafeAnswerAndSnippetsAreBothReplacedAndSessionStillCleared() throws Exception {
        when(assistant.searchAndAnswer(anyString(), anyString(), anyString())).thenReturn(Map.of("success", true,
                "answer", "原始回答", "articles", List.of(Map.of("content", "原始片段"))));
        safetyProperties.setReplacement("安全替换提示");
        when(judge.judge(eq("原始回答"), eq(List.of("原始片段"))))
                .thenReturn(new EduContentSafetyJudge.Verdict(true, false, "other_unsuitable", "测试不适合"));
        mvc.perform(post("/api/classroom/resources/sexedu/search").contentType("application/json")
                .content("{\"question\":\"如何表达自己的边界\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("安全替换提示"))
                .andExpect(jsonPath("$.data.articles").isEmpty()).andExpect(jsonPath("$.data.contentFlagged").value(true));
        verify(assistant).clearContext(startsWith("public_resource_"));
    }
}
