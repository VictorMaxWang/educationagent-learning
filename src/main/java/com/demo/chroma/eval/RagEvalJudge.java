package com.demo.chroma.eval;

import com.demo.config.AiChatProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * RAG 评估裁判：用统一文本模型完成答案生成与三项指标打分。
 *
 * <p>裁判保留独立实例，以便设置评估所需的温度、超时与重试策略；
 * 连接地址、密钥、模型和思考开关则由 {@link AiChatProperties} 统一提供，
 * 防止更换主模型后评估任务仍访问旧供应商。</p>
 *
 * <p><b>为什么不开 response_format=json_object</b>：项目内零先例，而 DeepSeek 的
 * json_object 模式要求 prompt 必须含 "json" 字样并给出结构示例，否则可能返回空 content ——
 * 空 content 会让整轮评估全部 FAILED。所以沿用项目通行的「prompt 写死格式 + cleanJson 兜底」，
 * 不把无法预先验证的依赖放进关键路径。</p>
 */
@Slf4j
@Service
public class RagEvalJudge {

    @Resource
    private AiChatProperties aiChat;

    /** 裁判温度：固定 0，保证同一份输入打出的分可复现、跨轮次可比 */
    @Value("${rag.eval.judge-temperature:0}")
    private Double judgeTemperature;

    @Value("${rag.eval.answer-temperature:0}")
    private Double answerTemperature;

    /** 评估是后台任务，超时给得比在线链路宽松（在线用的是 60s 的 interruptible 客户端） */
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    /** 拼进 prompt 的检索资料总长上限，防止超长 prompt 拖慢/截断 */
    private static final int MAX_CONTEXT_CHARS = 8000;

    /** 落库的裁判原始 JSON 上限 */
    private static final int MAX_RAW_JSON_CHARS = 4000;

    /**
     * 裁判 prompt 版本。**改动下面任一 prompt 都必须 bump 这个值** ——
     * 分数只在同一 prompt 版本内可比，混在一起画趋势图会得出错误结论。
     */
    public static final String PROMPT_VERSION = "v1";

    @Resource
    private ObjectMapper objectMapper;

    private OpenAiChatModel answerModel;
    private OpenAiChatModel judgeModel;

    @PostConstruct
    public void init() {
        this.answerModel = buildModel(answerTemperature);
        this.judgeModel = buildModel(judgeTemperature);
        log.info("📊 RAG 评估裁判已就绪: model={} 裁判温度={} 生成温度={}",
                aiChat.getModel(), judgeTemperature, answerTemperature);
    }

    private OpenAiChatModel buildModel(Double temperature) {
        return aiChat.chatModelBuilder()
                .temperature(temperature)
                .maxRetries(1)
                .timeout(TIMEOUT)
                .build();
    }

    // ======================== 答案生成 ========================

    /**
     * 严格基于检索到的 context 生成答案。
     *
     * <p>「资料不足就直说未提及」这条约束是 faithfulness 有意义的前提 ——
     * 否则模型会拿参数化知识作答，faithfulness 恒低，指标就失去区分度。</p>
     *
     * @return 生成的答案；调用失败返回 null（调用方据此记 FAILED，而不是塞一个空串）
     */
    public String generateAnswer(String question, List<String> contexts) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是严格依据给定资料作答的助手。\n\n");
        prompt.append("【资料】\n").append(formatContexts(contexts)).append("\n\n");
        prompt.append("【问题】\n").append(safe(question)).append("\n\n");
        prompt.append("要求：\n");
        prompt.append("1. 只依据上面的资料回答，不要使用资料之外的任何知识。\n");
        prompt.append("2. 如果资料中没有足够信息回答问题，直接回答「资料中未提及」，不要猜测、不要编造。\n");
        prompt.append("3. 回答简洁准确，不超过 200 字。\n");

