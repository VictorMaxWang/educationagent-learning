package com.demo.edu.classroom.study.feedback;

import com.demo.edu.classroom.ClassroomException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 共享classroom身份下的可选学习过程API；不接受客户端自报成绩、资料正文或模型结果。 */
@RestController
@RequestMapping("/api/classroom")
public class LearningFeedbackController {
    private final LearningFeedbackService feedback;
    public LearningFeedbackController(LearningFeedbackService feedback) { this.feedback = feedback; }

    @GetMapping("/courses/{courseId}/learning-attempts")
    public ObjectNode history(@PathVariable String courseId, @RequestParam(required = false) String sceneId) {
        return feedback.history(courseId, sceneId);
    }
    @PostMapping("/courses/{courseId}/learning-attempts")
    public ObjectNode create(@PathVariable String courseId, @RequestBody LearningFeedbackService.CreateRequest request) {
        return feedback.create(courseId, request);
    }
    @PostMapping("/learning-attempts/{id}/check")
    public ObjectNode check(@PathVariable String id, @RequestBody(required = false) LearningFeedbackService.CheckRequest request) {
        return feedback.check(id, request);
    }
    @PostMapping("/learning-attempts/{id}/hint")
    public ObjectNode hint(@PathVariable String id, @RequestBody(required = false) LearningFeedbackService.HintRequest request) {
        return feedback.hint(id, request);
    }
    @PostMapping("/learning-attempts/{id}/self-check")
    public ObjectNode selfCheck(@PathVariable String id, @RequestBody(required = false) LearningFeedbackService.SelfCheckRequest request) {
        return feedback.selfCheck(id, request);
    }
    @ExceptionHandler(ClassroomException.class)
    public ResponseEntity<Map<String, String>> error(ClassroomException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("error", exception.getMessage()));
    }
}
