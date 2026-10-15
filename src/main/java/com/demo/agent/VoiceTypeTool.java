package com.demo.agent;

import com.demo.service.tools.VoiceTypeService;
import com.demo.util.VoiceContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 音色切换
 * Agent 根据用户语义自主判断，切换 TTS 语音合成音色
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceTypeTool {

    private final VoiceTypeService voiceTypeService;

    @Tool("切换语音音色。触发词：换声音、换音色、男声、女声、童声、御姐、少年。"
            + "参数 voiceType 必须从指定ID选择，禁止编造。")
    public String changeVoiceType(
            @P("音色ID：403005=云雅琴（默认女声）,101007=旧推荐女声,101002=成熟男声,101015=可爱童声,101301=情感男声,101006=活力女声,101008=少年男声,101016=御姐女声")
            String voiceType) {

        String userId = VoiceContext.getUserId();
        if (userId == null) {
            log.warn("🎙️ VoiceContext 未设置 userId，无法设置音色");
            return "音色切换失败，请重试~";
        }

        voiceTypeService.setVoiceType(userId, voiceType);
        log.info("🎙️ [{}] LLM 调用音色切换：{}", userId, voiceType);
        return "已为你切换声音，接下来我会用新音色说话~";
    }
}
