package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;

/** 对生成器边界再次校验。TypeScript 类型声明不能验证实际模型输出。 */
final class ClassroomDocumentValidator {
    private static final Set<String> TYPES = Set.of("slide", "quiz", "interactive");
    private ClassroomDocumentValidator() {}

    static ArrayNode outlines(JsonNode input) {
        if (input == null || !input.isArray() || input.size() < 4 || input.size() > 8)
            throw invalid("大纲必须包含 4–8 个学习步骤");
        Set<String> ids = new HashSet<>();
        ArrayNode result = ((ArrayNode) input).deepCopy();
        for (int i = 0; i < result.size(); i++) {
            JsonNode n = result.get(i);
            if (!n.isObject()) throw invalid("大纲步骤格式无效");
            text(n, "id", 80); text(n, "title", 160);
            if (!ids.add(n.path("id").asText())) throw invalid("大纲步骤 ID 重复");
            if (!TYPES.contains(n.path("type").asText())) throw invalid("只支持图文、练习和互动步骤");
            if (n.toString().length() > 12000) throw invalid("单个大纲步骤过长");
            ((ObjectNode) n).put("order", i);
        }
        return result;
    }

    static ObjectNode scene(ObjectNode scene, JsonNode outline, String courseId) {
        if (scene == null || scene.toString().length() > 1_500_000) throw invalid("课堂页面为空或过大");
        text(scene, "title", 200);
        String type = scene.path("type").asText();
        if (!type.equals(outline.path("type").asText()) || !TYPES.contains(type)) throw invalid("课堂页面类型与大纲不一致");
        JsonNode content = scene.path("content");
        if (!content.isObject() || !type.equals(content.path("type").asText())) throw invalid("课堂页面内容类型无效");
        scene.put("id", outline.path("id").asText());
        scene.put("stageId", courseId);
        scene.put("order", outline.path("order").asInt());
        if (type.equals("slide")) ((ObjectNode)content).set("canvas",ClassroomEditingSupport.canvas(content.path("canvas")));
        if (type.equals("interactive")) {
            // HTML 必须由客户端放进沙箱；此处只限制传输尺寸，不把正则匹配当安全隔离。
            String html = content.path("html").asText("");
            // 数学互动内嵌 KaTeX 与字体，避免依赖 CDN；仍留出 JSONL 2 MB 的协议余量。
            if (html.isBlank() || html.length() > 1_200_000) throw invalid("互动页面缺少 HTML 或内容过大");
            if(content.has("widgetType")&&!Set.of("simulation","diagram","code","game","visualization3d","procedural-skill").contains(content.path("widgetType").asText()))
                throw invalid("互动类型无效");
            ((ObjectNode)content).put("html",ClassroomSlideSanitizer.cleanInteractiveHtml(html));
        }
        if (type.equals("quiz")) { questions(content.path("questions"), content.path("thinking")); thinking(content.path("thinking")); }
        if(scene.has("whiteboards")) {
            if(!scene.path("whiteboards").isArray()||scene.path("whiteboards").size()>20) throw invalid("课件白板数量无效");
            ArrayNode checked=scene.arrayNode();
            for(JsonNode board:scene.path("whiteboards")) checked.add(ClassroomEditingSupport.canvas(board));
            scene.set("whiteboards",checked);
        }
        scene.set("actions",ClassroomActionPolicy.validate(scene.path("actions"),scene,scene.objectNode(),true));
        return ClassroomSlideSanitizer.cleanScene(scene);
    }

    /** 建卷路径专用：只处理计分题，因此仍要求非空。 */
    static void questions(JsonNode questions) { questions(questions, null); }

