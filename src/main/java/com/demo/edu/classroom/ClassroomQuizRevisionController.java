package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 登记只开放既有本机运维路径；公开课堂接口仅返回修订跳转的白名单字段。 */
@RestController
public class ClassroomQuizRevisionController {
    private final ClassroomQuizRevisionRegistry revisions;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ClassroomPublicationPolicy publication;

    public ClassroomQuizRevisionController(ClassroomQuizRevisionRegistry revisions) { this.revisions = revisions; }

    @PostMapping("/api/internal/quiz-revisions")
    public ObjectNode register(@RequestBody ClassroomQuizRevisionRegistry.RegisterRequest request) {
        return revisions.register(request);
    }

    @GetMapping("/api/classroom/courses/{courseId}/quiz-revisions")
    public ObjectNode list(@PathVariable String courseId) {
        if (publication != null) publication.requireActive(courseId);
        return revisions.publicRevisions(courseId);
    }

    @ExceptionHandler(ClassroomException.class)
    public ResponseEntity<Map<String, String>> error(ClassroomException error) {
        return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
    }
}
