package com.demo.edu.classroom;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

/** 课堂编辑器只接触本站缓存地址，不能选择供应商 URL、密钥或视频模型。 */
@RestController
@RequestMapping("/api/classroom/media")
public class ClassroomMediaController {
    private final ClassroomMediaService media;
    public ClassroomMediaController(ClassroomMediaService media) { this.media = media; }
    public record ImageRequest(String prompt, String aspectRatio) {}

    @PostMapping("/images")
    public ClassroomMediaService.ImageAsset generate(@RequestBody ImageRequest request) {
        return media.generateImage(request.prompt(), request.aspectRatio(), new ClassroomCancellation());
    }

    @GetMapping(value = "/{key:[0-9a-f]{64}}.jpg", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable String key) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(media.image(key));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ClassroomMediaService.ImageAsset upload(@RequestParam("file") MultipartFile file) {
        if (file.getSize() > ClassroomMediaService.MAX_UPLOAD_BYTES)
            throw new ClassroomException(400, "请选择不超过 8 MB 的图片");
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(ClassroomMediaService.MAX_UPLOAD_BYTES + 1);
            return media.uploadImage(bytes, new ClassroomCancellation());
        } catch (IOException invalid) { throw new ClassroomException(400, "图片读取失败，请重新选择文件"); }
    }

    @ExceptionHandler(ClassroomException.class)
    public ResponseEntity<Map<String, String>> error(ClassroomException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("error", failure.getMessage()));
    }
}
