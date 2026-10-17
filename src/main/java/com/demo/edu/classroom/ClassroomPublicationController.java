package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 仅本机维护可调用，反向代理不得向公开站点开放课程发布管理。 */
@RestController
@RequestMapping("/api/internal/course-publication")
public class ClassroomPublicationController {
    private final ClassroomPublicationPolicy policy;
    public ClassroomPublicationController(ClassroomPublicationPolicy policy) { this.policy = policy; }
    @PostMapping public ObjectNode apply(@RequestBody ClassroomPublicationPolicy.Batch batch) { return policy.apply(batch); }
    @ExceptionHandler(ClassroomException.class)
    public ResponseEntity<Map<String, String>> error(ClassroomException error) {
        return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
    }
}
