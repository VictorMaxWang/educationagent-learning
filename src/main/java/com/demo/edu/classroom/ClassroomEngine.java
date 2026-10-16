package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** 课程生成端口，测试使用假生成器，不启动应用或连接供应商。 */
public interface ClassroomEngine {
    JsonNode outline(ObjectNode input, ClassroomCancellation cancellation);
    ObjectNode scene(ObjectNode input, JsonNode outline, ClassroomCancellation cancellation);
    /** 只读补审：input 需含 questions，返回每题的两级审核结论；不生成、不修改任何内容。 */
    JsonNode review(ObjectNode input, ClassroomCancellation cancellation);
    /** 针对被标记的题生成修复方案（fix-key / regenerate / none）；只出方案，不写任何东西。 */
    JsonNode repair(ObjectNode input, ClassroomCancellation cancellation);
    Map<String, Object> health();
}
