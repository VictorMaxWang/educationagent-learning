package com.demo.travel;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;

/**
 * AI试衣服务 — 阿里云百炼 AI试衣 Plus（aitryon-plus，专业 VTON）。
 * <p>
 * 异步接口：提交任务 → 轮询 → 下载结果图。
 * 图片入参用 base64 data URI（先试零配置方案；若 API 拒绝需换公网 URL/OSS）。
 * 仅华北2（北京）地域可用，需对应地域的 API Key。
 */
@Slf4j
@Service
public class AITryOnService {

    @Value("${ai.tryon.api-key:${dashscope.api.key:}}")
    private String apiKey;

    @Value("${ai.tryon.model:aitryon-plus}")
    private String model;

    /** 华北2专属 MaaS 端点（也可用默认 dashscope.aliyuncs.com/api/v1） */
    @Value("${ai.tryon.endpoint:https://dashscope.aliyuncs.com/api/v1}")
    private String endpoint;

    private String submitUrl() {
        return endpoint + "/services/aigc/image2image/image-synthesis";
    }

    private String taskUrl(String taskId) {
        return endpoint + "/tasks/" + taskId;
    }

    private static final long POLL_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 4_000;

    /** 入参图片兜底压缩：长边上限 + JPEG 质量（与前端压缩规则一致，前端未压缩/大图时兜底，减小 base64 请求体占用） */
    private static final int MAX_IMAGE_EDGE = 1280;
    private static final float JPEG_QUALITY = 0.7f;

    @Resource
    @Qualifier("visionRestTemplate")
    private RestTemplate restTemplate;

    /** 试穿结果（含具体错误信息，便于前端/日志排查） */
    public static class TryOnResult {
        public boolean success;
        public byte[] image;
        public String error;

        static TryOnResult fail(String msg) {
            TryOnResult r = new TryOnResult();
            r.success = false;
            r.error = msg;
            return r;
        }
    }

    /** 单件衣物输入 */
    public static class GarmentInput {
        public byte[] image;
        public String category;

        public GarmentInput(byte[] image, String category) {
            this.image = image;
            this.category = category;
        }
    }

