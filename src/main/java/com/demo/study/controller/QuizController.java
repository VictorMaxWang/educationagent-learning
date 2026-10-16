package com.demo.study.controller;
import com.demo.controller.DesktopFileStore;

import com.demo.service.TtsService;
import com.demo.study.quiz.QuizException;
import com.demo.study.quiz.QuizService;
import com.demo.edu.classroom.ClassroomException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 刷题接口（桌面端）。
 * <ul>
 *   <li>POST /api/desktop/quiz/generate —— 上传的文件(fileId)解析成试卷并入库，返回题目（不含答案）</li>
 *   <li>POST /api/desktop/quiz/submit —— 提交答案，判分并写做题记录（错题本数据源）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop/quiz")
public class QuizController {

    /** 修订指针必须让所有旧判分入口一致返回409，不落回普通400或泛化500。 */
    @ExceptionHandler(ClassroomException.class)
    public ResponseEntity<Map<String,String>> classroomBoundary(ClassroomException error) {
        return ResponseEntity.status(error.status()).body(Map.of("error",error.getMessage()));
    }

    @Resource
    private QuizService quizService;

    @Resource
    private DesktopFileStore fileStore;

    @Resource
    private TtsService ttsService;

    /** 提交请求体 */
    public record SubmitRequest(Long quizId, String userId, List<QuizService.AnswerSubmission> answers) {}

    /** 单题即时判请求体 */
    public record GradeOneRequest(Long quizId, String userId, Integer no, String answer) {}

    /** 错题重做请求体 */
    public record MistakeRedoRequest(String userId, Long quizId, Long questionId, String answer) {}

    /** 知识点 toggle 请求体 */
    public record KnowledgeToggleRequest(String userId, String knowledgePoint) {}

    /** 知识点下的题目请求体 */
    public record KnowledgeQuestionsRequest(String userId, String knowledgePoint) {}

    /** 生成试卷：从 fileId 取文件字节 → 解析入库 → 返回题目 */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        String fileId = payload.get("fileId");

        DesktopFileStore.FileEntry entry = fileStore.get(fileId);
        if (entry == null || entry.bytes() == null || entry.bytes().length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不存在或已过期，请重新上传"));
        }

        try {
            // file_type 列是 VARCHAR(32)，用扩展名而非完整 MIME（docx 的 MIME 长达 72 字符会截断报错）
            String fileType = fileExtOf(entry.fileName());
            QuizService.GenerateResult result = quizService.generate(
                    userId, entry.bytes(), entry.fileName(), fileType);
            // 解析成功，消费文件缓存
            fileStore.remove(fileId);
            return ResponseEntity.ok(result);
        } catch (ClassroomException boundary) {
            // 保留稳定业务状态，让统一处理器返回409/422等；不能被下方catch-all改写为500。
            throw boundary;
        } catch (QuizException e) {
            log.warn("刷题生成失败：{}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("刷题生成异常", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "服务器异常：" + e.getMessage()));
        }
    }

