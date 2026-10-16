package com.demo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebsiteTtsServiceTest {
    @Test
    void allWebsiteRolesUseCherryAndNeverChangeTencentExplicitVoice() {
        TtsService tencent = mock(TtsService.class);
        AliyunTtsService aliyun = mock(AliyunTtsService.class);
        WebsiteAudioEncoder encoder = mock(WebsiteAudioEncoder.class);
        when(aliyun.isAvailable()).thenReturn(true); when(encoder.isAvailable()).thenReturn(true);
        byte[] wav = AliyunTtsServiceTest.wav(2400), mp3 = new byte[]{'I', 'D', '3', 1};
        when(aliyun.synthesizeWav("你好。", "Cherry")).thenReturn(wav);
        when(encoder.toMp3(wav)).thenReturn(mp3);
        WebsiteTtsService website = new WebsiteTtsService(tencent, aliyun, mock(MaasRealtimeTtsService.class), encoder, "maas-qwen", "Cherry");
        assertEquals("Cherry", website.resolveVoice("403005"));
        assertEquals("Cherry", website.resolveVoice("1001"));
        assertEquals("芊悦", website.voiceName(website.getDefaultVoiceType()));
        assertArrayEquals(mp3, website.synthesize("你好。", "403005", 16000));
        verifyNoInteractions(tencent);
    }

    @Test
    void missingEncoderOrFailedConversionNeverFallsBackToTencent() {
        TtsService tencent = mock(TtsService.class);
        AliyunTtsService aliyun = mock(AliyunTtsService.class);
        WebsiteAudioEncoder encoder = mock(WebsiteAudioEncoder.class);
        when(aliyun.isAvailable()).thenReturn(true);
        WebsiteTtsService website = new WebsiteTtsService(tencent, aliyun, mock(MaasRealtimeTtsService.class), encoder, "maas-qwen", "Cherry");
        assertFalse(website.isAvailable());
        assertNull(website.synthesize("你好。", "Cherry", 16000));
        verify(aliyun, never()).synthesizeWav(anyString(), anyString());
        when(encoder.isAvailable()).thenReturn(true);
        when(aliyun.synthesizeWav(anyString(), anyString())).thenReturn(AliyunTtsServiceTest.wav(100));
        assertNull(website.synthesize("你好。", "Cherry", 16000));
        verifyNoInteractions(tencent);
    }

    @Test
    void providerAndModelChangesInvalidateCacheEvenForSameTextAndLabel() {
        TtsService tencent = mock(TtsService.class);
        AliyunTtsService aliyun = mock(AliyunTtsService.class);
        WebsiteAudioEncoder encoder = mock(WebsiteAudioEncoder.class);
        when(aliyun.cacheIdentity()).thenReturn("maas-qwen\nqwen3-tts-flash");
        WebsiteTtsService qwen = new WebsiteTtsService(tencent, aliyun, mock(MaasRealtimeTtsService.class), encoder, "maas-qwen", "Cherry");
        WebsiteTtsService legacy = new WebsiteTtsService(tencent, aliyun, mock(MaasRealtimeTtsService.class), encoder, "tencent", "Cherry");
        String identity = qwen.cacheIdentity("Cherry");
        assertNotEquals(identity, legacy.cacheIdentity("Cherry"));
        when(aliyun.cacheIdentity()).thenReturn("maas-qwen\nqwen3-tts-flash-2025-11-27");
        assertNotEquals(identity, qwen.cacheIdentity("Cherry"));
        assertTrue(identity.contains("mp3\n16000"));
    }

    @Test
    void realtimeFailureNeverFallsBackToHttpOrTencent() {
        TtsService tencent = mock(TtsService.class);
        AliyunTtsService http = mock(AliyunTtsService.class);
        MaasRealtimeTtsService realtime = mock(MaasRealtimeTtsService.class);
        WebsiteAudioEncoder encoder = mock(WebsiteAudioEncoder.class);
        when(realtime.isAvailable()).thenReturn(true); when(encoder.isAvailable()).thenReturn(true);
        WebsiteTtsService website = new WebsiteTtsService(tencent, http, realtime, encoder, "maas-qwen-realtime", "Cherry");
        assertNull(website.synthesize("你好。", "403005", 16000));
        verify(realtime).synthesizeWav("你好。", "Cherry");
        verifyNoInteractions(tencent, http);
        verify(encoder, never()).toMp3(any(byte[].class));
    }
}
