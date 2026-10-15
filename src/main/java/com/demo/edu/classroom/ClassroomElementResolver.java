package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 浏览器只提交身份；问答证据由服务端从已保存课件解析，避免客户端伪造“当前对象”。 */
final class ClassroomElementResolver {
    private static final Set<String> REFERENCE_FIELDS=Set.of("kind","sceneId","elementId","selector","whiteboardId");
    private static final Set<String> EXCLUDED=Set.of("html","head","body","script","style","link","meta","iframe","object","embed","template","canvas");
    private ClassroomElementResolver() {}

    static ObjectNode resolve(ObjectNode course,ObjectNode scene,JsonNode reference) {
        if(reference==null || reference.isNull()) return null;
        if(!reference.isObject() || reference.toString().length()>1200) throw bad("对象引用格式无效");
        reference.fieldNames().forEachRemaining(key -> {if(!REFERENCE_FIELDS.contains(key)) throw bad("对象引用只能包含身份字段");});
        if(reference.has("sceneId")&&!scene.path("id").asText().equals(reference.path("sceneId").asText())) throw bad("对象引用不属于当前页面");
        String kind=reference.path("kind").asText();
        ObjectNode identity=JsonNodeFactory.instance.objectNode().put("kind",kind);
        ObjectNode result=JsonNodeFactory.instance.objectNode();
        JsonNode element;
        switch(kind) {
            case "slide_element" -> {
                if(!"slide".equals(scene.path("type").asText())) throw bad("当前页面不是图文画布");
                String id=ClassroomEditingSupport.requiredText(reference,"elementId",128);
                element=findElement(scene.path("content").path("canvas").path("elements"),id);
                identity.put("sceneId",scene.path("id").asText()).put("elementId",id);
                result.put("text",bounded(ClassroomCourseService.visibleText(element),6000));
                result.put("elementType",element.path("type").asText());
                // 不把图片数据 URI/大段二进制送给纯文本模型；没有视觉证据时不能猜图片内容。
                ObjectNode projected=((ObjectNode)element).deepCopy();
                projected.remove(List.of("src","html","pattern","poster"));
                if(projected.toString().length()<=12000) result.set("element",projected);
            }
            case "whiteboard_element" -> {
                String boardId=ClassroomEditingSupport.requiredText(reference,"whiteboardId",128);
                String elementId=ClassroomEditingSupport.requiredText(reference,"elementId",128);
                JsonNode board=course.path("runtime").path(scene.path("id").asText()).path("whiteboard");
                JsonNode canvas=board.path("canvas");
                if(!boardId.equals(board.path("id").asText())) {
                    board=null;
                    for(JsonNode candidate:scene.path("whiteboards")) if(boardId.equals(candidate.path("id").asText())) {board=candidate;break;}
                    if(board==null) throw bad("白板不存在或不属于当前页面");
                    canvas=board.has("canvas")?board.path("canvas"):board;
                }
                element=findElement(canvas.path("elements"),elementId);
                identity.put("whiteboardId",boardId).put("elementId",elementId);
                result.put("text",bounded(ClassroomCourseService.visibleText(element),6000)).put("elementType",element.path("type").asText());
                ObjectNode projected=((ObjectNode)element).deepCopy(); projected.remove(List.of("src","html","pattern"));
                if(projected.toString().length()<=12000) result.set("element",projected);
            }
            case "interactive_component" -> {
                if(!"interactive".equals(scene.path("type").asText())) throw bad("当前页面不是互动实验");
                String selector=ClassroomEditingSupport.requiredText(reference,"selector",128);
                Element selected=interactiveTarget(scene,selector);
                identity.put("sceneId",scene.path("id").asText()).put("selector",selector);
                Element copy=selected.clone(); copy.select("script,style,iframe,object,embed,template,link,meta").remove();
                result.put("text",bounded(copy.text(),6000));
                result.put("markup",bounded(ClassroomSlideSanitizer.cleanHtml(copy.outerHtml()),12000));
                result.put("tagName",selected.tagName());
                if(selected.hasAttr("value")) result.put("initialValue",bounded(selected.attr("value"),300));
            }
            default -> throw bad("不支持的对象引用类型");
        }
        result.set("reference",identity); return result;
    }

    static JsonNode observation(ObjectNode scene,JsonNode value) {
        if(value==null||value.isNull()) return null;
        if(!"interactive".equals(scene.path("type").asText())) throw bad("实验状态只能用于当前互动页面");
        JsonNode checked=ClassroomEditingSupport.boundedState(value,32768);
        JsonNode reportedId=checked.path("snapshot").path("identity").path("sceneId");
        if(!reportedId.isMissingNode()&&!scene.path("id").asText().equals(reportedId.asText())) throw bad("实验状态来自其他页面");
        if(checked.has("sourceHtmlHash")) {
            String actual=sha256(scene.path("content").path("html").asText());
            if(!actual.equals(checked.path("sourceHtmlHash").asText())) throw new ClassroomException(409,"实验内容已更新，请重新采集状态");
        }
        return checked;
    }

    static boolean hasInteractiveTarget(ObjectNode scene,String selector) {
        try { interactiveTarget(scene,selector); return true; } catch(ClassroomException absent) { return false; }
    }
    static Element interactiveTarget(ObjectNode scene,String selector) {
        if(selector==null||!selector.matches("#[A-Za-z][A-Za-z0-9_-]{0,126}")) throw bad("互动对象须使用稳定的 #id 引用");
        var elements=Jsoup.parse(scene.path("content").path("html").asText()).getElementsByAttributeValue("id",selector.substring(1));
        if(elements.size()!=1 || EXCLUDED.contains(elements.getFirst().tagName().toLowerCase(Locale.ROOT))) throw bad("互动对象不存在、重复或不可选择");
        return elements.getFirst();
    }
    private static JsonNode findElement(JsonNode elements,String id) {
        for(JsonNode element:elements) if(id.equals(element.path("id").asText())) return element;
        throw bad("对象已不存在，请重新选择");
    }
    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception unavailable) { throw new IllegalStateException("SHA-256 unavailable",unavailable); }
    }
    private static String bounded(String text,int max) { return text.substring(0,Math.min(text.length(),max)); }
    private static ClassroomException bad(String message) { return new ClassroomException(422,message); }
}
