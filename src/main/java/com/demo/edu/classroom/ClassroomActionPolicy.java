package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import java.util.*;

/** 对导师命令逐类校验；返回的动作只表示待执行，执行确认由 runtime 接口保存。 */
final class ClassroomActionPolicy {
    static final Set<String> TYPES=Set.of("speech","spotlight","laser","discussion","play_video",
            "widget_setState","widget_highlight","widget_annotation","widget_reveal",
            "wb_open","wb_close","wb_clear","wb_delete","wb_draw_text","wb_draw_shape","wb_draw_latex",
            "wb_draw_line","wb_draw_chart","wb_draw_table","wb_draw_code");
    private ClassroomActionPolicy() {}

    static ArrayNode validate(JsonNode input,ObjectNode scene,JsonNode runtime,boolean teacher) {
        ArrayNode result=JsonNodeFactory.instance.arrayNode();
        if(input==null||input.isMissingNode()||input.isNull()) return result;
        if(!input.isArray()||input.size()>100) throw bad("课堂动作必须为不超过100项的数组");
        if(!teacher) return result;
        Map<String,String> slideElements=new HashMap<>();
        for(JsonNode element:scene.path("content").path("canvas").path("elements")) slideElements.put(element.path("id").asText(),element.path("type").asText());
        Set<String> boardElements=new HashSet<>();
        for(JsonNode e:runtime.path("whiteboard").path("canvas").path("elements")) boardElements.add(e.path("id").asText());
        for(JsonNode board:scene.path("whiteboards")) for(JsonNode e:board.path("elements")) boardElements.add(e.path("id").asText());
        Set<String> ids=new HashSet<>();
        for(JsonNode raw:input) {
            if(!raw.isObject()||raw.toString().length()>50000) throw bad("课堂动作格式无效或过大");
            ObjectNode action=(ObjectNode)raw.deepCopy();
            String type=action.path("type").asText(); if(!TYPES.contains(type)) throw bad("不支持的课堂动作："+type);
            if(!action.hasNonNull("id")) action.put("id",UUID.randomUUID().toString());
            if(!ids.add(text(action,"id",128))) throw bad("课堂动作 ID 重复");
            validateColors(action);
            if(type.startsWith("widget_")) {
                if(!"interactive".equals(scene.path("type").asText())) throw bad("实验控制动作只能用于互动页面");
                if(type.equals("widget_setState")) action.set("state",ClassroomEditingSupport.boundedState(action.path("state"),16000));
                else ClassroomElementResolver.interactiveTarget(scene,text(action,"target",128));
                if(action.has("content")) action.put("content",plain(action.path("content"),2000));
            } else if(type.startsWith("wb_")) {
                if(type.equals("wb_clear")) boardElements.clear();
                else if(type.equals("wb_delete")) {
                    if(!boardElements.remove(text(action,"elementId",128))) throw bad("要删除的白板对象不存在");
                } else if(type.startsWith("wb_draw_")) {
                    if(!action.hasNonNull("elementId")) action.put("elementId","wb-"+UUID.randomUUID());
                    if(!boardElements.add(text(action,"elementId",128))) throw bad("白板对象 ID 重复");
                    draw(action,type);
                }
            } else switch(type) {
                case "speech" -> text(action,"text",6000);
                case "spotlight","laser","play_video" -> {
                    String target=text(action,"elementId",128);
                    if(!"slide".equals(scene.path("type").asText())||!slideElements.containsKey(target)) throw bad("视觉动作引用了不存在的画布对象");
                    if(type.equals("play_video")&&!"video".equals(slideElements.get(target))) throw bad("播放目标不是视频对象");
                    if(action.has("dimOpacity")) finite(action,"dimOpacity",false,0,1);
                }
                case "discussion" -> { text(action,"topic",2000); action.put("agentId","peer"); }
                default -> throw bad("不支持的课堂动作");
            }
            result.add(action);
        }
        return result;
    }

