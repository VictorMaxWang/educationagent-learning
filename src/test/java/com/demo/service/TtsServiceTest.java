package com.demo.service;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 全部使用模拟 HTTP，证明旧聊天调用也不会因 150 字上限而漏讲。 */
class TtsServiceTest {
    private TtsService service;
    private RestTemplate http;
    private static final String SUCCESS = "{\"Response\":{\"Audio\":\"AQID\"}}";

    @BeforeEach
    void setup() {
        service = new TtsService();
        http = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "secretId", "unit-test-id");
        ReflectionTestUtils.setField(service, "secretKey", "unit-test-key");
        ReflectionTestUtils.setField(service, "sampleRate", 16000);
        ReflectionTestUtils.setField(service, "region", "ap-guangzhou");
        ReflectionTestUtils.setField(service, "restTemplate", http);
        service.init();
    }

    @Test
    void defaultVoiceUsesYunyaqinOnTextToVoiceWith16kMp3() {
        when(http.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(ResponseEntity.ok(SUCCESS));
        assertEquals("403005", service.getDefaultVoiceType());
        assertNotNull(service.synthesize("我们一起慢慢学。"));
        ArgumentCaptor<HttpEntity<String>> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(http).postForEntity(eq("https://tts.tencentcloudapi.com"), request.capture(), eq(String.class));
        var body = JSON.parseObject(request.getValue().getBody());
        assertEquals(403005, body.getIntValue("VoiceType"));
        assertEquals(16000, body.getIntValue("SampleRate"));
        assertEquals("mp3", body.getString("Codec"));
        assertEquals("TextToVoice", request.getValue().getHeaders().getFirst("X-TC-Action"));
        assertEquals("2019-08-23", request.getValue().getHeaders().getFirst("X-TC-Version"));
    }

    @Test
    void configuredDefaultAndExplicitUserVoiceRemainEffective() {
        when(http.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(ResponseEntity.ok(SUCCESS));
        ReflectionTestUtils.setField(service, "voiceType", "101007");
        assertNotNull(service.synthesize("使用配置的默认声音。"));
        assertNotNull(service.synthesize("保留指定声音。", "0", 16000));
        assertNotNull(service.synthesize("空选择继承默认声音。", "", 16000));
        ArgumentCaptor<HttpEntity<String>> requests = ArgumentCaptor.forClass(HttpEntity.class);
        verify(http, times(3)).postForEntity(anyString(), requests.capture(), eq(String.class));
        assertEquals(List.of(101007, 0, 101007), requests.getAllValues().stream()
                .map(request -> JSON.parseObject(request.getBody()).getIntValue("VoiceType")).toList());
    }

    @Test
    void legacySynthesizePreservesLongChineseWithoutPunctuation() {
        when(http.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(ResponseEntity.ok(SUCCESS));
        String text = "乡村学生可以循序渐进地学习".repeat(40);
        byte[] audio = service.synthesize(text);
        ArgumentCaptor<HttpEntity<String>> requests = ArgumentCaptor.forClass(HttpEntity.class);
        verify(http, atLeast(2)).postForEntity(anyString(), requests.capture(), eq(String.class));
        List<String> segments = requests.getAllValues().stream().map(r -> JSON.parseObject(r.getBody()).getString("Text")).toList();
        assertEquals(text, String.join("", segments));
        assertTrue(segments.stream().allMatch(s -> s.length() <= 150));
        assertEquals(segments.size() * 3, audio.length);
    }

    @Test
    void splittingPreservesEnglishChineseNewlinesAndSurrogatePairs() {
        String text = "Learn English.\n" + "a".repeat(149) + "🌱" + "乡村你好！".repeat(40);
        List<String> segments = TtsService.splitForTts(text);
        assertEquals(text, String.join("", segments));
        assertTrue(segments.stream().allMatch(s -> s.length() <= 150));
        assertTrue(segments.stream().noneMatch(s -> Character.isHighSurrogate(s.charAt(s.length() - 1))));
        assertTrue(segments.stream().noneMatch(s -> Character.isLowSurrogate(s.charAt(0))));
    }

    @Test
    void mixedEnglishLongSynthesisPreservesAllWords() {
        when(http.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(ResponseEntity.ok(SUCCESS));
        String text = "Learning in a village means 学会观察 and ask why. ".repeat(12);
        assertNotNull(service.synthesizeLong(text));
        ArgumentCaptor<HttpEntity<String>> requests = ArgumentCaptor.forClass(HttpEntity.class);
        verify(http, atLeast(2)).postForEntity(anyString(), requests.capture(), eq(String.class));
        String combined = requests.getAllValues().stream().map(r -> JSON.parseObject(r.getBody()).getString("Text")).reduce("", String::concat);
        assertEquals(text.stripTrailing(), combined.stripTrailing());
    }

    @Test
    void failureOfOneSegmentFailsWholeLegacyResult() {
        when(http.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(SUCCESS))
                .thenReturn(ResponseEntity.ok("{\"Response\":{\"Error\":{\"Code\":\"Unavailable\",\"Message\":\"try later\"}}}"));
        assertNull(service.synthesizeLong("中".repeat(450)));
        verify(http, times(2)).postForEntity(anyString(), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void blankLinesDoNotTurnValidSpeechIntoAFailure() {
        when(http.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(ResponseEntity.ok(SUCCESS));
        assertNotNull(service.synthesizeLong("第一段。\n\n第二段。\n"));
    }
}
