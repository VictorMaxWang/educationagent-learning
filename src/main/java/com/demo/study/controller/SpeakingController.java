package com.demo.study.controller;

import com.demo.study.english.speaking.SpeakingPracticeService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 口语练习接口。
 * <p>
 * POST /api/maimemo/speech-evaluate —— 文本 + 录音 → 讯飞 ISE 评测 + LLM 反馈
 * GET  /api/maimemo/speech-preset —— 预设练习文本
 */
@Slf4j
@RestController
@RequestMapping("/api/maimemo")
public class SpeakingController {

    @Resource
    private SpeakingPracticeService speakingPracticeService;

    /** 口语评测 */
    @PostMapping("/speech-evaluate")
    public ResponseEntity<?> evaluate(@RequestBody Map<String, String> payload) {
        String text = payload.get("text");
        String audioBase64 = payload.get("audioBase64");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "缺少目标文本"));
        }
        if (audioBase64 == null || audioBase64.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "缺少录音音频，请先录音"));
        }
        try {
            return ResponseEntity.ok(speakingPracticeService.evaluate(text.trim(), audioBase64));
        } catch (Exception e) {
            log.error("口语评测异常", e);
            return ResponseEntity.ok(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /** 预设练习文本 */
    @GetMapping("/speech-preset")
    public ResponseEntity<?> preset() {
        return ResponseEntity.ok(Map.of("code", 200, "items", speakingPracticeService.presetTexts()));
    }

    /** 情景对话：按场景+篇幅生成双人对话剧本（用户扮演 B 角色逐句跟读） */
    @PostMapping("/speech-dialogue")
    public ResponseEntity<?> dialogue(@RequestBody Map<String, String> payload) {
        String scenario = payload.get("scenario");
        if (scenario == null || scenario.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "请输入场景"));
        }
        return ResponseEntity.ok(speakingPracticeService.dialogue(scenario.trim(), payload.get("length")));
    }
}
