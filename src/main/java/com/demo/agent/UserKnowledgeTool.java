package com.demo.agent;

import com.demo.chroma.UserKnowledgeService;
import com.demo.service.tools.holder.UserFileHolder;
import com.demo.skill.annotation.RouterMatch;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户个人知识库工具。
 * <p>
 * 用户发文件（PDF/Word/TXT）+ 说"存到我的知识库" → LLM 调此工具
 * → 文件保存到 data/user-knowledge/{userId}/ → 索引进 Chroma
 * → 后续聊天自动参考用户上传的资料。
 */
@Slf4j
@Component
public class UserKnowledgeTool {

    @Resource
    private UserKnowledgeService userKnowledgeService;

    @Resource
    private UserFileHolder userFileHolder;

    @Tool("保存用户最新上传的文件到个人知识库。当用户说「存到我的知识库」「保存这个文件」「加到我的资料库」时调用。")
    @RouterMatch(
        patterns = {
            "存(?:到|入)?(?:我的)?(?:知识库|资料库)",
            "保存(?:这个|此)?文件(?:到(?:我的)?(?:知识库|资料库))?",
            "加到我的资料库",
            "上传(?:到)?(?:我的)?(?:知识库|资料库)"
        },
        confidence = 0.75,
        priority = 3
    )
    public String saveToMyKnowledge(
            @P(value = "用户描述，如：这是日本签证攻略、这是我的旅游计划等。帮助确认文件用途", required = false) String description) {

        String userId = com.demo.util.VoiceContext.getUserId();
        if (userId == null) userId = "default";

        if (!userFileHolder.hasPending(userId)) {
            return "⚠️ 没有找到待保存的文件。请先发送文件（PDF/Word/TXT），然后再说「把这个存到我的知识库」。";
        }

        log.info("📚 [{}] 用户请求保存知识库文件: {}", userId, description != null ? description : "无描述");
        return userKnowledgeService.saveAndIndex(userId);
    }
}
