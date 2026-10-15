package com.demo.service.preprocessing;

import com.demo.bot.BotContext;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.demo.service.tools.holder.UserFileHolder;
import com.demo.service.tools.holder.UserImageHolder;
import com.demo.util.TraceContext;
import com.demo.util.VoiceContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 消息统一预处理器
 * 功能：将微信接收的图片、语音、文本消息统一转换成纯文本内容
 * 处理优先级：图片消息 > 语音消息 > 普通文本消息
 * 图片 → 下载 → Qwen-VL 识别 → 文字描述
 * 语音 → 下载 → qwen-audio 转写 → 文字
 * 文本 → 原样返回
 * 只负责多媒体转文字，不参与后续对话逻辑处理
 */
@Slf4j
@Service
public class MessagePreprocessor {

    @Resource
    private VisionService visionService;

    @Resource
    private AudioService audioService;

    @Resource
    private FileService fileService;

    @Resource
    private UserImageHolder userImageHolder;

    @Resource
    private UserFileHolder userFileHolder;

    /**
     * 统一消息预处理入口方法
     * @param userText 用户发送的纯文本内容
     * @param imageItem 图片消息实体对象
     * @param voiceItem 语音消息实体对象
     * @param fileItem 文件消息实体对象（Word/PDF/PPT/ZIP等）
     * @return 拼接整合后的纯文本内容，无有效内容返回null
     */
    public String process(String userText, MessageItem imageItem, MessageItem voiceItem, MessageItem fileItem) {
        // 优先级判定：图片 > 语音 > 文件 > 纯文本

        if (imageItem != null) {
            return processImage(userText, imageItem);
        }
        if (voiceItem != null) {
            return processVoice(userText, voiceItem);
        }
        if (fileItem != null) {
            return processFile(userText, fileItem);
        }
        if (userText != null && !userText.isBlank()) {
            return userText;
        }
        return null;
    }

