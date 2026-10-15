package com.demo.config;

import com.demo.agent.*;
import com.demo.pet.PetAgentTool;
import com.demo.agent.FileConvertTool;
import com.demo.mapper.ChatMessageMapper;
import com.demo.chroma.TravelKnowledgeService;
import com.demo.chroma.UserKnowledgeService;
import com.demo.skill.SkillManager;
import com.demo.chroma.UserMemoryService;
import com.demo.chroma.UserMemoryExtractor;
import com.demo.service.interrupt.InterruptManager;
import com.demo.service.interrupt.InterruptibleHttpClientBuilder;
import com.demo.service.tools.ChatModeService;
import com.demo.util.ChatContextManager;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j Agent 装配配置
 * 手动构建三层记忆架构（Chroma长期记忆 + 本地20轮对话 + 当前消息）
 * 创建 OpenAI 兼容文本模型 + 所有 @Tool 工具 + AgentExecutor
 */
@Slf4j
@Configuration
public class AgentConfig {

    @Resource
    private AiChatProperties aiChat;

    @Value("${chat.context.max-rounds:20}")
    private int maxContextRounds;

    @Value("${memory.chroma.enabled:false}")
    private boolean chromaEnabled;

    @Value("${memory.chroma.url:http://localhost:8000}")
    private String chromaUrl;

    /**
     * 创建三层上下文管理器
     * MySQL 持久化（20 轮） + Chroma 向量数据库长期记忆（可选）
     * 重启后自动从 MySQL 恢复对话历史到内存缓存
     */
    @Bean
    public ChatContextManager chatContextManager(
            ChatMessageMapper chatMessageMapper,
            UserMemoryService longTermMemoryService,
            UserMemoryExtractor memoryExtractor) {

        if (chromaEnabled) {
            log.info("🔗 Chroma 已启用（{}），开启 MySQL 持久化 + Chroma 长期记忆", chromaUrl);
        } else {
            log.info("📝 Chroma 未启用，使用 MySQL 持久化（重启保留最近 {} 轮对话）", maxContextRounds);
        }
        return new ChatContextManager(maxContextRounds, chatMessageMapper,
                chromaEnabled ? longTermMemoryService : null,
                chromaEnabled ? memoryExtractor : null);
    }

    /**
     * 可中断的 LLM HTTP 客户端构建器。
     * JDK HttpClient 的 Socket 阻塞读取不受 Thread.interrupt() 影响，
     * 此 Builder 包装 {@code JdkHttpClientBuilder}，在 execute 前检测中断信号并提前抛出。
     */
    @Bean
    public InterruptibleHttpClientBuilder interruptibleHttpClientBuilder(InterruptManager interruptManager) {
        InterruptibleHttpClientBuilder builder = new InterruptibleHttpClientBuilder(interruptManager);
        builder.setConnectTimeout(Duration.ofSeconds(30));
        builder.setReadTimeout(Duration.ofSeconds(60));
        return builder;
    }

    /**
     * 统一对话模型 Bean（使用可中断的 HttpClient，保留旧 Bean 名以兼容现有注入）。
     * 供 Agent 对话与刷题知识点打标等场景共用。
     */
    @Bean
    public OpenAiChatModel deepseekChatModel(InterruptibleHttpClientBuilder interruptibleHttpClientBuilder) {
        if (!aiChat.hasApiKey()) {
            log.error("❌ AI API Key 未配置！请设置 AI_CHAT_API_KEY 或私密配置 ai.chat.api-key");
            throw new IllegalStateException("AI API Key 未配置");
        }
        // maxRetries=0：中断后 LLM 调用已取消，无需重试
        return aiChat.chatModelBuilder()
                .temperature(0.3)
                .maxRetries(0)
                .httpClientBuilder(interruptibleHttpClientBuilder)
                .build();
    }

