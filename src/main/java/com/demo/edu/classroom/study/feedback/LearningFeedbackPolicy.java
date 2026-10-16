package com.demo.edu.classroom.study.feedback;

import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.Instant;

/**
 * 学习反馈的来源边界：M003 第10页§5.3建议先重构、核对来源、再独立评价理解。
 * 该建议是设计借鉴，不是由论文证明学生掌握度；因此没有可信资料的评价必须保持未确定。
 */
final class LearningFeedbackPolicy {
    private static final Set<String> STATES = Set.of("covered", "missing", "conflict", "uncertain");
    private LearningFeedbackPolicy() {}

    static ObjectNode checkedAnalysis(JsonNode value, ClassroomStudyContext.Snapshot context, ObjectMapper json) {
        if (!value.isObject() || !value.path("analysis").isArray() || value.path("analysis").isEmpty()
                || value.path("analysis").size() > 12) throw invalid("analysis必须包含1至12个核对点");
        ObjectNode clean = json.createObjectNode();
        ObjectNode sources = checkedSourceAssessment(value.path("sourceAssessment"), context, json);
        boolean confirmedSource = sources.path("status").asText().equals("consistent");
        clean.set("sourceAssessment", sources);
        clean.put("summary", confirmedSource ? text(value, "summary", 1200) : sourceUncertainty(sources));
        ArrayNode analysis = clean.putArray("analysis"), goals = clean.putArray("suggestedGoals");
        Set<String> seen = new HashSet<>();
        for (JsonNode item : value.path("analysis")) {
            String goal = text(item, "goal", 200), state = text(item, "status", 20);
            if (!STATES.contains(state) || !seen.add(goal)) throw invalid("核对状态或重复目标无效");
            // 资料甲与乙互相矛盾时，不能因学生碰巧复述了其中一份就判covered。
            // 来源一致性是本节评价的共同前提；没有该前提，所有结论统一保守为uncertain。
            if (!confirmedSource) state = "uncertain";
            ObjectNode entry = analysis.addObject().put("goal", goal).put("status", state)
                    .put("feedback", confirmedSource ? text(item, "feedback", 1000) : sourceUncertainty(sources));
            boolean supplied = item.hasNonNull("anchorId") || item.hasNonNull("quote");
            if (!state.equals("uncertain") || supplied) {
                var anchor = verifiedAnchor(item, context);
                entry.put("anchorId", anchor.id()).put("quote", text(item, "quote", 600));
            }
            if (state.equals("missing") || state.equals("conflict")) goals.add(goal);
        }
        return clean;
    }

    /** 冲突证据至少包含两段不同真实原文，可来自同一锚点的不同句子。 */
    static ObjectNode checkedSourceAssessment(JsonNode assessment, ClassroomStudyContext.Snapshot context, ObjectMapper json) {
        if(!context.sourceComplete()) {
            ObjectNode incomplete=json.createObjectNode().put("status","insufficient")
                .put("reason","本次资料未完整进入核对范围："+String.join("；",context.incompleteSources())+"。请缩小资料范围后再核对。");
            incomplete.putArray("evidence");return incomplete;
        }
        String status = text(assessment, "status", 30);
        if (!Set.of("consistent", "conflicting", "insufficient").contains(status)) throw invalid("资料一致性状态无效");
        ObjectNode clean = json.createObjectNode().put("status", status).put("reason", text(assessment, "reason", 1000));
        JsonNode evidence = assessment.path("evidence");
        if (!evidence.isArray() || evidence.size() > 8) throw invalid("资料一致性证据必须为0至8条引用");
        ArrayNode references = clean.putArray("evidence");
        Set<String> distinct = new HashSet<>();
        for (JsonNode item : evidence) {
            var anchor = verifiedAnchor(item, context);
            String quote = text(item, "quote", 600), identity = ClassroomStudyContext.normalize(quote);
            if (!distinct.add(identity)) throw invalid("资料一致性证据不能重复");
            references.addObject().put("anchorId", anchor.id()).put("quote", quote);
        }
        if (status.equals("conflicting") && distinct.size() < 2) throw invalid("来源冲突必须给出至少两段不同真实引用");
        if (status.equals("consistent") && distinct.isEmpty()) throw invalid("来源一致必须至少引用一段当前事实资料");
        return clean;
    }

    static String sourceUncertainty(JsonNode assessment) {
        return (assessment.path("status").asText().equals("conflicting")
                ? "当前资料存在相互矛盾的说法，请先核对资料，暂不判断表述对错。"
                : "当前资料不足以支持确定结论，请先补充或核对资料，暂不判断表述对错。")
                + assessment.path("reason").asText();
    }

