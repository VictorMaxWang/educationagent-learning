package com.demo.util;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.entity.ChatMessageEntity;
import com.demo.mapper.ChatMessageMapper;
import com.demo.chroma.UserMemoryService;
import com.demo.chroma.UserMemoryExtractor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话上下文管理器（MySQL 持久化 + Chroma 长期记忆）
 * 【三层数据架构（从近到远）】：
 *   1. 用户最新提问 ← 当前输入
 *   2. 本地 20 轮近期对话 ← ConcurrentHashMap 内存缓存 + MySQL 持久化（重启保留）
 *   3. Chroma 向量数据库 4-5 条长期记忆 ← 永久存储
 * 【持久化策略】：
 *   - 写：内存缓存 + 异步写入 MySQL（写穿模式，保证重启恢复）
 *   - 读：纯内存缓存（零 DB 延迟）
 *   - 启动：从 MySQL 加载所有用户的对话历史到内存
 */
@Slf4j
public class ChatContextManager {

    /** 默认单用户最大保留对话轮数 */
    private static final int DEFAULT_MAX_ROUNDS = 20;
    /** 长期记忆默认检索匹配条数 */
    private static final int DEFAULT_TOP_K_MEMORIES = 5;

    /** 自定义单用户最大对话轮数 */
    private final int maxRounds;
    /** 向量记忆检索返回条数 */
    private final int topKMemories;
    /** 标记是否启用Chroma长期记忆功能 */
    private final boolean chromaEnabled;

    /**
     * 本地 Lucene 记忆库是否已接管（{@code memory.integration.enabled}）。
     *
     * <p>两条用户记忆链路是<b>二选一</b>：本地库开启时，Chroma 那条既不会被读取
     * （{@code AgentExecutor} 的提示词组装走的是本地库分支），也就不该继续写入——
     * 而 {@code UserMemoryExtractor} 的写入要**额外调一次 DeepSeek**，
     * 等于每轮对话白花一次调用。实测日志里它还在跑，还顺带抛异常刷 ERROR。
     */
    @Value("${memory.integration.enabled:false}")
    private boolean localMemoryEnabled;

    // ========== 本地内存缓存（20 轮近期对话，读零延迟） ==========
    private final Map<String, List<ChatMessage>> memories = new ConcurrentHashMap<>();

