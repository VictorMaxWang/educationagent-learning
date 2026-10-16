package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机运维入口：只读补审、准备固定修题方案，以及显式应用已审阅的方案。
 *
 * <h3>只读</h3>
 * audit只报告规则问题、盲解分歧和无法判定的题；不生成内容，不改课件、题库和成绩。
 * repair准备完整同题型候选，并保存在有时限的进程内planId下；apply必须使用该编号，不重新调用模型。
 *
 * <h3>为什么不直接降级为不计分思考题</h3>
 * 生成期可以降级，是因为那时候题库还没建。对已有课程不行：分数分母取自**题库**
 * （{@code QuizService.submit} 返回的 total 是建卷时写入的题数），而降级改的是**课件文档**里的
 * 题目数组，两者会永久错位 —— 学生只做 4 题，界面却显示"完成 5 道题"，正确率被系统性低估。
 * 未作答题可在课程与试卷锁下保持题号题数、同事务修订课件与题库。
 * 已有作答的题不能原位改写，否则旧错题会错误地关联新题干；应复制课程建立修订版，
 * 再由{@link ClassroomQuizRevisionRegistry}登记单向替代指针，原课程和历史记录均保留。
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
    private static final Duration PLAN_TTL=Duration.ofMinutes(15);
    private final Map<String,PendingRepair> pending=new ConcurrentHashMap<>();
    private static final class PendingRepair {
        final String courseId,fingerprint;final Map<String,JsonNode> changes;final Instant expiresAt;
        ObjectNode receipt;ClassroomException failure;boolean consumed;
        PendingRepair(String courseId,String fingerprint,Map<String,JsonNode> changes) {
            this.courseId=courseId;this.fingerprint=fingerprint;this.changes=Map.copyOf(changes);this.expiresAt=Instant.now().plus(PLAN_TTL);
        }
    }

    public ClassroomQuizReviewController(ClassroomCourseService courses, ClassroomEngine engine, ObjectMapper json) {
        this.courses = courses;
        this.engine = engine;
        this.json = json;
    }

    /** {@code courseId} 省略时补审课程列表；旧页最多10题、每批5题，每页最多2次盲解调用。 */
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
                    ArrayNode found = entry.putArray("problems"), uncertain=entry.putArray("unjudged");
                    for (JsonNode finding : result.path("findings")) {
                        String stage = finding.path("stage").asText();
                        String outcome = finding.path("outcome").asText();
                        // L1 命中即有问题；L2 只有明确分歧才算，unjudged 是"查不了"而非"查出错"。
                        if("unjudged".equals(outcome)){uncertain.add(finding.deepCopy());continue;}
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
     * 默认只出固定方案，返回15分钟有效planId。apply=true必须携带已审阅planId，不重新调用模型。
     *
     * <p>落库只改被标记的那几道题，<b>题号与题数不变</b>——分数分母取自题库（建卷时的题数），
     * 改题数会让学生的成绩分母错位。课件文档与题库行在同一事务里改，两者必须一致：判分读题库，学生看课件。</p>
     *
     * <p>有任何作答的原题拒绝原位修订，避免旧答案被关联到新题干；须另建课程修订版。</p>
     */
    @PostMapping("/repair")
    public ObjectNode repair(@RequestParam(required = false) String courseId,
                             @RequestParam(defaultValue = "false") boolean apply,
                             @RequestParam(required=false)String planId) {
        if(apply)return applyPlan(courseId,planId);
        if(planId!=null&&!planId.isBlank())throw new ClassroomException(400,"新修题方案不接受已有planId");
        pending.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(Instant.now()));
        if(pending.size()>=32)throw new ClassroomException(429,"已有待确认修题方案较多，请稍后重试");
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
                    ArrayNode changes = boundChanges(questions,plan);
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
            if(plans.values().stream().anyMatch(value->{for(JsonNode change:value)if("regenerate".equals(change.path("action").asText()))return true;return false;})) {
                String id=UUID.randomUUID().toString();PendingRepair prepared=new PendingRepair(course.path("id").asText(),ClassroomCourseService.quizRepairFingerprint(course),plans);
                synchronized(pending){if(pending.size()>=32)throw new ClassroomException(429,"待确认修题方案已满");pending.put(id,prepared);}
                courseReport.put("planId",id).put("expiresAt",prepared.expiresAt.toString());
            }
        }
        ObjectNode report = json.createObjectNode();
        report.put("mode", "dry-run");
        report.put("fixableCount", fixable);
        report.put("manualCount", manual);
        report.put("note","只出固定方案，未修改课件或题库。审阅后以apply=true和planId应用；已有作答题会被拒绝，须建立课程修订版。");
        report.set("courses", reported);
        return report;
    }

    /** 保留Java直接调用的旧签名；没有planId的apply会明确拒绝，绝不重新生成一份未知方案。 */
    public ObjectNode repair(String courseId,boolean apply){return repair(courseId,apply,null);}

    private ArrayNode boundChanges(JsonNode questions,JsonNode result) {
        if(!result.path("changes").isArray()||result.path("changes").size()>questions.size())throw new ClassroomException(502,"修题方案结构无效");
        ArrayNode bound=json.createArrayNode();Set<Integer> seen=new HashSet<>();
        for(JsonNode change:result.path("changes")) {
            int index=change.path("no").asInt()-1;
            if(!change.isObject()||!change.path("no").isIntegralNumber()||index<0||index>=questions.size()||!seen.add(index))throw new ClassroomException(502,"修题方案题号无效或重复");
            JsonNode original=questions.get(index);String action=change.path("action").asText();
            if(!original.path("id").asText().equals(change.path("id").asText())||!Set.of("none","regenerate").contains(action))throw new ClassroomException(502,"修题方案不属于当前题目");
            if(action.equals("regenerate")&&(!change.path("after").isObject()||!original.path("type").equals(change.path("after").path("type"))
                    ||!"agree".equals(change.path("verification").path("blindSolve").asText())||!"consistent".equals(change.path("verification").path("explanation").asText())))
                throw new ClassroomException(502,"修题方案未完成同题型答案及解析复核");
            ObjectNode item=((ObjectNode)change).deepCopy();
            // before绑定真实快照，不让模型自行改写历史题面；apply再次逐字段核对。
            item.set("before",ClassroomCourseService.quizRepairQuestionSnapshot(original));bound.add(item);
        }
        return bound;
    }

    private ObjectNode applyPlan(String requestedCourse,String planId) {
        if(planId==null||!planId.matches("[a-fA-F0-9-]{36}"))throw new ClassroomException(400,"应用修题必须提供已审阅的planId");
        PendingRepair plan=pending.get(planId);
        if(plan==null||!plan.expiresAt.isAfter(Instant.now()))throw new ClassroomException(410,"修题方案已过期或服务已重启，请重新生成并审阅方案");
        if(requestedCourse!=null&&!requestedCourse.isBlank()&&!requestedCourse.equals(plan.courseId))throw new ClassroomException(409,"修题方案不属于该课程");
        synchronized(plan) {
            if(plan.receipt!=null)return plan.receipt.deepCopy();
            if(plan.failure!=null)throw new ClassroomException(plan.failure.status(),plan.failure.getMessage());
            if(plan.consumed)throw new ClassroomException(409,"这份修题方案已消费，请核对之前的应用结果");
            plan.consumed=true;
            try {
                ObjectNode applied=courses.applyQuizRepairs(plan.courseId,plan.fingerprint,plan.changes);
                ObjectNode receipt=json.createObjectNode().put("mode","applied").put("planId",planId).put("courseId",plan.courseId)
                        .put("note","已应用所审阅方案；题号题数保持不变，已有作答的题目不允许原位修改。");
                receipt.set("applied",applied);plan.receipt=receipt.deepCopy();return receipt;
            }catch(RuntimeException failure) {
                plan.failure=failure instanceof ClassroomException classroom?new ClassroomException(classroom.status(),classroom.getMessage())
                        :new ClassroomException(500,"修题应用未完成，原记录已由事务保留；请检查后重新生成方案");
                throw plan.failure;
            }
        }
    }

    @ExceptionHandler(ClassroomException.class)
    public ResponseEntity<Map<String,String>> error(ClassroomException failure){return ResponseEntity.status(failure.status()).body(Map.of("error",failure.getMessage()));}
}
