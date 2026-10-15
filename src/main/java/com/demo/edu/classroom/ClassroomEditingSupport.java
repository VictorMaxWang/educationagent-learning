package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 课堂编辑的纯校验与变换；不调用模型、不执行 HTML、也不直接访问数据库。 */
final class ClassroomEditingSupport {
    private static final Set<String> ELEMENT_TYPES=Set.of("text","shape","line","chart","table","latex","image","code","video","audio");
    private static final Set<String> MEDIA_FIELDS=Set.of("src","url","href","pattern","poster","backgroundimage");
    private ClassroomEditingSupport() {}

    static long revision(ObjectNode course) { return course.path("revision").asLong(0); }
    static long bump(ObjectNode course) { long next=revision(course)+1; course.put("revision",next); return next; }
    static void requireRevision(ObjectNode course,Long expected,boolean required) {
        if (expected==null) { if(required) throw bad("请提供 expectedRevision"); return; }
        if(expected<0 || expected!=revision(course)) throw new ClassroomException(409,"课程已更新，请刷新后再保存");
    }
    static void initialize(ObjectNode course) {
        if(!course.has("revision")) course.put("revision",0);
        if(!course.path("runtime").isObject()) course.putObject("runtime");
        if(!course.path("runtimeOperations").isObject()) course.putObject("runtimeOperations");
    }

    static ObjectNode editedScene(ObjectNode original,JsonNode content,JsonNode actions,String courseId) {
        if("quiz".equals(original.path("type").asText())) throw new ClassroomException(409,"已关联题库的练习不能覆盖编辑，请新建课程保留历史成绩");
        if(content==null || !content.isObject() || !original.path("type").asText().equals(content.path("type").asText()))
            throw bad("编辑内容类型必须与当前页面一致");
        ObjectNode candidate=original.deepCopy(); candidate.set("content",content.deepCopy());
        if(actions!=null && !actions.isNull()) candidate.set("actions",actions.deepCopy());
        else pruneDanglingActions(candidate);
        ObjectNode outline=JsonNodeFactory.instance.objectNode().put("id",original.path("id").asText())
                .put("type",original.path("type").asText()).put("order",original.path("order").asInt());
        return ClassroomDocumentValidator.scene(candidate,outline,courseId);
    }

    /** 删除对象时同步清理旧讲稿中指向该对象的视觉动作，避免编辑器被旧 spotlight 阻塞。 */
    private static void pruneDanglingActions(ObjectNode scene) {
        if(!scene.path("actions").isArray()) return;
        Set<String> ids=new HashSet<>();
        for(JsonNode element:scene.path("content").path("canvas").path("elements")) ids.add(element.path("id").asText());
        ArrayNode kept=JsonNodeFactory.instance.arrayNode();
        for(JsonNode action:scene.path("actions")) {
            String type=action.path("type").asText();
            if(Set.of("spotlight","laser","play_video").contains(type) && !ids.contains(action.path("elementId").asText())) continue;
            if(type.startsWith("widget_") && action.has("target") && !ClassroomElementResolver.hasInteractiveTarget(scene,action.path("target").asText())) continue;
            kept.add(action.deepCopy());
        }
        scene.set("actions",kept);
    }

    static ObjectNode applyEdits(ObjectNode original,JsonNode edits,String courseId) {
        if(edits==null || edits.isNull() || edits.isEmpty()) return original.deepCopy();
        if(!edits.isArray() || edits.size()>50 || edits.toString().length()>1_300_000) throw bad("模型编辑操作过多或格式无效");
        if("quiz".equals(original.path("type").asText())) throw new ClassroomException(409,"不能修改已关联题库的练习");
        ObjectNode content=(ObjectNode)original.path("content").deepCopy();
        if("interactive".equals(original.path("type").asText())) {
            if(edits.size()!=1 || !"replace_html".equals(edits.get(0).path("op").asText()) || !edits.get(0).path("html").isTextual())
                throw bad("互动页面编辑须使用单次 replace_html 操作");
            content.put("html",edits.get(0).path("html").asText());
        } else {
            ArrayNode elements=(ArrayNode)content.path("canvas").path("elements");
            for(JsonNode edit:edits) {
                String operation=edit.path("op").asText();
                if(operation.equals("add")) {
                    if(!edit.path("element").isObject()) throw bad("新增对象缺少 element");
                    ObjectNode element=(ObjectNode)edit.path("element").deepCopy();
                    if(!element.hasNonNull("id")) element.put("id",UUID.randomUUID().toString());
                    elements.add(element); continue;
                }
                String id=edit.path("elementId").asText(); int index=-1;
                for(int i=0;i<elements.size();i++) if(id.equals(elements.get(i).path("id").asText())) {index=i;break;}
                if(index<0) throw bad("编辑对象已不存在，请重新选择");
                if(operation.equals("delete")) elements.remove(index);
                else if(operation.equals("update")) {
                    JsonNode changes=edit.path("changes");
                    if(!changes.isObject() || changes.has("id") || changes.has("type")) throw bad("对象编辑不能替换 id/type");
                    ObjectNode element=(ObjectNode)elements.get(index);
                    changes.fields().forEachRemaining(entry -> element.set(entry.getKey(),entry.getValue().deepCopy()));
                } else throw bad("不支持的对象编辑操作");
            }
        }
        return editedScene(original,content,null,courseId);
    }

