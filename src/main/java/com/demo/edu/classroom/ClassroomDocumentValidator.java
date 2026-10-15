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
        if (type.equals("quiz")) questions(content.path("questions"));
        if(scene.has("whiteboards")) {
            if(!scene.path("whiteboards").isArray()||scene.path("whiteboards").size()>20) throw invalid("课件白板数量无效");
            ArrayNode checked=scene.arrayNode();
            for(JsonNode board:scene.path("whiteboards")) checked.add(ClassroomEditingSupport.canvas(board));
            scene.set("whiteboards",checked);
        }
        scene.set("actions",ClassroomActionPolicy.validate(scene.path("actions"),scene,scene.objectNode(),true));
        return ClassroomSlideSanitizer.cleanScene(scene);
    }

    static void questions(JsonNode questions) {
        if (!questions.isArray() || questions.isEmpty() || questions.size() > 10) throw invalid("随堂练习必须包含 1–10 道题");
        Set<String> ids = new HashSet<>();
        for (JsonNode q : questions) {
            text(q, "id", 80); text(q, "question", 4000);
            if (!ids.add(q.path("id").asText())) throw invalid("随堂题目 ID 重复");
            String type = q.path("type").asText();
            if (!Set.of("single", "multiple").contains(type)) throw invalid("随堂练习只支持单选和多选");
            JsonNode options = q.path("options");
            if (!options.isArray() || options.size() < 2 || options.size() > 8) throw invalid("选择题需有 2–8 个选项");
            Set<String> keys = new HashSet<>();
            for (JsonNode o : options) {
                text(o, "label", 2000); text(o, "value", 80);
                if (!keys.add(o.path("value").asText())) throw invalid("选择题选项值重复");
            }
            JsonNode answer = q.path("answer");
            if (!answer.isArray() || answer.isEmpty() || (type.equals("single") && answer.size() != 1)) throw invalid("选择题缺少合法标准答案");
            Set<String> selected = new HashSet<>();
            for (JsonNode a : answer) {
                if (!a.isTextual() || !keys.contains(a.asText()) || !selected.add(a.asText())) throw invalid("标准答案引用了无效或重复选项");
            }
        }
    }

    private static void text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) throw invalid("课堂字段 " + field + " 无效");
    }
    private static ClassroomException invalid(String message) { return new ClassroomException(422, message); }
}
