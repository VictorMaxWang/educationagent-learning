package com.demo.agent;

import com.demo.service.tools.ImageGenService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * LangChain4j Agent 工具 —— 文生图
 * Agent 根据用户意图自主决定是否调用此工具。
 * wan2.5-t2i-preview 返回图片数据后压缩为 JPEG（缩小尺寸和质量），
 * 再由 WechatMsgListener 调用 {@code wechatClient.sendImage()} 发送给微信用户。
 * 压缩原因：原始 PNG 通常 >1MB，微信 iLink SDK 的 CDN 上传有大小限制，
 * 压缩至 ~800px JPEG quality=0.85 后通常 <200KB。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageGenTool {

    // 微信 CDN 上传最大建议尺寸（像素，长边）
    private static final int MAX_DIMENSION = 800;

    // JPEG 压缩质量 0~1
    private static final float JPEG_QUALITY = 0.85f;

    // 超过此大小（字节）才触发压缩
    private static final int COMPRESS_THRESHOLD = 500 * 1024; // 500KB

    private final ImageGenService imageGenService;

   // 最近一次生成的图片字节；WechatMsgListener 读取后发送
    @Getter
    private byte[] lastGeneratedImage;

    // 最近一次生成的图片 MIME 类型
    @Getter
    private String lastGeneratedMime;

    /**
     * AI调用绘图工具
     * @param prompt 绘图描述文案
     * @return 文字提示结果
     */
    @Tool("根据文字描述生成图片。当用户要求画图、生成图片、制作海报/头像/封面时调用此工具。"
            + "支持中文描述，越详细效果越好，将用户的简单需求扩展为详细画面描述。"
            + "例如：'一只橘猫趴在窗台上晒太阳，阳光透过窗户洒进来，温暖写实风格'、"
            + "'水墨山水画，远山近水，云雾缭绕'、'赛博朋克风格的城市夜景'")
    public String generateImage(
            @P("详细的图片描述，支持中文。建议包含主体、环境、风格、动作、颜色等细节，"
                    + "越详细生成效果越好。")
            String prompt) {

        log.info("🎨 Agent 调用文生图工具 | prompt={}", prompt);

        clear();


        byte[] imageBytes = imageGenService.generate(prompt);

        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("图片生成失败");
            return "图片生成失败，请稍后重试或修改描述后再次生成。";
        }

        // 压缩图片（PNG太大 → 缩小+JPEG），保证能过微信 CDN 上传限制
        byte[] compressed = compressImage(imageBytes);
        this.lastGeneratedImage = compressed;
        this.lastGeneratedMime = "image/jpeg";

        log.info("✅ 文生图完成 | 原始 {}KB → 压缩后 {}KB",
                imageBytes.length / 1024, compressed.length / 1024);
        return "图片已生成完成，已发送给你，请查收！";
    }

    /**
     * 图片缩放与压缩
     * @param input 原始图片字节
     * @return 压缩后图片字节
     */
    private byte[] compressImage(byte[] input) {
        // 小于阈值不压缩
        if (input.length < COMPRESS_THRESHOLD) {
            this.lastGeneratedMime = "image/png";
            return input;
        }

        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(input));
            if (original == null) {
                log.warn("无法解码图片，使用原图");
                this.lastGeneratedMime = "image/png";
                return input;
            }

            int width = original.getWidth();
            int height = original.getHeight();
            int newW, newH;

            // 等比例缩放
            if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
                newW = width;
                newH = height;
            } else if (width >= height) {
                newW = MAX_DIMENSION;
                newH = height * MAX_DIMENSION / width;
            } else {
                newH = MAX_DIMENSION;
                newW = width * MAX_DIMENSION / height;
            }

            // 缩放： 重新绘制图片
            BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(original, 0, 0, newW, newH, null);
            g.dispose();

            // 输出压缩 JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(resized, null, null), param);
            } finally {
                writer.dispose();
            }

            byte[] result = baos.toByteArray();
            log.info("🖼️ 图片压缩: {}×{} → {}×{}, {}KB → {}KB",
                    width, height, newW, newH,
                    input.length / 1024, result.length / 1024);
            return result;
        } catch (Exception e) {
            log.warn("图片压缩失败，使用原图: {}", e.getMessage());
            this.lastGeneratedMime = "image/png";
            return input;
        }
    }

    // 清除缓存的图片数据（每次对话前调用）
    public void clear() {
        this.lastGeneratedImage = null;
        this.lastGeneratedMime = null;
    }
}
