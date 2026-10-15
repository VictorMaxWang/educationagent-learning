package com.demo.agent;

import com.demo.service.tools.ReminderService;
import com.demo.util.VoiceContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 定时提醒
 * Agent 根据用户语义自主调用，设置 N 分钟后的定时提醒
 * 用户说 "15分钟后提醒我洗头" → LLM 计算 delayMinutes=15
 * 用户说 "12:00提醒我打卡" → LLM 按当前时间计算剩余分钟数
 * 到期后自动通过微信发送提醒消息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderTool {

    private final ReminderService reminderService;

    @Tool("设置定时提醒。当用户说：提醒我、N分钟后叫我、到点提醒时调用此工具。"
            + "例如：'15分钟后提醒洗头' → message='洗头', delayMinutes='15'。"
            + "'12:00提醒打卡' → 如果现在是11:30，则 delayMinutes='30'。"
            + "注意：delayMinutes 由LLM根据当前时间计算出具体数字，范围1-1440之间。")
    public String setReminder(
            @P("提醒内容，如：洗头、喝水、打卡、开会等，简洁明了") String message,
            @P("多少分钟后提醒。由 LLM 根据用户描述计算出具体数字，范围1-1440分钟。"
                    + "例如 '15分钟后提醒我' → '15'，'12:00提醒' → 从当前时间到12:00的分钟数")
            String delayMinutes) {

        String userId = VoiceContext.getUserId();
        if (userId == null) {
            log.warn("⏰ VoiceContext 未设置 userId，无法设置提醒");
            return "提醒设置失败，请稍后再试~";
        }

        log.info("🧰 Agent 调用提醒工具 [{}]：{}，{} 分钟后", userId, message, delayMinutes);
        String result = reminderService.setReminder(userId, message, delayMinutes);
        log.info("🧰 提醒工具返回：{}", result);
        return result;
    }
}
