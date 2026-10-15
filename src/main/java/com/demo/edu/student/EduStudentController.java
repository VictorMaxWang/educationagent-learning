package com.demo.edu.student;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 学生侧接口（<b>不经过教师端角色门</b>）。
 *
 * <h3>为什么学生接口不做任何鉴权，而教师接口要做</h3>
 * 学生接口只允许读<b>自己</b>的数据，而这个系统里"自己"的判定口径与其余 25 个控制器一致
 * （userId 由前端直传，全项目无鉴权体系）——即"知道自己 userId 的人就能看自己的数据"。
 * 这与全项目现状一致，没有额外放大风险；
 * 而教师接口是<b>跨用户聚合</b>，一旦放开就是别人班的数据，所以必须挂角色门。
 * 这个区分是刻意的，答辩时也会这么讲。
 */
@Slf4j
@RestController
@RequestMapping("/api/edu/student")
public class EduStudentController {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Resource
    private EduWeeklyReportService reportService;
    @Resource
    private StudentExerciseService exerciseService;

    /**
     * 导出我的学习周报（Word）。
     *
     * <p>请求：{@code {"userId":"user_xxx","days":7}}；响应：docx 字节流（浏览器直接下载）。
     * 只有学生主动调用才生成，服务端没有定时任务、不会自动上报。
     */
    // ======================== 班级作业（教师发布 → 学生作答）========================

    /**
     * 本班已发布、未过截止时间的习题。
     *
     * <p><b>不返回标准答案</b>——交卷前把答案发下去毫无意义；
     * 班级由服务端从 userId 解析，学生无法指定看哪个班。
     */
    @GetMapping("/exercise/list")
    public Map<String, Object> exerciseList(@RequestParam(value = "userId", required = false) String userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (userId == null || userId.isBlank()) {
            out.put("code", 400);
            out.put("msg", "userId 不能为空");
            return out;
        }
        Map<String, Object> data = exerciseService.list(userId.trim());
        out.put("code", Boolean.TRUE.equals(data.get("success")) ? 200
                : Integer.parseInt(String.valueOf(data.getOrDefault("code", 500))));
        out.put("msg", Boolean.TRUE.equals(data.get("success")) ? "ok" : String.valueOf(data.get("error")));
        out.put("data", data);
        return out;
    }

    /**
     * 提交作答并自动判分（客观题）。请求体：{@code {userId, questionId, answer}}。
     *
     * <p>判分后才返回标准答案与解析；同一学生同一题重做是<b>覆盖</b>而不是新增
     * （否则反复重做会把班级正确率刷歪）。
     */
    @PostMapping("/exercise/submit")
    public Map<String, Object> exerciseSubmit(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String userId = body == null ? "" : String.valueOf(body.getOrDefault("userId", "")).trim();
        if (userId.isBlank()) {
            out.put("code", 400);
            out.put("msg", "userId 不能为空");
            return out;
        }
        Object qid = body.get("questionId");
        Long questionId = qid instanceof Number n ? n.longValue() : null;
        if (questionId == null && qid != null) {
            try {
                questionId = Long.parseLong(String.valueOf(qid).trim());
            } catch (NumberFormatException ignore) {
                // 下面按"questionId 不能为空"处理
            }
        }
        String answer = String.valueOf(body.getOrDefault("answer", ""));
        Map<String, Object> data = exerciseService.submit(userId, questionId, answer);
        boolean success = Boolean.TRUE.equals(data.get("success"));
        out.put("code", success ? 200 : Integer.parseInt(String.valueOf(data.getOrDefault("code", 500))));
        out.put("msg", success ? "已提交并判分" : String.valueOf(data.get("error")));
        out.put("data", data);
        return out;
    }

    @PostMapping("/weekly-report")
    public ResponseEntity<byte[]> weeklyReport(@RequestBody(required = false) Map<String, Object> body) {
        String userId = body == null ? "" : str(body.get("userId"));
        if (userId.isBlank()) {
            return jsonError(400, "userId 不能为空");
        }
        int days = 7;
        Object daysValue = body.get("days");
        if (daysValue instanceof Number n) {
            days = n.intValue();
        } else if (daysValue != null) {
            try {
                days = Integer.parseInt(String.valueOf(daysValue).trim());
            } catch (NumberFormatException ignore) {
                // 保持默认 7 天
            }
        }

        try {
            byte[] bytes = reportService.buildWeeklyReport(userId, days);
            String fileName = "学习周报_" + userId + "_"
                    + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".docx";
            // 中文文件名必须走 RFC 5987 的 filename*，否则某些浏览器会得到乱码文件名
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"weekly-report.docx\"; filename*=UTF-8''" + encoded)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(MediaType.parseMediaType(DOCX_MIME))
                    .body(bytes);
        } catch (Exception e) {
            log.error("学生周报导出失败：{}", e.toString());
            return jsonError(500, "周报生成失败：" + e.getMessage());
        }
    }

    private static ResponseEntity<byte[]> jsonError(int code, String msg) {
        String json = "{\"code\":" + code + ",\"msg\":\"" + msg.replace("\"", "'") + "\",\"data\":null}";
        return ResponseEntity.status(code)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
