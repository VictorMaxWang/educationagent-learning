package com.demo.agent;

import com.demo.service.tools.ChatModeService;
import com.demo.util.VoiceContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 对话模式切换
 * Agent 根据用户语义自主判断，切换回答风格模式
 * 支持四种模式（模仿模式由 {@link #setMimicMode} 独立处理）：
 * - expert   专家模式：深度专业回答
 * - concise  精简模式：30 字以内简洁回答
 * - plain    通俗释义模式：用中小学生能听懂的话解释（教育场景）
 * - normal   普通模式：恢复正常对话
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModeTool {

    private final ChatModeService chatModeService;

    @Tool("切换对话模式。触发词：进入专家模式、精简模式、讲通俗点、大白话、听不懂、说人话、恢复普通模式。"
            + "mode 参数仅支持：expert（专家模式）、concise（精简模式）、plain（通俗释义模式）、normal（普通模式）。"
            + "必须严格按用户意图选择参数，禁止编造。")
    public String changeMode(
            @P("对话模式：expert=专家模式，concise=精简模式，plain=通俗释义（用大白话解释，面向学生），normal=普通模式（恢复默认）")
            String mode) {

        String userId = VoiceContext.getUserId();
        if (userId == null) {
            log.warn("💬 VoiceContext 未设置 userId，无法切换模式");
            return "模式切换失败，请重试~";
        }

        chatModeService.setMode(userId, mode);
        log.info("💬 [{}] LLM 调用切换模式：{}", userId, mode);

        return switch (mode) {
            case "expert" -> "已切换为专家模式，接下来我会用专业深度风格回答～";
            case "concise" -> "已切换为精简模式，接下来我会简洁回答～";
            case "plain" -> "已切换为通俗释义模式，接下来我用大白话给你讲，遇到术语会随手解释～";
            case "normal" -> "已恢复普通对话模式～";
            default -> "无法识别的模式，请重试~";
        };
    }

    @Tool("设置模仿模式。当用户提供了模仿文本后调用此工具。"
            + "注意：用户说'模仿模式'时不要直接调用此工具，先回复'请给我需要模仿的文本或人物名字'，"
            + "等用户发送文本后，再将用户发的文本作为 sampleText 传入调用此工具。"
            + "调用后 LLM 后续回答将严格模仿该文本的风格。")
    public String setMimicMode(
            @P("用户提供的风格样本文本，LLM 需要模仿这段文字的说话风格、语气、句式")
            String sampleText) {

        String userId = VoiceContext.getUserId();
        if (userId == null) {
            log.warn("💬 VoiceContext 未设置 userId，无法设置模仿模式");
            return "模仿模式设置失败，请重试~";
        }

        if (sampleText == null || sampleText.isBlank()) {
            return "请提供要模仿的文本内容～";
        }

        chatModeService.setMimicMode(userId, sampleText);
        log.info("💬 [{}] LLM 调用模仿模式，样本长度：{}", userId, sampleText.length());
        return "已记住你的说话风格，接下来我会模仿你的语气回答～";
    }
}