    /**
     * 处理图片消息
     * 流程：下载图片字节 -> 调用视觉模型识别内容 -> 拼接用户附带文字
     * @param userText 用户附带文字
     * @param imageItem 图片消息对象
     * @return 整合后的图片描述文本
     */
    private String processImage(String userText, MessageItem imageItem) {
        log.info("🖼️ 收到图片消息，开始下载...");
        TraceContext.span("预处理:下载图片");
        try {
            //拿到当前这条消息来自哪个微信机器人的专属连接对象，调用对应机器人的微信接口下载图片
            byte[] imageBytes = BotContext.getClient().downloadImageFromMessageItem(imageItem);
            if (imageBytes != null && imageBytes.length > 0) {
                log.info("📥 图片下载完成：{}KB", imageBytes.length / 1024);
                userImageHolder.set(imageBytes);
                TraceContext.span("预处理:视觉识别", imageBytes.length / 1024 + "KB");
                String desc = visionService.describe(imageBytes);
                if (desc != null) {
                    String result = "【用户发送了一张图片】图片描述：" + desc;
                    if (userText != null && !userText.isBlank()) {
                        result += "，" + userText;
                    }
                    TraceContext.span("预处理:识别完成", truncate(desc, 60));
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("图片处理失败", e);
        }
        return userText != null && !userText.isBlank()
                ? userText
                : "（你发送了一张图片，暂时无法识别，请重试或附文字）";
    }

    /**
     * 处理语音消息
     * 流程：获取语音参数 -> 下载语音文件 -> 调用语音接口转文字 -> 拼接附带文本
     * @param userText 用户附带文字
     * @param voiceItem 语音消息对象
     * @return 整合后的语音转写文本
     */
    private String processVoice(String userText, MessageItem voiceItem) {
        VoiceItem voice = voiceItem.getVoice_item();
        int encodeType = voice != null && voice.getEncode_type() != null ? voice.getEncode_type() : 0;
        // 微信返回是毫秒，转成秒并四舍五入
        int msTime = voice != null && voice.getPlaytime() != null ? voice.getPlaytime() : 0;
        int playtime = (int) Math.round(msTime / 1000.0);

        log.info("🎤 收到语音消息（{}s, encode={}），开始下载...", playtime, encodeType);
        TraceContext.span("预处理:下载语音", playtime + "s");
        try {
            //同上面的图片
            byte[] audioBytes = BotContext.getClient().downloadVoiceFromMessageItem(voiceItem);
            if (audioBytes != null && audioBytes.length > 0) {
                log.info("📥 语音下载完成：{}KB", audioBytes.length / 1024);
                TraceContext.span("预处理:语音转写", audioBytes.length / 1024 + "KB");
                String transcript = audioService.transcribe(audioBytes, encodeType, playtime);
                if (transcript != null) {
                    String result = "【用户发送了一段语音】语音转写：" + transcript;
                    if (userText != null && !userText.isBlank()) {
                        result += "，" + userText;
                    }
                    TraceContext.span("预处理:转写完成", truncate(transcript, 60));
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("语音处理失败", e);
        }
        return userText != null && !userText.isBlank()
                ? userText
                : "（你发送了一段语音，暂时无法识别，请重试或发文字）";
    }

    /**
     * 处理文件消息（Word/PDF/PPT/ZIP等）
     * 流程：下载文件字节 -> 魔数检测文件类型 -> 返回格式信息
     * @param userText 用户附带文字
     * @param fileItem 文件消息对象
     * @return 整合后的文件格式描述文本
     */
    private String processFile(String userText, MessageItem fileItem) {
        // 获取文件名（微信可能不返回）
        String fileName = null;
        String fileSize = null;
        try {
            // 从文件消息实体中解析文件名与文件长度
            var fi = fileItem.getFile_item();
            if (fi != null) {
                fileName = fi.getFile_name();
                fileSize = fi.getLen();
            }
        } catch (Exception e) {
            // ignore
        }

        log.info("📄 收到文件消息，开始下载... 文件名={}", fileName != null ? fileName : "未知");
        TraceContext.span("预处理:下载文件", fileName != null ? fileName : "未知");
        try {
            //同上面的图片
            byte[] fileBytes = BotContext.getClient().downloadFileFromMessageItem(fileItem);

            if (fileBytes != null && fileBytes.length > 0) {
                log.info("📥 文件下载完成：{}KB", fileBytes.length / 1024);
                TraceContext.span("预处理:分析格式", fileBytes.length / 1024 + "KB");
                String formatDesc = fileService.analyze(fileBytes);
                log.info("📄 文件格式识别结果：{}", formatDesc);

                if (formatDesc != null) {
                    TraceContext.span("预处理:提取内容", formatDesc);
                    String result = "【用户发送了一个文件】格式：" + formatDesc;
                    // 如果有文件名就加上
                    if (fileName != null && !fileName.isBlank()) {
                        result += "，文件名：" + fileName;
                    }
                    if (fileSize != null && !fileSize.isBlank()) {
                        result += "，大小：" + formatFileSize(Long.parseLong(fileSize));
                    }
                    if (userText != null && !userText.isBlank()) {
                        result += "，附言：" + userText;
                    }
                    // 尝试提取文件内容（支持 txt/docx/xlsx/pptx/pdf/doc/xls/ppt）
                    String content = fileService.extractContent(fileBytes);
                    if (content != null && !content.isBlank()) {
                        result += "\n【文件内容】" + content;
                    }

                    // 缓存文件到 UserFileHolder（供 saveToMyKnowledge 工具使用）
                    String userId = VoiceContext.getUserId();
                    if (userId != null && fileBytes != null) {
                        userFileHolder.put(userId, fileBytes, fileName != null ? fileName : "unknown.bin");
                    }

                    return result;
                }
            }
        } catch (Exception e) {
            log.error("文件处理失败", e);
        }
        return userText != null && !userText.isBlank()
                ? userText
                : "（你发送了一个文件，暂时无法识别，请重试或发文字）";
    }

    /**
     * 文件大小格式化（字节→可读格式）
     */
    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

}
