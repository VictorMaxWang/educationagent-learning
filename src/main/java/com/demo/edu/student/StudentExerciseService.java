package com.demo.edu.student;

import com.demo.edu.knowledge.EduKnowledgeAccessService;
import com.demo.edu.mapper.EduExerciseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 学生侧「班级作业」：拉取本班已发布习题、提交作答并自动判分。
 *
 * <h3>隔离靠两件事，都不靠"前端别传"</h3>
 * <ol>
 *   <li><b>班级由服务端解析</b>：从请求里的 userId 查 {@code edu_user_class} 得到班级，
 *       学生<b>无法指定</b>要看哪个班（传 classId 也没人读）；</li>
 *   <li><b>班级是 SQL 的 where 条件</b>：{@code findPublishedForJudge} 把 class_id 写进查询，
 *       所以"提交别的班的题"根本查不到 → 直接拒绝。</li>
 * </ol>
 *
 * <h3>两个容易做错的点</h3>
 * <ul>
 *   <li><b>列表接口不返回答案</b>：交卷前把标准答案发下去毫无意义；{@code answer}/{@code explanation}
 *       只在提交之后才随判分结果返回；</li>
 *   <li><b>重做覆盖而非追加</b>：同一学生同一题只保留一条作答记录
 *       （否则反复重做会把班级正确率刷歪，见 {@code EduExerciseMapper.updateAnswer}）。</li>
 * </ul>
 */
@Slf4j
@Service
public class StudentExerciseService {

    private final EduExerciseMapper exerciseMapper;
    private final EduKnowledgeAccessService accessService;

    public StudentExerciseService(EduExerciseMapper exerciseMapper,
                                  EduKnowledgeAccessService accessService) {
        this.exerciseMapper = exerciseMapper;
        // 复用知识库访问校验里的班级解析（含 uid 后缀回退）：
        // 那份归一化逻辑只有一处实现，避免两个调用点各写一份、慢慢漂移
        this.accessService = accessService;
    }

    /** 本班已发布、未过截止时间的习题（**不含答案**）。 */
    public Map<String, Object> list(String userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        String classId = accessService.resolveClassId(userId);
        if (classId == null || classId.isBlank()) {
            out.put("success", false);
            out.put("code", 403);
            out.put("error", "未识别到你的班级（请先在学生端绑定班级），暂时看不到班级作业");
            return out;
        }
        try {
            List<Map<String, Object>> rows = exerciseMapper.listPublishedForClass(classId);
            out.put("success", true);
            out.put("classId", classId);
            out.put("exercises", rows);
            return out;
        } catch (Exception e) {
            log.warn("查询班级作业失败：{}", e.toString());
            out.put("success", false);
            out.put("code", 500);
            out.put("error", "班级作业暂时不可用");
            return out;
        }
    }

    /**
     * 提交作答并自动判分。
     *
     * @param userId     学生标识（服务端据此解析班级）
     * @param questionId 题目 id（必须已发布给该生所在班级、且未过截止时间）
     * @param answer     学生答案（单选/判断如 "A"；多选如 "AB" 或 "A,B"）
     */
    public Map<String, Object> submit(String userId, Long questionId, String answer) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (questionId == null) {
            return fail(out, 400, "questionId 不能为空");
        }
        if (answer == null || answer.isBlank()) {
            return fail(out, 400, "答案不能为空");
        }
        String classId = accessService.resolveClassId(userId);
        if (classId == null || classId.isBlank()) {
            return fail(out, 403, "未识别到你的班级，不能提交作业");
        }

        Map<String, Object> question;
        try {
            // 班级写进 where：所以"提交别班的题"查不到 → 下面直接 403
            question = exerciseMapper.findPublishedForJudge(classId, questionId);
        } catch (Exception e) {
            log.warn("查询待判分习题失败：{}", e.toString());
            return fail(out, 500, "作业暂时不可用");
        }
        if (question == null) {
            log.warn("🚫 学生提交了未发布给本班的习题：user={} class={} questionId={}",
                    userId, classId, questionId);
            return fail(out, 403, "这道题没有发布给你所在的班级，或已过截止时间");
        }

        String qType = String.valueOf(question.get("qType"));
        String standard = question.get("answer") == null ? "" : String.valueOf(question.get("answer"));
        boolean correct = isCorrect(qType, standard, answer);
        int flag = correct ? 1 : 0;

        try {
            Long paperId = question.get("paperId") == null
                    ? null : ((Number) question.get("paperId")).longValue();
            // 先试覆盖（重做），没有既有记录才插入
            int updated = exerciseMapper.updateAnswer(userId, questionId, answer, flag);
            if (updated == 0) {
                exerciseMapper.insertAnswer(userId, paperId, questionId, answer, flag);
            }
        } catch (Exception e) {
            log.warn("作答记录写入失败：{}", e.toString());
            // 判分结果照常返回：学生看到结果比"因为落库失败而什么都不显示"更重要
        }

        out.put("success", true);
        out.put("correct", correct);
        out.put("standardAnswer", standard);
        out.put("explanation", question.get("explanation"));
        out.put("classId", classId);
        log.info("📥 班级作业提交：class={} questionId={} 是否正确={}", classId, questionId, correct);
        return out;
    }

    /**
     * 客观题判分：单选 / 判断直接比对；多选题按字母集合比对（`AB` / `BA` / `A,B` 等价）。
     *
     * <p>大小写、空白、逗号、顿号都做归一化——学生的输入方式不可控，
     * 因为格式差异判错是最容易激起"系统判断不准"投诉的一类 bug。
     */
    static boolean isCorrect(String qType, String standard, String submitted) {
        String type = qType == null ? "SINGLE" : qType.toUpperCase(Locale.ROOT);
        if ("MULTI".equals(type)) {
            return lettersOf(standard).equals(lettersOf(submitted));
        }
        return normalize(standard).equals(normalize(submitted));
    }

    /** 抽出所有字母/数字并排序（多选题的答案顺序不影响正确性）。 */
    private static String lettersOf(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        char[] chars = sb.toString().toCharArray();
        java.util.Arrays.sort(chars);
        return new String(chars);
    }

    /** 去空白与常见分隔符后大写（单选/判断用）。 */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toUpperCase(Locale.ROOT)
                .replace(" ", "").replace("　", "")
                .replace(".", "").replace("。", "")
                .replace("、", "").replace(",", "");
    }

    private static Map<String, Object> fail(Map<String, Object> out, int code, String msg) {
        out.clear();
        out.put("success", false);
        out.put("code", code);
        out.put("error", msg);
        return out;
    }
}
