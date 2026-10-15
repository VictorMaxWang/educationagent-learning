package com.demo.edu.classroom;

import com.demo.config.AiChatProperties;
import com.demo.edu.classroom.media.ClassroomImageTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClassroomMediaControllerTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void editorCanGenerateAndReadOnlyTheLocalJpegUrl() throws Exception {
        MockMvc mvc = mvc();
        String response = mvc.perform(post("/api/classroom/media/images").contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"绿色植物\",\"aspectRatio\":\"1:1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mimeType").value("image/jpeg"))
                .andReturn().getResponse().getContentAsString();
        String url = json.readTree(response).path("url").asText();
        assertTrue(url.matches("/api/classroom/media/[0-9a-f]{64}\\.jpg"));
        assertFalse(response.contains("test-only-key"));
        assertFalse(response.contains("signature"));
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/classroom/media/not-a-cache-key.jpg")).andExpect(status().isNotFound());
    }

    @Test
    void uploadDoesNotTrustExtensionOrMimeAndBadPromptsReturnStableErrors() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(multipart("/api/classroom/media/upload").file(new MockMultipartFile(
                        "file", "picture.png", "image/png", ClassroomMediaServiceTest.png(64, 48))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.width").value(64))
                .andExpect(jsonPath("$.height").value(48));
        mvc.perform(multipart("/api/classroom/media/upload").file(new MockMultipartFile(
                        "file", "pretend.jpg", "image/jpeg", "<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/classroom/media/images").contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
    }

    private MockMvc mvc() {
        MockEnvironment env = new MockEnvironment().withProperty("classroom.data-dir", directory.toString())
                .withProperty("AI_CHAT_BASE_URL", "https://provider.example.test/v1")
                .withProperty("AI_CHAT_API_KEY", "test-only-key");
        ClassroomImageTransport transport = new ClassroomImageTransport() {
            @Override public byte[] postJson(URI endpoint, String apiKey, byte[] body, ClassroomCancellation token) {
                return "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"image\":\"https://cdn.example.test/image.png?signature=test-only\"}]}}]}}".getBytes(StandardCharsets.UTF_8);
            }
            @Override public byte[] download(URI image, ClassroomCancellation token) throws IOException {
                return ClassroomMediaServiceTest.png(64, 48);
            }
        };
        ClassroomMediaService service = new ClassroomMediaService(new AiChatProperties(env), env, json, transport);
        return MockMvcBuilders.standaloneSetup(new ClassroomMediaController(service)).build();
    }
}