    /** 主题和学习目标不是事实证据，不能仅据其判定学生漏掉了某个知识结论。 */
    static ClassroomStudyContext.Anchor verifiedAnchor(JsonNode value, ClassroomStudyContext.Snapshot context) {
        String id = text(value, "anchorId", 100), quote = text(value, "quote", 600);
        var anchor = context.anchors().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> invalid("引用不属于当前资料"));
        if (!Set.of("reference", "lesson").contains(anchor.kind())) throw invalid("主题和目标不能充当知识事实来源");
        String normalized = ClassroomStudyContext.normalize(quote);
        if (normalized.codePointCount(0, normalized.length()) < 2
                || !ClassroomStudyContext.normalize(anchor.text()).contains(normalized))
            throw invalid("quote必须是当前锚点的真实连续原文");
        return anchor;
    }

    static ObjectNode checkedExplanation(JsonNode value, ClassroomStudyContext.Snapshot context, ObjectMapper json) {
        ObjectNode assessment = checkedSourceAssessment(value.path("sourceAssessment"), context, json);
        if (!assessment.path("status").asText().equals("consistent"))
            return json.createObjectNode().put("blocked", true).set("sourceAssessment", assessment);
        var anchor = verifiedAnchor(value, context);
        return json.createObjectNode().put("text", text(value, "text", 1500))
                .put("anchorId", anchor.id()).put("quote", text(value, "quote", 600)).set("sourceAssessment", assessment);
    }

    /**
     * 独立自检使用现有四类客观判分，避免触发另一条无45秒总预算的简答阅卷链。
     * 自主表述本身已由有界模型进行语义核对；原课堂五题型能力保持不变。
     */
    static ObjectNode checkedQuestion(JsonNode value, ClassroomStudyContext.Snapshot context, ObjectMapper json) {
        ObjectNode assessment = checkedSourceAssessment(value.path("sourceAssessment"), context, json);
        if (!assessment.path("status").asText().equals("consistent"))
            return json.createObjectNode().put("blocked", true).set("sourceAssessment", assessment);
        verifiedAnchor(value, context);
        JsonNode raw = value.path("question");
        String type = text(raw, "type", 30);
        if (!Set.of("single", "multiple", "judge", "fill").contains(type)) throw invalid("自检使用单选、多选、判断或填空");
        ObjectNode question = json.createObjectNode().put("type", type)
                .put("question", text(raw, "question", 2000)).put("analysis", text(raw, "analysis", 1500));
        JsonNode answer = raw.path("answer");
        if (type.equals("single") || type.equals("multiple")) {
            JsonNode options = raw.path("options");
            if (!options.isArray() || options.size() < 2 || options.size() > 6) throw invalid("自检选项必须为2至6项");
            ArrayNode cleanOptions = question.putArray("options");
            Set<String> allowed = new HashSet<>();
            for (int i = 0; i < options.size(); i++) {
                String label = text(options.get(i), "label", 600), key = text(options.get(i), "value", 1);
                if (!key.equals(Character.toString('A' + i))) throw invalid("自检选项按A、B、C连续排列");
                cleanOptions.addObject().put("label", label).put("value", key); allowed.add(key);
            }
            if (!answer.isArray() || answer.isEmpty() || type.equals("single") && answer.size() != 1)
                throw invalid("自检选择题答案无效");
            Set<String> chosen = new HashSet<>();
            for (JsonNode key : answer) if (!key.isTextual() || !allowed.contains(key.asText()) || !chosen.add(key.asText()))
                throw invalid("自检答案未对应有效选项");
        } else if (type.equals("judge")) {
            if (!(answer.isBoolean() || answer.isTextual() && Set.of("对", "错").contains(answer.asText())))
                throw invalid("判断题答案必须为对或错");
        } else {
            if (!(answer.isNumber() || answer.isTextual()) || answer.asText().isBlank() || answer.asText().length() > 2000)
                throw invalid("自检参考答案不能为空");
        }
        question.set("answer", answer.deepCopy());
        question.set("sourceAssessment", assessment);
        return question;
    }

    static String text(JsonNode node, String key, int max) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().codePointCount(0, value.asText().length()) > max)
            throw invalid("字段" + key + "不能为空或超过上限");
        return value.asText().strip();
    }

    static ObjectNode project(ObjectNode source, ObjectMapper json) {
        ObjectNode result = json.createObjectNode();
        for (String key : List.of("id", "courseId", "sceneId", "contentFingerprint", "parentAttemptId", "understanding",
                "checkPoint", "createdAt", "status", "analysis", "summary", "hints", "hintLevel", "suggestedGoals", "error", "sourceAssessment"))
            if (source.has(key)) result.set(key, source.path(key).deepCopy());
        if (source.path("status").asText().equals("checking")) {
            Instant started;
            try { started = Instant.parse(source.path("_checkStartedAt").asText()); }
            catch (RuntimeException invalidTime) { started = Instant.EPOCH; }
            // 45秒调用预算之外再留15秒落库余量。重启遗留状态只提示手动重试，不自动重发模型请求。
            if (started.plusSeconds(60).isBefore(Instant.now())) result.put("status", "unconfirmed")
                    .put("error", "上次核对尚未确认，原表述已保留，可以重新核对");
        }
        JsonNode question = source.path("_selfCheckScene").path("content").path("questions").path(0);
        if (question.isObject()) {
            ObjectNode visible = result.putObject("selfCheck");
            for (String key : List.of("id", "type", "question", "options")) if (question.has(key)) visible.set(key, question.path(key).deepCopy());
            visible.put("hasAnswer", true);
        }
        if (source.path("_pendingSubmission").isObject()) {
            result.put("selfCheckSubmissionId", source.path("_pendingSubmission").path("id").asText());
            result.put("selfCheckAnswer", source.path("_pendingSubmission").path("answer").asText());
        }
        if (source.has("selfCheckResult")) {
            JsonNode stored = source.path("selfCheckResult");
            ObjectNode visible = result.putObject("selfCheckResult");
            visible.set("correct", stored.path("correct").deepCopy());
            visible.put("explanation", stored.path("explanation").asText());
            // 未确定不等于作答已完成；仅事务提交的布尔判分结果可以公开标答。
            if (stored.path("correct").isBoolean() && source.path("_submissionResult").isObject()
                    && stored.path("correctAnswer").isTextual()) visible.set("correctAnswer", stored.path("correctAnswer"));
        }
        return result;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
