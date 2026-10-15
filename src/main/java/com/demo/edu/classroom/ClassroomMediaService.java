package com.demo.edu.classroom;

import com.demo.config.AiChatProperties;
import com.demo.edu.classroom.media.ClassroomImageTransport;
import com.demo.edu.classroom.media.SafeImageHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * 课堂图片生成、上传与本地缓存边界。只接受图片模型，永不发起视频任务。
 * Node 只输出 gen_img_* 占位；图片字节不经过 JSONL，也不把供应商签名地址或密钥交给浏览器。
 */
@Service
public class ClassroomMediaService {
    public static final int MAX_UPLOAD_BYTES = SafeImageHttpClient.MAX_IMAGE_BYTES;
    public static final int MAX_JPEG_BYTES = 350 * 1024;
    private static final int MAX_PROMPT_CHARS = 2000;
    private static final String URL_PREFIX = "/api/classroom/media/";
    private static final String IMAGE_FAILURE = "本页插图暂时未生成，文字讲解仍可正常学习。";
    private final ObjectMapper json;
    private final ClassroomImageTransport transport;
    private final Path cacheDirectory;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final String protocol;
    private final boolean enabled;
    private final Object[] cacheLocks = new Object[32];

    @Autowired
    public ClassroomMediaService(AiChatProperties chat, Environment environment, ObjectMapper json) {
        this(chat, environment, json, new SafeImageHttpClient());
    }

    /** 测试只替换外部 HTTP 边界；配置、请求、图像验证和文件缓存仍执行真实实现。 */
    ClassroomMediaService(AiChatProperties chat, Environment environment, ObjectMapper json,
                          ClassroomImageTransport transport) {
        this.json = json;
        this.transport = transport;
        cacheDirectory = Path.of(setting(environment, "CLASSROOM_DATA_DIR", "classroom.data-dir", "data/classroom"))
                .resolve("media").toAbsolutePath().normalize();
        protocol = setting(environment, "CLASSROOM_IMAGE_PROTOCOL", "classroom.image.protocol", "dashscope").toLowerCase(Locale.ROOT);
        model = setting(environment, "CLASSROOM_IMAGE_MODEL", "classroom.image.model", "z-image-turbo");
        enabled = Boolean.parseBoolean(setting(environment, "CLASSROOM_IMAGE_ENABLED", "classroom.image.enabled", "true"));
        String base = setting(environment, "CLASSROOM_IMAGE_BASE_URL", "classroom.image.base-url", chat.getBaseUrl());
        URI configuredBase = configuredUri(base);
        URI chatBase = configuredUri(chat.getBaseUrl());
        String explicitKey = setting(environment, "CLASSROOM_IMAGE_API_KEY", "classroom.image.api-key", "");
        // 仅同 origin 才复用聊天密钥；管理员换了图片供应商时必须同时明确配置它自己的密钥。
        apiKey = !explicitKey.isBlank() ? explicitKey : sameOrigin(configuredBase, chatBase) ? chat.getApiKey() : "";
        endpoint = endpoint(configuredBase, protocol);
        for (int i = 0; i < cacheLocks.length; i++) cacheLocks[i] = new Object();
    }

    public record ImageAsset(String url, int width, int height, String mimeType, boolean cached) {}
    private record EncodedImage(byte[] bytes, int width, int height) {}
    private record Size(int width, int height) {}

    /** 配置检查不发起付费探针，也不向前端暴露供应商连接或凭据。 */
    public boolean isAvailable() {
        return enabled && endpoint != null && !apiKey.isBlank() && !apiKey.startsWith("${") && isImageModel(model);
    }

