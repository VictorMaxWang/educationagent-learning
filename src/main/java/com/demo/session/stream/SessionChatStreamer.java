package com.demo.session.stream;

import com.demo.agent.Assistant;
import com.demo.mcp.antvchart.ChartImageOutputHolder;
import com.demo.mcp.lazy.McpLazyActivator;
import com.demo.agent.ImageEditTool;
import com.demo.agent.ImageGenTool;
import com.demo.agent.ScreenshotTool;
import com.demo.controller.DesktopFileStore;
import com.demo.fileconvert.FileConvertOutputHolder;
import com.demo.service.TtsService;
import com.demo.service.preprocessing.FileService;
import com.demo.service.preprocessing.VisionService;
import com.demo.service.tools.VoiceModeService;
import com.demo.service.tools.VoiceTypeService;
import com.demo.service.tools.holder.UserFileHolder;
import com.demo.service.tools.holder.UserImageHolder;
import com.demo.session.service.ChatSessionService;
import com.demo.session.service.SessionImageStore;
import com.demo.util.VoiceContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 桌面端 NDJSON 流式对话执行体（共享）
 * <p>
 * 从 {@link com.demo.controller.DesktopController} 抽出，供两个入口复用：
 * <ul>
 *   <li>{@code POST /api/desktop/chat/stream}（旧入口，向后兼容，sessionId 缺省）</li>
 *   <li>{@code POST /chat/stream}（会话入口，sessionId 必填）</li>
 * </ul>
 * 会话模式下：memoryId = {@code session_ + sessionId} 传入 {@link ChatSessionService} 隔离上下文；
 * 流式结束后自动落库（消息 + 标题 + 三层缓存 + chat:memory 上下文镜像）。
 */
@Slf4j
@Component
public class SessionChatStreamer {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 会话上下文在 ChatContextManager 中的 key 前缀（与 ChatSessionService 约定一致） */
    private static final String SESSION_MEMORY_PREFIX = "session_";

    @Resource
    private Assistant assistant;

    @Resource
    private McpLazyActivator mcpLazyActivator;

    @Resource
    private VisionService visionService;

    @Resource
    private UserImageHolder userImageHolder;

    @Resource
    private UserFileHolder userFileHolder;

    @Resource
    private FileService fileService;

    @Resource
    private DesktopFileStore fileStore;

    @Resource
    private ChatSessionService sessionService;

    @Resource
    private SessionImageStore sessionImageStore;

    @Resource
    private TtsService ttsService;

    @Resource
    private VoiceModeService voiceModeService;

    @Resource
    private VoiceTypeService voiceTypeService;

    // 工具图片缓存（与 WechatMsgListener 一致的清单）
    @Resource
    private ImageGenTool imageGenTool;

    @Resource
    private ImageEditTool imageEditTool;

    @Resource
    private ScreenshotTool screenshotTool;

    @Resource
    private ChartImageOutputHolder chartImageOutputHolder;

    @Resource
    private FileConvertOutputHolder fileConvertOutputHolder;

    /**
     * 流式响应实体。
     * delta=文本增量；done=true 时 delta=权威完整回复；image=base64 data URL（AI 生成的图片）；
     * file=转换产物（转成pdf等），由前端触发下载；audio=语音气泡（TTS 合成的 MP3）；
     * type=stream_thinking/stream_content 时为实时两段流：thinking→content 严格先后。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StreamItem {
        public String delta;
        public boolean done;
        public String image;
        public FileItem file;
        public AudioItem audio;
        /** 消息类型：stream_thinking=思考增量 / stream_content=正文增量（与 content 配套） */
        public String type;
        /** 对应 type 的增量文本 */
        public String content;

        /** 思考增量事件：type=stream_thinking */
        public static StreamItem ofThinking(String thinking) {
            StreamItem item = new StreamItem((String) null, false);
            item.type = "stream_thinking";
            item.content = thinking;
            return item;
        }

        /** 正文增量事件：type=stream_content */
        public static StreamItem ofContent(String text) {
            StreamItem item = new StreamItem((String) null, false);
            item.type = "stream_content";
            item.content = text;
            return item;
        }