    /** 单题即时判（有答案的题规则比对；无答案的题返回 auto=false，交卷时 LLM 判） */
    @PostMapping("/grade-one")
    public ResponseEntity<?> gradeOne(@RequestBody GradeOneRequest req) {
        if (req == null || req.quizId() == null || req.no() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 quizId 或题号"));
        }
        try {
            QuizService.GradeOneResult result = quizService.gradeOne(req.quizId(), req.no(), req.answer());
            return ResponseEntity.ok(result);
        } catch (ClassroomException boundary) {
            throw boundary;
        } catch (QuizException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("单题判分异常", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "服务器异常：" + e.getMessage()));
        }
    }

    /** 提交答案判分 */
    @PostMapping("/submit")
    public ResponseEntity<?> submit(@RequestBody SubmitRequest req) {
        if (req == null || req.quizId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 quizId"));
        }
        String userId = (req.userId() == null || req.userId().isBlank()) ? "desktop_user" : req.userId();
        try {
            QuizService.SubmitResult result = quizService.submit(userId, req.quizId(),
                    req.answers() == null ? List.of() : req.answers());
            return ResponseEntity.ok(result);
        } catch (ClassroomException boundary) {
            throw boundary;
        } catch (QuizException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("刷题判分异常", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "服务器异常：" + e.getMessage()));
        }
    }

    // ======================== 试卷管理 ========================

    /** 我的试卷列表 */
    @PostMapping("/papers")
    public ResponseEntity<?> papers(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        return ResponseEntity.ok(quizService.listPapers(userId));
    }

    /** 加载已有试卷（历史试卷一键再刷） */
    @PostMapping("/paper")
    public ResponseEntity<?> paper(@RequestBody Map<String, String> payload) {
        String qid = payload.get("quizId");
        if (qid == null) return ResponseEntity.badRequest().body(Map.of("error", "缺少 quizId"));
        try {
            return ResponseEntity.ok(quizService.loadPaper(Long.valueOf(qid)));
        } catch (QuizException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除试卷（级联删题目/材料/做题记录） */
    @PostMapping("/paper-delete")
    public ResponseEntity<?> paperDelete(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        String qid = payload.get("quizId");
        if (qid == null) return ResponseEntity.badRequest().body(Map.of("error", "缺少 quizId"));
        try {
            quizService.deletePaper(userId, Long.valueOf(qid));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (QuizException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 听力材料语音合成：quizId + passageNo → 返回 base64 MP3 */
    @PostMapping("/passage-audio")
    public ResponseEntity<?> passageAudio(@RequestBody Map<String, String> payload) {
        String qid = payload.get("quizId");
        String pno = payload.get("passageNo");
        if (qid == null || pno == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 quizId 或 passageNo"));
        }
        try {
            String content = quizService.getPassageContent(Long.valueOf(qid), Integer.valueOf(pno));
            if (content == null || content.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "材料不存在"));
            }
            byte[] mp3 = ttsService.synthesizeLong(content);
            if (mp3 == null) {
                return ResponseEntity.ok(Map.of("success", false, "error", "语音合成失败（TTS 未配置或额度用完）"));
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "audio", Base64.getEncoder().encodeToString(mp3),
                    "mime", "audio/mpeg"));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "参数格式错误"));
        }
    }

    // ======================== 学习模式（错题本 / 知识点） ========================

    /** 错题列表 */
    @PostMapping("/mistakes")
    public ResponseEntity<?> mistakes(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        return ResponseEntity.ok(quizService.mistakes(userId));
    }

    /** 错题重做：判分 → 答对自动移出错题本 */
    @PostMapping("/mistake-redo")
    public ResponseEntity<?> mistakeRedo(@RequestBody MistakeRedoRequest req) {
        if (req == null || req.questionId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 questionId"));
        }
        try {
            String userId = (req.userId() == null || req.userId().isBlank()) ? "desktop_user" : req.userId();
            return ResponseEntity.ok(quizService.mistakeRedo(userId, req.quizId(), req.questionId(), req.answer()));
        } catch (QuizException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 手动移出错题本 */
    @PostMapping("/mistake-remove")
    public ResponseEntity<?> mistakeRemove(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        String qid = payload.get("questionId");
        if (qid == null) return ResponseEntity.badRequest().body(Map.of("error", "缺少 questionId"));
        quizService.mistakeRemove(userId, Long.valueOf(qid));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 知识点聚合统计（含收藏态） */
    @PostMapping("/knowledge")
    public ResponseEntity<?> knowledge(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        return ResponseEntity.ok(quizService.knowledgeAgg(userId));
    }

    /** 收藏/取消收藏知识点 */
    @PostMapping("/knowledge-toggle")
    public ResponseEntity<?> knowledgeToggle(@RequestBody KnowledgeToggleRequest req) {
        if (req == null || req.knowledgePoint() == null || req.knowledgePoint().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少知识点"));
        }
        String userId = (req.userId() == null || req.userId().isBlank()) ? "desktop_user" : req.userId();
        boolean fav = quizService.knowledgeToggle(userId, req.knowledgePoint());
        return ResponseEntity.ok(Map.of("fav", fav));
    }

    /** 收藏/取消收藏题目 */
    @PostMapping("/question-fav-toggle")
    public ResponseEntity<?> questionFavToggle(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        String qid = payload.get("questionId");
        if (qid == null) return ResponseEntity.badRequest().body(Map.of("error", "缺少 questionId"));
        boolean fav = quizService.questionFavToggle(userId, Long.valueOf(qid));
        return ResponseEntity.ok(Map.of("fav", fav));
    }

    /** 收藏的题目列表 */
    @PostMapping("/fav-questions")
    public ResponseEntity<?> favQuestions(@RequestBody Map<String, String> payload) {
        String userId = payload.getOrDefault("userId", "desktop_user");
        return ResponseEntity.ok(quizService.favQuestions(userId));
    }

    /** 某知识点下的题目（复习用） */
    @PostMapping("/knowledge-questions")
    public ResponseEntity<?> knowledgeQuestions(@RequestBody KnowledgeQuestionsRequest req) {
        if (req == null || req.knowledgePoint() == null || req.knowledgePoint().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少知识点"));
        }
        String userId = (req.userId() == null || req.userId().isBlank()) ? "desktop_user" : req.userId();
        return ResponseEntity.ok(quizService.knowledgeQuestions(userId, req.knowledgePoint()));
    }

    /** 从文件名取短扩展名（如 "考试.docx" → "docx"），无扩展名返回 null */
    private String fileExtOf(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            return fileName.substring(dot + 1).toLowerCase();
        }
        return null;
    }
}
