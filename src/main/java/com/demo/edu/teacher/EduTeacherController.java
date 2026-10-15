package com.demo.edu.teacher;

import com.demo.edu.auth.EduContext;
import com.demo.edu.auth.EduTeacherService;
import com.demo.edu.mapper.EduFeedbackMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 乡村教师助教工作台 REST（全部需要 {@code X-Edu-Token}）。
 *
 * <h3>接口签名里为什么没有 classId</h3>
 * 班级由 {@code EduAuthInterceptor} 从口令映射后写入 {@code EduContext}，
 * 控制器<b>只读 ThreadLocal、不接收请求参数</b>。
 * 这样"查别的班"这件事在接口层面就不存在——不是靠权限判断拦住，而是压根没有入口。
 */
@Slf4j
@RestController
@RequestMapping("/api/edu/teacher")
public class EduTeacherController {

    @Resource
    private EduTeacherAggregateService aggregateService;
    @Resource
    private EduTeacherService teacherService;
    @Resource
    private SchoolResourceService schoolResourceService;
    @Resource
    private LessonPrepareService lessonPrepareService;
    @Resource
    private EduPdfExportService pdfExportService;
    @Resource
    private EduFeedbackMapper feedbackMapper;
    @Resource
    private EduClassStatService classStatService;
    @Resource
    private EduTrainingDocService trainingDocService;
    @Resource
    private TeacherExerciseService exerciseService;
    @Resource
    private com.demo.edu.knowledge.EduKnowledgeAccessService knowledgeAccessService;

    /** 教师端可开关的知识库领域（与前端 COURSES 的 domainId 对齐）。 */
    private static final java.util.Set<String> KNOWLEDGE_DOMAINS =
            java.util.Set.of("lifesafety", "sexedu", "school");
    /** 业务指标埋点（可选，缺失时自动跳过）。 */
    @Resource
    private com.demo.metrics.BusinessMetrics businessMetrics;

    // ======================== 登录（不经过角色门，见 EduWebConfig 的 excludePathPatterns）========================

