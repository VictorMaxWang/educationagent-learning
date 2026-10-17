package com.demo.edu.classroom;

import com.demo.config.AiChatProperties;
import com.demo.edu.classroom.ai.ClassroomModelAdmission;
import com.demo.edu.classroom.ai.ClassroomModelAdmissionProperties;
import com.demo.edu.safety.CrisisGuard;
import com.demo.edu.safety.EduContentSafetyJudge;
import com.demo.edu.safety.EduContentSafetyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 课堂复用现有模型和教育安全服务，Node 永远拿不到供应商凭据。 */
@Component
public class ClassroomTutorModel {
    /** 生成器声明的用途；只有这个用途需要 0 温度复核模型。 */
    private static final String PURPOSE_REVIEW = "quiz-review";

    private final OpenAiChatModel model;
    private final OpenAiChatModel reviewModel;
    private final CrisisGuard crisisGuard;
    private final EduContentSafetyJudge safetyJudge;
    private final EduContentSafetyProperties safetyProperties;
    private final AiChatProperties chatProperties;
    private final ClassroomQuizReviewProperties quizReviewProperties;
    private final ClassroomModelAdmission admission;

    public ClassroomTutorModel(OpenAiChatModel model, CrisisGuard crisisGuard, EduContentSafetyJudge safetyJudge,
                               EduContentSafetyProperties safetyProperties, AiChatProperties chatProperties,
                               ClassroomQuizReviewProperties quizReviewProperties) {
        this(model,crisisGuard,safetyJudge,safetyProperties,chatProperties,quizReviewProperties,new ClassroomModelAdmission(new ClassroomModelAdmissionProperties()));
    }
    @Autowired public ClassroomTutorModel(OpenAiChatModel model, CrisisGuard crisisGuard, EduContentSafetyJudge safetyJudge,
                               EduContentSafetyProperties safetyProperties, AiChatProperties chatProperties,
                               ClassroomQuizReviewProperties quizReviewProperties,ClassroomModelAdmission admission) {
        this.model = model;
        this.crisisGuard = crisisGuard;
        this.safetyJudge = safetyJudge;
        this.safetyProperties = safetyProperties;
        this.chatProperties = chatProperties;
        this.quizReviewProperties = quizReviewProperties;
        this.admission=admission;
        this.reviewModel = buildReviewModel();
    }

    /**
     * 盲解复核必须 0 温度，而生成模型在 AgentConfig 里固定 temperature(0.3)，无法就地改；
     * 因此单独建一个复核专用实例。未配置密钥时退回生成模型，保证上下文仍可启动
     * （无密钥时整个生成链路本来就不可用）。
     */
    private OpenAiChatModel buildReviewModel() {
        if (!chatProperties.hasApiKey()) return model;
        var builder = chatProperties.chatModelBuilder()
                .temperature(quizReviewProperties.getTemperature())
                .maxTokens(quizReviewProperties.getMaxTokens())
                .timeout(Duration.ofMillis(Math.max(1_000, quizReviewProperties.getTimeoutMs())))
                .maxRetries(0);
        String name = quizReviewProperties.getModel();
        if (name != null && !name.isBlank()) builder.modelName(name.trim());
        return builder.build();
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

    public String complete(JsonNode messages) { return complete(messages, ""); }

    /**
     * @param purpose 生成器声明并经 {@code ClassroomNodeEngine} 校验过的用途。
     *                {@code quiz-review} 路由到 0 温度复核模型，其余（含重生成）走生成模型。
     */
    public String complete(JsonNode messages, String purpose) {
        return complete(messages,purpose,PURPOSE_REVIEW.equals(purpose)?quizReviewProperties.getTimeoutMs():150_000,null);
    }

    public String complete(JsonNode messages,String purpose,long budgetMs){return complete(messages,purpose,budgetMs,null);}

    /** Node提供剩余总预算；仅实际chat占用许可，调用方中断不允许自动重发未知请求。 */
    public String complete(JsonNode messages,String purpose,long budgetMs,ClassroomCancellation cancellation) {
        if(budgetMs<=0)throw new ClassroomException(504,"课堂模型调用已到截止时间");
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
        OpenAiChatModel target = PURPOSE_REVIEW.equals(purpose) && quizReviewProperties.isEnabled() ? reviewModel : model;
        String text = admission.execute(Duration.ofMillis(Math.min(180_000,budgetMs)),cancellation,
                remaining->target.chat(ChatRequest.builder().messages(converted).build()).aiMessage().text());
        if (text == null || text.isBlank()) throw new ClassroomException(502, "模型返回了空内容，请重试");
        return text;
    }

    public Object health() { return safetyJudge.snapshot(); }
}
