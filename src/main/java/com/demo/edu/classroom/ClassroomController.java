package com.demo.edu.classroom;

import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Java 作为课堂唯一业务 API，浏览器不直连模型与 Node。 */
@RestController
@RequestMapping("/api/classroom")
public class ClassroomController {
    private final ClassroomCourseService courses;
    public ClassroomController(ClassroomCourseService courses) { this.courses = courses; }

    @GetMapping("/health") public Map<String, Object> health() { return courses.health(); }
    @GetMapping("/courses") public List<ObjectNode> list() { return courses.list(); }
    @PostMapping("/courses") public ResponseEntity<ObjectNode> create(@RequestBody ClassroomCourseService.CreateRequest request) {
        return ResponseEntity.accepted().body(courses.create(request));
    }
    @GetMapping("/courses/{id}") public ObjectNode get(@PathVariable String id) { return courses.get(id); }
    public record OutlineRequest(JsonNode outlines) {}
    @PutMapping("/courses/{id}/outline") public ObjectNode outline(@PathVariable String id, @RequestBody OutlineRequest request) {
        return courses.editOutline(id, request.outlines());
    }
    @PostMapping("/courses/{id}/generate") public ResponseEntity<ObjectNode> generate(@PathVariable String id) {
        return ResponseEntity.accepted().body(courses.generate(id));
    }
    @PostMapping("/courses/{id}/cancel") public ObjectNode cancel(@PathVariable String id) { return courses.cancel(id); }
    public record ProgressRequest(Integer sceneIndex, Integer actionIndex) {}
    @PutMapping("/courses/{id}/progress") public ObjectNode progress(@PathVariable String id, @RequestBody ProgressRequest request) {
        if (request.sceneIndex() == null || request.actionIndex() == null) throw new ClassroomException(400, "请提供完整学习位置");
        return courses.saveProgress(id, request.sceneIndex(), request.actionIndex());
    }
    @PostMapping("/courses/{id}/answer") public QuizService.SubmitResult answer(@PathVariable String id,
            @RequestBody ClassroomCourseService.AnswerRequest request) { return courses.answer(id, request); }
    @PostMapping("/courses/{id}/chat") public ObjectNode chat(@PathVariable String id,
            @RequestBody ClassroomCourseService.ChatRequest request) { return courses.chat(id, request); }
    @PostMapping("/materials") public ObjectNode materials(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > 10 * 1024 * 1024) throw new ClassroomException(400, "资料文件不能超过 10 MB");
        return courses.materials(file.getBytes(), file.getOriginalFilename());
    }

    @ExceptionHandler(ClassroomException.class)
    public ResponseEntity<Map<String, String>> error(ClassroomException error) {
        return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
    }
}