    /**
     * AI 试穿（支持多件：上装 + 下装一起试穿整套）。
     *
     * @param personBytes 人物全身照字节
     * @param garments    选中的衣物列表（每件带品类）；第一件 上衣/连衣裙/外套 → top，第一件 下装 → bottom
     * @return TryOnResult：success + image，或 error 具体原因
     */
    public TryOnResult tryOn(byte[] personBytes, List<GarmentInput> garments) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            return TryOnResult.fail("百炼 API Key 未配置");
        }
        if (personBytes == null || personBytes.length == 0) {
            return TryOnResult.fail("人物照片为空");
        }
        if (garments == null || garments.isEmpty()) {
            return TryOnResult.fail("衣物图为空");
        }

        String personUri = toDataUri(compress(personBytes));
        String topUri = null;
        String bottomUri = null;
        for (GarmentInput g : garments) {
            if (g.image == null || g.image.length == 0) continue;
            String cat = g.category == null ? "" : g.category.toLowerCase();
            if ("top".equals(cat) || "dress".equals(cat) || "coat".equals(cat)) {
                if (topUri == null) topUri = toDataUri(compress(g.image));
            } else if ("bottom".equals(cat)) {
                if (bottomUri == null) bottomUri = toDataUri(compress(g.image));
            }
        }
        if (topUri == null && bottomUri == null) {
            return TryOnResult.fail("选中的衣物品类不支持试穿（仅支持上衣/下装/连衣裙/外套）");
        }

        JSONObject input = new JSONObject();
        input.put("person_image_url", personUri);
        if (topUri != null) input.put("top_garment_url", topUri);
        if (bottomUri != null) input.put("bottom_garment_url", bottomUri);
        JSONObject params = new JSONObject();
        params.put("resolution", -1);
        params.put("restore_face", true);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", params);

        SubmitOutcome outcome = submit(body);
        if (!outcome.ok) {
            return TryOnResult.fail("提交失败: " + outcome.error);
        }
        TryOnResult result = pollAndDownload(outcome.taskId);
        if (!result.success) {
            return TryOnResult.fail("任务失败: " + result.error);
        }
        return result;
    }

    /** 提交结果 */
    private static class SubmitOutcome {
        boolean ok;
        String taskId;
        String error;
    }

    /** 提交异步任务，返回 task_id + 具体错误 */
    private SubmitOutcome submit(JSONObject body) {
        SubmitOutcome o = new SubmitOutcome();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-DashScope-Async", "enable");
            headers.setBearerAuth(apiKey);

            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(submitUrl(), entity, String.class);
            if (response.getStatusCodeValue() != 200) {
                String bodyText = response.getBody();
                String err = bodyText == null ? ("HTTP " + response.getStatusCodeValue()) : truncate(bodyText, 200);
                log.error("提交 AI 试衣任务失败: {}", err);
                o.ok = false;
                o.error = err;
                return o;
            }
            JSONObject result = JSON.parseObject(response.getBody());
            // 非 200 的业务错误：code + message（如 InvalidApiKey / 图片格式错）
            String code = result.getString("code");
            if (code != null && !"".equals(code) && !"null".equals(code)) {
                o.ok = false;
                o.error = result.getString("message") == null ? code : code + "：" + result.getString("message");
                log.error("AI 试衣提交业务错误: {}", o.error);
                return o;
            }
            JSONObject output = result.getJSONObject("output");
            if (output == null) {
                o.ok = false;
                o.error = "响应缺少 output";
                return o;
            }
            String taskId = output.getString("task_id");
            if (taskId == null || taskId.isBlank()) {
                o.ok = false;
                o.error = "响应缺少 task_id";
                return o;
            }
            o.ok = true;
            o.taskId = taskId;
            log.info("📋 AI 试衣任务已提交 | model={} | task_id={}", model, taskId);
            return o;
        } catch (Exception e) {
            log.error("❌ 提交 AI 试衣任务失败: {}", e.getMessage());
            o.ok = false;
            o.error = e.getMessage();
            return o;
        }
    }

    /** 轮询任务并下载结果图（失败携带具体原因） */
    private TryOnResult pollAndDownload(String taskId) {
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth(apiKey);
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < POLL_TIMEOUT_MS) {
            try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TryOnResult.fail("轮询被中断");
            }
            try {
                HttpEntity<String> getEntity = new HttpEntity<>(getHeaders);
                ResponseEntity<String> pollResp = restTemplate.exchange(
                        taskUrl(taskId), HttpMethod.GET, getEntity, String.class);
                if (pollResp.getStatusCodeValue() != 200) continue;

                JSONObject pollResult = JSON.parseObject(pollResp.getBody());
                JSONObject output = pollResult.getJSONObject("output");
                if (output == null) continue;

                String status = output.getString("task_status");
                if ("SUCCEEDED".equals(status)) {
                    // 结果字段：优先 output.image_url，回退 output.results[0].url
                    String url = output.getString("image_url");
                    if ((url == null || url.isBlank()) && output.getJSONArray("results") != null) {
                        JSONArray results = output.getJSONArray("results");
                        if (!results.isEmpty()) url = results.getJSONObject(0).getString("url");
                    }
                    if (url == null || url.isBlank()) {
                        return TryOnResult.fail("任务成功但缺少结果图地址");
                    }
                    byte[] img = download(url);
                    if (img == null) {
                        return TryOnResult.fail("结果图下载失败");
                    }
                    log.info("✅ AI 试衣成功 | {}KB | 耗时={}s", img.length / 1024, (System.currentTimeMillis() - start) / 1000);
                    TryOnResult ok = new TryOnResult();
                    ok.success = true;
                    ok.image = img;
                    return ok;
                } else if ("FAILED".equals(status)) {
                    String msg = output.getString("message");
                    log.error("AI 试衣任务失败: {}", msg);
                    return TryOnResult.fail(msg == null ? "任务失败" : msg);
                }
            } catch (Exception e) {
                log.warn("AI 试衣轮询异常: {}", e.getMessage());
            }
        }
        return TryOnResult.fail("轮询超时（>120s）");
    }

    /** 下载结果图 */
    private byte[] download(String url) {
        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, null, byte[].class);
            return resp.getBody();
        } catch (Exception e) {
            log.warn("试衣结果图下载失败: {}", e.getMessage());
            return null;
        }
    }

    /** 字节 → data URI（自动识别 mime） */
    private static String toDataUri(byte[] bytes) {
        return "data:" + detectMime(bytes) + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String detectMime(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return "image/jpeg";
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (bytes[0] == 0x89 && bytes[1] == 0x50) return "image/png";
        if (bytes[0] == 0x47 && bytes[1] == 0x49) return "image/gif";
        return "image/jpeg";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 入参图片兜底压缩：已是 JPEG 且长边 ≤1280 直接透传（前端已压缩，避免二次压损质）；
     * 否则等比缩放到长边 ≤1280 并转 JPEG(quality 0.7)，显著减小 base64 请求体/token 占用。
     * 任何异常都透传原图，绝不让压缩失败导致请求失败。
     */
    private static byte[] compress(byte[] src) {
        if (src == null || src.length == 0) return src;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
            if (img == null) return src;
            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 0 || h <= 0) return src;
            if (isJpeg(src) && Math.max(w, h) <= MAX_IMAGE_EDGE) {
                return src; // 已合规，直接透传
            }
            double scale = Math.min(1.0, (double) MAX_IMAGE_EDGE / Math.max(w, h));
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));
            BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            byte[] encoded = encodeJpeg(out, JPEG_QUALITY);
            if (encoded != null && encoded.length > 0) {
                log.info("🖼 AI 试衣入参已压缩 | {}x{} → {}x{} | {}KB → {}KB",
                        w, h, nw, nh, src.length / 1024, encoded.length / 1024);
                return encoded;
            }
            return src;
        } catch (Exception e) {
            log.warn("AI 试衣入参压缩失败，透传原图: {}", e.getMessage());
            return src;
        }
    }

    /** JPEG 编码（指定质量 0-1），返回失败时 null */
    private static byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpg");
        if (!it.hasNext()) return null;
        ImageWriter writer = it.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality)));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
            }
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static boolean isJpeg(byte[] b) {
        return b != null && b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }
}