    /** 按占位 src 回填最多一张新图；失败只移除失效元素和动作，保留课程正文。 */
    public ObjectNode enrichSlide(ObjectNode scene, JsonNode outline, ClassroomCancellation token) {
        token.check();
        ObjectNode result = scene.deepCopy();
        if (!"slide".equals(result.path("type").asText())) return result;
        JsonNode elements = result.path("content").path("canvas").path("elements");
        if (!elements.isArray()) return result;
        Map<String, JsonNode> approved = approvedImages(outline);
        Set<String> removed = new HashSet<>();
        ArrayNode kept = json.createArrayNode();
        String attempted = null;
        ImageAsset generated = null;
        boolean warned = false;
        for (JsonNode element : elements) {
            token.check();
            if (!"image".equals(element.path("type").asText())) { kept.add(element); continue; }
            String src = element.path("src").asText();
            if (src.matches("/api/classroom/media/[0-9a-f]{64}\\.jpg")) { kept.add(element); continue; }
            JsonNode request = approved.get(src);
            if (request != null && (attempted == null || attempted.equals(src))) {
                if (attempted == null) {
                    attempted = src;
                    try { generated = generateImage(request.path("prompt").asText(), request.path("aspectRatio").asText("1:1"), token); }
                    catch (CancellationException cancelled) { throw cancelled; }
                    catch (RuntimeException unavailable) { warned = true; }
                }
                if (generated != null && element.isObject()) {
                    ((ObjectNode) element).put("src", generated.url());
                    kept.add(element);
                    continue;
                }
            }
            warned = true;
            removed.add(element.path("id").asText());
        }
        ((ObjectNode) result.path("content").path("canvas")).set("elements", kept);
        if (result.path("actions").isArray() && !removed.isEmpty()) {
            ArrayNode actions = json.createArrayNode();
            for (JsonNode action : result.path("actions")) {
                if (!removed.contains(action.path("elementId").asText("__none__"))) actions.add(action);
            }
            result.set("actions", actions);
        }
        if (warned) {
            ArrayNode warnings = result.path("mediaWarnings").isArray()
                    ? (ArrayNode) result.get("mediaWarnings") : result.putArray("mediaWarnings");
            boolean recorded = false;
            for (JsonNode warning : warnings) if (IMAGE_FAILURE.equals(warning.asText())) recorded = true;
            if (!recorded) warnings.add(IMAGE_FAILURE);
        }
        token.check();
        return result;
    }

    /** 相同提示、比例、模型和端点只生成一次，已存在缓存可在供应商临时不可用时继续使用。 */
    public ImageAsset generateImage(String prompt, String aspectRatio, ClassroomCancellation token) {
        token.check();
        String checkedPrompt = checkedPrompt(prompt);
        Size size = size(aspectRatio);
        String key = hash(("classroom-image-v1\n" + protocol + "\n" + endpoint + "\n" + model + "\n"
                + size.width() + "x" + size.height() + "\n" + checkedPrompt).getBytes(StandardCharsets.UTF_8));
        synchronized (cacheLocks[(key.hashCode() & Integer.MAX_VALUE) % cacheLocks.length]) {
            token.check();
            ImageAsset cached = cachedAsset(key);
            if (cached != null) return cached;
            if (!isAvailable()) throw new ClassroomException(503, "课堂配图尚未配置，可以先学习文字内容");
            try {
                ObjectNode body = request(checkedPrompt, size);
                byte[] response = transport.postJson(endpoint, apiKey, json.writeValueAsBytes(body), token);
                token.check();
                byte[] source = imageBytes(json.readTree(response), token);
                EncodedImage image = encode(source);
                token.check();
                save(key, image.bytes());
                token.check();
                return asset(key, image.width(), image.height(), false);
            } catch (CancellationException cancelled) { throw cancelled; }
            catch (Exception unavailable) { throw new ClassroomException(502, "图片生成暂时失败，请稍后重试或上传本地图片"); }
        }
    }

    /** 上传只保存重新编码后的真实光栅图，丢弃文件名、元数据及任何附带内容。 */
    public ImageAsset uploadImage(byte[] bytes, ClassroomCancellation token) {
        token.check();
        EncodedImage image = encode(bytes);
        String key = hash(image.bytes());
        synchronized (cacheLocks[(key.hashCode() & Integer.MAX_VALUE) % cacheLocks.length]) {
            token.check();
            ImageAsset cached = cachedAsset(key);
            if (cached != null) return cached;
            save(key, image.bytes());
        }
        token.check();
        return asset(key, image.width(), image.height(), false);
    }

