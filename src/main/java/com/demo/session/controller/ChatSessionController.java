package com.demo.session.controller;

import com.demo.service.tools.VoiceModeService;
import com.demo.session.dto.MessagePage;
import com.demo.session.entity.ChatSessionEntity;
import com.demo.session.service.ChatSessionService;
import com.demo.session.service.SessionImageStore;
import com.demo.session.stream.SessionChatStreamer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;

/**
 * 历史对话侧边栏 — 会话管理 REST 接口
 * <p>
 * 统一数据模型（与前端 types/chat.ts 严格对齐）：
 * <ul>
 *   <li>ChatSession：sessionId / title / createTime(ms) / updateTime(ms)</li>
 *   <li>ChatMessage：msgId / sessionId / role / content / image / createTime(ms)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatSessionController {

    @Resource
    private ChatSessionService sessionService;

    @Resource
    private SessionChatStreamer streamer;

    @Resource
    private SessionImageStore sessionImageStore;

    @Resource
    private VoiceModeService voiceModeService;

    /** 获取用户全部会话列表（按 updateTime 倒序） */
    @GetMapping("/sessions")
    public List<ChatSessionEntity> sessions(@RequestParam String userId) {
        return sessionService.listSessions(userId);
    }

    /** 创建/确保会话存在（幂等）：前端"新建对话"生成 UUID 后调用，建立空会话 */
    @PostMapping("/session")
    public ChatSessionEntity create(@RequestParam String userId, @RequestBody Map<String, String> body) {
        return sessionService.createSession(body.get("sessionId"), userId, body.get("title"));
    }

    /** 获取会话历史消息（支持分页，page=1 为最近一页，list 升序返回） */
    @GetMapping("/session/{sessionId}/messages")
    public MessagePage messages(@PathVariable String sessionId,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "50") int size) {
        return sessionService.getMessages(sessionId, page, size);
    }

    /** 删除会话（级联删消息 + 清理三层缓存 + 上下文） */
    @DeleteMapping("/session/{sessionId}")
    public Map<String, Object> delete(@PathVariable String sessionId) {
        boolean ok = sessionService.deleteSession(sessionId);
        return Map.of("success", ok);
    }

    /** 获取会话消息图片（data/images 下的落盘图片，供前端刷新后重新加载） */
    @GetMapping("/images/{filename}")
    public ResponseEntity<byte[]> image(@PathVariable String filename) {
        byte[] bytes = sessionImageStore.load(filename);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(sessionImageStore.contentType(filename)))
                .body(bytes);
    }

    /** 读取语音播报开关状态（桌面端听筒按钮） */
    @GetMapping("/voice-mode")
    public Map<String, Object> voiceMode(@RequestParam String userId) {
        return Map.of("enabled", voiceModeService.isGlobalEnabled(userId));
    }

    /** 设置语音播报开关（桌面端听筒按钮）：on → 全局语音模式；off → 关闭 */
    @PostMapping("/voice-mode")
    public Map<String, Object> setVoiceMode(@RequestBody Map<String, Object> body) {
        Object uid = body.get("userId");
        if (uid == null || uid.toString().isBlank()) {
            return Map.of("success", false, "msg", "userId 不能为空");
        }
        String userId = uid.toString();
        boolean enabled = body.get("enabled") != null && Boolean.parseBoolean(body.get("enabled").toString());
        if (enabled) {
            voiceModeService.enableGlobal(userId);
        } else {
            voiceModeService.disable(userId);
        }
        return Map.of("success", true, "enabled", enabled);
    }

    /** 会话重命名 */
    @PostMapping("/session/{sessionId}/rename")
    public Map<String, Object> rename(@PathVariable String sessionId, @RequestBody Map<String, String> body) {
        boolean ok = sessionService.renameSession(sessionId, body.get("title"));
        return Map.of("success", ok);
    }

    /**
     * NDJSON 流式对话（会话模式）。
     * 请求体：sessionId(必填) / userId / content / fileId(可选)。
     * 事件：{delta} 文本增量 / {image} AI 生成图片 / {file} 转换产物 / {done,delta=完整回复}。
     */
    @PostMapping(value = "/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody Map<String, String> payload) {
        StreamingResponseBody streamBody = outputStream -> streamer.stream(payload, outputStream);
        return ResponseEntity.ok(streamBody);
    }
}