        try {
            ChatResponse resp = answerModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from("你是严谨的知识问答助手，只依据给定资料作答。"),
                            UserMessage.from(prompt.toString())))
                    .build());
            String text = resp.aiMessage().text();
            if (text == null || text.isBlank()) {
                log.warn("📊 生成答案返回空内容");
                return null;
            }
            return text.trim();
        } catch (Exception e) {
            log.warn("📊 生成答案失败: {}", e.getMessage());
            return null;
        }
    }

    /** 当前裁判模型名，落库到 rag_eval_report.judge_model */
    public String getModelName() {
        return aiChat.getModel();
    }

    // ======================== 黄金集反向出题 ========================

    /**
     * 从一段 chunk 反向生成「测试问题 + 标准答案」。
     *
     * <p>要求问题「能且仅能」由这段资料回答 —— 否则这条例并不检验这段 chunk 的召回，
     * contextRecall 就失去意义。</p>
     *
     * @return 生成的用例；调用或解析失败返回 null（调用方跳过这条，不影响整体）
     */
    public GoldenCase generateCaseFromChunk(String chunk) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("下面是一段知识库资料。请基于这段资料出一个测试问题，并给出标准答案。\n\n");
        prompt.append("【资料】\n").append(safe(chunk)).append("\n\n");
        prompt.append("要求：\n");
        prompt.append("1. 问题必须能且仅能由上面这段资料回答，不要问「资料里说了什么」这类笼统问题。\n");
        prompt.append("2. 问题要具体、有明确答案，适合用来检验检索系统能否召回这段资料。\n");
        prompt.append("3. 标准答案必须完整来自资料，不要引入资料之外的信息。\n");
        prompt.append("4. 问题不超过 50 字，标准答案不超过 150 字。\n");
        prompt.append("5. 只输出一个 JSON 对象，不要输出任何解释、Markdown 代码块或其他文字。格式：\n");
        prompt.append("{\"question\":\"...\",\"ground_truth\":\"...\"}");

        try {
            ChatResponse resp = answerModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from("你是严谨的测试用例设计者，只输出 JSON 对象。"),
                            UserMessage.from(prompt.toString())))
                    .build());
            String raw = resp.aiMessage().text();
            if (raw == null || raw.isBlank()) {
                log.warn("📊 反向出题返回空内容");
                return null;
            }
            JsonNode root = objectMapper.readTree(cleanJson(raw));
            String q = root.path("question").asText("");
            String a = root.path("ground_truth").asText("");
            if (q.isBlank() || a.isBlank()) {
                log.warn("📊 反向出题缺少字段: {}", truncate(raw, 200));
                return null;
            }
            return new GoldenCase(q.trim(), a.trim());
        } catch (Exception e) {
            log.warn("📊 反向出题失败: {}", e.getMessage());
            return null;
        }
    }

    // ======================== 三项指标裁判 ========================

    /**
     * 一次调用算出三项指标。
     *
     * <p>采用单次调用而非每指标独立评估：一个用例从 3 次裁判降为 1 次，整轮成本降约 2/3。
     * 代价是可能比 RAGAS 的三份独立 prompt 略有漂移 —— 但本模块的用途是
     * <b>跨轮次相对比较</b>（改分块策略后有没有变好），单一裁判协议的一致性比
     * 「每指标一套 prompt」更重要。靠 temperature=0 + 逐项给理由抑制漂移。</p>
     *
     * @return 三项指标；任何一项缺失或无法解析时返回 <b>null</b>，
     *         让调用方记为 FAILED —— 「没算出来」和「算出来是 0 分」必须严格区分，
     *         否则解析失败会被当成 0 分污染均值
     */
    public JudgeResult judge(String question, String groundTruth, List<String> contexts, String answer) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 RAG 系统的严格评估裁判。请针对给定的【问题】【标准答案】【检索资料】【待评答案】，")
                .append("计算三项指标，每项取值 0 到 1 之间的小数。\n\n");
        prompt.append("【问题】\n").append(safe(question)).append("\n\n");
        prompt.append("【标准答案】（作为事实基准）\n").append(safe(groundTruth)).append("\n\n");
        prompt.append("【检索资料】（RAG 检索回来的内容）\n").append(formatContexts(contexts)).append("\n\n");
        prompt.append("【待评答案】（需要被评估的答案）\n").append(safe(answer)).append("\n\n");
        prompt.append("评分口径（务必严格按此执行）：\n\n");
        prompt.append("1. context_recall：\n")
                .append("   第一步，把【标准答案】拆解成 3~8 条原子事实点，逐条写进 context_recall_facts 数组。")
                .append("拆分粒度必须稳定：一个独立可验证的事实算一条，不要合并成一条、也不要拆得过细。\n")
                .append("   第二步，逐条对照【检索资料】判断该事实点能否找到，把判断依据写进 context_recall_reason。\n")
                .append("   第三步，context_recall = 命中事实点数 / 事实点总数。资料完全没有覆盖标准答案时记 0。\n\n");
        prompt.append("2. faithfulness：\n")
                .append("   把【待评答案】拆解成若干独立陈述，逐条判断该陈述是否被【检索资料】支撑，")
                .append("把判断依据写进 faithfulness_reason。\n")
                .append("   得分 = 被资料支撑的陈述数 / 陈述总数。")
                .append("答案中出现资料里没有的具体信息（数字、名称、结论）就算「不被支撑」，")
                .append("不能因为「听起来合理」而放过。\n")
                .append("   注意：若【待评答案】是「资料中未提及」这类拒答，说明生成器没用好已检索到的资料，")
                .append("faithfulness 记 0.2。\n\n");
        prompt.append("3. factual_correctness：\n")
                .append("   【待评答案】与【标准答案】的事实一致程度，判断依据写进 factual_correctness_reason。\n")
                .append("   完全一致记 1.0；部分遗漏或轻微偏差按比例扣分；")
                .append("出现与标准答案矛盾的事实、或编造标准答案中没有的关键信息，扣到 0.5 以下。\n\n");
        prompt.append("每个指标的 reason 用不超过 50 字说明打分依据。\n");
        prompt.append("先写理由、再给出分数（不要先定分数再补理由）。\n\n");
        prompt.append("只输出一个 JSON 对象，不要输出任何解释、Markdown 代码块或其他文字。格式：\n");
        prompt.append("{\"context_recall_facts\":[\"事实1\",\"事实2\"],")
                .append("\"context_recall_reason\":\"...\",\"context_recall\":0.0,")
                .append("\"faithfulness_reason\":\"...\",\"faithfulness\":0.0,")
                .append("\"factual_correctness_reason\":\"...\",\"factual_correctness\":0.0}");

        try {
            ChatResponse resp = judgeModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from("你是严格的 RAG 评估裁判，只输出 JSON 对象。"),
                            UserMessage.from(prompt.toString())))
                    .build());
            String raw = resp.aiMessage().text();
            if (raw == null || raw.isBlank()) {
                log.warn("📊 裁判返回空内容");
                return null;
            }
            return parseJudgeResult(raw);
        } catch (Exception e) {
            log.warn("📊 裁判调用失败: {}", e.getMessage());
            return null;
        }
    }

    /** 解析裁判返回的 JSON；三项指标缺任一则视为解析失败 */
    private JudgeResult parseJudgeResult(String raw) {
        try {
            JsonNode root = objectMapper.readTree(cleanJson(raw));
            if (!root.has("context_recall") || !root.has("faithfulness") || !root.has("factual_correctness")) {
                log.warn("📊 裁判返回缺少指标字段: {}", truncate(raw, 200));
                return null;
            }
            JudgeResult r = new JudgeResult();
            r.contextRecall = clamp(root.path("context_recall").asDouble());
            r.faithfulness = clamp(root.path("faithfulness").asDouble());
            r.factualCorrectness = clamp(root.path("factual_correctness").asDouble());
            // 事实点数组只做审计用途：缺失时告警但不判失败 —— 硬性要求会让整轮评估
            // 因一个格式细节全挂，代价大于收益
            JsonNode facts = root.path("context_recall_facts");
            if (!facts.isArray() || facts.isEmpty()) {
                log.warn("📊 裁判未给出 context_recall_facts，context_recall 分母不可审计");
            }
            r.rawJson = truncate(cleanJson(raw), MAX_RAW_JSON_CHARS);
            return r;
        } catch (Exception e) {
            log.warn("📊 裁判结果解析失败: {}，原始内容={}", e.getMessage(), truncate(raw, 200));
            return null;
        }
    }

    /** 把分数夹到 [0,1]：模型偶尔会给出 1.2 或 -0.1 */
    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 检索资料编号列出；总长超限时截断，避免超长 prompt */
    private String formatContexts(List<String> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "（本次检索没有返回任何资料）";
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < contexts.size(); i++) {
            String c = safe(contexts.get(i));
            if (used + c.length() > MAX_CONTEXT_CHARS) {
                sb.append(i + 1).append(". （资料过长已截断）\n");
                break;
            }
            sb.append(i + 1).append(". ").append(c).append("\n");
            used += c.length();
        }
        return sb.toString();
    }

    /**
     * 剥离 LLM 夹带的前后自然文本与 Markdown 代码块，只留 JSON 对象。
     * 抄 travel/OutfitService 的同名实现（项目里没有公共工具类）。
     */
    private static String cleanJson(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "");
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }
        return s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ======================== 结果载体 ========================

    /** 三项指标 + 裁判原始 JSON */
    public static class JudgeResult {
        public double contextRecall;
        public double faithfulness;
        public double factualCorrectness;
        public String rawJson;
    }

    /** 反向生成的黄金集用例 */
    public static class GoldenCase {
        public final String question;
        public final String groundTruth;

        public GoldenCase(String question, String groundTruth) {
            this.question = question;
            this.groundTruth = groundTruth;
        }
    }
}