    /**
     * 教师登录：校验 {@code edu_teacher} 表的账号密码（PBKDF2），签发带 TTL 的 token。
     *
     * <p>响应里<b>不含密码哈希</b>；后续所有教师接口带 {@code X-Edu-Token: <token>}。
     * 班级与 token 绑定，所以前端不需要（也无法）传 classId。
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, Object> body) {
        String username = body == null ? "" : str(body.get("username"));
        String password = body == null ? "" : String.valueOf(body.getOrDefault("password", ""));
        EduTeacherService.LoginResult result = teacherService.login(username, password);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!result.success()) {
            out.put("code", 401);
            out.put("msg", result.error());
            out.put("data", null);
            return out;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", result.token());
        data.put("username", result.username());
        data.put("classId", result.classId());
        data.put("className", result.className());
        data.put("expiresAt", result.expiresAt());
        out.put("code", 200);
        out.put("msg", "登录成功");
        out.put("data", data);
        return out;
    }

    /** 退出登录：让 token 立即失效（内存会话，重启也会全部失效）。 */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "X-Edu-Token", required = false) String token) {
        teacherService.logout(token);
        Map<String, Object> out = ok();
        out.put("msg", "已退出登录");
        return out;
    }

    // ======================== 班级聚合 ========================

    /**
     * 班级危机信号脱敏汇总（近 N 天）。
     *
     * <p>响应示例：{@code {"classId":"class_demo_1","windowDays":7,"studentCount":12,"total":5,
     * "days":[{"date":"2026-10-14","count":3,"hidden":false}]}}
     * —— 里面<b>没有</b> userId / sessionId / 学生姓名 / 原句。
     */
    @GetMapping("/crisis-summary")
    public Map<String, Object> crisisSummary(@RequestParam(value = "days", defaultValue = "7") int days) {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        Map<String, Object> result = ok();
        result.put("data", aggregateService.crisisSummary(classId, days));
        return result;
    }

    /**
     * 班级匿名学情统计：习题正确率 / 薄弱知识点分布 / 专题提问占比。
     *
     * <p>响应里只有聚合值——没有学生 ID、姓名、对话内容；样本不足时按 k-匿名隐藏数值。
     * 班级来自 token（不是请求参数），所以不存在"查别的班"。
     */
    /**
     * 班级情绪预警列表（规格命名）。
     *
     * <p>实现与 {@code /crisis-summary} <b>完全一致</b>——同一份聚合、同一套 k-匿名与等级口径。
     * 之所以只做一个别名、而不是另写一套：同一个数据两个实现迟早会漂移，
     * 而"教师端两个页面对不上"比"少一个接口"严重得多。
     */
    @GetMapping("/warn/list")
    public Map<String, Object> warnList(@RequestParam(value = "days", defaultValue = "7") int days) {
        return crisisSummary(days);
    }

    /**
     * 专题教育培训参考资料：返回各专题知识库的<b>教学口径（系统提示词）、语料清单与免责声明</b>，
     * 让教师的课堂讲解与学生端 AI 的回答对齐（不另写一份可能漂移的培训材料）。
     *
     * @param topic 可选，{@code lifesafety} / {@code sexedu} / {@code school}；为空返回全部
     */
    @GetMapping("/training/doc")
    public Map<String, Object> trainingDoc(
            @RequestParam(value = "topic", required = false) String topic) {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        Map<String, Object> result = ok();
        result.put("data", trainingDocService.trainingDocs(topic));
        return result;
    }

    /**
     * 本班的知识库访问开关（默认全部开放；只有"关过"的才有记录）。
     * 专题库默认对本班学生开放，教师可按学校要求停用。
     */
    @GetMapping("/knowledge/access")
    public Map<String, Object> knowledgeAccess() {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        Map<String, Object> result = ok();
        result.put("data", knowledgeAccessService.accessOverview(classId, KNOWLEDGE_DOMAINS));
        return result;
    }

    /**
     * 一键开关：{@code {"domainId":"sexedu","enabled":false}}。
     * 只影响本班学生的检索，不删除任何材料（材料仍在库里，重新开放即恢复）。
     */
    @PostMapping("/knowledge/access")
    public Map<String, Object> setKnowledgeAccess(@RequestBody(required = false) Map<String, Object> body) {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        String domainId = body == null ? "" : str(body.get("domainId"));
        if (domainId.isBlank() || !KNOWLEDGE_DOMAINS.contains(domainId)) {
            Map<String, Object> result = ok();
            result.put("code", 400);
            result.put("msg", "domainId 不合法（可选：" + KNOWLEDGE_DOMAINS + "）");
            return result;
        }
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));
        boolean okSet = knowledgeAccessService.setEnabledForClass(classId, domainId, enabled);
        Map<String, Object> result = ok();
        result.put("code", okSet ? 200 : 500);
        result.put("msg", okSet ? (enabled ? "已对本班开放" : "已对本班停用") : "设置失败，请查看后端日志");
        return result;
    }

    // ======================== 习题管理（教师自编题 → 发布到本班）========================

    /**
     * 新建习题。请求体：{@code {stem, qType, options, answer, explanation, knowledgePoint, publish, expireTime}}。
     *
     * <p>题型仅支持 {@code SINGLE / MULTI / JUDGE}（客观题，自动判分）；
     * 题目内容在创建时过既有安全词表，命中直接拒绝保存。
     */
    @PostMapping("/exercise/create")
    public Map<String, Object> createExercise(@RequestBody(required = false) Map<String, Object> body) {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        Map<String, Object> result = exerciseService.create(classId, currentTeacher(), body == null ? Map.of() : body);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        Map<String, Object> out = ok();
        out.put("code", success ? 200 : Integer.parseInt(String.valueOf(result.getOrDefault("code", 500))));
        out.put("msg", success ? "习题已创建" : String.valueOf(result.get("error")));
        out.put("data", result);
        return out;
    }

    /** 本班、本人创建的习题 + 聚合答题统计（不返回学生明细）。 */
    @GetMapping("/exercise/list")
    public Map<String, Object> listExercises() {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        Map<String, Object> out = ok();
        out.put("data", exerciseService.list(classId, currentTeacher()));
        return out;
    }

    /**
     * 发布 / 下架。请求体：{@code {questionId, published, expireTime}}。
     *
     * <p>越权校验在服务层：题目必须属于**本教师 + 本班**，否则 403——
     * 请求体里塞别的 questionId 也没用。
     */
    @PostMapping("/exercise/publish")
    public Map<String, Object> publishExercise(@RequestBody(required = false) Map<String, Object> body) {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        Map<String, Object> result = exerciseService.publish(classId, currentTeacher(), body == null ? Map.of() : body);
        return wrap(result, "已更新发布状态");
    }

    /**
     * 修改习题。已有人作答时**只允许改解析/知识点/截止时间**，
     * 改题干或答案会被拒（已提交的作答会对不上题）。
     */
    @PostMapping("/exercise/update")
    public Map<String, Object> updateExercise(@RequestBody(required = false) Map<String, Object> body) {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        Map<String, Object> result = exerciseService.update(classId, currentTeacher(), body == null ? Map.of() : body);
        return wrap(result, "习题已更新");
    }

    private String currentTeacher() {
        String t = EduContext.getTeacher();
        return (t == null || t.isBlank()) ? "unknown" : t;
    }

    private static Map<String, Object> wrap(Map<String, Object> result, String okMsg) {
        boolean success = Boolean.TRUE.equals(result.get("success"));
        Map<String, Object> out = ok();
        out.put("code", success ? 200
                : Integer.parseInt(String.valueOf(result.getOrDefault("code", 500))));
        out.put("msg", success ? okMsg : String.valueOf(result.get("error")));
        out.put("data", result);
        return out;
    }

    @GetMapping("/class/stat")
    public Map<String, Object> classStat(@RequestParam(value = "days", defaultValue = "7") int days) {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        Map<String, Object> result = ok();
        result.put("data", classStatService.classStat(classId, days));
        return result;
    }

    // ======================== 校本资源 ========================

    /**
     * 上传校本资源（文件先经 {@code POST /api/desktop/file/upload} 拿到 fileId）。
     * 支持 txt / pdf / docx；md 自动转 txt；入库后<b>强制重建索引</b>。
     */
    @PostMapping("/resource/upload")
    public Map<String, Object> uploadResource(@RequestBody(required = false) Map<String, Object> body) {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        String fileId = body == null ? "" : str(body.get("fileId"));
        if (fileId.isBlank()) {
            Map<String, Object> result = ok();
            result.put("code", 400);
            result.put("msg", "fileId 不能为空（请先调用 /api/desktop/file/upload）");
            return result;
        }
        Map<String, Object> data = schoolResourceService.uploadFromStore(classId, fileId);
        Map<String, Object> result = ok();
        boolean success = Boolean.TRUE.equals(data.get("success"));
        result.put("code", success ? 200 : 500);
        result.put("msg", success ? "已入库" : String.valueOf(data.getOrDefault("error", "上传失败")));
        result.put("data", data);
        return result;
    }

    /** 本校已上传的校本资源列表。 */
    @GetMapping("/resource/list")
    public Map<String, Object> resourceList() {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        Map<String, Object> result = ok();
        result.put("data", schoolResourceService.list(classId));
        return result;
    }

    // ======================== 备课素材 ========================

    /**
     * 生成备课素材（依据校本资源 + DeepSeek 组织）。
     * 请求：{@code {"topic":"...","notes":"..."}}；响应含 {@code references}（依据了哪些校本文件）。
     */
    @PostMapping("/lesson/prepare")
    public Map<String, Object> lessonPrepare(@RequestBody(required = false) Map<String, Object> body) {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        String topic = body == null ? "" : str(body.get("topic"));
        String notes = body == null ? "" : str(body.get("notes"));
        Map<String, Object> data = lessonPrepareService.prepare(topic, notes, classId);
        Map<String, Object> result = ok();
        boolean success = Boolean.TRUE.equals(data.get("success"));
        result.put("code", success ? 200 : 500);
        result.put("msg", success ? "素材已生成" : String.valueOf(data.getOrDefault("error", "生成失败")));
        result.put("data", data);
        return result;
    }

    /** 把备课素材导出成 PDF（中文内嵌字体；找不到字体时如实报错，不产出方框 PDF）。 */
    @PostMapping("/lesson/export-pdf")
    public ResponseEntity<byte[]> exportPdf(@RequestBody(required = false) Map<String, Object> body) {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        String title = body == null ? "" : str(body.get("title"));
        String content = body == null ? "" : str(body.get("content"));
        if (content.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            byte[] pdf = pdfExportService.toPdf(title.isBlank() ? "备课素材" : title, content);
            String fileName = "备课素材_" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".pdf";
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"lesson.pdf\"; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (Exception e) {
            log.warn("备课素材导出 PDF 失败：{}", e.toString());
            return ResponseEntity.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"code\":500,\"msg\":\"" + String.valueOf(e.getMessage()).replace("\"", "'")
                            + "\",\"data\":null}").getBytes(StandardCharsets.UTF_8));
        }
    }

    // ======================== 错误反馈 ========================

    /** 提交错误反馈（教师写的，不含学生数据）。 */
    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody(required = false) Map<String, Object> body) {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        String message = body == null ? "" : str(body.get("message"));
        String libId = body == null ? null : str(body.get("libId"));
        if (message.isBlank()) {
            Map<String, Object> result = ok();
            result.put("code", 400);
            result.put("msg", "反馈内容不能为空");
            return result;
        }
        if (message.length() > 1000) message = message.substring(0, 1000);
        try {
            feedbackMapper.insert(classId, libId == null || libId.isBlank() ? null : libId, message);
        } catch (Exception e) {
            log.warn("错误反馈落库失败：{}", e.toString());
            Map<String, Object> result = ok();
            result.put("code", 500);
            result.put("msg", "反馈保存失败：" + e.getMessage());
            return result;
        }
        Map<String, Object> result = ok();
        result.put("msg", "已记录，谢谢反馈");
        if (businessMetrics != null) {
            businessMetrics.recordTeacherFeedbackSubmitted();
        }
        return result;
    }

    /** 本校提交过的反馈列表。 */
    @GetMapping("/feedback/list")
    public Map<String, Object> feedbackList() {
        String classId = EduContext.getClassId();
        if (classId == null || classId.isBlank()) return unauthorized();
        List<Map<String, Object>> list;
        try {
            list = feedbackMapper.listByClass(classId);
        } catch (Exception e) {
            log.warn("反馈列表查询失败：{}", e.toString());
            list = List.of();
        }
        Map<String, Object> result = ok();
        result.put("data", list);
        return result;
    }

    // ======================== 工具 ========================

    private static Map<String, Object> ok() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("msg", "ok");
        return result;
    }

    private static Map<String, Object> unauthorized() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 401);
        result.put("msg", "未通过教师端鉴权");
        return result;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
