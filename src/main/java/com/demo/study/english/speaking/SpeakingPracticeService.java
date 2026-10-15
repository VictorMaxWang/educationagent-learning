package com.demo.study.english.speaking;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 口语练习服务。
 * <p>
 * 预设练习文本（单词/短语/句子跟读）→ 用户录音 → 调讯飞 ISE 评测 → DeepSeek 生成结构化反馈。
 * 强制规则：必须有评测结果才给反馈，禁止凭空判断发音好坏。
 */
@Slf4j
@Service
public class SpeakingPracticeService {

    @Resource
    private XfyunIseClient iseClient;

    @Resource
    private OpenAiChatModel deepseekChatModel;

    /** 预设口语练习文本 */
    private static final List<Map<String, String>> PRESET = List.of(
            Map.of("text", "apple", "type", "单词"),
            Map.of("text", "beautiful", "type", "单词"),
            Map.of("text", "pronunciation", "type", "单词"),
            Map.of("text", "comfortable", "type", "单词"),
            Map.of("text", "experience", "type", "单词"),
            Map.of("text", "Nice to meet you.", "type", "句子"),
            Map.of("text", "How are you doing today?", "type", "句子"),
            Map.of("text", "I would like a cup of coffee.", "type", "句子"),
            Map.of("text", "Could you help me with this?", "type", "句子"),
            Map.of("text", "It's a beautiful day, isn't it?", "type", "句子"),
            Map.of("text", "Please give me a hand.", "type", "句子"),
            Map.of("text", "Thank you very much for your help.", "type", "句子"),
            Map.of("text", "I'm looking forward to seeing you.", "type", "句子"),
            Map.of("text", "Let's go for a walk after dinner.", "type", "句子")
    );

    /** 预设练习文本列表 */
    public List<Map<String, String>> presetTexts() {
        return PRESET;
    }

    /**
     * 情景对话：LLM 生成双人对话剧本（A=对方 / B=用户要跟读）。
     * length 控制篇幅：short=6 句 / standard=8 句 / long=16 句长篇完整对话。
     * 每句 B 台词配合 /speech-evaluate 逐句评测。
     */
    public Map<String, Object> dialogue(String scenario, String length) {
        String lengthDesc = switch (length == null ? "standard" : length) {
            case "short" -> "共 6 句左右、简短直接";
            case "long" -> "共 16 句左右、情节完整的长篇对话，包含开场问候、核心交流推进、结尾收尾，逻辑连贯";
            default -> "共 8 句左右、有来有回";
        };
        String prompt = "请生成一段「" + scenario + "」的英文情景对话，用于口语跟读练习。"
                + "两个角色：A（对方）、B（用户扮演，需要跟读的台词），" + lengthDesc + "。"
                + "每句字段：role（'A' 或 'B'）、text（英文句子）、cn（该句简短中文意思）。"
                + "严格只输出 JSON 对象：{title: 中文标题, scenario: 场景, lines: [{role, text, cn}]}。不要输出任何其他文字。";
        try {
            ChatResponse resp = deepseekChatModel.chat(ChatRequest.builder()
                    .messages(List.of(SystemMessage.from("你是英语口语教材作者，严格按 JSON 输出。"), UserMessage.from(prompt)))
                    .build());
            JSONObject obj = JSON.parseObject(extractJsonObject(resp.aiMessage().text()));
            if (obj == null) {
                return Map.of("code", 500, "msg", "情景对话生成失败，请重试");
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", 200);
            r.put("title", obj.getString("title"));
            r.put("scenario", scenario);
            JSONArray lines = obj.getJSONArray("lines");
            List<Map<String, Object>> lineList = new ArrayList<>();
            if (lines != null) {
                for (Object o : lines) {
                    JSONObject lo = (JSONObject) o;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", lo.getString("role"));
                    m.put("text", lo.getString("text"));
                    m.put("cn", lo.getString("cn"));
                    lineList.add(m);
                }
            }
            r.put("lines", lineList);
            return r;
        } catch (Exception e) {
            log.error("情景对话生成失败", e);
            return Map.of("code", 500, "msg", "情景对话生成失败：" + e.getMessage());
        }
    }

    /** 从 LLM 文本中提取 JSON 对象 */
    private String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }

