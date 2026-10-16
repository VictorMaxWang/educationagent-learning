package com.demo.edu.classroom.study;

import com.demo.edu.classroom.ClassroomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 为推荐和自主表述生成同一份有来源锚点的课堂快照。
 * 指纹只绑定教学内容，播放、白板运行态和普通聊天不使资料核对无故过期（M001/M002）。
 */
public final class ClassroomStudyContext {
    private ClassroomStudyContext() {}
    public record Anchor(String id,String label,String text,String kind) {}
    public record Snapshot(String courseId,String sceneId,String fingerprint,String title,String topic,int grade,
                           String subject,String direction,long revision,List<Anchor> anchors,String visibleText,
                           List<String> existingPurposes,boolean sourceComplete,List<String> incompleteSources) {
        public Snapshot(String courseId,String sceneId,String fingerprint,String title,String topic,int grade,
                        String subject,String direction,long revision,List<Anchor> anchors,String visibleText,List<String> existingPurposes) {
            this(courseId,sceneId,fingerprint,title,topic,grade,subject,direction,revision,anchors,visibleText,existingPurposes,true,List.of());
        }
        public ObjectNode toJson(ObjectMapper json) { return json.valueToTree(this); }
    }

    public static Snapshot from(JsonNode course,String requestedSceneId) {
        if(course==null||!course.isObject())throw new ClassroomException(404,"课程不存在");
        String sceneId=requestedSceneId;
        JsonNode scenes=course.path("scenes"),scene=null;
        if(sceneId==null||sceneId.isBlank()) {
            int index=Math.max(0,Math.min(course.path("progress").path("sceneIndex").asInt(),scenes.size()-1));
            if(scenes.isArray()&&!scenes.isEmpty())scene=scenes.get(index);
        } else for(JsonNode candidate:scenes)if(sceneId.equals(candidate.path("id").asText())){scene=candidate;break;}
        if(scene==null)throw new ClassroomException(404,"这节课堂内容尚未准备好");
        sceneId=scene.path("id").asText();
        List<Anchor> anchors=new ArrayList<>();
        List<String> incomplete=new ArrayList<>();
        // 与资料入口一致，完整覆盖其20,000字符合约，不能无提示丢掉末尾限定/矛盾。
        if(!add(anchors,"source",course.path("sourceName").asText("课程参考资料"),course.path("sourceText").asText(""),"reference",20000))incomplete.add("参考资料超出20,000字符");
        String title=scene.path("title").asText(course.path("title").asText());
        add(anchors,"title","本节主题",title,"topic",300);
        for(JsonNode outline:course.path("outlines"))if(sceneId.equals(outline.path("id").asText()))
            add(anchors,"goal","本节学习目标",outline.path("description").asText(""),"objective",2000);
        StringBuilder content=new StringBuilder();
        collect(scene.path("content").path("canvas"),content);
        if(scene.path("content").has("html")) {
            var document=Jsoup.parse(scene.path("content").path("html").asText());
            document.select("script,style,template,noscript").remove();
            content.append(document.body().text()).append('\n');
        }
        if("quiz".equals(scene.path("type").asText())) for(JsonNode question:scene.path("content").path("questions"))
            content.append(question.path("question").asText("")).append('\n');
        if(!add(anchors,"page","当前课件文字",content.toString(),"lesson",12000))incomplete.add("本页文字超出12,000字符");
        StringBuilder speech=new StringBuilder();
        for(JsonNode action:scene.path("actions"))if(Set.of("speech","discussion").contains(action.path("type").asText()))
            speech.append(action.path("text").asText(action.path("content").asText(""))).append('\n');
        if(!add(anchors,"speech","当前课堂讲解",speech.toString(),"lesson",12000))incomplete.add("本页讲稿超出12,000字符");
        List<String> purposes=new ArrayList<>();
        String type=scene.path("type").asText();
        if(type.equals("interactive"))purposes.add("interactive_exploration");
        if(type.equals("quiz"))purposes.add("practice");
        if(!speech.isEmpty())purposes.add("explanation");
        ObjectMapper json=new ObjectMapper();
        ObjectNode basis=json.createObjectNode();
        for(String key:List.of("id","title","topic","grade","subject","direction","domain","sourceText","sourceName"))basis.set(key,course.path(key));
        basis.put("sceneId",sceneId);basis.set("content",scene.path("content"));basis.set("anchors",json.valueToTree(anchors));
        String all=String.join("\n",anchors.stream().map(a->"["+a.id()+"] "+a.text()).toList());
        return new Snapshot(course.path("id").asText(),sceneId,hash(basis),title,course.path("topic").asText(),
            course.path("grade").asInt(5),course.path("subject").asText(),course.path("direction").asText("academic"),
            course.path("revision").asLong(),List.copyOf(anchors),all,List.copyOf(purposes),incomplete.isEmpty(),List.copyOf(incomplete));
    }

    /** 验证真实引用采用同一空白归一化，模型不能凭空给出页码或引用其他课程。 */
    public static String normalize(String value) { return value==null?"":value.replaceAll("[\\p{Z}\\s]+"," ").trim(); }

    public static String hash(JsonNode node) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(node).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception error) { throw new IllegalStateException("教学内容指纹生成失败",error); }
    }
    private static String canonical(JsonNode node) {
        if(node.isObject()) {
            List<String> keys=new ArrayList<>();node.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);
            return "{"+String.join(",",keys.stream().map(k->new com.fasterxml.jackson.databind.node.TextNode(k)+":"+canonical(node.get(k))).toList())+"}";
        }
        if(node.isArray()) {List<String> items=new ArrayList<>();node.forEach(n->items.add(canonical(n)));return "["+String.join(",",items)+"]";}
        return node.toString();
    }
    private static boolean add(List<Anchor> out,String prefix,String label,String raw,String kind,int limit) {
        String value=raw==null?"":raw.replace('\r','\n').strip();if(value.isEmpty())return true;
        boolean complete=value.length()<=limit;
        if(!complete) {
            int end=limit;if(Character.isHighSurrogate(value.charAt(end-1)))end--;
            value=value.substring(0,end);
        }
        int index=0;
        for(String paragraph:value.split("\\n+")) {
            // 上传原文中的 <、> 或泛型记号属于资料，不再按 HTML 二次解释，保留可回查的真实引文。
            String text=normalize(paragraph);
            for(int start=0;start<text.length();start+=600) {
                int end=Math.min(text.length(),start+600);
                if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
                out.add(new Anchor(prefix+"-"+(++index),label,text.substring(start,end),kind));
                if(end<Math.min(text.length(),start+600))start--;
            }
        }
        return complete;
    }
    private static void collect(JsonNode node,StringBuilder text) {
        if(node.isArray()) {node.forEach(n->collect(n,text));return;}
        if(!node.isObject())return;
        node.fields().forEachRemaining(entry->{
            if(entry.getValue().isTextual()&&Set.of("text","content","title","label","latex").contains(entry.getKey()))
                text.append(Jsoup.parse(entry.getValue().asText()).text()).append('\n');
            else if(entry.getValue().isContainerNode())collect(entry.getValue(),text);
        });
    }
}
