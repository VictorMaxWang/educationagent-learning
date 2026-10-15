package com.demo.edu.teacher;

import com.alibaba.fastjson2.JSON;
import com.demo.entity.QuizPaperEntity;
import com.demo.entity.QuizQuestionEntity;
import com.demo.edu.mapper.EduExerciseMapper;
import com.demo.router.SafetyKeywordRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 教师自编习题：创建 / 查看 / 发布 / 修改。
 *
 * <h3>三个刻意的设计决定（都与"和已有模块不打架"有关）</h3>
 * <ol>
 *   <li><b>题目内容复用 {@code quiz_question}，不另建练习内容表</b>：
 *       学情大盘与错题本读的就是 {@code quiz_answer} + {@code quiz_question.knowledge_point}，
 *       另起一套表等于让"教师发布的题"永远不出现在学情统计里（除非改两处聚合 SQL）。</li>
 *   <li><b>发布状态与题解耦在 {@code edu_exercise}</b>：教师只绑定一个班，发布是 1:1，
 *       单独一张 release 表只多一次 join。将来支持多班再加。</li>
 *   <li><b>创建时就做内容风控</b>：题干/选项/答案过 {@link SafetyKeywordRules}，
 *       命中直接拒绝创建——而不是"发了以后再说"。</li>
 * </ol>
 *
 * <h3>改题的限制（避免污染已提交的作答）</h3>
 * 已有人作答的题<b>不允许改题干与标准答案</b>（会让已提交记录与题目对不上），
 * 只允许改解析与知识点；无人作答时可改全部。
 */
@Slf4j
@Service
public class TeacherExerciseService {

    /** 本阶段支持客观题的自动判分；简答题见类注释"下一步"。 */
    private static final List<String> SUPPORTED_TYPES = List.of("SINGLE", "MULTI", "JUDGE");

    private final EduExerciseMapper exerciseMapper;

    public TeacherExerciseService(EduExerciseMapper exerciseMapper) {
        this.exerciseMapper = exerciseMapper;
    }

    /**
     * 创建习题（可选直接发布）。
     *
     * @param classId 教师所属班级（来自角色门，**不是请求参数**）
     * @param teacher 教师账号
     */
    @Transactional
    public Map<String, Object> create(String classId, String teacher, Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String stem = str(body.get("stem"));
        String qType = str(body.get("qType")).toUpperCase(Locale.ROOT);
        String answer = str(body.get("answer"));
        String options = str(body.get("options"));
        String explanation = str(body.get("explanation"));
        String knowledgePoint = str(body.get("knowledgePoint"));
        boolean publish = !Boolean.FALSE.equals(body.get("publish"));

        if (stem.isBlank() || answer.isBlank()) {
            return fail(out, 400, "题干与标准答案不能为空");
        }
        if (!SUPPORTED_TYPES.contains(qType)) {
            return fail(out, 400, "题型仅支持 " + SUPPORTED_TYPES
                    + "（简答题的自动判分需要接入现有 QuizService 的 LLM 判分，尚未开放）");
        }
        if ("MULTI".equals(qType) && options.isBlank()) {
            return fail(out, 400, "多选题必须提供选项");
        }

        // ★ 内容风控：教师录的题也要过既有安全词表（自伤风险 / 违规内容）
        SafetyKeywordRules.Hit hit = SafetyKeywordRules.classify(stem + " " + options + " " + answer);
        if (hit.isHit()) {
            log.warn("🚫 教师习题内容被安全词表拦下：teacher={} verdict={} keyword={}",
                    teacher, hit.verdict(), hit.keyword());
            return fail(out, 400, "题目内容命中安全词表（" + hit.keyword()
                    + "），已拒绝保存。教学内容请调整措辞");
        }

        // 容器：quiz_paper（user_id 存教师标识，便于溯源"这道题是谁布置的"）
        QuizPaperEntity paper = new QuizPaperEntity();
        paper.setUserId("teacher:" + teacher);
        paper.setTitle(titleOf(knowledgePoint, stem));
        exerciseMapper.insertPaper(paper);

        // 题目本体：quiz_question
        QuizQuestionEntity question = new QuizQuestionEntity();
        question.setPaperId(paper.getId());
        question.setQuestionNo(1);
        question.setQType(qType);
        question.setStem(stem);
        question.setOptions(options.isBlank() ? "[]" : options);
        question.setAnswer(answer);
        question.setExplanation(explanation.isBlank() ? null : explanation);
        question.setKnowledgePoint(knowledgePoint.isBlank() ? null : knowledgePoint);
        question.setHasAnswer(1);
        exerciseMapper.insertQuestion(question);

        // 发布状态
        exerciseMapper.insert(question.getId(), classId, teacher,
                knowledgePoint.isBlank() ? null : knowledgePoint,
                publish ? 1 : 0, parseTime(body.get("expireTime")));

        out.put("success", true);
        out.put("questionId", question.getId());
        out.put("published", publish);
        log.info("📝 教师习题已创建：teacher={} class={} questionId={} published={}",
                teacher, classId, question.getId(), publish);
        return out;
    }

    /** 本班、本人创建的习题 + 聚合答题统计（不返回学生明细）。 */
    public List<Map<String, Object>> list(String classId, String teacher) {
        try {
            return exerciseMapper.listByClassAndCreator(classId, teacher);
        } catch (Exception e) {
            log.warn("查询教师习题列表失败：{}", e.toString());
            return List.of();
        }
    }

