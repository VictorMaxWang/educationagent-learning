package com.demo.service.preprocessing;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AudioServiceTest {
    private AudioService service;
    private RestTemplate http;

    @BeforeEach
    void setup() {
        service = new AudioService();
        http = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "secretId", "unit-test-id");
        ReflectionTestUtils.setField(service, "secretKey", "unit-test-key");
        ReflectionTestUtils.setField(service, "region", "ap-guangzhou");
        ReflectionTestUtils.setField(service, "restTemplate", http);
        service.init();
        when(http.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"Response\":{\"Result\":\"我想再听一次\"}}"));
    }

    @Test
    void explicitWavFormatAndLegacyWechatFormatsUseCorrectProviderParameters() {
        assertEquals("我想再听一次", service.transcribe(new byte[]{1, 2}, "wav", 2));
        assertNotNull(service.transcribe(new byte[]{1, 2}, 0, 2));
        assertNotNull(service.transcribe(new byte[]{1, 2}, 4, 2));
        ArgumentCaptor<HttpEntity<String>> requests = ArgumentCaptor.forClass(HttpEntity.class);
        verify(http, times(3)).postForEntity(anyString(), requests.capture(), eq(String.class));
        assertEquals(java.util.List.of("wav", "amr", "silk"), requests.getAllValues().stream()
                .map(r -> JSON.parseObject(r.getBody()).getString("VoiceFormat")).toList());
    }

    @Test
    void unsupportedOrOversizedInputsNeverReachPaidApi() {
        assertNull(service.transcribe(new byte[]{1}, "webm", 1));
        assertNull(service.transcribe(new byte[]{1}, "wav", 61));
        assertNull(service.transcribe(new byte[2_359_297], "wav", 1));
        verifyNoInteractions(http);
    }
}