    public byte[] image(String key) {
        Path path = cachePath(key);
        if (!isCached(path)) throw new ClassroomException(404, "课堂图片不存在，请重新生成或上传");
        try { return Files.readAllBytes(path); }
        catch (IOException unavailable) { throw new ClassroomException(503, "课堂图片暂时无法读取"); }
    }

    private ObjectNode request(String prompt, Size size) {
        ObjectNode body = json.createObjectNode().put("model", model);
        if (protocol.equals("dashscope")) {
            ObjectNode message = body.putObject("input").putArray("messages").addObject().put("role", "user");
            message.putArray("content").addObject().put("text", prompt);
            body.putObject("parameters").put("size", size.width() + "*" + size.height())
                    .put("prompt_extend", false).put("watermark", false);
        } else {
            body.put("prompt", prompt).put("n", 1).put("size", size.width() + "x" + size.height());
        }
        return body;
    }

    private byte[] imageBytes(JsonNode response, ClassroomCancellation token) throws IOException {
        if (response == null) throw new IOException("图片响应为空");
        String encoded = "", url = "";
        if (protocol.equals("dashscope")) {
            for (JsonNode choice : response.path("output").path("choices")) {
                for (JsonNode content : choice.path("message").path("content")) {
                    if (content.path("image").isTextual()) { url = content.path("image").asText(); break; }
                }
                if (!url.isBlank()) break;
            }
        } else {
            JsonNode first = response.path("data").path(0);
            encoded = first.path("b64_json").asText("");
            url = first.path("url").asText("");
        }
        if (!encoded.isBlank()) {
            if (encoded.length() > ((MAX_UPLOAD_BYTES + 2L) / 3) * 4) throw new IOException("图片响应过大");
            return Base64.getDecoder().decode(encoded);
        }
        if (url.isBlank()) throw new IOException("图片响应没有有效图像");
        URI uri;
        try { uri = URI.create(url); }
        catch (IllegalArgumentException invalid) { throw new IOException("图片地址无效"); }
        SafeImageHttpClient.requirePublicHttpsUri(uri);
        token.check();
        return transport.download(uri, token);
    }

