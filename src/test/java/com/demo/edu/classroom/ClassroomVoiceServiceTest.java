package com.demo.edu.classroom;

import com.demo.service.TtsService;
import com.demo.service.preprocessing.AudioService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClassroomVoiceServiceTest {
    @TempDir Path directory;

    @Test
    void teacherPeerAndGrowthPlaybackInheritYunyaqinAndCacheActualVoice() {
        TtsService tts = mock(TtsService.class);
        when(tts.isAvailable()).thenReturn(true);
        when(tts.getDefaultVoiceType()).thenReturn("403005");
        when(tts.synthesize(anyString(), anyString(), eq(16000))).thenReturn(new byte[]{1, 2, 3});
        ClassroomVoiceService voice = new ClassroomVoiceService(tts, mock(AudioService.class), directory.toString(), "", null);
        var teacher = voice.synthesize("我们一起慢慢学。", "teacher");
        var peer = voice.synthesize("我们一起慢慢学。", "peer");
        assertEquals(teacher, peer, "使用同一个实际音色时应复用语音缓存");
        verify(tts).synthesize("我们一起慢慢学。", "403005", 16000);
        assertEquals("403005", voice.health().get("voiceId"));
        assertEquals("云雅琴", voice.health().get("voiceName"));
        assertEquals("403005", voice.health().get("peerVoiceId"));
        assertEquals(16000, voice.health().get("sampleRate"));

        var custom = new ClassroomVoiceService(tts, mock(AudioService.class), directory.toString(), "0", "1001")
                .synthesize("我们一起慢慢学。", "teacher");
        assertNotEquals(teacher.segments().getFirst().url(), custom.segments().getFirst().url(),
                "切换实际音色后不能复用旧音色缓存");
        verify(tts).synthesize("我们一起慢慢学。", "0", 16000);
    }

    @Test
    void speechIsCachedAcrossRequestsAndRolesUseIndependentVoices() {
        TtsService tts = mock(TtsService.class);
        when(tts.isAvailable()).thenReturn(true);
        when(tts.synthesize(anyString(), anyString(), eq(16000))).thenReturn(new byte[]{1, 2, 3});
        AudioService asr = mock(AudioService.class);
        ClassroomVoiceService voice = service(tts, asr);
        var first = voice.synthesize("大家好。请观察地里的植物。", "teacher");
        assertEquals(2, first.segments().size());
        var cached = service(tts, asr).synthesize("大家好。请观察地里的植物。", "teacher");
        assertEquals(first, cached);
        verify(tts, times(2)).synthesize(anyString(), eq("0"), eq(16000));
        var peer = voice.synthesize("大家好。", "peer");
        assertNotEquals(first.segments().getFirst().url(), peer.segments().getFirst().url());
        verify(tts).synthesize("大家好。", "1001", 16000);
        String key = first.segments().getFirst().url().replace("/api/classroom/voice/audio/", "").replace(".mp3", "");
        assertArrayEquals(new byte[]{1, 2, 3}, voice.audio(key));
    }

    @Test
    void cacheCanStillBeReadWhenProviderUnavailableButMissingSegmentFails() {
        TtsService tts = mock(TtsService.class);
        when(tts.isAvailable()).thenReturn(true);
        when(tts.synthesize(anyString(), anyString(), anyInt())).thenReturn(new byte[]{1});
        ClassroomVoiceService voice = service(tts, mock(AudioService.class));
        var cached = voice.synthesize("第一句。", "teacher");
        when(tts.isAvailable()).thenReturn(false);
        assertEquals(cached, voice.synthesize("第一句。", "teacher"));
        assertEquals(503, assertThrows(ClassroomException.class, () -> voice.synthesize("第二句。", "teacher")).status());
    }

    @Test
    void partialSynthesisAndInvalidRequestsDoNotReturnSuccess() {
        TtsService tts = mock(TtsService.class);
        when(tts.isAvailable()).thenReturn(true);
        when(tts.synthesize("第一句。", "0", 16000)).thenReturn(new byte[]{1});
        ClassroomVoiceService voice = service(tts, mock(AudioService.class));
        assertEquals(502, assertThrows(ClassroomException.class, () -> voice.synthesize("第一句。第二句。", "teacher")).status());
        clearInvocations(tts);
        assertThrows(ClassroomException.class, () -> voice.synthesize("长".repeat(2001), "teacher"));
        assertThrows(ClassroomException.class, () -> voice.synthesize("你好", "unknown"));
        assertThrows(ClassroomException.class, () -> voice.audio("../../application.yml"));
        verifyNoInteractions(tts);
    }

    @Test
    void validatesActualWavBeforeAsrAndDoesNotPersistRecording() throws Exception {
        AudioService asr = mock(AudioService.class);
        when(asr.isAvailable()).thenReturn(true);
        byte[] wav = wav(16000);
        when(asr.transcribe(wav, "wav", 1)).thenReturn("老师，为什么会下雨？");
        ClassroomVoiceService voice = service(mock(TtsService.class), asr);
        assertEquals("老师，为什么会下雨？", voice.transcribe(wav));
        try (var files = java.nio.file.Files.list(directory)) { assertEquals(0, files.count()); }
        assertEquals(60, ClassroomVoiceService.validateWav(wav(16000 * 60)));
        assertThrows(ClassroomException.class, () -> ClassroomVoiceService.validateWav(wav(16000 * 60 + 1)));
        byte[] stereo = wav.clone(); stereo[22] = 2;
        assertThrows(ClassroomException.class, () -> voice.transcribe(stereo));
        byte[] wrongRate = wav.clone(); ByteBuffer.wrap(wrongRate).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 48000);
        assertThrows(ClassroomException.class, () -> voice.transcribe(wrongRate));
        byte[] damaged = wav.clone(); ByteBuffer.wrap(damaged).order(ByteOrder.LITTLE_ENDIAN).putInt(40, Integer.MAX_VALUE);
        assertThrows(ClassroomException.class, () -> voice.transcribe(damaged));
        assertThrows(ClassroomException.class, () -> voice.transcribe("not a wave file".getBytes(StandardCharsets.UTF_8)));
        verify(asr, times(1)).transcribe(any(byte[].class), eq("wav"), anyInt());
    }

    private ClassroomVoiceService service(TtsService tts, AudioService asr) {
        return new ClassroomVoiceService(tts, asr, directory.toString(), "0", "1001");
    }

    static byte[] wav(int samples) {
        ByteBuffer b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(b.capacity() - 8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        return b.array();
    }
}