    /**
     * 注册智能体对话 Bean
     * 注入三层上下文管理器 + 所有 @Tool 工具
     */
    @Bean
    public Assistant weatherAgent(WeatherTool weatherTool, ImageGenTool imageGenTool,
                                  VoiceModeTool voiceModeTool,
                                  VoiceTypeTool voiceTypeTool,
                                  FileSystemTool fileSystemTool,
                                  CalculatorTool calculatorTool,
                                  WebSearchTool webSearchTool,
                                  WebPageReaderTool webPageReaderTool,
                                  WebFetchTool webFetchTool,
                                  PetAgentTool petAgentTool,
                                  ReminderTool reminderTool,
                                  SendEmailTool sendEmailTool,
                                  ScreenshotTool screenshotTool,
                                  ImageEditTool imageEditTool,
                                  TimeTool timeTool,
                                  ChatModeTool chatModeTool,
                                  MapTool mapTool,
                                  TrainTicketTool trainTicketTool,
                                  JuheTrainTicketMcpTool juheTrainTicketMcpTool,
                                  BrowserSearchTool browserSearchTool,
                                  BrowserFormTool browserFormTool,
                                  BrowserRegisterTool browserRegisterTool,
                                  ReportScheduleTool reportScheduleTool,
                                  UserKnowledgeTool userKnowledgeTool,
                                  XhsPublishMcpTool xhsPublishMcpTool,
                                  FileConvertTool fileConvertTool,
                                  FileGenTool fileGenTool,
                                  ChatModeService chatModeService,
                                  ChatContextManager chatContextManager,
                                  TravelKnowledgeService travelKnowledgeService,
                                  UserKnowledgeService userKnowledgeService,
                                  InterruptManager interruptManager,
                                  InterruptibleHttpClientBuilder interruptibleHttpClientBuilder,
                                  OpenAiChatModel deepseekChatModel,
                                  SkillManager skillManager,
                                  com.demo.router.HybridRouter hybridRouter) {
        log.info("🤖 初始化 LangChain4j Agent...");
        log.info("   Model: {}", aiChat.getModel());
        log.info("   Base URL: {}", aiChat.getBaseUrl());

        // 使用共享文本模型，课堂、知识库和主对话共同采用这一连接配置。
        OpenAiChatModel chatModel = deepseekChatModel;

        // 流式模型：桌面端真流式对话专用，与 chatModel 同参数同 HTTP 客户端
        OpenAiStreamingChatModel streamingChatModel = aiChat.streamingModelBuilder()
                .temperature(0.3)
                .httpClientBuilder(interruptibleHttpClientBuilder)
                .build();

        // ======================== 注册到前置路由 ========================
        skillManager.registerToolInstances(
                weatherTool, imageGenTool, voiceModeTool, voiceTypeTool,
                fileSystemTool, calculatorTool, webSearchTool, webPageReaderTool, webFetchTool, petAgentTool,
                reminderTool, sendEmailTool, screenshotTool,
                imageEditTool, timeTool, chatModeTool, mapTool,
                trainTicketTool, juheTrainTicketMcpTool, browserSearchTool,
                browserFormTool, browserRegisterTool, reportScheduleTool,
                userKnowledgeTool, xhsPublishMcpTool,
                fileConvertTool,
                fileGenTool);

        // 构建 Agent 执行器（所有工具注册到同一实例）
        AgentExecutor agent = new AgentExecutor(chatModel, streamingChatModel, chatModeService, chatContextManager,
                travelKnowledgeService, userKnowledgeService, interruptManager, hybridRouter,
                weatherTool, imageGenTool, voiceModeTool, voiceTypeTool,
                fileSystemTool, calculatorTool, webSearchTool, webPageReaderTool, webFetchTool, petAgentTool,
                reminderTool, sendEmailTool, screenshotTool,
                imageEditTool, timeTool, chatModeTool, mapTool,
                trainTicketTool, juheTrainTicketMcpTool, browserSearchTool,
                browserFormTool, browserRegisterTool, reportScheduleTool,
                userKnowledgeTool, xhsPublishMcpTool,
                fileConvertTool,
                fileGenTool);

        log.info("✅ Agent 初始化完成：{} 个工具", agent.toolCount());
        return agent;
    }
}
