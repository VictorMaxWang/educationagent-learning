package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** 课程生成端口，测试使用假生成器，不启动应用或连接供应商。 */
public interface ClassroomEngine {
    JsonNode outline(ObjectNode input, ClassroomCancellation cancellation);
    ObjectNode scene(ObjectNode input, JsonNode outline, ClassroomCancellation cancellation);
    Map<String, Object> health();
}
