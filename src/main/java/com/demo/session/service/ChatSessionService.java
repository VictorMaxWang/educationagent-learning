package com.demo.session.service;

import com.demo.session.cache.SessionCacheManager;
import com.demo.session.dto.MessagePage;
import com.demo.session.entity.ChatSessionEntity;
import com.demo.session.entity.SessionMessageEntity;
import com.demo.session.mapper.ChatSessionMapper;
import com.demo.session.mapper.SessionMessageMapper;
import com.demo.util.ChatContextManager;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 会话管理业务服务（历史对话侧边栏）
 * <p>
 * 三级存储读写规则：
 * <ul>
 *   <li>读：L1 内存 → L2 Redis → L3 MySQL，命中向上回填（由 {@link SessionCacheManager} 承担）</li>
 *   <li>写：先落 MySQL 持久层 → 同步刷新 Redis → 更新 L1（写穿）</li>
 *   <li>删：MySQL 级联删消息+会话 → 清 Redis 三 key → 清 L1 + ChatContextManager 上下文</li>
 * </ul>
 * LangChain4j 上下文按会话隔离：memoryId = {@code "session_" + sessionId} 传入
 * {@link ChatContextManager}，每个会话独立上下文，互不串扰。
 */
@Slf4j
@Service
public class ChatSessionService {

    /** 会话上下文在 ChatContextManager 中的 key 前缀 */
    private static final String MEMORY_PREFIX = "session_";
    /** 默认会话标题 */
    private static final String DEFAULT_TITLE = "新对话";
    /** 标题降级策略：截取用户问题前 20 字符 */
    private static final int TITLE_FALLBACK_MAX = 20;

    private final ChatSessionMapper sessionMapper;
    private final SessionMessageMapper messageMapper;
    private final SessionCacheManager cacheManager;
    private final ChatContextManager chatContextManager;
    private final OpenAiChatModel chatModel;

