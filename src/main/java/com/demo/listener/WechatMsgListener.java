package com.demo.listener;

import com.demo.agent.*;
import com.demo.bot.BotContext;
import com.demo.mcp.antvchart.ChartImageOutputHolder;
import com.demo.service.CmdService;
import com.demo.service.interrupt.InterruptDetector;
import com.demo.service.interrupt.InterruptManager;
import com.demo.fileconvert.FileConvertOutputHolder;
import com.demo.service.tools.holder.UserImageHolder;
import com.demo.service.preprocessing.MessagePreprocessor;
import com.demo.service.TtsService;
import com.demo.service.tools.VoiceModeService;
import com.demo.service.tools.VoiceTypeService;
import com.demo.util.TraceContext;
import com.demo.util.VoiceContext;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 消息处理怎么拿到正确的client发送回复
 * 微信消息全局监听器
 * 职责：统一接收微信推送消息，分发处理并完成消息回复
 * 支持消息全链路追踪（TraceContext）
 */
@Slf4j
@Component
public class WechatMsgListener {

    //注入所有的业务工具
    @Resource
    private CmdService cmdService;

    @Resource
    private MessagePreprocessor preprocessor;

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

    @Resource
    private UserImageHolder userImageHolder;

    @Resource
    private VoiceModeService voiceModeService;

    @Resource
    private VoiceTypeService voiceTypeService;

    @Resource
    private TtsService ttsService;

    @Resource
    private BrowserFormTool browserFormTool;

    @Resource
    private BrowserRegisterTool browserRegisterTool;

    @Resource
    private InterruptManager interruptManager;

    @Resource
    private InterruptDetector interruptDetector;

    //消息去重
    private final ConcurrentHashMap<Long, Long> dedupCache = new ConcurrentHashMap<>();

    //所有bot收到消息就会触发这个方法
    public void onMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            Long msgId = msg.getMessage_id();
            if (msgId != null) {
                if (dedupCache.containsKey(msgId)) {
                    System.out.println("⏭️ 跳过重复消息 msgId=" + msgId);
                    continue;
                }
                dedupCache.put(msgId, System.currentTimeMillis());
                if (dedupCache.size() > 200) {
                    long cutoff = System.currentTimeMillis() - 60_000;
                    dedupCache.values().removeIf(t -> t < cutoff);
                }
            }

            //提取基础信息
            String userId = msg.getFrom_user_id();
            // 在 poll 线程设置用户 ID，供 MessagePreprocessor 缓存文件/图片时使用
            VoiceContext.setUserId(userId);
            List<MessageItem> items = msg.getItem_list();
            if (items == null || items.isEmpty()) continue;

            // ========== 开始全链路追踪 ==========
            String traceId = TraceContext.begin(userId);

