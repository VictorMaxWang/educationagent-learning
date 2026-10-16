package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机运维入口：对**已有课程**跑一次只读补审，报告哪些题不可信。
 *
 * <h3>只读</h3>
 * 不生成内容、不改课件、不改题库、不写任何数据。它只回答"这道题可信吗、复核认为答案是什么"。
 *
 * <h3>为什么不直接降级为不计分思考题</h3>
 * 生成期可以降级，是因为那时候题库还没建。对已有课程不行：分数分母取自**题库**
 * （{@code QuizService.submit} 返回的 total 是建卷时写入的题数），而降级改的是**课件文档**里的
 * 题目数组，两者会永久错位 —— 学生只做 4 题，界面却显示"完成 5 道题"，正确率被系统性低估。
 * 项目本身也禁止改动已建卷的练习（{@code ClassroomEditingSupport} 抛 409）。
 * 因此已有课程只能"保持题号题数不变、就地换题"，而那是写操作，须单独确认后再做。
 *
 * <h3>不要把本路径加进 Caddy 白名单</h3>
 * 它挂在 {@code /api/internal/*} 下，**不匹配** {@code deploy/linux/Caddyfile} 里放行的
 * {@code /api/classroom/*}，会被 {@code @privateApi} 兜底成 404。这正是需要的效果：
 * 补审要花模型调用，不能让匿名访问者触发。
 */
@RestController
@RequestMapping("/api/internal/quiz-review")
public class ClassroomQuizReviewController {
    private final ClassroomCourseService courses;
    private final ClassroomEngine engine;
    private final ObjectMapper json;

    public ClassroomQuizReviewController(ClassroomCourseService courses, ClassroomEngine engine, ObjectMapper json) {
        this.courses = courses;
        this.engine = engine;
        this.json = json;
    }

    /** {@code courseId} 省略时补审全部课程；每个练习页一次复核调用，课程多时耗时较长。 */
    @PostMapping
    public ObjectNode audit(@RequestParam(required = false) String courseId) {
        List<ObjectNode> documents = courseId == null || courseId.isBlank()
                ? courses.internalDocuments()
                : List.of(courses.internalDocument(courseId));
        ArrayNode reported = json.createArrayNode();
        int problems = 0;
        int reviewed = 0;
        for (ObjectNode course : documents) {
            ArrayNode scenes = json.createArrayNode();
            for (JsonNode scene : course.path("scenes")) {
                if (!"quiz".equals(scene.path("type").asText())) continue;
                JsonNode questions = scene.path("content").path("questions");
                if (!questions.isArray() || questions.isEmpty()) continue;
                ObjectNode entry = scenes.addObject()
                        .put("sceneId", scene.path("id").asText())
                        .put("sceneTitle", scene.path("title").asText())
                        .put("questionCount", questions.size())
                        // 已建卷说明这页正在被计分，改动它才会牵动成绩。
                        .put("attachedToQuizPaper", scene.has("quizId"));
                try {
                    ObjectNode input = json.createObjectNode();
                    input.put("courseId", course.path("id").asText());
                    input.set("questions", questions.deepCopy());
                    JsonNode result = engine.review(input, new ClassroomCancellation());
                    entry.put("reviewed", result.path("reviewed").asInt());
                    entry.put("ruleDropped", result.path("ruleDropped").asInt());
                    entry.put("callFailed", result.path("callFailed").asBoolean());
                    ArrayNode found = entry.putArray("problems");
                    for (JsonNode finding : result.path("findings")) {
                        String stage = finding.path("stage").asText();
                        String outcome = finding.path("outcome").asText();
                        // L1 命中即有问题；L2 只有明确分歧才算，unjudged 是"查不了"而非"查出错"。
                        if (!"rule".equals(stage) && !"disagree".equals(outcome)) continue;
                        found.add(finding.deepCopy());
                    }
                    reviewed += result.path("reviewed").asInt();
                    problems += found.size();
                } catch (RuntimeException failure) {
                    // 单页失败不影响其余课程，如实记下原因而不是当成通过。
                    entry.put("error", failure instanceof ClassroomException classroom
                            ? classroom.getMessage() : "补审未能完成");
                }
            }
            if (!scenes.isEmpty()) {
                ObjectNode courseReport = reported.addObject();
                courseReport.put("courseId", course.path("id").asText()).put("title", course.path("title").asText());
                courseReport.set("scenes", scenes);
            }
        }
        ObjectNode report = json.createObjectNode();
        report.put("mode", "read-only");
        report.put("courseCount", reported.size());
        report.put("reviewedQuestionCount", reviewed);
        report.put("problemCount", problems);
        report.put("note", "只读补审：不生成、不改课件、不改题库、不写任何数据。复核与出题同族模型，"
                + "PASS 只表示第二次也同意，不等于答案正确；简答与文本填空无法比对，一律 unjudged。");
        report.set("courses", reported);
        return report;
    }

    /**
     * 修题：**默认只出方案（apply=false），不写任何数据**。确认无误后加 {@code apply=true} 再调一次才落库。
     *
     * <p>落库只改被标记的那几道题，<b>题号与题数不变</b>——分数分母取自题库（建卷时的题数），
     * 改题数会让学生的成绩分母错位。课件文档与题库行在同一事务里改，两者必须一致：判分读题库，学生看课件。</p>
     *
     * <p><b>不回改历史成绩</b>：已提交的作答记录保持原样，那是当时的判分事实。</p>
     */
    @PostMapping("/repair")
    public ObjectNode repair(@RequestParam(required = false) String courseId,
                             @RequestParam(defaultValue = "false") boolean apply) {
        List<ObjectNode> documents = courseId == null || courseId.isBlank()
                ? courses.internalDocuments()
                : List.of(courses.internalDocument(courseId));
        ArrayNode reported = json.createArrayNode();
        int fixable = 0;
        int manual = 0;
        for (ObjectNode course : documents) {
            ArrayNode scenes = json.createArrayNode();
            Map<String, JsonNode> plans = new LinkedHashMap<>();
            for (JsonNode scene : course.path("scenes")) {
                if (!"quiz".equals(scene.path("type").asText())) continue;
                JsonNode questions = scene.path("content").path("questions");
                if (!questions.isArray() || questions.isEmpty()) continue;
                ObjectNode entry = scenes.addObject()
                        .put("sceneId", scene.path("id").asText())
                        .put("sceneTitle", scene.path("title").asText())
                        .put("attachedToQuizPaper", scene.has("quizId"));
                try {
                    ObjectNode input = json.createObjectNode();
                    input.put("courseId", course.path("id").asText());
                    input.set("questions", questions.deepCopy());
                    JsonNode plan = engine.repair(input, new ClassroomCancellation());
                    ArrayNode changes = plan.path("changes").isArray() ? (ArrayNode) plan.path("changes") : json.createArrayNode();
                    entry.set("changes", changes.deepCopy());
                    if (changes.isEmpty()) continue;
                    plans.put(scene.path("id").asText(), changes.deepCopy());
                    for (JsonNode change : changes) {
                        if ("none".equals(change.path("action").asText())) manual++; else fixable++;
                    }
                } catch (RuntimeException failure) {
                    // 单页失败不影响其余页面，如实记录而不是当成无需修复。
                    entry.put("error", failure instanceof ClassroomException classroom
                            ? classroom.getMessage() : "修题方案未能生成");
                }
            }
            if (scenes.isEmpty()) continue;
            ObjectNode courseReport = reported.addObject();
            courseReport.put("courseId", course.path("id").asText()).put("title", course.path("title").asText());
            courseReport.set("scenes", scenes);
            if (apply && !plans.isEmpty()) {
                courseReport.set("applied", courses.applyQuizRepairs(course.path("id").asText(), plans));
            }
        }
        ObjectNode report = json.createObjectNode();
        report.put("mode", apply ? "applied" : "dry-run");
        report.put("fixableCount", fixable);
        report.put("manualCount", manual);
        report.put("note", apply
                ? "已落库：只改被标记的题，题号与题数不变；不回改历史成绩（quiz_answer.is_correct 保持原样）。"
                : "只出方案，未写任何数据。确认无误后加 apply=true 再调一次即可落库。");
        report.set("courses", reported);
        return report;
    }
}