    /**
     * 口语评测：必调讯飞 ISE，再用 LLM 生成反馈。
     *
     * @param text        目标英文文本
     * @param audioBase64 用户录音（16kHz PCM 的 base64）
     */
    public Map<String, Object> evaluate(String text, String audioBase64) {
        String category = (text == null || text.trim().contains(" ")) ? "read_sentence" : "read_word";
        System.out.println("[Speaking] ===== 口语评测 ===== text=" + text + " category=" + category);

        JSONObject iseResult = iseClient.evaluate(text, audioBase64, category);
        System.out.println("[Speaking] 讯飞评测完成");

        int score = 0;
        JSONObject scores = iseResult.getJSONObject("scores");
        if (scores != null) score = scores.getIntValue("total");
        boolean rejected = scores != null && scores.getBooleanValue("rejected");
        int rejectType = scores != null ? scores.getIntValue("rejectType") : 0;
        double acc = scores != null ? scores.getDoubleValue("accuracy") : 0;
        double flu = scores != null ? scores.getDoubleValue("fluency") : 0;
        double intg = scores != null ? scores.getDoubleValue("integrity") : 0;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> perWord = scores != null
                ? (List<Map<String, Object>>) scores.get("perWord") : null;
        if (perWord == null) perWord = List.of();

        // 引擎拒绝本次录音（过短/过安静/背景噪音等）：不给误导性分数，提示重新录音
        if (rejected) {
            System.out.println("[Speaking] ⚠️ 引擎拒绝评测 reject_type=" + rejectType + "，返回重录提示");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", 200);
            r.put("rejected", true);
            r.put("rejectType", rejectType);
            r.put("score", score);
            r.put("text", text);
            r.put("category", category);
            r.put("msg", "⚠️ 本次录音未被评测通过（" + rejectReason(rejectType) + "），请调整后重新录音");
            r.put("raw", iseResult.toJSONString());
            return r;
        }

        // LLM 基于讯飞完整结构化数据做「人话版」讲解，不重新打分
        String feedback = llmFeedback(text, iseResult, score, acc, flu, intg, perWord);
        System.out.println("[Speaking] LLM 人话讲解生成完成, 总分=" + score);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("text", text);
        r.put("score", score);
        r.put("category", category);
        r.put("scores", Map.of("total", score, "accuracy", acc, "fluency", flu, "integrity", intg));
        r.put("perWord", perWord);
        r.put("feedback", feedback);
        r.put("raw", iseResult.toJSONString());
        return r;
    }

    /** reject_type 位掩码 → 友好原因（常见位） */
    private String rejectReason(int type) {
        return switch (type) {
            case 1 -> "音量太小";
            case 2 -> "有效语音太短";
            case 4 -> "背景噪音过大";
            case 8 -> "未按文本朗读";
            case 16 -> "检测到静音";
            case 32 -> "断句过短";
            case 64 -> "音频格式异常";
            default -> "评测超时/音频异常(reject_type=" + type + ")";
        };
    }

    /**
     * DeepSeek 基于讯飞完整结构化数据生成「人话版」发音教学讲解。
     * 分数一律引用讯飞（总分 + 三维度 + 逐词），LLM 只负责翻译成通俗讲解，绝不重新打分。
     */
    private String llmFeedback(String text, JSONObject iseResult, int score,
                               double acc, double flu, double intg,
                               List<Map<String, Object>> perWord) {
        String detail = iseResult.getString("raw_xml");
        if (detail == null) detail = iseResult.toJSONString();

        StringBuilder structured = new StringBuilder();
        structured.append("总分(0-100): ").append(score).append("\n");
        structured.append("三维度(0-5): 准确度=").append(fmt(acc)).append(" 流利度=").append(fmt(flu))
                .append(" 完整度=").append(fmt(intg)).append("\n");
        if (perWord != null && !perWord.isEmpty()) {
            structured.append("逐词得分（数值越大越好）:\n");
            for (Map<String, Object> w : perWord) {
                structured.append("  - ").append(w.get("word"))
                        .append(" 准确=").append(w.get("accuracy"))
                        .append(" 流利=").append(w.get("fluency"))
                        .append(" 完整=").append(w.get("integrity"));
                Object t = w.get("total");
                if (t != null && ((Number) t).doubleValue() > 0) structured.append(" 总分=").append(t);
                structured.append("\n");
            }
        }

        String prompt = "你是英语口语教练。以下是讯飞 ISE 的权威评测数据"
                + "（所有分数来自讯飞，你只负责用大白话讲解，绝对不能自己生成或修改分数）：\n"
                + "【评测文本】" + text + "\n"
                + "【结构化数据】\n" + structured
                + "\n【完整原始数据（含逐词与音素级正误明细，供你定位具体发音问题）】\n"
                + truncate(detail, 6000)
                + "\n\n请输出一段「人话版」发音教学讲解：\n"
                + "1. 总体评价：一句话，基于讯飞总分和三维度说清水平\n"
                + "2. 逐词讲解：重点讲得分偏低的词，逐个用大白话说明：哪个音素/重音/连读有问题、"
                + "正确怎么读（可给音标/近似中文谐音/口型要点）、一句正确示范；读得好的词一句话带过\n"
                + "3. 最容易犯的 1 个整体问题 + 立刻能练的 1-2 个技巧\n"
                + "4. 一句鼓励 + 一句模仿短句\n"
                + "要求：语气温和具体、避免空话套话；涉及分数时只引用讯飞数据，禁止编造分数。";
        try {
            ChatResponse resp = deepseekChatModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from("你是英语口语教练，严格按要求的讲解结构输出。"),
                            UserMessage.from(prompt)))
                    .build());
            return resp.aiMessage().text();
        } catch (Exception e) {
            log.error("口语讲解生成失败", e);
            return "得分 " + score + " 分。详细讲解生成失败，请查看评测明细。";
        }
    }

    /** double 保留两位小数 */
    private String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    /** 长文本截断 */
    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
