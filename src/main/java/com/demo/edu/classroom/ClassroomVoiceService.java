package com.demo.edu.classroom;

import com.demo.service.TtsService;
import com.demo.service.preprocessing.AudioService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 课堂语音边界：按句缓存教师/同学 MP3，录音只做一次转写，不创建实时语音会话。
 * 缓存只包含合成课堂正文；学生原始录音仅在请求内处理，不落盘。
 */
@Service
public class ClassroomVoiceService {
    public static final int MAX_TEXT_LENGTH = 2000;
    public static final int MAX_WAV_BYTES = 1_920_044; // 60s × 16000Hz × 2 bytes + 标准头
    private static final int SAMPLE_RATE = 16000;

    private final TtsService tts;
    private final AudioService asr;
    private final Path cacheDirectory;
    private final String teacherVoice;
    private final String peerVoice;
    private final Object[] cacheLocks = new Object[32];

    public ClassroomVoiceService(TtsService tts, AudioService asr,
            @Value("${classroom.data-dir:data/classroom}") String dataDirectory,
            @Value("${classroom.voice.teacher-voice-type:0}") String teacherVoice,
            @Value("${classroom.voice.peer-voice-type:1001}") String peerVoice) {
        this.tts = tts;
        this.asr = asr;
        this.cacheDirectory = Path.of(dataDirectory).resolve("audio").toAbsolutePath().normalize();
        this.teacherVoice = teacherVoice;
        this.peerVoice = peerVoice;
        for (int i = 0; i < cacheLocks.length; i++) cacheLocks[i] = new Object();
    }

    public record Segment(String text, String url) {}
    public record Speech(List<Segment> segments) {}

    /** 配置状态检查，不探测付费接口，也不暴露密钥。 */
    public Map<String, Boolean> health() {
        return Map.of("ttsAvailable", tts.isAvailable(), "asrAvailable", asr.isAvailable());
    }

    /** 输入限制在进入云端前检查；任一片失败时不给出残缺的成功播放列表。 */
    public Speech synthesize(String text, String role) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
            throw new ClassroomException(400, "朗读正文须为 1 至 2000 字");
        }
        String voice = switch (role == null ? "teacher" : role) {
            case "teacher" -> teacherVoice;
            case "peer" -> peerVoice;
            default -> throw new ClassroomException(400, "朗读角色仅支持教师或同学");
        };
        List<Segment> segments = new ArrayList<>();
        for (String segment : TtsService.splitForTts(text)) {
            if (segment.isBlank()) continue;
            String key = cacheKey(segment, voice);
            Path audio = cachePath(key);
            // 分条锁限制锁对象数量；相同句子并发到达时只合成一次。
            synchronized (cacheLocks[(key.hashCode() & Integer.MAX_VALUE) % cacheLocks.length]) {
                if (!isCached(audio)) {
                    if (!tts.isAvailable()) throw new ClassroomException(503, "语音合成尚未配置，可以继续阅读文字");
                    byte[] bytes = tts.synthesize(segment, voice, SAMPLE_RATE);
                    if (bytes == null || bytes.length == 0) {
                        throw new ClassroomException(502, "语音合成暂时失败，可以继续阅读文字或稍后重试");
                    }
                    saveAtomically(audio, bytes);
                }
            }
            segments.add(new Segment(segment, "/api/classroom/voice/audio/" + key + ".mp3"));
        }
        return new Speech(List.copyOf(segments));
    }

    public byte[] audio(String key) {
        Path path = cachePath(key);
        if (!isCached(path)) throw new ClassroomException(404, "语音片段不存在，请重新生成");
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ClassroomException(503, "语音缓存暂时无法读取");
        }
    }

    /** 时长取自 WAV 实际数据量，不信任前端提交的扩展名、MIME 或 duration。 */
    public String transcribe(byte[] wav) {
        int durationSeconds = validateWav(wav);
        if (!asr.isAvailable()) throw new ClassroomException(503, "语音识别尚未配置，请使用文字输入");
        String text = asr.transcribe(wav, "wav", durationSeconds);
        if (text == null || text.isBlank()) throw new ClassroomException(502, "没有识别到有效语音，请重录或输入文字");
        return text;
    }

    /** 严格接收本项目录音器的 PCM WAV，同时允许标准附加块；不接受伪装的 webm。 */
    static int validateWav(byte[] wav) {
        if (wav == null || wav.length < 44 || wav.length > MAX_WAV_BYTES) {
            throw new ClassroomException(400, "请上传不超过 60 秒的 16k 单声道 WAV 录音");
        }
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (!tag(wav, 0, "RIFF") || !tag(wav, 8, "WAVE")
                || Integer.toUnsignedLong(buffer.getInt(4)) + 8 != wav.length) {
            throw new ClassroomException(400, "录音不是有效 WAV 文件");
        }
        boolean hasFormat = false;
        long dataBytes = -1;
        int offset = 12;
        while (offset + 8 <= wav.length) {
            long size = Integer.toUnsignedLong(buffer.getInt(offset + 4));
            long next = offset + 8L + size + (size & 1);
            if (next > wav.length) throw new ClassroomException(400, "WAV 数据不完整");
            if (tag(wav, offset, "fmt ")) {
                if (hasFormat || size < 16 || buffer.getShort(offset + 8) != 1
                        || buffer.getShort(offset + 10) != 1 || buffer.getInt(offset + 12) != SAMPLE_RATE
                        || buffer.getInt(offset + 16) != SAMPLE_RATE * 2 || buffer.getShort(offset + 20) != 2
                        || buffer.getShort(offset + 22) != 16) {
                    throw new ClassroomException(400, "录音须为 16kHz、单声道、16 位 PCM WAV");
                }
                hasFormat = true;
            } else if (tag(wav, offset, "data")) {
                if (dataBytes >= 0) throw new ClassroomException(400, "WAV 含重复音频数据块");
                dataBytes = size;
            }
            offset = (int) next;
        }
        if (!hasFormat || offset != wav.length || dataBytes <= 0 || dataBytes % 2 != 0 || dataBytes > 60L * SAMPLE_RATE * 2) {
            throw new ClassroomException(400, "WAV 音频为空、损坏或超过 60 秒");
        }
        return (int) Math.ceil(dataBytes / (double) (SAMPLE_RATE * 2));
    }

    private static boolean tag(byte[] bytes, int start, String expected) {
        for (int i = 0; i < expected.length(); i++) if (bytes[start + i] != expected.charAt(i)) return false;
        return true;
    }

    private Path cachePath(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new ClassroomException(400, "语音片段标识无效");
        return cacheDirectory.resolve(key + ".mp3");
    }

    private static boolean isCached(Path audio) {
        try { return Files.isRegularFile(audio) && Files.size(audio) > 0; }
        catch (IOException e) { return false; }
    }

    private static String cacheKey(String text, String voice) {
        try {
            byte[] bytes = ("classroom-tts-v1\n" + voice + "\n" + SAMPLE_RATE + "\n" + text).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void saveAtomically(Path audio, byte[] bytes) {
        Path temporary = null;
        try {
            Files.createDirectories(cacheDirectory);
            temporary = Files.createTempFile(cacheDirectory, "speech-", ".tmp");
            Files.write(temporary, bytes);
            try { Files.move(temporary, audio, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, audio, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            throw new ClassroomException(503, "语音缓存无法保存，请检查课堂数据目录");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* 不遮盖原始失败 */ }
            }
        }
    }
}
