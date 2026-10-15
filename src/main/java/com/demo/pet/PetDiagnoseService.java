package com.demo.pet;

import com.demo.config.AiChatProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 宠物智能诊断核心服务
 * 流程：图片识别(VisionService) → 联网搜索(WebSearch) → LLM综合分析 → 解析 → 存储
 */
@Slf4j
@Service
public class PetDiagnoseService {

    @Resource
    private AiChatProperties aiChat;

    @Resource
    private PetService petService;

    /** 图片识别 SPI（可选）：无实现时图片诊断优雅降级 */
    @Resource
    private ObjectProvider<PetImageRecognizer> imageRecognizerProvider;

    /** 联网搜索 SPI（可选）：无实现时跳过搜索，仅告知 LLM 未配置 */
    @Resource
    private ObjectProvider<PetWebSearcher> webSearcherProvider;

    @Resource
    private PetDiagnosisMapper diagnosisMapper;

    private OpenAiChatModel chatModel;

    private static final String DIAGNOSIS_SYSTEM_PROMPT = """
            你是一位资深宠物医生助手，拥有丰富的临床经验。请根据以下信息进行综合分析，给出专业诊断建议。

            【重要格式要求】
            你的回复必须严格按以下格式输出：

            【诊断结论】
            用1-3句话总结可能的疾病/问题

            【详细分析】
            基于症状和搜索结果，分析可能的病因和病情严重程度

            【处理建议】
            1. 分步骤列出具体可操作的建议
            2. 包括家庭护理措施
            3. 如需用药请注明"请遵医嘱"

            【注意事项】
            - 列出需要避免的事项
            - 说明什么情况下必须立即就医

            【免责声明】
            AI诊断仅供参考，不能替代专业兽医的诊断和治疗。如宠物有明显不适，请及时带往正规宠物医院就诊。
            """;

    @PostConstruct
    public void init() {
        if (aiChat == null || !aiChat.hasApiKey()) {
            log.warn("🐾 未配置 AI_CHAT_API_KEY，宠物 AI 诊断功能降级为不可用");
            return;
        }
        this.chatModel = aiChat.chatModelBuilder()
                .temperature(0.3)
                .maxRetries(1)
                .build();
        log.info("🐾 宠物诊断 LLM 初始化完成 (model={})", aiChat.getModel());
    }

    // ==================== 图片诊断（微信端，图片已由预处理器描述过） ====================

    /**
     * 微信端图片诊断 — 图片描述已由 MessagePreprocessor 生成
     */
    public String diagnoseByImage(String userId, String petName, String imageDescription,
                                   String concern) {
        // 1. 查宠物档案
        PetEntity pet = petService.getOrCreate(userId, petName);
        String petInfo = buildPetContext(pet);

        // 2. 联网搜索
        String searchQuery = buildSearchQuery(pet, concern, imageDescription);
        String searchResults = searchWeb(searchQuery);

        // 3. LLM 综合分析
        String userPrompt = buildImageDiagnosisPrompt(petInfo, imageDescription, concern, searchResults);
        String rawReply = chatWithSystemPrompt(DIAGNOSIS_SYSTEM_PROMPT, userPrompt);

        // 4. 解析
        DiagnosisResult result = parseDiagnosis(rawReply);

        // 5. 存储
        PetDiagnosisEntity record = new PetDiagnosisEntity(userId, petName);
        record.setConcern(concern);
        record.setAiRawText(rawReply);
        record.setDiagnosis(result.diagnosis);
        record.setSuggestions(result.suggestions);
        record.setKeywords(result.keywords);
        diagnosisMapper.insert(record);

        // 6. 追加病史
        if (result.diagnosis != null && !result.diagnosis.contains("健康") && !result.diagnosis.contains("无法确定")) {
            petService.appendIllness(userId, petName, result.diagnosis, concern);
        }

        log.info("🐾 [{}] 图片诊断完成: {} → {}", userId, petName, result.diagnosis);
        // 新建宠物时提示
        if (pet.getSpecies() == null) {
            return rawReply + "\n\n---\n💡 " + petName + "的档案已自动创建。需要补充信息时告诉我即可。";
        }
        return rawReply;
    }

