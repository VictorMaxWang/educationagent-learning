package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/** 仅保存浏览器已应用的课堂状态，不把模型提出的动作当作执行成功。 */
final class ClassroomRuntimeSupport {
    private ClassroomRuntimeSupport() {}

    static ObjectNode merge(ObjectNode course,ObjectNode scene,JsonNode whiteboard,JsonNode interactiveState,
                            JsonNode acknowledgements,JsonNode actionResults) {
        JsonNode prior=course.path("runtime").path(scene.path("id").asText());
        ObjectNode next=prior.isObject()?((ObjectNode)prior).deepCopy():JsonNodeFactory.instance.objectNode();
        if(whiteboard!=null&&!whiteboard.isNull()) next.set("whiteboard",whiteboard(whiteboard));
        if(interactiveState!=null&&!interactiveState.isNull()) {
            if(!"interactive".equals(scene.path("type").asText())) throw bad("非互动页面不能保存实验状态");
            next.set("interactiveState",ClassroomEditingSupport.boundedState(interactiveState,32768));
        }
        Set<String> known=new HashSet<>();
        for(JsonNode action:scene.path("actions")) known.add(action.path("id").asText());
        for(JsonNode message:course.path("messages")) if(scene.path("id").asText().equals(message.path("sceneId").asText())&&"teacher".equals(message.path("speaker").asText()))
            for(JsonNode action:message.path("actions")) known.add(action.path("id").asText());
        for(JsonNode id:prior.path("acknowledgedActionIds")) known.add(id.asText());
        for(JsonNode result:prior.path("actionResults")) known.add(result.path("actionId").asText());
        if(acknowledgements!=null&&!acknowledgements.isNull()) {
            if(!acknowledgements.isArray()||acknowledgements.size()>2000) throw bad("动作确认记录过多或无效");
            Set<String> accepted=new LinkedHashSet<>();
            for(JsonNode id:prior.path("acknowledgedActionIds")) accepted.add(id.asText());
            for(JsonNode id:acknowledgements) {
                if(!id.isTextual()||!known.contains(id.asText())) throw bad("不能确认未下发的课堂动作");
                accepted.add(id.asText());
            }
            if(accepted.size()>2000) throw bad("动作确认记录已达上限");
            ArrayNode values=next.putArray("acknowledgedActionIds"); accepted.forEach(values::add);
        }
        if(actionResults!=null&&!actionResults.isNull()) {
            if(!actionResults.isArray()||actionResults.size()>50) throw bad("动作结果最多保存50条");
            LinkedHashMap<String,ObjectNode> results=new LinkedHashMap<>();
            for(JsonNode item:prior.path("actionResults")) if(item.isObject()) results.put(item.path("actionId").asText(),(ObjectNode)item.deepCopy());
            for(JsonNode item:actionResults) {
                String id=ClassroomEditingSupport.requiredText(item,"actionId",128);
                if(!known.contains(id)||!item.path("success").isBoolean()) throw bad("动作执行结果不属于当前课堂");
                ObjectNode accepted=JsonNodeFactory.instance.objectNode().put("actionId",id).put("success",item.path("success").asBoolean());
                if(item.has("error")) {
                    if(!item.path("error").isTextual()||item.path("error").asText().length()>300) throw bad("动作错误说明过长");
                    accepted.put("error",item.path("error").asText());
                }
                JsonNode at=item.path("at");
                if(at.isNumber()&&Double.isFinite(at.asDouble())&&at.asDouble()>=0&&at.asDouble()<1e16) accepted.set("at",at.deepCopy());
                else if(at.isTextual()&&at.asText().length()<=64) {
                    try { Instant.parse(at.asText()); } catch(Exception invalid) { throw bad("动作结果时间不是 ISO 时间"); }
                    accepted.put("at",at.asText());
                } else if(at.isMissingNode()) accepted.put("at",Instant.now().toString());
                else throw bad("动作结果时间无效");
                if(!accepted.path("success").asBoolean()) for(JsonNode ack:next.path("acknowledgedActionIds"))
                    if(id.equals(ack.asText())) throw bad("失败动作不能同时标记为已成功执行");
                results.remove(id); results.put(id,accepted);
            }
            while(results.size()>50) results.remove(results.keySet().iterator().next());
            ArrayNode values=next.putArray("actionResults"); results.values().forEach(values::add);
        }
        if(next.toString().length()>1_600_000) throw bad("课堂运行状态过大");
        return next;
    }

    static ObjectNode whiteboard(JsonNode value) {
        if(!value.isObject()) throw bad("白板状态无效");
        ObjectNode result=JsonNodeFactory.instance.objectNode().put("id",ClassroomEditingSupport.requiredText(value,"id",128));
        result.set("canvas",ClassroomEditingSupport.canvas(value.path("canvas")));
        if(value.has("isOpen")&&!value.path("isOpen").isBoolean()) throw bad("白板显隐状态无效");
        result.put("isOpen",value.path("isOpen").asBoolean(true)); return result;
    }

    static String fingerprint(JsonNode node) { return ClassroomElementResolver.sha256(canonical(node).toString()); }
    private static JsonNode canonical(JsonNode node) {
        if(node.isObject()) {
            ObjectNode sorted=JsonNodeFactory.instance.objectNode(); TreeSet<String> keys=new TreeSet<>(); node.fieldNames().forEachRemaining(keys::add);
            for(String key:keys) sorted.set(key,canonical(node.get(key))); return sorted;
        }
        if(node.isArray()) { ArrayNode array=JsonNodeFactory.instance.arrayNode(); for(JsonNode value:node) array.add(canonical(value)); return array; }
        return node.deepCopy();
    }
    private static ClassroomException bad(String message) { return new ClassroomException(422,message); }
}