    private static EncodedImage encode(byte[] source) {
        if (source == null || source.length == 0 || source.length > MAX_UPLOAD_BYTES)
            throw new ClassroomException(400, "图片须为不超过 8 MB 的有效图片文件");
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("无效图片");
            ImageReader reader = readers.next();
            BufferedImage decoded;
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                // 先读尺寸再解码，避免很小的压缩文件展开后耗尽服务器内存。
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 20_000_000)
                    throw new IOException("图片尺寸过大");
                ImageReadParam param = reader.getDefaultReadParam();
                int subsample = Math.max(1, Math.max(width, height) / 1024);
                param.setSourceSubsampling(subsample, subsample, 0, 0);
                decoded = reader.read(0, param);
            } finally { reader.dispose(); }
            if (decoded == null) throw new IOException("图片无法解码");
            BufferedImage resized = fit(decoded, 1024);
            for (int shrink = 0; shrink < 4; shrink++) {
                for (float quality : new float[]{0.85f, 0.70f, 0.55f, 0.40f}) {
                    byte[] jpeg = jpeg(resized, quality);
                    if (jpeg.length <= MAX_JPEG_BYTES) return new EncodedImage(jpeg, resized.getWidth(), resized.getHeight());
                }
                resized = fit(resized, Math.max(256, (int) (Math.max(resized.getWidth(), resized.getHeight()) * 0.75)));
            }
            throw new IOException("图片无法压缩到课堂大小限制");
        } catch (IOException | RuntimeException invalid) {
            throw new ClassroomException(400, "图片无法识别或尺寸过大，请选择普通 PNG、JPEG 或 GIF 图片");
        }
    }

    private static BufferedImage fit(BufferedImage image, int maxSide) {
        double scale = Math.min(1.0, (double) maxSide / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return output;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam options = writer.getDefaultWriteParam();
            options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); options.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), options);
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }

    private ImageAsset cachedAsset(String key) {
        Path path = cachePath(key);
        if (!isCached(path)) return null;
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try { reader.setInput(input); return asset(key, reader.getWidth(0), reader.getHeight(0), true); }
            finally { reader.dispose(); }
        } catch (IOException | RuntimeException invalid) { return null; }
    }

    private static ImageAsset asset(String key, int width, int height, boolean cached) {
        return new ImageAsset(URL_PREFIX + key + ".jpg", width, height, "image/jpeg", cached);
    }

    private Path cachePath(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new ClassroomException(400, "课堂图片标识无效");
        return cacheDirectory.resolve(key + ".jpg");
    }

    private static boolean isCached(Path path) {
        try { return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) > 0 && Files.size(path) <= MAX_JPEG_BYTES; }
        catch (IOException unavailable) { return false; }
    }

    private void save(String key, byte[] bytes) {
        Path temporary = null;
        try {
            Files.createDirectories(cacheDirectory);
            temporary = Files.createTempFile(cacheDirectory, "image-", ".tmp");
            Files.write(temporary, bytes);
            try { Files.move(temporary, cachePath(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, cachePath(key), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException unavailable) { throw new ClassroomException(503, "课堂图片缓存无法保存，请稍后重试"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }

    private static Map<String, JsonNode> approvedImages(JsonNode outline) {
        Map<String, JsonNode> approved = new LinkedHashMap<>();
        Set<String> duplicates = new HashSet<>();
        if (outline == null) return approved;
        for (JsonNode request : outline.path("mediaGenerations")) {
            String id = request.path("elementId").asText();
            if (!"image".equals(request.path("type").asText()) || !id.matches("gen_img_[A-Za-z0-9_-]{1,100}")) continue;
            if (approved.putIfAbsent(id, request) != null) duplicates.add(id);
        }
        duplicates.forEach(approved::remove);
        return approved;
    }

    private static String checkedPrompt(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > MAX_PROMPT_CHARS)
            throw new ClassroomException(400, "请填写 1 至 2000 字的课堂配图描述");
        return prompt.trim();
    }

    private static Size size(String ratio) {
        return switch (ratio == null || ratio.isBlank() ? "1:1" : ratio.trim()) {
            case "1:1" -> new Size(1024, 1024);
            case "16:9" -> new Size(1024, 576);
            case "4:3" -> new Size(1024, 768);
            case "9:16" -> new Size(576, 1024);
            default -> throw new ClassroomException(400, "图片比例仅支持 1:1、16:9、4:3 或 9:16");
        };
    }

    private static String setting(Environment env, String variable, String property, String fallback) {
        String value = env.getProperty(variable);
        if (value == null || value.isBlank()) value = env.getProperty(property);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static URI configuredUri(String value) {
        try {
            URI uri = URI.create(value.replaceAll("/+$", ""));
            SafeImageHttpClient.requirePublicHttpsUri(uri);
            return uri.getQuery() == null ? uri : null;
        } catch (IllegalArgumentException | IOException invalid) { return null; }
    }

    private static URI endpoint(URI base, String protocol) {
        if (base == null) return null;
        if (protocol.equals("dashscope")) return base.resolve("/api/v1/services/aigc/multimodal-generation/generation");
        if (protocol.equals("openai")) return URI.create(base.toString() + "/images/generations");
        return null;
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first != null && second != null && first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && (first.getPort() == -1 ? 443 : first.getPort()) == (second.getPort() == -1 ? 443 : second.getPort());
    }

    private static boolean isImageModel(String model) {
        String lower = model.toLowerCase(Locale.ROOT);
        if (lower.matches(".*(video|t2v|i2v|veo|sora|kling|seedance|happyhorse).*")) return false;
        return lower.matches("(z-image.*|qwen-image.*|gpt-image.*|dall-e.*|.*flux.*|.*stable-diffusion.*|.*sdxl.*|imagen.*|.*kolors.*)");
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