    /**
     * 桌面端图片诊断 — 传入原始图片字节，内部调 VisionService
     */
    public String diagnoseByImageBytes(String userId, String petName, byte[] imageBytes,
                                        String concern, String imageSavePath) {
        // 1. 图片识别（可选 SPI，未配置时优雅降级）
        PetImageRecognizer recognizer = imageRecognizerProvider.getIfUnique();
        if (recognizer == null) {
            return "⚠️ 图片识别服务未配置。请接入视觉识别能力，或使用纯文字症状自查。";
        }
        String imageDescription = recognizer.describe(imageBytes);
        if (imageDescription == null || imageDescription.isBlank()) {
            return "⚠️ 图片识别失败，请确认图片清晰可见后重试。";
        }

        // 2. 查宠物档案
        PetEntity pet = petService.getOrCreate(userId, petName);
        String petInfo = buildPetContext(pet);

        // 3. 联网搜索
        String searchQuery = buildSearchQuery(pet, concern, imageDescription);
        String searchResults = searchWeb(searchQuery);

        // 4. LLM 分析（使用 system prompt + user message）
        String rawReply = chatWithSystemPrompt(DIAGNOSIS_SYSTEM_PROMPT,
                buildImageDiagnosisPrompt(petInfo, imageDescription, concern, searchResults));

        // 5. 解析
        DiagnosisResult result = parseDiagnosis(rawReply);

        // 6. 存储
        PetDiagnosisEntity record = new PetDiagnosisEntity(userId, petName);
        record.setImageUrl(imageSavePath);
        record.setConcern(concern);
        record.setAiRawText(rawReply);
        record.setDiagnosis(result.diagnosis);
        record.setSuggestions(result.suggestions);
        record.setKeywords(result.keywords);
        diagnosisMapper.insert(record);

        // 7. 追加病史
        if (result.diagnosis != null && !result.diagnosis.contains("健康") && !result.diagnosis.contains("无法确定")) {
            petService.appendIllness(userId, petName, result.diagnosis, concern);
        }

        log.info("🐾 [{}] 桌面端图片诊断完成: {} → {}", userId, petName, result.diagnosis);
        return rawReply;
    }

    // ==================== 纯文字症状自查 ====================

    public String checkSymptom(String userId, String petName, String symptoms) {
        PetEntity pet = petService.getOrCreate(userId, petName);
        String petInfo = buildPetContext(pet);

        String userPrompt = buildSymptomCheckPrompt(petInfo, symptoms);
        String rawReply = chatWithSystemPrompt(DIAGNOSIS_SYSTEM_PROMPT, userPrompt);

        DiagnosisResult result = parseDiagnosis(rawReply);

        PetDiagnosisEntity record = new PetDiagnosisEntity(userId, petName);
        record.setConcern(symptoms);
        record.setAiRawText(rawReply);
        record.setDiagnosis(result.diagnosis);
        record.setSuggestions(result.suggestions);
        record.setKeywords(result.keywords);
        diagnosisMapper.insert(record);

        if (result.diagnosis != null && !result.diagnosis.contains("健康")
                && !result.diagnosis.contains("无法确定")) {
            petService.appendIllness(userId, petName, result.diagnosis, symptoms);
        }

        // 如果是新创建的宠物，提示可补充信息
        StringBuilder reply = new StringBuilder(rawReply);
        if (pet.getSpecies() == null) {
            reply.append("\n\n---\n💡 ").append(petName).append("的档案已自动创建。你可以补充信息，如'").append(petName).append("是金毛，3岁'");
        }
        return reply.toString();
    }

    // ==================== 诊断历史查询 ====================

    public List<PetDiagnosisEntity> getHistory(String userId, String petName) {
        if (petName != null && !petName.isBlank()) {
            return diagnosisMapper.findByUserIdAndPetName(userId, petName);
        }
        return diagnosisMapper.findByUserId(userId);
    }

    public String formatHistory(String userId, String petName) {
        List<PetDiagnosisEntity> records = getHistory(userId, petName);
        if (records.isEmpty()) {
            return "📋 " + (petName != null ? petName : "您的宠物") + "暂无诊断记录。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📋 ").append(petName != null ? petName : "全部宠物").append(" 诊断记录：\n\n");
        for (int i = 0; i < Math.min(records.size(), 10); i++) {
            PetDiagnosisEntity r = records.get(i);
            sb.append("【").append(i + 1).append("】");
            sb.append(formatDate(r.getCreatedAt())).append("\n");
            if (r.getConcern() != null && !r.getConcern().isBlank()) {
                sb.append("  主诉: ").append(r.getConcern()).append("\n");
            }
            if (r.getDiagnosis() != null && !r.getDiagnosis().isBlank()) {
                sb.append("  诊断: ").append(r.getDiagnosis()).append("\n");
            }
            sb.append("\n");
        }
        if (records.size() > 10) {
            sb.append("  ...共 ").append(records.size()).append(" 条记录");
        }
        return sb.toString();
    }

    // ==================== 内部方法 ====================

