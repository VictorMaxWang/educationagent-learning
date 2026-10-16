package com.demo.edu.classroom.recommendation;

import com.demo.edu.classroom.ClassroomException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 推荐资源的公开响应仅包含已验证卡片，私有决策证据不经此接口输出。 */
@RestController
@RequestMapping("/api/classroom/courses/{courseId}/recommendations")
public class ClassroomRecommendationController {
    private final ClassroomRecommendationService recommendations;
    public ClassroomRecommendationController(ClassroomRecommendationService recommendations){this.recommendations=recommendations;}
    @GetMapping public ObjectNode get(@PathVariable String courseId,@RequestParam(required=false)String sceneId){return recommendations.get(courseId,sceneId);}
    @PostMapping("/refresh") public ObjectNode refresh(@PathVariable String courseId,@RequestBody(required=false)ClassroomRecommendationService.RefreshRequest input){return recommendations.refresh(courseId,input);}
    @PostMapping("/feedback") public ObjectNode feedback(@PathVariable String courseId,@RequestBody ClassroomRecommendationService.FeedbackRequest input){return recommendations.feedback(courseId,input);}
    @ExceptionHandler(ClassroomException.class) public ResponseEntity<Map<String,String>> error(ClassroomException error){return ResponseEntity.status(error.status()).body(Map.of("error",error.getMessage()));}
}
