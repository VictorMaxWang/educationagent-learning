package com.demo.study.controller;

import com.demo.study.maimemo.MaimemoConfigProperties;
import com.demo.study.maimemo.MaimemoService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 英语学习（墨墨背单词）接口。
 * <p>
 * 词库：generate（AI 生成）/ words / update-status / add-words / review-today
 * 查词：lookup-word / lookup-text
 * 听力：listen-dictation / listen-passage / audio（音频文件）
 * 同步：sync / config
 */
@Slf4j
@RestController
@RequestMapping("/api/maimemo")
public class MaimemoController {

    @Resource
    private MaimemoService maimemoService;

    @Resource
    private MaimemoConfigProperties config;

    /** AI 生成词库 */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Map<String, String> payload) {
        String bookType = payload.getOrDefault("bookType", "cet4");
        int count = parseInt(payload.get("count"), 50);
        String difficulty = payload.getOrDefault("difficulty", "中等");
        boolean withExamples = !"false".equals(payload.get("withExamples"));
        return ResponseEntity.ok(maimemoService.generate(bookType, count, difficulty, withExamples));
    }

    /** 词库列表（支持按 词库类型/状态/生词本 过滤） */
    @GetMapping("/words")
    public ResponseEntity<?> words(@RequestParam(required = false) String bookType,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) Integer notebook) {
        return ResponseEntity.ok(maimemoService.getWords(bookType, status, notebook));
    }

    /** 文本导入词库（每行一个词，或 word|释义|音标） */
    @PostMapping("/import")
    public ResponseEntity<?> importWords(@RequestBody Map<String, String> payload) {
        String bookType = payload.getOrDefault("bookType", "cet4");
        String content = payload.get("content");
        return ResponseEntity.ok(maimemoService.importWords(bookType, content));
    }

    /** 加入/移出生词本 */
    @PostMapping("/notebook")
    public ResponseEntity<?> notebook(@RequestBody Map<String, String> payload) {
        String word = payload.get("word");
        if (word == null || word.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "请输入单词"));
        }
        boolean inNotebook = Boolean.parseBoolean(payload.getOrDefault("inNotebook", "true"));
        return ResponseEntity.ok(maimemoService.setNotebook(word, inNotebook));
    }

    /** 更新复习状态 */
    @PostMapping("/update-status")
    public ResponseEntity<?> updateStatus(@RequestBody Map<String, String> payload) {
        Long id = payload.get("id") == null ? null : Long.valueOf(payload.get("id"));
        if (id == null) return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "缺少 id"));
        return ResponseEntity.ok(maimemoService.updateStatus(id, payload.getOrDefault("status", "new")));
    }

    /** 批量加入词库 */
    @PostMapping("/add-words")
    public ResponseEntity<?> addWords(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<String> words = payload.get("words") == null ? List.of()
                : ((List<?>) payload.get("words")).stream().map(String::valueOf).toList();
        boolean sync = Boolean.TRUE.equals(payload.get("syncToMaimemo"));
        String source = (String) payload.get("source");
        return ResponseEntity.ok(maimemoService.addWords(words, sync, source));
    }

    /** 今日待复习（艾宾浩斯到期词；focus=weak 只刷难词） */
    @PostMapping("/review-today")
    public ResponseEntity<?> reviewToday(@RequestBody(required = false) Map<String, String> payload) {
        String focus = payload == null ? null : payload.get("focus");
        return ResponseEntity.ok(maimemoService.reviewToday(focus));
    }

    /** 复习统计（遗忘曲线排期：等级分布 + 各时间窗到期数） */
    @GetMapping("/review-stats")
    public ResponseEntity<?> reviewStats() {
        return ResponseEntity.ok(maimemoService.reviewStats());
    }

    /** 复习判分：correct=答对，revisit=模糊(明天再刷)；更新艾宾浩斯等级与下次复习时间 */
    @PostMapping("/review-answer")
    public ResponseEntity<?> reviewAnswer(@RequestBody Map<String, String> payload) {
        Long id = payload.get("wordId") == null ? null : Long.valueOf(payload.get("wordId"));
        if (id == null) return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "缺少 wordId"));
        boolean correct = Boolean.parseBoolean(payload.getOrDefault("correct", "false"));
        boolean revisit = Boolean.parseBoolean(payload.getOrDefault("revisit", "false"));
        return ResponseEntity.ok(maimemoService.reviewAnswer(id, correct, revisit));
    }

    /** 单词查释义 */
    @PostMapping("/lookup-word")
    public ResponseEntity<?> lookupWord(@RequestBody Map<String, String> payload) {
        String word = payload.get("word");
        if (word == null || word.isBlank()) return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "请输入单词"));
        return ResponseEntity.ok(maimemoService.lookupWord(word.trim().toLowerCase()));
    }

    /** 文本批量提取生词 */
    @PostMapping("/lookup-text")
    public ResponseEntity<?> lookupText(@RequestBody Map<String, String> payload) {
        String text = payload.get("text");
        if (text == null || text.isBlank()) return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "请输入文本"));
        int limit = parseInt(payload.get("limit"), 30);
        return ResponseEntity.ok(maimemoService.lookupText(text, limit));
    }

    /** 单词听写包 */
    @PostMapping("/listen-dictation")
    public ResponseEntity<?> listenDictation(@RequestBody Map<String, String> payload) {
        int count = parseInt(payload.get("count"), 10);
        return ResponseEntity.ok(maimemoService.listenDictation(count));
    }

    /** AI 短文听力 */
    @PostMapping("/listen-passage")
    public ResponseEntity<?> listenPassage(@RequestBody Map<String, String> payload) {
        int count = parseInt(payload.get("count"), 10);
        boolean withQuestions = !"false".equals(payload.get("withQuestions"));
        return ResponseEntity.ok(maimemoService.listenPassage(count, withQuestions));
    }

    /** 墨墨同步 */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<String> words = payload.get("words") == null ? List.of()
                : ((List<?>) payload.get("words")).stream().map(String::valueOf).toList();
        return ResponseEntity.ok(maimemoService.sync((String) payload.get("bookType"), words));
    }

    /** 语法专项：按语法点生成规则讲解 + 练习 */
    @PostMapping("/grammar-quiz")
    public ResponseEntity<?> grammarQuiz(@RequestBody Map<String, String> payload) {
        String topic = payload.get("topic");
        if (topic == null || topic.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "请输入语法点"));
        }
        return ResponseEntity.ok(maimemoService.grammarQuiz(topic.trim()));
    }

    /** 配置（autoSync 等） */
    @GetMapping("/config")
    public ResponseEntity<?> config() {
        return ResponseEntity.ok(maimemoService.getConfig());
    }

    /** 听力音频文件 */
    @GetMapping("/audio/{fileName:.+}")
    public ResponseEntity<FileSystemResource> audio(@PathVariable String fileName) {
        Path audioRoot = Paths.get(config.getAudioDir()).toAbsolutePath().normalize();
        Path file = audioRoot.resolve(fileName).normalize();
        if (!file.startsWith(audioRoot)) {
            return ResponseEntity.badRequest().build();
        }
        FileSystemResource resource = new FileSystemResource(file);
        if (!resource.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(resource);
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}
