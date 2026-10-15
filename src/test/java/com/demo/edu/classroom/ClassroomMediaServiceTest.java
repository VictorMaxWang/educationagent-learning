package com.demo.edu.classroom;

import com.demo.config.AiChatProperties;
import com.demo.edu.classroom.media.ClassroomImageTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class ClassroomMediaServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path directory;

    @Test
    void nativeRequestUsesSameOriginAndCachesOnlyLocalCompressedImages() throws Exception {
        FakeTransport transport = new FakeTransport();
        MockEnvironment env = environment();
        ClassroomMediaService service = service(env, transport);
        assertTrue(service.isAvailable());
        var image = service.generateImage("光合作用的植物插图", "16:9", new ClassroomCancellation());
        assertEquals("https://provider.example.test/api/v1/services/aigc/multimodal-generation/generation", transport.endpoint.toString());
        assertEquals("test-chat-key", transport.key);
        assertEquals("z-image-turbo", transport.body.path("model").asText());
        assertEquals("光合作用的植物插图", transport.body.at("/input/messages/0/content/0/text").asText());
        assertEquals("1024*576", transport.body.at("/parameters/size").asText());
        assertFalse(transport.body.at("/parameters/prompt_extend").asBoolean());
        assertFalse(transport.body.at("/parameters/watermark").asBoolean());
        assertTrue(image.url().matches("/api/classroom/media/[0-9a-f]{64}\\.jpg"));
        assertFalse(image.url().contains("signature"));
        assertEquals(1024, image.width());
        assertEquals(576, image.height());
        assertEquals("image/jpeg", image.mimeType());
        byte[] stored = service.image(key(image.url()));
        assertTrue(stored.length <= ClassroomMediaService.MAX_JPEG_BYTES);
        assertEquals(0xff, stored[0] & 255);
        assertEquals(0xd8, stored[1] & 255);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(stored)));
        // 新实例也能复用磁盘缓存，课程重试和服务重启不会重复付费。
        var cached = service(env, transport).generateImage("光合作用的植物插图", "16:9", new ClassroomCancellation());
        assertEquals(image.url(), cached.url());
        assertTrue(cached.cached());
        assertEquals(1, transport.posts);
        assertEquals(1, transport.downloads);
    }

    @Test
    void enrichUsesSrcNotCanvasIdAndLimitsNewImagesToOne() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClassroomMediaService service = service(environment(), transport);
        ObjectNode scene = scene();
        ObjectNode outline = outline();
        ObjectNode result = service.enrichSlide(scene, outline, new ClassroomCancellation());
        assertEquals(2, result.at("/content/canvas/elements").size());
        assertEquals("canvas-random-one", result.at("/content/canvas/elements/1/id").asText());
        assertTrue(result.at("/content/canvas/elements/1/src").asText().startsWith("/api/classroom/media/"));
        assertEquals("gen_img_one", scene.at("/content/canvas/elements/1/src").asText());
        assertEquals(2, result.path("actions").size());
        assertEquals("canvas-random-one", result.at("/actions/1/elementId").asText());
        assertTrue(result.path("mediaWarnings").isArray());
        assertEquals(1, transport.posts);
        assertEquals(1, transport.downloads);
    }

    @Test
    void failedImagesRemoveDanglingActionsAndPreserveTextWithoutProviderErrors() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.failure = true;
        ObjectNode result = service(environment(), transport).enrichSlide(scene(), outline(), new ClassroomCancellation());
        assertEquals(1, result.at("/content/canvas/elements").size());
        assertEquals("认真观察植物", result.at("/content/canvas/elements/0/content").asText());
        assertEquals(1, result.path("actions").size());
        assertEquals("speech", result.at("/actions/0/type").asText());
        assertTrue(result.path("mediaWarnings").get(0).asText().contains("文字讲解"));
        assertFalse(result.toString().contains("test-private-provider-error"));
    }

    @Test
    void cancellationBeforeOrAfterProviderResponseDoesNotBecomeWarningOrPersistImage() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClassroomMediaService service = service(environment(), transport);
        ClassroomCancellation before = new ClassroomCancellation(); before.cancel();
        assertThrows(CancellationException.class, () -> service.enrichSlide(scene(), outline(), before));
        assertEquals(0, transport.posts);
        transport.cancelAfterPost = true;
        assertThrows(CancellationException.class, () -> service.enrichSlide(scene(), outline(), new ClassroomCancellation()));
        assertEquals(1, transport.posts);
        assertEquals(0, transport.downloads);
        assertFalse(Files.exists(directory.resolve("media")));
    }

    @Test
    void uploadsValidateRealBytesResizeAndRejectTraversalAndOversize() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClassroomMediaService service = service(environment(), transport);
        var asset = service.uploadImage(png(1600, 900), new ClassroomCancellation());
        assertEquals(1024, asset.width());
        assertEquals(576, asset.height());
        assertEquals(0, transport.posts);
        assertThrows(ClassroomException.class, () -> service.uploadImage("<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8), new ClassroomCancellation()));
        assertThrows(ClassroomException.class, () -> service.uploadImage(new byte[ClassroomMediaService.MAX_UPLOAD_BYTES + 1], new ClassroomCancellation()));
        assertThrows(ClassroomException.class, () -> service.uploadImage(png(8193, 1), new ClassroomCancellation()));
        assertEquals(400, assertThrows(ClassroomException.class, () -> service.image("../outside")).status());
        assertEquals(404, assertThrows(ClassroomException.class, () -> service.image("0".repeat(64))).status());
    }

    @Test
    void foreignProviderRequiresExplicitImageKeyAndOpenAiUsesImageProtocolOnly() throws Exception {
        FakeTransport transport = new FakeTransport();
        MockEnvironment env = environment().withProperty("classroom.image.base-url", "https://other.example.test/v1")
                .withProperty("classroom.image.protocol", "openai").withProperty("classroom.image.model", "gpt-image-1");
        ClassroomMediaService noKey = service(env, transport);
        assertFalse(noKey.isAvailable());
        assertEquals(503, assertThrows(ClassroomException.class,
                () -> noKey.generateImage("种子发芽", "1:1", new ClassroomCancellation())).status());
        assertEquals(0, transport.posts);
        env.withProperty("classroom.image.api-key", "test-image-only-key");
        transport.openAi = true;
        var result = service(env, transport).generateImage("种子发芽", "1:1", new ClassroomCancellation());
        assertNotNull(result);
        assertEquals("https://other.example.test/v1/images/generations", transport.endpoint.toString());
        assertEquals("test-image-only-key", transport.key);
        assertEquals("种子发芽", transport.body.path("prompt").asText());
        assertEquals(1, transport.body.path("n").asInt());
        assertEquals("1024x1024", transport.body.path("size").asText());
        assertEquals(0, transport.downloads);
    }

    @Test
    void videoModelsAndInvalidPromptsNeverReachProvider() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClassroomMediaService video = service(environment().withProperty("classroom.image.model", "qwen-image-video"), transport);
        assertFalse(video.isAvailable());
        assertThrows(ClassroomException.class, () -> video.generateImage("不能生成视频", "1:1", new ClassroomCancellation()));
        ClassroomMediaService image = service(environment(), transport);
        assertThrows(ClassroomException.class, () -> image.generateImage(" ", "1:1", new ClassroomCancellation()));
        assertThrows(ClassroomException.class, () -> image.generateImage("画".repeat(2001), "1:1", new ClassroomCancellation()));
        assertThrows(ClassroomException.class, () -> image.generateImage("种子发芽", "100:1", new ClassroomCancellation()));
        assertEquals(0, transport.posts);
    }

    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("classroom.data-dir", directory.toString())
                .withProperty("AI_CHAT_BASE_URL", "https://provider.example.test/compatible-mode/v1")
                .withProperty("AI_CHAT_API_KEY", "test-chat-key").withProperty("AI_CHAT_MODEL", "qwen3.8-flash");
    }

    private ClassroomMediaService service(MockEnvironment env, FakeTransport transport) {
        return new ClassroomMediaService(new AiChatProperties(env), env, json, transport);
    }

    private ObjectNode scene() throws IOException {
        return (ObjectNode) json.readTree("""
                {"id":"scene","type":"slide","content":{"type":"slide","canvas":{"elements":[
                {"id":"text","type":"text","content":"认真观察植物"},
                {"id":"canvas-random-one","type":"image","src":"gen_img_one"},
                {"id":"canvas-random-two","type":"image","src":"gen_img_two"}]}},"actions":[
                {"type":"speech","text":"认真观察植物"},
                {"type":"spotlight","elementId":"canvas-random-one"},
                {"type":"laser","elementId":"canvas-random-two"}]}
                """);
    }

    private ObjectNode outline() throws IOException {
        return (ObjectNode) json.readTree("""
                {"mediaGenerations":[
                {"type":"image","elementId":"gen_img_one","prompt":"种子发芽","aspectRatio":"1:1"},
                {"type":"image","elementId":"gen_img_two","prompt":"树叶","aspectRatio":"1:1"}]}
                """);
    }

    static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(Color.GREEN); graphics.fillRect(0, 0, width, height); }
        finally { graphics.dispose(); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static String key(String url) { return url.substring(url.lastIndexOf('/') + 1, url.length() - 4); }

    private final class FakeTransport implements ClassroomImageTransport {
        int posts, downloads;
        boolean failure, cancelAfterPost, openAi;
        URI endpoint;
        String key;
        JsonNode body;

        @Override public byte[] postJson(URI endpoint, String key, byte[] body, ClassroomCancellation token) throws IOException {
            this.endpoint = endpoint; this.key = key; this.body = json.readTree(body); posts++;
            if (failure) throw new IOException("test-private-provider-error");
            if (cancelAfterPost) token.cancel();
            if (openAi) return json.writeValueAsBytes(java.util.Map.of("data", java.util.List.of(java.util.Map.of("b64_json", Base64.getEncoder().encodeToString(png(1600, 900))))));
            return "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"image\":\"https://cdn.example.test/image.png?signature=test-only\"}]}}]}}".getBytes(StandardCharsets.UTF_8);
        }

        @Override public byte[] download(URI image, ClassroomCancellation token) throws IOException {
            downloads++;
            assertEquals("cdn.example.test", image.getHost());
            return png(1600, 900);
        }
    }
}
