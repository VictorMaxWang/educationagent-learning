package com.demo.edu.classroom;

import com.demo.service.TtsService;
import com.demo.service.AliyunTtsService;
import com.demo.service.MaasRealtimeTtsService;
import com.demo.service.WebsiteAudioEncoder;
import com.demo.service.WebsiteTtsService;
import com.demo.service.preprocessing.AudioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 不启动 Spring 应用、数据库或云服务，只验证 HTTP 边界和失败降级状态。 */
class ClassroomVoiceControllerTest {
    @TempDir Path directory;
    private MockMvc mvc;
    private TtsService tts;
    private AudioService asr;

    @BeforeEach
    void setup() {
        tts = mock(TtsService.class);
        asr = mock(AudioService.class);
        WebsiteTtsService website = new WebsiteTtsService(tts, mock(AliyunTtsService.class), mock(MaasRealtimeTtsService.class), mock(WebsiteAudioEncoder.class), "tencent", "Cherry");
        mvc = MockMvcBuilders.standaloneSetup(new ClassroomVoiceController(
                new ClassroomVoiceService(website, asr, directory.toString(), "0", "1001"))).build();
    }

    @Test
    void healthReportsConfigurationWithoutAttemptingSynthesis() throws Exception {
        mvc.perform(get("/api/classroom/voice/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ttsAvailable").value(false))
                .andExpect(jsonPath("$.asrAvailable").value(false));
        verify(tts).isAvailable(); verify(asr).isAvailable(); verifyNoMoreInteractions(tts, asr);
        mvc.perform(post("/api/classroom/voice/tts").contentType("application/json")
                .content("{\"text\":\"你好\",\"role\":\"teacher\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").exists());
        verify(tts, never()).synthesize(anyString(), anyString(), anyInt());
    }

    @Test
    void uploadedWavProducesEditableTextAndOtherFormatsFailBeforeProvider() throws Exception {
        when(asr.isAvailable()).thenReturn(true);
        when(asr.transcribe(any(byte[].class), eq("wav"), eq(1))).thenReturn("我想问一道题");
        mvc.perform(multipart("/api/classroom/voice/asr").file(new MockMultipartFile(
                "file", "recording.wav", "audio/wav", ClassroomVoiceServiceTest.wav(16000))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("我想问一道题"));
        mvc.perform(multipart("/api/classroom/voice/asr").file(new MockMultipartFile(
                "file", "fake.wav", "audio/wav", new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        verify(asr, times(1)).transcribe(any(byte[].class), eq("wav"), anyInt());
    }
}