    private static void draw(ObjectNode action,String type) {
        if(type.equals("wb_draw_line")) {
            for(String field:List.of("startX","startY","endX","endY")) finite(action,field,false,-10000,10000);
            finite(action,"width",true,0.1,100); return;
        }
        finite(action,"x",false,-10000,10000); finite(action,"y",false,-10000,10000);
        boolean optional=Set.of("wb_draw_text","wb_draw_latex","wb_draw_code").contains(type);
        finite(action,"width",optional,0.1,10000); finite(action,"height",optional,0.1,10000);
        switch(type) {
            case "wb_draw_text" -> { action.put("content",ClassroomSlideSanitizer.cleanHtml(text(action,"content",6000))); finite(action,"fontSize",true,1,500); }
            case "wb_draw_shape" -> { if(!Set.of("rectangle","circle","triangle").contains(action.path("shape").asText())) throw bad("不支持的白板形状"); }
            case "wb_draw_latex" -> text(action,"latex",8000);
            case "wb_draw_code" -> { text(action,"language",80); text(action,"code",16000); }
            case "wb_draw_table" -> {
                // 与浏览器白板的真实绘制预算一致，避免批准一个必然无法执行的动作。
                JsonNode rows=action.path("data"); if(!rows.isArray()||rows.isEmpty()||rows.size()>30) throw bad("白板表格行数无效");
                int columns=-1;
                ArrayNode cleaned=JsonNodeFactory.instance.arrayNode();
                for(JsonNode row:rows) {
                    if(!row.isArray()||row.isEmpty()||row.size()>15||(columns>=0&&columns!=row.size())) throw bad("白板表格列数不一致或过多");
                    columns=row.size(); ArrayNode cleanRow=cleaned.addArray();
                    for(JsonNode cell:row) {
                        if(!cell.isTextual()||cell.asText().length()>1000) throw bad("白板单元格无效");
                        cleanRow.add(ClassroomSlideSanitizer.cleanHtml(cell.asText()));
                    }
                }
                action.set("data",cleaned);
            }
            case "wb_draw_chart" -> {
                if(!Set.of("bar","column","line","pie","ring","area","radar","scatter").contains(action.path("chartType").asText())) throw bad("不支持的白板图表");
                JsonNode data=action.path("data"); ClassroomEditingSupport.boundedState(data,30000);
                if(!data.path("labels").isArray()||data.path("labels").size()>100||!data.path("legends").isArray()
                        ||!data.path("series").isArray()||data.path("series").isEmpty()||data.path("series").size()>20) throw bad("白板图表数据无效");
                for(JsonNode series:data.path("series")) {
                    if(!series.isArray()||series.size()>100) throw bad("图表序列过大");
                    for(JsonNode number:series) if(!number.isNumber()||!Double.isFinite(number.asDouble())) throw bad("图表数据必须为有限数值");
                }
            }
            default -> throw bad("不支持的白板绘制动作");
        }
    }
    private static void validateColors(JsonNode node) {
        if(node.isObject()) node.fields().forEachRemaining(entry -> {
            String key=entry.getKey(); JsonNode value=entry.getValue();
            if(Set.of("color","fillColor").contains(key)) color(value);
            else if(key.equals("themeColors")) {
                if(!value.isArray()||value.size()>20) throw bad("图表颜色列表无效");
                value.forEach(ClassroomActionPolicy::color);
            } else if(value.isContainerNode()&&!key.equals("state")) validateColors(value);
        });
        else if(node.isArray()) node.forEach(ClassroomActionPolicy::validateColors);
    }
    private static void color(JsonNode value) {
        if(!value.isTextual()||!value.asText().matches("(?i)(#[0-9a-f]{3,8}|[a-z]{1,30}|(?:rgb|rgba|hsl|hsla)\\([0-9.,%+\\- /]+\\))"))
            throw bad("绘图颜色格式无效");
    }
    private static String plain(JsonNode value,int max) {
        if(!value.isTextual()||value.asText().length()>max) throw bad("动作说明无效");
        return Jsoup.parse(value.asText()).text();
    }
    private static String text(JsonNode node,String field,int max) { return ClassroomEditingSupport.requiredText(node,field,max); }
    private static void finite(JsonNode n,String f,boolean optional,double min,double max) { ClassroomEditingSupport.finite(n,f,optional,min,max); }
    private static ClassroomException bad(String message) { return new ClassroomException(422,message); }
}