    static ObjectNode canvas(JsonNode input) {
        if(!input.isObject() || !input.path("elements").isArray() || input.path("elements").size()>1000 || input.toString().length()>1_500_000)
            throw bad("画布结构无效或对象过多");
        ObjectNode canvas=(ObjectNode)input.deepCopy();
        finite(canvas,"viewportSize",true,1,100000); finite(canvas,"viewportRatio",true,0.01,100);
        Set<String> ids=new HashSet<>();
        for(JsonNode element:canvas.path("elements")) {
            if(!element.isObject()) throw bad("画布对象格式无效");
            String id=requiredText(element,"id",128);
            if(!ids.add(id)) throw bad("画布对象 ID 重复");
            String type=requiredText(element,"type",32);
            if(!ELEMENT_TYPES.contains(type)) throw bad("不支持的画布对象类型");
            finite(element,"left",false,-100000,100000); finite(element,"top",false,-100000,100000);
            finite(element,"width",false,0,100000); finite(element,"height",type.equals("line"),0,100000);
            finite(element,"rotate",true,-36000,36000);
            if(type.equals("image") && (!element.path("src").isTextual() || element.path("src").asText().isBlank())) throw bad("图片对象缺少资源地址");
            if(type.equals("code")) {
                JsonNode lines=element.path("lines");
                if(!lines.isArray() || lines.size()>500) throw bad("代码对象的行数无效");
                Set<String> lineIds=new HashSet<>();
                for(JsonNode line:lines) {
                    if(!lineIds.add(requiredText(line,"id",128))) throw bad("代码行 ID 重复");
                    if(!line.path("content").isTextual() || line.path("content").asText().length()>4000) throw bad("代码行内容无效");
                }
            }
        }
        validateJson(canvas,0,new int[]{0}); validateMediaUris(canvas);
        return ClassroomSlideSanitizer.cleanCanvas(canvas);
    }

    /** 禁止 NaN/Infinity 和原型污染键，也限制状态数据的递归深度、节点数与文本长度。 */
    static JsonNode boundedState(JsonNode value,int maxChars) {
        if(value==null || !value.isObject() || value.toString().length()>maxChars) throw bad("实验状态格式无效或过大");
        validateJson(value,0,new int[]{0}); return value.deepCopy();
    }
    private static void validateJson(JsonNode node,int depth,int[] count) {
        if(depth>24 || ++count[0]>30000) throw bad("页面数据嵌套过深或节点过多");
        if(node.isNumber()&&!Double.isFinite(node.asDouble())) throw bad("数值必须有限");
        if(node.isObject()) node.fields().forEachRemaining(entry -> {
            if(Set.of("__proto__","constructor","prototype").contains(entry.getKey())) throw bad("不允许的状态字段");
            validateJson(entry.getValue(),depth+1,count);
        });
        else if(node.isArray()) for(JsonNode child:node) validateJson(child,depth+1,count);
    }
    private static void validateMediaUris(JsonNode node) {
        if(node.isArray()) { for(JsonNode child:node) validateMediaUris(child); }
        else if(node.isObject()) node.fields().forEachRemaining(entry -> {
            JsonNode value=entry.getValue(); String key=entry.getKey().toLowerCase(Locale.ROOT);
            if(MEDIA_FIELDS.contains(key)&&value.isTextual()&&!ClassroomSlideSanitizer.safeUrl(value.asText())) throw bad("媒体地址协议不受支持");
            if(key.equals("link")&&value.isObject()&&!ClassroomSlideSanitizer.safeUrl(value.path("target").asText())) throw bad("对象链接协议不受支持");
            if(value.isContainerNode()) validateMediaUris(value);
        });
    }
    static String requiredText(JsonNode node,String field,int max) {
        JsonNode value=node.path(field);
        if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>max) throw bad("字段 "+field+" 无效");
        return value.asText();
    }
    static void finite(JsonNode node,String field,boolean optional,double min,double max) {
        JsonNode value=node.path(field);
        if(optional&&value.isMissingNode()) return;
        if(!value.isNumber()||!Double.isFinite(value.asDouble())||value.asDouble()<min||value.asDouble()>max) throw bad("字段 "+field+" 不是合法有限数值");
    }
    static ClassroomException bad(String message) { return new ClassroomException(422,message); }
}
