package com.demo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WebsiteAudioEncoderTest {
    @Test
    void invalidInputOrMissingBinaryNeverMasqueradesAsMp3() {
        WebsiteAudioEncoder encoder = new WebsiteAudioEncoder("missing-educationagent-ffmpeg-binary");
        assertFalse(encoder.isAvailable());
        assertNull(encoder.toMp3(AliyunTtsServiceTest.wav(100)));
        assertFalse(WebsiteAudioEncoder.isMp3(AliyunTtsServiceTest.wav(100)));
    }

    @Test
    void locallyGeneratedWavIsActuallyEncodedAsMp3WithoutAnyCloudCall() {
        WebsiteAudioEncoder encoder = new WebsiteAudioEncoder("ffmpeg");
        assumeTrue(encoder.isAvailable(), "本机未安装 ffmpeg 时只跳过离线实际转码测试");
        byte[] result = encoder.toMp3(AliyunTtsServiceTest.wav(24000));
        assertNotNull(result);
        assertTrue(WebsiteAudioEncoder.isMp3(result));
        assertFalse(AliyunTtsService.isWav(result));
        assertTrue(result.length > 1000);
    }
}
