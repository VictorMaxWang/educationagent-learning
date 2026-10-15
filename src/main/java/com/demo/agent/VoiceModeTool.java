package com.demo.agent;

import com.demo.service.tools.VoiceModeService;
import com.demo.util.VoiceContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 语音模式切换
 * Agent 根据用户语义自主判断，切换语音回复输出模式
 * 支持三种模式：global（全程语音）、single（仅本条）、off（关闭）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceModeTool {

    private final VoiceModeService voiceModeService;

    @Tool("切换语音/文字回复模式。触发词：关闭语音、不要语音、切回文字、静音、开启语音、全程语音、这句用语音。"
            + "参数 mode 仅支持 global/single/off 三种。必须严格按用户意图选择参数，禁止编造。")
    public String controlVoiceMode(
            @P("语音模式：global=全程语音，single=仅本条语音，off=关闭语音切文字")
            String mode) {

        String userId = VoiceContext.getUserId();
        if (userId == null) {
            log.warn("🔊 VoiceContext 未设置 userId，无法设置语音模式");
            return "语音模式设置失败，请重试~";
        }

        switch (mode) {
            case "global":
                voiceModeService.enableGlobal(userId);
                log.info("🔊 [{}] LLM 调用：global", userId);
                return "已开启全程语音回复模式，接下来的回复都会用语音发送~";
            case "single":
                voiceModeService.setSingle(userId);
                log.info("🔊 [{}] LLM 调用：single", userId);
                return "本条回复使用语音发送~";
            case "off":
                voiceModeService.disable(userId);
                log.info("🔊 [{}] LLM 调用：off", userId);
                return "已关闭语音回复，切换回纯文字模式~";
            default:
                log.warn("🔊 [{}] LLM 传入未知模式: {}", userId, mode);
                return "无法识别语音模式指令，请重试~";
        }
    }
}