    /**
     * 发布 / 下架。
     *
     * <p>越权校验在这里：题目必须属于<b>本教师 + 本班</b>，否则直接 403——
     * 请求体里即便塞了别的 questionId 也没用。
     */
    public Map<String, Object> publish(String classId, String teacher, Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        Long questionId = parseLong(body.get("questionId"));
        if (questionId == null) return fail(out, 400, "questionId 不能为空");

        Map<String, Object> owned = ownedQuestion(questionId, classId, teacher);
        if (owned == null) return fail(out, 403, "该习题不属于本班或不由你创建，不能操作");

        boolean published = !Boolean.FALSE.equals(body.get("published"));
        // 只改发布状态时**保留原有截止时间**——否则"发布/下架"会顺手把已设的截止时间清掉
        LocalDateTime expire = body.containsKey("expireTime")
                ? parseTime(body.get("expireTime"))
                : toTime(owned.get("expireTime"));
        exerciseMapper.updatePublish(questionId, classId, published ? 1 : 0, expire);
        out.put("success", true);
        out.put("published", published);
        log.info("📤 习题{}：teacher={} class={} questionId={}",
                published ? "已发布" : "已下架", teacher, classId, questionId);
        return out;
    }

    /**
     * 修改习题。规则：
     * <ul>
     *   <li><b>没人作答</b> → 题干、选项、答案、解析、知识点、截止时间都可改；</li>
     *   <li><b>已有人作答</b> → 只允许改解析、知识点、截止时间；
     *       改题干或答案会被拒绝（已提交的作答会对不上题）。</li>
     * </ul>
     */
    public Map<String, Object> update(String classId, String teacher, Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        Long questionId = parseLong(body.get("questionId"));
        if (questionId == null) return fail(out, 400, "questionId 不能为空");

        Map<String, Object> owned = ownedQuestion(questionId, classId, teacher);
        if (owned == null) return fail(out, 403, "该习题不属于本班或不由你创建，不能修改");

        boolean contentChanged = body.containsKey("stem") || body.containsKey("answer")
                || body.containsKey("options");
        int answered = exerciseMapper.countAnswers(questionId);
        if (contentChanged && answered > 0) {
            return fail(out, 409, "已有 " + answered + " 条作答，不能再改题干/选项/答案"
                    + "（会让已提交的记录与题目对不上）。解析与知识点可以改");
        }

        // 局部更新：只传要改的字段（Mapper 用 COALESCE 表达"null = 不改"）
        exerciseMapper.updateQuestionContent(questionId,
                body.containsKey("stem") ? str(body.get("stem")) : null,
                body.containsKey("options") ? str(body.get("options")) : null,
                body.containsKey("answer") ? str(body.get("answer")) : null,
                body.containsKey("explanation") ? str(body.get("explanation")) : null);

        if (body.containsKey("knowledgePoint")) {
            String kp = str(body.get("knowledgePoint"));
            exerciseMapper.updateKnowledgePoint(questionId, classId, kp.isBlank() ? null : kp);
        }
        if (body.containsKey("expireTime") || body.containsKey("published")) {
            int published;
            if (body.containsKey("published")) {
                published = Boolean.FALSE.equals(body.get("published")) ? 0 : 1;
            } else {
                Object current = owned.get("published");
                published = current instanceof Number n ? n.intValue() : 0;
            }
            // 只传 published 时**保留原有截止时间**：否则"发布"这个动作会把已设的截止时间清掉
            LocalDateTime expire = body.containsKey("expireTime")
                    ? parseTime(body.get("expireTime"))
                    : toTime(owned.get("expireTime"));
            exerciseMapper.updatePublish(questionId, classId, published, expire);
        }

        out.put("success", true);
        out.put("contentLocked", answered > 0);
        log.info("✏️ 习题已更新：teacher={} class={} questionId={} 已有作答={}",
                teacher, classId, questionId, answered);
        return out;
    }

    /** 归属校验：题目必须由本教师创建、且发给本班。 */
    private Map<String, Object> ownedQuestion(Long questionId, String classId, String teacher) {
        try {
            Map<String, Object> row = exerciseMapper.selectByQuestionId(questionId);
            if (row == null) return null;
            String rowClass = String.valueOf(row.get("classId"));
            String rowCreator = String.valueOf(row.get("creator"));
            if (!classId.equals(rowClass) || !teacher.equals(rowCreator)) {
                log.warn("🚫 习题越权操作被拒：teacher={} class={} 试图操作 questionId={}（属于 class={} creator={}）",
                        teacher, classId, questionId, rowClass, rowCreator);
                return null;
            }
            return row;
        } catch (Exception e) {
            log.warn("习题归属校验失败：{}", e.toString());
            return null;
        }
    }

    private static String titleOf(String knowledgePoint, String stem) {
        String base = knowledgePoint == null || knowledgePoint.isBlank() ? stem : knowledgePoint + "：" + stem;
        return base.length() > 60 ? base.substring(0, 60) : base;
    }

    private static Map<String, Object> fail(Map<String, Object> out, int code, String msg) {
        out.clear();
        out.put("success", false);
        out.put("code", code);
        out.put("error", msg);
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 把库里读出来的时间字段（可能是 Timestamp / LocalDateTime）统一成 LocalDateTime。 */
    private static LocalDateTime toTime(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime t) return t;
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (o instanceof java.util.Date d) return LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.systemDefault());
        return parseTime(o);
    }

    private static Long parseLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return null;
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 截止时间：接受 ISO-8601（如 2026-11-01T18:00）；空/非法 = 无截止。 */
    @SuppressWarnings("unused")
    private static LocalDateTime parseTime(Object o) {
        String s = str(o);
        if (s.isEmpty()) return null;
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            try {
                return JSON.parseObject("{\"t\":\"" + s + "\"}").getLocalDateTime("t");
            } catch (Exception ignore) {
                log.warn("截止时间格式无法解析，按无截止处理：{}", s);
                return null;
            }
        }
    }
}