            //拆分消息里的文本、图片、语音、文件
            try {
                String userText = null;
                MessageItem imageItem = null;
                MessageItem voiceItem = null;
                MessageItem fileItem = null;

                for (MessageItem item : items) {
                    if (item.getText_item() != null) {
                        userText = item.getText_item().getText();
                    }
                    if (item.getImage_item() != null) {
                        imageItem = item;
                    }
                    if (item.getVoice_item() != null) {
                        voiceItem = item;
                    }
                    if (item.getFile_item() != null) {
                        fileItem = item;
                    }
                }

                // 过滤机器人自身发出的消息（被 SDK echo 回来的）
                if (userText != null && (userText.startsWith("⏰") || userText.startsWith("✅")
                        || userText.startsWith("🌅") || userText.startsWith("📅")
                        || userText.startsWith("🚫"))) {
                    System.out.println("⏭️ 跳过机器人自动消息（echo）：" + userText);
                    TraceContext.span("跳过echo", userText);
                    TraceContext.end();
                    continue;
                }

                // ★ 【三层智能打断】检测到意图 → 取消旧任务，新消息继续处理
                boolean isInterrupt = interruptDetector.detect(userText, userId);
                if (isInterrupt) {
                    System.out.println("🚨 [" + userId + "] 用户打断: " + userText);
                    TraceContext.span("用户打断", userText);
                    interruptManager.signal(userId);  // 取消当前运行的任务
                    // ⛔ 不回复 "⛔"、不 continue —— 新消息正常进入处理流程
                }

                // 记录消息类型
                String msgType = imageItem != null ? "图片"
                        : voiceItem != null ? "语音"
                        : fileItem != null ? "文件"
                        : "文本";
                TraceContext.span("消息类型", msgType + (userText != null ? " + " + userText : ""));

                // 预处理（媒体→文字）— 在 poll 线程同步执行（快速）
                String agentInput = preprocessor.process(userText, imageItem, voiceItem, fileItem);

                if (agentInput != null) {
                    System.out.println("收到微信消息 [" + userId + "]: " + agentInput);

                    // 清空上次工具缓存（在 poll 线程执行）
                    imageGenTool.clear();
                    imageEditTool.clear();
                    screenshotTool.clear();
                    chartImageOutputHolder.clear();
                    fileConvertOutputHolder.clear();

                    // ★ 捕获 BotContext（poll 线程），供异步任务使用
                    ILinkClient client = getClient();
                    String botName = BotContext.getBotName();

                    // ★ 异步提交 Agent 处理（不阻塞 poll 线程轮询新消息）
                    interruptManager.submit(userId, () -> {
                        // 在 executor 线程重新设置上下文
                        BotContext.set(client, botName);
                        VoiceContext.setUserId(userId);
                        // ★ 消费残留的中断信号（避免新任务被旧任务的打断信号误杀）
                        interruptManager.checkAndConsume(userId);
                        try {
                            // 执行 Agent
                            TraceContext.begin(userId);
                            TraceContext.span("进入 Agent");
                            String reply = cmdService.dealMsg(agentInput, userId);
                            TraceContext.span("Agent 回复", truncate(reply, 80));

                            // 发图链路追踪
                            byte[] imageBytes = imageGenTool.getLastGeneratedImage();
                            if (imageBytes != null) {
                                TraceContext.span("发送图片", "文生图 " + imageBytes.length + "B");
                                try {
                                    client.sendImage(userId, imageBytes, "生成的图片", "");
                                } catch (Exception e) {
                                    System.err.println("发送生成图片失败: " + e.getMessage());
                                }
                            }


                            byte[] editedImageBytes = imageEditTool.getLastEditedImage();
                            if (editedImageBytes != null) {
                                TraceContext.span("发送编辑图", editedImageBytes.length + "B");
                                try {
                                    client.sendImage(userId, editedImageBytes, "编辑后的图片", "");
                                } catch (Exception e) {
                                    System.err.println("发送编辑图片失败: " + e.getMessage());
                                }
                            }

                            // 发送网页长截图
                            byte[] screenshotBytes = screenshotTool.getLastScreenshot();
                            if (screenshotBytes != null) {
                                TraceContext.span("发送截图", screenshotBytes.length + "B");
                                try {
                                    client.sendImage(userId, screenshotBytes, "网页截图", "");
                                } catch (Exception e) {
                                    System.err.println("发送截图失败: " + e.getMessage());
                                }
                            }

                            // 填表截图发送
                            byte[] formScreenshotBytes = browserFormTool.getLastFormScreenshot();
                            if (formScreenshotBytes != null) {
                                TraceContext.span("发送填表截图", formScreenshotBytes.length + "B");
                                try {
                                    client.sendImage(userId, formScreenshotBytes, "表单填写截图", "");
                                } catch (Exception e) {
                                    System.err.println("发送填表截图失败: " + e.getMessage());
                                } finally {
                                    browserFormTool.clearScreenshot();
                                }
                            }

                            // 注册截图发送
                            byte[] registerScreenshotBytes = browserRegisterTool.getLastRegisterScreenshot();
                            if (registerScreenshotBytes != null) {
                                TraceContext.span("发送注册截图", registerScreenshotBytes.length + "B");
                                try {
                                    client.sendImage(userId, registerScreenshotBytes, "注册结果截图", "");
                                } catch (Exception e) {
                                    System.err.println("发送注册截图失败: " + e.getMessage());
                                } finally {
                                    browserRegisterTool.clearScreenshot();
                                }
                            }

                            // 图表图片发送（antv-chart MCP 返回的 base64 图）
                            byte[] chartImageBytes = chartImageOutputHolder.getBytes();
                            if (chartImageBytes != null) {
                                TraceContext.span("发送图表", chartImageBytes.length + "B");
                                try {
                                    client.sendImage(userId, chartImageBytes, "图表", "");
                                } catch (Exception e) {
                                    System.err.println("发送图表失败: " + e.getMessage());
                                }
                            }

                            // 文件转换产物发送
                            byte[] convertedBytes = fileConvertOutputHolder.getBytes();
                            if (convertedBytes != null) {
                                String convertedName = fileConvertOutputHolder.getFileName();
                                TraceContext.span("发送转换文件", convertedName + " " + convertedBytes.length + "B");
                                try {
                                    client.sendFile(userId, convertedBytes, convertedName, "");
                                } catch (Exception e) {
                                    System.err.println("发送转换文件失败: " + e.getMessage());
                                }
                            }

                            // 语音模式判断 + 发送
                            boolean sendVoice = voiceModeService.shouldSendVoice(userId);
                            TraceContext.span("语音判断", sendVoice ? "发语音" : "发文字");

                            if (sendVoice) {
                                String voiceType = voiceTypeService.getVoiceType(userId);
                                TraceContext.span("TTS 合成", "音色=" + voiceType + " 字数=" + reply.length());
                                byte[] mp3Data = ttsService.synthesize(reply, voiceType);
                                if (mp3Data != null) {
                                    TraceContext.span("发送语音", mp3Data.length + "B");
                                    try {
                                        client.sendFile(userId, mp3Data, "回复.mp3", "");
                                    } catch (Exception e) {
                                        sendTextSafe(userId, reply);
                                        TraceContext.span("语音发送失败，回退文字");
                                    }
                                } else {
                                    sendTextSafe(userId, reply);
                                }
                            } else {
                                sendTextSafe(userId, reply);
                            }

                            TraceContext.end();
                        } catch (Exception e) {
                            log.error("[{}] 异步处理消息异常", userId, e);
                            TraceContext.span("异常", e.getClass().getSimpleName() + ": " + e.getMessage());
                            TraceContext.end("error");
                        } finally {
                            VoiceContext.clear();
                            BotContext.clear();
                        }
                    });
                } else {
                    TraceContext.span("空消息", "预处理返回 null，跳过");
                }

                TraceContext.end();
            } catch (Exception e) {
                log.error("[{}] 处理消息异常", userId, e);
                TraceContext.span("异常", e.getClass().getSimpleName() + ": " + e.getMessage());
                TraceContext.end("error");
            }
        }
    }

    private void sendTextSafe(String userId, String text) {
        TraceContext.span("发送文字", truncate(text, 40));
        try {
            getClient().sendText(userId, text);
        } catch (Exception e) {
            System.err.println("回复消息失败: " + e.getMessage());
        }
    }

    /** 获取当前线程绑定的 ILinkClient */
    //处理消息是多线程并发的，用THreadLLocal存了当前这条消息对应的机器人连接对象
    private ILinkClient getClient() {//拿到对应的账号
        return BotContext.getClient();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