    /**
     * @param thinking 与本页配套的不计分思考题数组；计分题可以为空，但两者不能同时为空。
     *                 允许「整页都是思考题」是刻意的：题目被独立复核判定答案有错且重生成失败时，
     *                 宁可整页只留思考题，也不把一个已知错误的标答当计分题下发。
     */
    static void questions(JsonNode questions, JsonNode thinking) {
        boolean hasThinking = thinking != null && thinking.isArray() && !thinking.isEmpty();
        if (!questions.isArray() || questions.size() > 10 || (questions.isEmpty() && !hasThinking))
            throw invalid("随堂练习必须包含 1–10 道题");
        Set<String> ids = new HashSet<>();
        for (JsonNode q : questions) {
            text(q, "id", 80); text(q, "question", 4000);
            if (!ids.add(q.path("id").asText())) throw invalid("随堂题目 ID 重复");
            String type = q.path("type").asText();
            if (!Set.of("single", "multiple", "judge", "fill", "short_answer").contains(type)) throw invalid("随堂练习题型无效");
            JsonNode answer = q.path("answer");
            if (type.equals("judge")) {
                if (!(answer.isBoolean() || answer.isTextual() && Set.of("对", "错").contains(answer.asText())))
                    throw invalid("判断题标准答案必须为对或错");
                continue;
            }
            if (type.equals("fill") || type.equals("short_answer")) {
                boolean supplied = !answer.isMissingNode() && !answer.isNull();
                if (supplied && !(answer.isNumber() || answer.isTextual()) || supplied && (answer.asText().isBlank() || answer.asText().length() > 4000))
                    throw invalid("填空和简答的参考答案必须为单个文本或数值");
                if (type.equals("fill") && !supplied) throw invalid("填空题缺少标准答案");
                JsonNode points = q.path("gradingPoints");
                if (!points.isMissingNode()) {
                    if (!points.isArray() || points.size() > 12) throw invalid("简答评分要点格式无效");
                    for (JsonNode point : points) if (!point.isTextual() || point.asText().isBlank() || point.asText().length() > 600) throw invalid("简答评分要点格式无效");
                }
                // 无参考文本的简答仍需明确要点，不能把空白规则当成可靠判分。
                if (type.equals("short_answer") && !supplied && q.path("analysis").asText().isBlank() && points.isEmpty())
                    throw invalid("简答题需提供参考答案或评分要点");
                continue;
            }
            JsonNode options = q.path("options");
            if (!options.isArray() || options.size() < 2 || options.size() > 8) throw invalid("选择题需有 2–8 个选项");
            Set<String> keys = new HashSet<>();
            for (JsonNode o : options) {
                text(o, "label", 2000); text(o, "value", 80);
                if (!keys.add(o.path("value").asText())) throw invalid("选择题选项值重复");
            }
            if (!answer.isArray() || answer.isEmpty() || (type.equals("single") && answer.size() != 1)) throw invalid("选择题缺少合法标准答案");
            Set<String> selected = new HashSet<>();
            for (JsonNode a : answer) {
                if (!a.isTextual() || !keys.contains(a.asText()) || !selected.add(a.asText())) throw invalid("标准答案引用了无效或重复选项");
            }
        }
    }

    /**
     * 降级出来的不计分思考题：**只有题干**。
     *
     * 这里显式拒绝 options/answer/gradingPoints —— 思考题的整个意义是「不拿没核验的答案判学生」，
     * 一旦带了标答就退化回计分题，等于绕过了降级。review 字段（含复核结论）允许存在，
     * 由 ClassroomCourseService.publicCourse() 与导出逻辑负责剥离。
     */
    static void thinking(JsonNode thinking) {
        if (thinking == null || thinking.isMissingNode() || thinking.isNull()) return;
        if (!thinking.isArray() || thinking.size() > 10) throw invalid("思考题必须是不超过 10 条的数组");
        Set<String> ids = new HashSet<>();
        for (JsonNode item : thinking) {
            if (!item.isObject()) throw invalid("思考题格式无效");
            text(item, "id", 80);
            text(item, "question", 4000);
            if (!ids.add(item.path("id").asText())) throw invalid("思考题 ID 重复");
            if (item.has("options") || item.has("answer") || item.has("gradingPoints"))
                throw invalid("思考题不得携带选项或标准答案");
        }
    }

    private static void text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) throw invalid("课堂字段 " + field + " 无效");
    }
    private static ClassroomException invalid(String message) { return new ClassroomException(422, message); }
}
