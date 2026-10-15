package com.demo.edu.classroom;

import com.demo.edu.safety.CrisisGuard;
import com.demo.edu.safety.EduContentSafetyJudge;
import com.demo.edu.safety.EduContentSafetyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 课堂复用现有模型和教育安全服务，Node 永远拿不到供应商凭据。 */
@Component
public class ClassroomTutorModel {
    private final OpenAiChatModel model;
    private final CrisisGuard crisisGuard;
    private final EduContentSafetyJudge safetyJudge;
    private final EduContentSafetyProperties safetyProperties;

    public ClassroomTutorModel(OpenAiChatModel model, CrisisGuard crisisGuard, EduContentSafetyJudge safetyJudge,
                               EduContentSafetyProperties safetyProperties) {
        this.model = model;
        this.crisisGuard = crisisGuard;
        this.safetyJudge = safetyJudge;
        this.safetyProperties = safetyProperties;
    }

    public Optional<String> precheck(String text) { return crisisGuard.checkForUser(ClassroomCourseService.LEARNER_ID, text); }

    /** 裁判原接口只取前 1200 字，课堂长输出按段检查，避免后半篇完全绕过检查。 */
    public Optional<String> unsafeReplacement(String text) {
        return unsafeReplacement(text, null);
    }

    public Optional<String> unsafeReplacement(String text, ClassroomCancellation cancellation) {
        if (CrisisGuard.isSafetyScript(text)) return Optional.empty();
        if (text == null) return Optional.empty();
        for (int start = 0; start < text.length(); start += 1000) {
            if (cancellation != null) cancellation.check();
            var verdict = safetyJudge.judge(text.substring(start, Math.min(text.length(), start + 1200)));
            // 课堂独立消费裁判结果，其他入口仍保留原有 log/enforce 配置语义。
            if (verdict.evaluated() && !verdict.safe()) return Optional.of(safetyProperties.getReplacement());
        }
        if (cancellation != null) cancellation.check();
        return Optional.empty();
    }

    public String complete(JsonNode messages) {
        if (!messages.isArray() || messages.isEmpty() || messages.size() > 32)
            throw new ClassroomException(502, "生成器发出了无效模型请求");
        List<ChatMessage> converted = new ArrayList<>();
        int chars = 0;
        for (JsonNode message : messages) {
            String content = message.path("content").asText("");
            chars += content.length();
            if (chars > 150000) throw new ClassroomException(502, "生成器模型请求过长");
            converted.add(switch (message.path("role").asText()) {
                case "system" -> SystemMessage.from(content);
                case "user" -> UserMessage.from(content);
                case "assistant" -> AiMessage.from(content);
                default -> throw new ClassroomException(502, "生成器模型消息角色无效");
            });
        }
        String text = model.chat(ChatRequest.builder().messages(converted).build()).aiMessage().text();
        if (text == null || text.isBlank()) throw new ClassroomException(502, "模型返回了空内容，请重试");
        return text;
    }

    public Object health() { return safetyJudge.snapshot(); }
}
