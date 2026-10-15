package com.demo.edu.classroom;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/** 课堂短语音接口，原始录音不保存；识别出的文字由学生确认后再提交课堂。 */
@RestController
@RequestMapping("/api/classroom/voice")
public class ClassroomVoiceController {
    private final ClassroomVoiceService voice;
    public ClassroomVoiceController(ClassroomVoiceService voice) { this.voice = voice; }

    public record TtsRequest(String text, String role) {}

    @GetMapping("/health")
    public Map<String, Boolean> health() { return voice.health(); }

    @PostMapping("/tts")
    public ClassroomVoiceService.Speech tts(@RequestBody TtsRequest request) {
        return voice.synthesize(request.text(), request.role());
    }

    @GetMapping(value = "/audio/{key}.mp3", produces = "audio/mpeg")
    public ResponseEntity<byte[]> audio(@PathVariable String key) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(voice.audio(key));
    }

    @PostMapping(value = "/asr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> asr(@RequestParam("file") MultipartFile file) {
        if (file.getSize() > ClassroomVoiceService.MAX_WAV_BYTES) {
            throw new ClassroomException(400, "录音超过 60 秒 WAV 大小限制");
        }
        try { return Map.of("text", voice.transcribe(file.getBytes())); }
        catch (IOException e) { throw new ClassroomException(400, "录音文件读取失败，请重新上传"); }
    }

    @ExceptionHandler(ClassroomException.class)
    public ResponseEntity<Map<String, String>> error(ClassroomException error) {
        return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
    }
}
