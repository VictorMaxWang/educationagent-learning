package com.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 将百炼 WAV 实际转为 MP3，保持网站播放器、缓存下载与课程导出的既有契约。 */
@Slf4j
@Service
public class WebsiteAudioEncoder {
    private final String executable;

    public WebsiteAudioEncoder(@Value("${website.tts.ffmpeg:ffmpeg}") String executable) {
        this.executable = executable;
    }

    /** 只检查可执行文件是否可定位；不启动转码，也不触发模型请求。 */
    public boolean isAvailable() { return resolveExecutable() != null; }

    public byte[] toMp3(byte[] wav) {
        String binary = resolveExecutable();
        if (binary == null || !AliyunTtsService.isWav(wav)) return null;
        Path input = null, output = null;
        Process process = null;
        try {
            input = Files.createTempFile("educationagent-tts-", ".wav");
            output = Files.createTempFile("educationagent-tts-", ".mp3");
            Files.write(input, wav);
            // 直接传参数，不拼 shell 命令；只运行本次转码进程，限制执行时长。
            process = new ProcessBuilder(binary, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                    "-i", input.toString(), "-vn", "-ar", "16000", "-ac", "1", "-codec:a", "libmp3lame",
                    "-b:a", "64k", output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                log.warn("网站 TTS 音频转码超时");
                return null;
            }
            if (process.exitValue() != 0 || Files.size(output) == 0 || Files.size(output) > AliyunTtsService.MAX_AUDIO_BYTES) {
                log.warn("网站 TTS 音频转码失败");
                return null;
            }
            byte[] result = Files.readAllBytes(output);
            return isMp3(result) ? result : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return null;
        } catch (IOException e) {
            log.warn("网站 TTS 转码不可用：{}", e.getClass().getSimpleName());
            return null;
        } finally {
            deleteTemporary(input);
            deleteTemporary(output);
        }
    }

    String resolveExecutable() {
        if (executable == null || executable.isBlank()) return null;
        try {
            Path configured = Path.of(executable);
            if (configured.isAbsolute() || executable.contains("/") || executable.contains("\\")) {
                return usable(configured) ? configured.toAbsolutePath().toString() : null;
            }
            String searchPath = System.getenv("PATH");
            if (searchPath == null) return null;
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            for (String folder : searchPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (folder.isBlank()) continue;
                Path candidate = Path.of(folder, executable);
                if (usable(candidate)) return candidate.toAbsolutePath().toString();
                if (windows && !executable.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                    candidate = Path.of(folder, executable + ".exe");
                    if (usable(candidate)) return candidate.toAbsolutePath().toString();
                }
            }
        } catch (RuntimeException e) { return null; }
        return null;
    }

    private static boolean usable(Path path) { return Files.isRegularFile(path) && Files.isExecutable(path); }
    private static void deleteTemporary(Path path) {
        if (path != null) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { /* 清理异常不掩盖合成结果 */ }
        }
    }
    static boolean isMp3(byte[] bytes) {
        return bytes != null && bytes.length > 3 && ((bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3')
                || ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xe0) == 0xe0));
    }
}