    // ========== MySQL 持久化（MyBatis-Plus） ==========
    private final ChatMessageMapper chatMessageMapper;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-db-persist");
        t.setDaemon(true);
        return t;
    });

    /** Chroma向量长期记忆业务服务 */
    private final UserMemoryService longTermMemoryService;
    /** 对话内容关键信息抽取工具类 */
    private final UserMemoryExtractor memoryExtractor;

    // ============================== 构造方法 ==============================
    /**
     * 无参构造：纯内存临时会话模式
     */
    public ChatContextManager() {
        this(DEFAULT_MAX_ROUNDS, null, null, null);
    }

    public ChatContextManager(int maxRounds) {
        this(maxRounds, null, null, null);
    }

    /**
     * 完整版构造器：支持MySQL持久化+Chroma长期记忆
     */
    public ChatContextManager(int maxRounds, ChatMessageMapper chatMessageMapper,
                              UserMemoryService longTermMemoryService,
                              UserMemoryExtractor memoryExtractor) {
        this.maxRounds = maxRounds;
        this.topKMemories = DEFAULT_TOP_K_MEMORIES;
        this.chatMessageMapper = chatMessageMapper;
        this.longTermMemoryService = longTermMemoryService;
        this.memoryExtractor = memoryExtractor;
        // 判断向量记忆组件是否全部注入，判定是否启用
        this.chromaEnabled = (longTermMemoryService != null && memoryExtractor != null);

        // 启动时从 MySQL 加载所有用户的对话历史
        if (chatMessageMapper != null) {
            loadAllFromDatabase();
        }

        log.info("🧠 ChatContextManager 初始化完成：每用户 {} 轮对话{}，持久化=MySQL(MyBatis-Plus)",
                maxRounds,
                chromaEnabled ? " + Chroma 长期记忆" : "");
    }

    // ============================== 内存会话操作 ==============================
    /**
     * 新增一轮完整对话记录
     * 自动完成：内存缓存存储 + 异步数据库落库 + 异步抽取长期记忆
     * @param userId 用户唯一ID
     * @param userMessage 用户提问文本
     * @param aiResponse AI回复文本
     */
    public void addMessage(String userId, String userMessage, String aiResponse) {
        if (userId == null || userMessage == null || aiResponse == null) return;

        // 1. 写入本地内存缓存
        List<ChatMessage> history = memories.computeIfAbsent(userId, k -> new ArrayList<>());
        synchronized (history) {
            history.add(new UserMessage(userMessage));
            history.add(AiMessage.from(aiResponse));

            // 超出最大轮次自动清理最早对话
            int maxMessages = maxRounds * 2;
            if (history.size() > maxMessages) {
                int excess = history.size() - maxMessages;
                history.subList(0, excess).clear();
            }
        }
        log.debug("[{}] 上下文轮次：{}/{}", userId, getRoundCount(userId), maxRounds);

        // 2. 异步线程写入MySQL持久化
        if (chatMessageMapper != null) {
            saveToDatabaseAsync(userId, userMessage, aiResponse, null);
        }

        // 3. 异步抽取对话关键信息存入Chroma向量库
        // 本地记忆库接管时跳过：两条链路二选一，同时跑等于每轮白花一次 DeepSeek 调用
        if (chromaEnabled && memoryExtractor != null && !localMemoryEnabled) {
            memoryExtractor.extractAndStoreAsync(userId, userMessage, aiResponse);
        }
    }

    /**
     * 新增一轮对话记录（带违规标记）
     * 当 FSM 检测到 LLM 跳过工具调用时，violation 记录违规类型，方便后续排查
     */
    public void addMessage(String userId, String userMessage, String aiResponse, String violation) {
        if (userId == null || userMessage == null || aiResponse == null) return;

        List<ChatMessage> history = memories.computeIfAbsent(userId, k -> new ArrayList<>());
        synchronized (history) {
            history.add(new UserMessage(userMessage));
            history.add(AiMessage.from(aiResponse));
            int maxMessages = maxRounds * 2;
            if (history.size() > maxMessages) {
                int excess = history.size() - maxMessages;
                history.subList(0, excess).clear();
            }
        }

        if (chatMessageMapper != null) {
            saveToDatabaseAsync(userId, userMessage, aiResponse, violation);
        }

        // 本地记忆库接管时跳过：两条链路二选一，同时跑等于每轮白花一次 DeepSeek 调用
        if (chromaEnabled && memoryExtractor != null && !localMemoryEnabled) {
            memoryExtractor.extractAndStoreAsync(userId, userMessage, aiResponse);
        }
    }

    /**
     * 清空指定用户全部会话上下文
     * 同时清空内存缓存与数据库存储记录
     * @param userId 用户唯一ID
     */
    public void clearUser(String userId) {
        memories.remove(userId);
        if (chatMessageMapper != null) {
            dbExecutor.submit(() -> {
                try {
                    chatMessageMapper.deleteByUserId(userId);
                } catch (Exception e) {
                    log.warn("[{}] 删除 MySQL 对话记录失败: {}", userId, e.getMessage());
                }
            });
        }
        log.debug("[{}] 上下文已清空", userId);
    }

    /**
     * 清空系统内所有用户的会话上下文
     * 清空内存+全量数据库会话数据
     */
    public void clearAll() {
        memories.clear();
        if (chatMessageMapper != null) {
            dbExecutor.submit(() -> {
                try {
                    chatMessageMapper.delete(new LambdaQueryWrapper<>());
                } catch (Exception e) {
                    log.warn("清空 MySQL 对话记录失败: {}", e.getMessage());
                }
            });
        }
        log.info("🧹 所有用户上下文已清空");
    }

    /**
     * 获取用户完整历史对话消息列表
     * 返回集合副本，防止外部修改内存原数据
     * @param userId 用户唯一ID
     * @return 对话消息集合
     */
    public List<ChatMessage> getHistory(String userId) {
        List<ChatMessage> history = memories.get(userId);
        if (history == null) return Collections.emptyList();
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /**
     * 统计用户当前有效对话轮数
     * 一轮对话 = 用户提问 + AI回复两条消息
     * @param userId 用户唯一ID
     * @return 对话轮数
     */
    public int getRoundCount(String userId) {
        List<ChatMessage> history = memories.get(userId);
        if (history == null) return 0;
        synchronized (history) {
            return history.size() / 2;
        }
    }

    /**
     * 判断指定用户（会话）当前是否已有内存上下文
     * 供会话模块判断是否需要从 Redis chat:memory 回填
     */
    public boolean hasMemory(String userId) {
        return memories.containsKey(userId);
    }

    /**
     * 用外部历史回填指定用户的内存上下文
     * 供会话模块在服务重启/缓存淘汰后从 Redis chat:memory 恢复 LangChain4j 上下文
     * 仅在调用方确认当前无内存时使用，避免覆盖正在累积的上下文
     */
    public void seedMemory(String userId, List<ChatMessage> history) {
        if (userId == null || history == null || history.isEmpty()) return;
        List<ChatMessage> target = memories.computeIfAbsent(userId, k -> new ArrayList<>());
        synchronized (target) {
            target.clear();
            target.addAll(history);
            // 同样受最大轮次约束，防止回填内容无上限膨胀
            int maxMessages = maxRounds * 2;
            if (target.size() > maxMessages) {
                target.subList(0, target.size() - maxMessages).clear();
            }
        }
        log.debug("[{}] 已从外部回填 {} 条上下文消息", userId, history.size());
    }

    /** 获取当前在线的用户数 */
    public int getUserCount() { return memories.size(); }

    /** 获取所有有上下文的用户 ID */
    public Set<String> getUserIds() { return memories.keySet(); }

    /** 获取配置的最大轮次数 */
    public int getMaxRounds() { return maxRounds; }

    // ============================== Chroma长期记忆检索 ==============================
    /**
     * 搜索与当前用户消息最相关的 4-5 条长期记忆
     * Chroma 不可用时返回空列表，不阻塞主流程
     */
    public List<String> getLongTermMemories(String userId, String query) {
        if (!chromaEnabled || longTermMemoryService == null) return Collections.emptyList();
        if (query == null || query.isBlank()) return Collections.emptyList();
        return longTermMemoryService.searchMemories(userId, query, topKMemories);
    }

    /** Chroma 是否启用 */
    public boolean isChromaEnabled() { return chromaEnabled; }

    // ============================== MySQL数据库持久化逻辑 ==============================
    /**
     * 项目启动初始化方法
     * 从MySQL批量读取所有用户历史会话，加载至本地内存缓存
     */
    private void loadAllFromDatabase() {
        try {
            // 按对话轮次升序查询全量会话数据
            List<ChatMessageEntity> all = chatMessageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessageEntity>().orderByAsc(ChatMessageEntity::getRoundNum));

            if (all.isEmpty()) {
                log.info("📂 MySQL 中无历史对话记录（首次运行正常）");
                return;
            }

            // 按照用户ID分组整理会话数据
            Map<String, List<ChatMessageEntity>> grouped = new LinkedHashMap<>();
            for (ChatMessageEntity entity : all) {
                grouped.computeIfAbsent(entity.getUserId(), k -> new ArrayList<>()).add(entity);
            }

            int loaded = 0;
            for (var entry : grouped.entrySet()) {
                String userId = entry.getKey();
                List<ChatMessageEntity> entities = entry.getValue();

                // 只保留最近 N 轮（maxRounds * 2 条消息）
                int maxMessages = maxRounds * 2;
                if (entities.size() > maxMessages) {
                    entities = entities.subList(entities.size() - maxMessages, entities.size());
                }

                // 数据库实体转换为LangChain对话消息对象
                List<ChatMessage> history = new ArrayList<>();
                for (ChatMessageEntity entity : entities) {
                    if ("user".equals(entity.getRole())) {
                        history.add(new UserMessage(entity.getContent()));
                    } else if ("assistant".equals(entity.getRole())) {
                        history.add(AiMessage.from(entity.getContent()));
                    }
                }

                // 写入内存缓存
                if (!history.isEmpty()) {
                    memories.put(userId, history);
                    loaded++;
                    log.debug("[{}] 从 MySQL 恢复 {} 条消息", userId, history.size());
                }
            }
            log.info("📂 从 MySQL 恢复 {} 个用户的对话历史（共 {} 条消息）", loaded, all.size());
        } catch (Exception e) {
            log.warn("从 MySQL 加载对话历史失败: {}", e.getMessage());
        }
    }

    /**
     * 异步线程执行会话数据入库
     * 不阻塞对话响应主线程，提升接口响应速度
     * @param userId 用户ID
     * @param userMessage 用户消息
     * @param aiResponse AI回复消息
     */
    private void saveToDatabaseAsync(String userId, String userMessage, String aiResponse, String violation) {
        dbExecutor.submit(() -> {
            try {
                // 查询用户当前最大对话轮次，生成新一轮次编号
                int maxRound = chatMessageMapper.findMaxRoundNumByUserId(userId);
                int nextRound = maxRound + 1;

                // 封装数据库实体类，批量插入一轮对话
                ChatMessageEntity userEntity = new ChatMessageEntity(userId, "user", userMessage, nextRound);
                ChatMessageEntity aiEntity = new ChatMessageEntity(userId, "assistant", aiResponse, nextRound);
                aiEntity.setViolation(violation);
                //chatMessageMapper:
                //继承了 MyBatis-Plus 的BaseMapper<ChatMessageEntity>，
                // 是操作chat_messages数据表的工具对象，自带insert插入方法。
                //userEntity:ChatMessageEntity实体对象，存的是用户说的那句话
                //aiEntity:ChatMessageEntity实体对象，存的是AI 回复的那句话
                //.insert(对象) 做了什么
                //insert是 MyBatis-Plus 自带方法：
                //自动读取实体里所有字段，生成一条INSERT SQL，发送给 MySQL 执行，在 chat_messages 表里新增一行数据。
                chatMessageMapper.insert(userEntity);
                chatMessageMapper.insert(aiEntity);

                // 自动清理超出保留轮数的老旧会话数据
                int keepFrom = nextRound - maxRounds;
                if (keepFrom > 0) {
                    int deleted = chatMessageMapper.deleteOldMessages(userId, keepFrom);
                    if (deleted > 0) {
                        log.debug("[{}] 清理旧消息 {} 条（保留最近 {} 轮）", userId, deleted, maxRounds);
                    }
                }
            } catch (Exception e) {
                log.warn("[{}] 持久化对话到 MySQL 失败: {}", userId, e.getMessage());
            }
        });
    }

    // ============================== 通用工具方法 ==============================
    /**
     * 获取用户会话文本摘要信息
     * 用于日志打印调试，自动截断超长文本（50）
     * @param userId 用户唯一ID
     * @return 精简后的对话文本列表
     */
    public List<String> getHistorySummary(String userId) {
        List<ChatMessage> history = memories.get(userId);
        if (history == null) return Collections.emptyList();

        List<String> summary = new ArrayList<>();
        synchronized (history) {
            for (ChatMessage msg : history) {
                if (msg instanceof UserMessage) {
                    summary.add("👤 " + truncate(((UserMessage) msg).singleText(), 50));
                } else if (msg instanceof AiMessage aiMsg) {
                    String text = aiMsg.text();
                    if (text != null) {
                        summary.add("🤖 " + truncate(text, 50));
                    }
                }
            }
        }
        return summary;
    }

    /**
     * 字符串长度截断工具
     * 超出指定长度自动拼接省略号
     * @param text 原始文本
     * @param maxLen 最大保留字符长度
     * @return 截断后文本
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