    private String buildPetContext(PetEntity pet) {
        if (pet == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("宠物名: ").append(pet.getPetName());
        if (pet.getSpecies() != null) sb.append(", 种类: ").append(pet.getSpecies());
        if (pet.getBreed() != null) sb.append(", 品种: ").append(pet.getBreed());
        if (pet.getGender() != null) sb.append(", 性别: ").append(pet.getGender());
        if (pet.getBirthDate() != null) sb.append(", 出生: ").append(pet.getBirthDate());
        if (pet.getWeightKg() != null) sb.append(", 体重: ").append(pet.getWeightKg()).append("kg");
        if (pet.getIllnesses() != null && !pet.getIllnesses().isBlank())
            sb.append(", 既往病史: ").append(pet.getIllnesses());
        return sb.toString();
    }

    private String buildSearchQuery(PetEntity pet, String concern, String imageDesc) {
        StringBuilder q = new StringBuilder();
        if (pet != null && pet.getBreed() != null) q.append(pet.getBreed());
        if (pet != null && pet.getSpecies() != null) q.append(pet.getSpecies());
        if (concern != null && !concern.isBlank()) q.append(" ").append(concern);
        q.append(" 症状 治疗");
        return q.toString().trim();
    }

    private String buildImageDiagnosisPrompt(String petInfo, String imageDesc,
                                              String concern, String searchResults) {
        return String.format("""
                【宠物信息】
                %s

                【图片描述（AI视觉识别）】
                %s

                【用户担忧】
                %s

                【联网搜索结果】
                %s

                请根据以上信息进行综合分析诊断。""",
                petInfo, imageDesc,
                concern != null ? concern : "未说明",
                searchResults != null ? searchResults : "无搜索结果");
    }

    private String buildSymptomCheckPrompt(String petInfo, String symptoms) {
        return String.format("""
                【宠物信息】
                %s

                【症状描述】
                %s

                请根据以上信息进行综合分析诊断。""", petInfo, symptoms);
    }

    /** 联网搜索（可选 SPI）：无实现或失败时返回占位文本，不阻断诊断主流程 */
    private String searchWeb(String query) {
        PetWebSearcher searcher = webSearcherProvider.getIfUnique();
        if (searcher == null) {
            log.warn("🐾 未配置联网搜索服务，跳过搜索");
            return "（未配置联网搜索）";
        }
        try {
            return searcher.search(query, 8);
        } catch (Exception e) {
            log.warn("🐾 联网搜索失败: {}", e.getMessage());
            return "（联网搜索失败: " + e.getMessage() + "）";
        }
    }

    /** 以 system prompt + user message 方式调用 LLM */
    private String chatWithSystemPrompt(String systemPrompt, String userMessage) {
        if (chatModel == null) {
            return "⚠️ 未配置 AI 诊断能力（缺少 AI_CHAT_API_KEY）。请配置后重试。";
        }
        var response = chatModel.chat(ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(userMessage)))
                .build());
        return response.aiMessage().text();
    }

    /** 解析 LLM 输出为结构化字段 */
    private DiagnosisResult parseDiagnosis(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new DiagnosisResult("无法确定", "", "");
        }

        String diagnosis = extractSection(rawText, "诊断结论");
        String suggestions = extractSection(rawText, "处理建议");
        if (suggestions.isEmpty()) suggestions = extractSection(rawText, "详细分析");

        // 提取关键词：宠物常见病症词汇
        List<String> kw = new ArrayList<>();
        String[] commonTerms = {"皮炎", "湿疹", "猫癣", "过敏", "耳螨", "寄生虫", "感染", "骨折",
                "腹泻", "呕吐", "感冒", "犬瘟", "细小", "猫瘟", "肾衰竭", "尿结石", "心脏病",
                "关节炎", "肥胖", "营养不良", "中毒", "焦虑", "肿瘤", "结膜炎", "口腔炎"};
        for (String term : commonTerms) {
            if (rawText.contains(term)) kw.add(term);
        }
        String keywords = String.join(",", kw);

        return new DiagnosisResult(diagnosis, suggestions, keywords);
    }

    /** 从 LLM 输出中提取指定段落 */
    private String extractSection(String text, String sectionName) {
        // 匹配 【sectionName】... 到下一个【 或文本结束
        Pattern p = Pattern.compile("【" + sectionName + "】\\s*([\\s\\S]*?)(?=【|$)");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(1).trim().replaceAll("\\n{3,}", "\n\n");
        }
        return "";
    }

    private String formatDate(LocalDateTime dt) {
        if (dt == null) return "";
        return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    // ==================== SPI 接口（可选，解耦外部服务） ====================

    /** 图片识别 SPI：实现类接入视觉识别服务（如 Qwen-VL）。未配置时图片诊断返回降级提示。 */
    @FunctionalInterface
    public static  interface PetImageRecognizer {
        String describe(byte[] imageBytes);
    }

    /** 联网搜索 SPI：实现类接入搜索服务（如百度千帆）。未配置时诊断跳过搜索。 */
    @FunctionalInterface
    public interface PetWebSearcher {
        String search(String query, int count);
    }

    // ==================== 数据模型 ====================

    public record DiagnosisResult(String diagnosis, String suggestions, String keywords) {}
}
