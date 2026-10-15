package com.demo.edu.classroom.guide;

import com.demo.edu.classroom.ClassroomException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 独立成长问答 API。严查原始 JSON 类型，避免 Jackson 把 1.5 年级静默截为 1。 */
@RestController
@RequestMapping("/api/classroom/guide")
public class GuideController {
    private final GuideService service;
    public GuideController(GuideService service) { this.service = service; }

    @GetMapping("/topics") public GuideTopics.TopicResponse topics(@RequestParam Integer grade) { return service.topics(grade); }

    @PostMapping("/chat") public GuideService.Reply chat(@RequestBody JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        JsonNode grade = body.path("grade");
        if (!grade.isIntegralNumber() || !grade.canConvertToInt()) throw new ClassroomException(400, "年级必须是 1 至 12 的整数");
        String question = text(body, "question");
        String category = text(body, "category");
        JsonNode history = body.path("history");
        List<GuideService.HistoryMessage> items = new ArrayList<>();
        if (!history.isMissingNode() && !history.isNull()) {
            if (!history.isArray() || history.size() > GuideService.MAX_HISTORY) throw new ClassroomException(400, "近期对话必须是最多 12 条的列表");
            for (JsonNode item : history) {
                if (!item.isObject()) throw invalid();
                items.add(new GuideService.HistoryMessage(text(item, "role"), text(item, "content")));
            }
        }
        return service.chat(new GuideService.ChatRequest(question, grade.intValue(), category, items));
    }

    private static String text(JsonNode object, String name) {
        if (!object.path(name).isTextual()) throw invalid();
        return object.path(name).textValue();
    }
    private static ClassroomException invalid() { return new ClassroomException(400, "成长问答参数格式无效，请检查问题、年级和话题"); }
    @ExceptionHandler(ClassroomException.class) public ResponseEntity<Map<String, String>> error(ClassroomException error) {
        return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
    }
}