    /** 异步 LLM 标题精炼线程池（首轮标题降级已即时落库，精炼不阻塞响应） */
    private final ExecutorService titleExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-session-title");
        t.setDaemon(true);
        return t;
    });

    public ChatSessionService(ChatSessionMapper sessionMapper,
                              SessionMessageMapper messageMapper,
                              SessionCacheManager cacheManager,
                              ChatContextManager chatContextManager,
                              OpenAiChatModel chatModel) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.cacheManager = cacheManager;
        this.chatContextManager = chatContextManager;
        this.chatModel = chatModel;
    }

    /** 会话上下文 key */
    public String memoryKey(String sessionId) {
        return MEMORY_PREFIX + sessionId;
    }

    // ======================== 会话元数据 ========================

    /**
     * 获取用户全部会话（按 updateTime 倒序）
     */
    public List<ChatSessionEntity> listSessions(String userId) {
        if (userId == null || userId.isBlank()) return Collections.emptyList();
        List<ChatSessionEntity> list = sessionMapper.selectByUserId(userId);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 创建/确保会话存在（幂等）。
     * 前端"新建对话"时生成 UUID 传入，首次调用落库并建立空会话。
     */
    public ChatSessionEntity createSession(String sessionId, String userId, String title) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        ChatSessionEntity existing = cacheManager.getSession(sessionId,
                () -> sessionMapper.selectById(sessionId));
        if (existing != null) {
            return existing;
        }
        long now = System.currentTimeMillis();
        String safeTitle = (title == null || title.isBlank()) ? DEFAULT_TITLE : title.trim();
        ChatSessionEntity entity = new ChatSessionEntity(sessionId, userId, safeTitle, now, now);
        // 写穿：先落 MySQL，再同步 L1+L2
        sessionMapper.insert(entity);
        cacheManager.putSession(entity);
        log.info("📝 新建会话 session={} user={}", sessionId, userId);
        return entity;
    }

    /**
     * 会话重命名（更新标题 + updateTime）
     */
    public boolean renameSession(String sessionId, String title) {
        ChatSessionEntity entity = cacheManager.getSession(sessionId,
                () -> sessionMapper.selectById(sessionId));
        if (entity == null) return false;
        entity.setTitle((title == null || title.isBlank()) ? DEFAULT_TITLE : title.trim());
        entity.setUpdateTime(System.currentTimeMillis());
        sessionMapper.updateById(entity);   // L3 MySQL
        cacheManager.putSession(entity);    // 刷新 L1+L2
        return true;
    }

    /**
     * 删除会话：MySQL 级联删消息+会话 → 清 Redis 三 key + L1 → 清 ChatContextManager 上下文
     */
    public boolean deleteSession(String sessionId) {
        if (sessionId == null) return false;
        try {
            messageMapper.deleteBySessionId(sessionId);          // L3: 级联删消息
            sessionMapper.deleteById(sessionId);                 // L3: 删会话
            cacheManager.evictMessages(sessionId);               // L2+L1: chat:messages
            cacheManager.evictSession(sessionId);                // L2+L1: chat:session
            cacheManager.evictMemory(sessionId);                 // L2: chat:memory
            chatContextManager.clearUser(memoryKey(sessionId));  // 清 L1 上下文 + chat_messages 轮次记录
            log.info("🗑 删除会话 session={}（消息+三层缓存+上下文已清）", sessionId);
            return true;
        } catch (Exception e) {
            log.error("删除会话失败 session={}", sessionId, e);
            return false;
        }
    }

    // ======================== 会话消息 ========================

    /**
     * 分页查询会话历史消息（page=1 为最近一页，list 升序返回）
     */
    public MessagePage getMessages(String sessionId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        // 读走三级缓存（L1→L2→L3），缓存持有该会话升序全量列表
        List<SessionMessageEntity> all = cacheManager.getMessages(sessionId,
                () -> messageMapper.selectBySessionAsc(sessionId));

        if (all == null || all.isEmpty()) {
            return new MessagePage(0, safePage, safeSize, Collections.emptyList());
        }

        int total = all.size();
        int end = total - (safePage - 1) * safeSize;
        if (end <= 0) {
            return new MessagePage(total, safePage, safeSize, Collections.emptyList());
        }
        int start = Math.max(0, end - safeSize);
        List<SessionMessageEntity> slice = new ArrayList<>(all.subList(start, end));
        return new MessagePage(total, safePage, safeSize, slice);
    }

    /**
     * 保存一轮完整对话（用户消息 + AI 回复 + AI 生成图片）到会话，并刷新三层缓存与上下文镜像。
     * 首轮对话自动生成会话标题（降级截取前 20 字符，异步 LLM 精炼覆盖）。
     *
     * @param assistantImage AI 回复附带的图片相对 URL（如 /chat/images/xxx.png），无图片传 null
     */
    public void saveTurn(String sessionId, String userId, String userText, String assistantText, String assistantImage) {
        if (sessionId == null || userText == null || assistantText == null) return;

        // 确保会话存在（如流式请求时新建的空会话）
        ChatSessionEntity session = cacheManager.getSession(sessionId,
                () -> sessionMapper.selectById(sessionId));
        if (session == null) {
            session = createSession(sessionId, userId, null);
        }

        long before = messageMapper.countBySession(sessionId);
        boolean firstTurn = (before == 0);

        long now = System.currentTimeMillis();
        // 写穿：先落 MySQL
        messageMapper.insert(new SessionMessageEntity(UUID.randomUUID().toString(), sessionId, "user", userText, now));
        SessionMessageEntity assistantMsg = new SessionMessageEntity(
                UUID.randomUUID().toString(), sessionId, "assistant", assistantText, now + 1);
        assistantMsg.setImage(assistantImage);
        messageMapper.insert(assistantMsg);

        // 更新会话元数据：updateTime 必刷；首轮生成标题（仅当仍是默认标题，避免覆盖用户自定义重命名）
        session.setUpdateTime(now);
        boolean isDefaultTitle = session.getTitle() == null
                || session.getTitle().isBlank()
                || DEFAULT_TITLE.equals(session.getTitle());
        if (firstTurn && isDefaultTitle) {
            session.setTitle(buildFallbackTitle(userText));
            final String question = userText;
            final String sessionIdRef = sessionId;
            titleExecutor.submit(() -> refineTitleAsync(sessionIdRef, question));
        }
        sessionMapper.updateById(session);

        // 刷新三级缓存 + 上下文镜像
        cacheManager.putSession(session);
        List<SessionMessageEntity> fresh = messageMapper.selectBySessionAsc(sessionId);
        cacheManager.putMessages(sessionId, fresh);
        cacheManager.saveMemory(memoryKey(sessionId), chatContextManager.getHistory(memoryKey(sessionId)));

        log.info("💾 会话落库 session={} 第{}轮 | user={} assistant={}",
                sessionId, firstTurn ? 1 : (before / 2 + 1),
                truncate(userText, 30), truncate(assistantText, 30));
    }

    // ======================== 上下文 ========================

    /**
     * 确保会话的 LangChain4j 上下文就绪。
     * 若 ChatContextManager 中该会话无内存（重启后 L1 未加载），从 Redis chat:memory 反序列化回填。
     * 若 Redis 也无，则 ChatContextManager 启动时已从 MySQL chat_messages 恢复，无需处理。
     * 同时刷新会话最近访问时间，避免长流式期间被闲置淘汰。
     */
    public void ensureContext(String sessionId) {
        if (sessionId == null) return;
        cacheManager.touch(sessionId);
        String key = memoryKey(sessionId);
        if (chatContextManager.hasMemory(key)) return;
        List<ChatMessage> restored = cacheManager.loadMemory(sessionId);
        if (restored != null && !restored.isEmpty()) {
            chatContextManager.seedMemory(key, restored);
            log.info("🔄 从 Redis chat:memory 恢复会话上下文 session={}（{} 条消息）", sessionId, restored.size());
        }
    }

    // ======================== 标题生成 ========================

    /** 降级策略：截取用户提问前 20 字符 */
    private String buildFallbackTitle(String question) {
        String q = question == null ? "" : question.trim().replaceAll("\\s+", " ");
        if (q.isEmpty()) return DEFAULT_TITLE;
        return q.length() <= TITLE_FALLBACK_MAX ? q : q.substring(0, TITLE_FALLBACK_MAX);
    }

    /**
     * 异步 LLM 标题精炼：用 DeepSeek 生成 ≤15 字标题，成功后覆盖更新（不影响 updateTime 排序）。
     */
    private void refineTitleAsync(String sessionId, String question) {
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            new SystemMessage("你是会话标题生成器。用不超过15个字概括用户问题的核心意图，直接输出标题文本，不要引号、不要解释、不要标点。"),
                            new UserMessage(question)))
                    .build();
            ChatResponse response = chatModel.chat(request);
            String title = response.aiMessage() != null ? response.aiMessage().text() : null;
            if (title == null || title.isBlank()) return;
            String clean = title.trim().replaceAll("[“\"']", "");
            if (clean.length() > 20) clean = clean.substring(0, 20);

            ChatSessionEntity session = cacheManager.getSession(sessionId,
                    () -> sessionMapper.selectById(sessionId));
            if (session == null) return;
            session.setTitle(clean);
            sessionMapper.updateById(session);
            cacheManager.putSession(session);
            log.info("✨ LLM 精炼会话标题 session={} → {}", sessionId, clean);
        } catch (Exception e) {
            log.warn("⚠ LLM 标题精炼失败 session={}（保留降级标题） | {}", sessionId, e.getMessage());
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