        /** 转换产物：name=文件名，base64=文件字节 */
        public static class FileItem {
            public String name;
            public String base64;
        }

        /** 语音气泡：TTS 合成的 MP3（base64），前端渲染可点击播放 */
        public static class AudioItem {
            public String name;
            public String mime;
            public String base64;
        }

        public StreamItem(String delta, boolean done) {
            this.delta = delta;
            this.done = done;
        }

        public StreamItem(String image) {
            this.image = image;
        }

        public StreamItem(FileItem file) {
            this.file = file;
        }

        public StreamItem(AudioItem audio) {
            this.audio = audio;
        }
    }

    /**
     * 执行一轮 NDJSON 流式对话。
     *
     * @param payload 请求体：content(用户文本) / userId / fileId(可选) / sessionId(可选，会话模式)
     * @param outputStream NDJSON 输出流
     * @throws IOException 写出失败（客户端断开等），由 StreamingResponseBody 回调层兜底
     */
    public void stream(Map<String, String> payload, OutputStream outputStream) throws IOException {
        String userContent = payload.get("content");
        String rawUserId = payload.get("userId");
        String fileId = payload.get("fileId");
        String sessionId = payload.get("sessionId");

        String userId = (rawUserId == null || rawUserId.isBlank()) ? "desktop_user" : rawUserId;
        // 会话模式：每会话独立上下文（memoryId = session_{sessionId}）；非会话模式沿用桌面用户上下文
        boolean sessionMode = sessionId != null && !sessionId.isBlank();
        String memoryId = sessionMode ? SESSION_MEMORY_PREFIX + sessionId : "desktop_" + userId;

        try {
            // 设置用户上下文，供 @Tool 内部读取（语音模式/音色等）
            VoiceContext.setUserId(userId);

            // 会话模式前置：确保会话行存在（幂等）+ 上下文就绪（Redis chat:memory 回填）
            if (sessionMode) {
                sessionService.createSession(sessionId, userId, null);
                sessionService.ensureContext(sessionId);
            }

            // ★ 处理用户上传的附件：图片走视觉识别，其他文件走 FileService 分析提取
            String agentInput = userContent;
            String saveUserText = userContent; // 落库展示文本（附件场景兜底）
            DesktopFileStore.FileEntry entry = fileStore.take(fileId);
            if (entry != null && entry.bytes() != null && entry.bytes().length > 0) {
                boolean isImage = (entry.mime() != null && entry.mime().startsWith("image/"))
                        || detectMime(entry.bytes()) != null;
                if (isImage) {
                    log.info("🖼️ [{}] 处理上传图片：{}KB", memoryId, entry.bytes().length / 1024);
                    userImageHolder.set(entry.bytes());
                    String desc = visionService.describe(entry.bytes());
                    agentInput = buildImageAgentInput(userContent, desc);
                    if (userContent == null || userContent.isBlank()) {
                        saveUserText = "[图片]";
                    }
                } else {
                    log.info("📄 [{}] 处理上传文件：{} ({}KB)",
                            memoryId, entry.fileName(), entry.bytes().length / 1024);
                    userFileHolder.put(userId, entry.bytes(), entry.fileName());
                    String formatDesc = fileService.analyze(entry.bytes());
                    String content = fileService.extractContent(entry.bytes());
                    agentInput = buildFileAgentInput(userContent, entry.fileName(), formatDesc, content);
                    if (userContent == null || userContent.isBlank()) {
                        saveUserText = "[文件] " + entry.fileName();
                    }
                }
            }

            // 清空上次工具图片缓存 + 转换产物（避免残留误发，与微信链路一致）
            imageGenTool.clear();
            imageEditTool.clear();
            screenshotTool.clear();
            chartImageOutputHolder.clear();
            fileConvertOutputHolder.clear();

            log.info("🖥️ [{}] 接收消息：{}", memoryId, truncate(agentInput, 80));

            // ★ MCP 懒加载：与微信链路一致，进入 LLM 前先做关键词预加载（网页端也触发）
            mcpLazyActivator.matchAndActivate(agentInput);

            // ★ 真流式：思考增量(stream_thinking) → 正文增量(stream_content)，实时先后推送
            // 记录每次思考/正文推送的计数与时刻，用于排查"多轮 FSM 是否每轮都推送"
            final long streamStart = System.currentTimeMillis();
            final long[] thinkingPushCount = {0};
            final long[] contentPushCount = {0};
            String reply = assistant.chatStream(memoryId, agentInput,
                    token -> {
                        contentPushCount[0]++;
                        if (contentPushCount[0] <= 3) {
                            log.info("🔵 [{}] 推送正文片段 #{} | 距流开始 {}ms", memoryId, contentPushCount[0], System.currentTimeMillis() - streamStart);
                        }
                        writeItemQuietly(outputStream, StreamItem.ofContent(token));
                    },
                    think -> {
                        thinkingPushCount[0]++;
                        if (thinkingPushCount[0] <= 5) {
                            log.info("🟢 [{}] 推送思考片段 #{} | 距流开始 {}ms", memoryId, thinkingPushCount[0], System.currentTimeMillis() - streamStart);
                        }
                        writeItemQuietly(outputStream, StreamItem.ofThinking(think));
                    });

            // ★ 发送 AI 生成的图片（文生图/图生图/截图/图表）+ 转换产物（转成pdf等）
            // 图片同时落盘 data/images/，返回最后一张的持久化 URL 供会话落库（刷新后可加载）
            String assistantImage = emitGeneratedOutputs(outputStream);

            // 结束包：携带权威完整回复，前端以此为准覆盖
            writeItem(outputStream, new StreamItem(reply, true));

            // 语音播报（语音模式开启时）：TTS 合成回复文本，推送给前端渲染成可点击播放的语音气泡
            emitVoice(outputStream, reply, userId);

            // 会话模式：完整保存本轮（用户消息 + AI 回复 + AI 生成图片）到会话
            if (sessionMode) {
                sessionService.saveTurn(sessionId, userId, saveUserText, reply, assistantImage);
            }
        } catch (Exception e) {
            log.error("流式对话异常", e);
            writeItem(outputStream, new StreamItem("😢 请求失败：" + e.getMessage(), true));
        } finally {
            VoiceContext.clear();
        }
    }

    /** 组装带文件分析结果的 Agent 输入（与 MessagePreprocessor.processFile 一致） */
    private String buildFileAgentInput(String userText, String fileName, String formatDesc, String content) {
        String result = "【用户发送了一个文件】格式：" + (formatDesc != null ? formatDesc : "未知");
        if (fileName != null && !fileName.isBlank()) {
            result += "，文件名：" + fileName;
        }
        if (content != null && !content.isBlank()) {
            result += "\n【文件内容】" + content;
        }
        if (userText != null && !userText.isBlank()) {
            result += "，附言：" + userText;
        }
        return result;
    }

    /** 组装带图片描述的 Agent 输入（与 MessagePreprocessor.processImage 一致） */
    private String buildImageAgentInput(String userText, String desc) {
        if (desc != null && !desc.isBlank()) {
            String prefix = "【用户发送了一张图片】图片描述：" + desc;
            return (userText != null && !userText.isBlank()) ? prefix + "，" + userText : prefix;
        }
        // 识别失败：退化为纯文字或提示
        return (userText != null && !userText.isBlank())
                ? userText
                : "（你发送了一张图片，暂时无法识别，请重试或附文字）";
    }

    /**
     * 逐个检查工具图片缓存，把生成的图片作为 {image} 事件发出并落盘；
     * 转换产物作为 {file} 事件发出。
     *
     * @return 最后一张生成图片的持久化 URL（供会话落库），无图片返回 null
     */
    private String emitGeneratedOutputs(OutputStream outputStream) throws IOException {
        String lastImageUrl = null;
        byte[] gen = imageGenTool.getLastGeneratedImage();
        if (gen != null && gen.length > 0) {
            lastImageUrl = emitImage(outputStream, gen, imageGenTool.getLastGeneratedMime());
        }
        byte[] edit = imageEditTool.getLastEditedImage();
        if (edit != null && edit.length > 0) {
            lastImageUrl = emitImage(outputStream, edit, detectMime(edit));
        }
        byte[] shot = screenshotTool.getLastScreenshot();
        if (shot != null && shot.length > 0) {
            lastImageUrl = emitImage(outputStream, shot, screenshotTool.getLastScreenshotMime());
        }
        byte[] chart = chartImageOutputHolder.getBytes();
        if (chart != null && chart.length > 0) {
            lastImageUrl = emitImage(outputStream, chart, detectMime(chart));
        }
        // 文件产物（"转成pdf"等转换 / 生成文件）：已落盘 D 盘的生成文件跳过下载事件，
        // 桌面端不再弹保存对话框（回复文本已含保存路径）；未落盘的转换产物以 {file} 事件触发下载。
        byte[] converted = fileConvertOutputHolder.getBytes();
        if (converted != null && converted.length > 0) {
            if (fileConvertOutputHolder.isSaved()) {
                log.info("📤 [stream] 生成文件已落盘 D 盘（{}），跳过桌面下载事件", fileConvertOutputHolder.getFileName());
            } else {
                StreamItem.FileItem fileItem = new StreamItem.FileItem();
                String name = fileConvertOutputHolder.getFileName();
                fileItem.name = (name != null && !name.isBlank()) ? name : "converted.bin";
                fileItem.base64 = Base64.getEncoder().encodeToString(converted);
                writeItem(outputStream, new StreamItem(fileItem));
                log.info("📤 [stream] 流出转换产物: {} ({}KB)", fileItem.name, converted.length / 1024);
            }
        }
        return lastImageUrl;
    }

    /**
     * 单张图片：实时流 base64（前端即时显示）+ 落盘持久化（刷新后仍可加载）。
     *
     * @return 持久化相对 URL；落盘失败返回 null（不影响实时显示）
     */
    private String emitImage(OutputStream outputStream, byte[] bytes, String mime) throws IOException {
        writeItem(outputStream, new StreamItem(toDataUrl(bytes, mime)));
        return sessionImageStore.save(bytes, mime);
    }

    /**
     * 语音播报：语音模式开启时，把回复文本用 TTS 合成 MP3，以 {audio} 事件推送给前端渲染成语音气泡。
     * 失败静默跳过（不影响文本/图片正常返回）。
     */
    private void emitVoice(OutputStream outputStream, String reply, String userId) {
        try {
            if (!voiceModeService.shouldSendVoice(userId)) return;
            String voiceType = voiceTypeService.getVoiceType(userId);
            byte[] mp3 = ttsService.synthesize(reply, voiceType);
            if (mp3 == null || mp3.length == 0) return;
            StreamItem.AudioItem audioItem = new StreamItem.AudioItem();
            audioItem.name = "语音.mp3";
            audioItem.mime = "audio/mpeg";
            audioItem.base64 = Base64.getEncoder().encodeToString(mp3);
            writeItem(outputStream, new StreamItem(audioItem));
            log.info("🔊 [{}] 已推送语音气泡: {}KB", userId, mp3.length / 1024);
        } catch (Exception e) {
            log.warn("语音合成/推送失败（跳过）: {}", e.getMessage());
        }
    }

    /** 图片字节 → data URL（base64） */
    private String toDataUrl(byte[] bytes, String mime) {
        String safeMime = (mime == null || mime.isBlank()) ? "image/jpeg" : mime;
        return "data:" + safeMime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** 魔数嗅探图片 MIME（PNG/JPEG/GIF/BMP），非图片返回 null */
    private String detectMime(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return null;
        int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF, b2 = bytes[2] & 0xFF, b3 = bytes[3] & 0xFF;
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "image/png";
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "image/jpeg";
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return "image/gif";
        if (b0 == 0x42 && b1 == 0x4D) return "image/bmp";
        return null;
    }

    private void writeItem(OutputStream outputStream, StreamItem item) throws IOException {
        String jsonLine = objectMapper.writeValueAsString(item) + "\n";
        outputStream.write(jsonLine.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /** onToken 回调来自 SSE 后台线程，写入失败不冒泡（避免中断 Agent 主流程） */
    private void writeItemQuietly(OutputStream outputStream, StreamItem item) {
        try {
            writeItem(outputStream, item);
        } catch (IOException e) {
            log.warn("流式写出失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
