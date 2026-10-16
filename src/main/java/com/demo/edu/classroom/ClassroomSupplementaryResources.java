package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** 可移植课包只携带历史视频入口，导入不能继承当前站点的核验状态或模型审计。 */
final class ClassroomSupplementaryResources {
    private static final Pattern VIDEO=Pattern.compile("^https://(?:www\\.)?bilibili\\.com/video/(BV[0-9A-Za-z]{10})/?(?:\\?p=([1-9][0-9]{0,4}))?$");
    private ClassroomSupplementaryResources() {}

    static ArrayNode clean(JsonNode source,Map<String,String> sceneIds,ObjectMapper json) {
        ArrayNode result=json.createArrayNode();
        if(source==null||source.isMissingNode()||source.isNull())return result;
        if(!source.isArray()||source.size()>192)throw invalid("补充资料数量无效");
        Set<String> seen=new HashSet<>();
        for(JsonNode raw:source) {
            if(!raw.isObject())throw invalid("补充资料格式无效");
            String oldScene=text(raw,"sceneId",200,true),sceneId=sceneIds.get(oldScene);
            if(sceneId==null)throw invalid("补充资料不属于本课页面");
            var match=VIDEO.matcher(text(raw,"url",500,true));
            if(!match.matches())throw invalid("补充资料必须是明确的 Bilibili 视频地址");
            String part=match.group(2)==null?"1":match.group(2),id=match.group(1)+":"+part;
            JsonNode duration=raw.path("durationSeconds");
            if(!duration.isIntegralNumber()||!duration.canConvertToInt()||duration.asInt()<=0||duration.asInt()>86400)
                throw invalid("补充资料缺少有效时长");
            if(!seen.add(sceneId+":"+id))continue;
            ObjectNode row=result.addObject().put("sceneId",sceneId).put("id",id)
                .put("url","https://www.bilibili.com/video/"+match.group(1)+"/?p="+part)
                .put("title",text(raw,"title",300,true)).put("author",text(raw,"author",200,true))
                .put("durationSeconds",duration.asInt()).put("purpose",text(raw,"purpose",200,false))
                .put("reason",text(raw,"reason",1000,false)).put("resourceStatus","historical");
            ArrayNode goals=row.putArray("goals");
            if(raw.has("goals")) {
                if(!raw.path("goals").isArray()||raw.path("goals").size()>16)throw invalid("补充资料知识点无效");
                for(JsonNode goal:raw.path("goals")) {
                    if(!goal.isTextual()||goal.asText().isBlank()||goal.asText().length()>300)throw invalid("补充资料知识点无效");
                    goals.add(goal.asText().trim());
                }
            }
            if(raw.path("checkedAt").isTextual()&&raw.path("checkedAt").asText().length()<=40) {
                try{OffsetDateTime.parse(raw.path("checkedAt").asText());row.put("checkedAt",raw.path("checkedAt").asText());}
                catch(RuntimeException ignored){/* 无法解析的历史时间不能伪装为刚核验。 */}
            }
        }
        return result;
    }
    private static String text(JsonNode object,String field,int limit,boolean required) {
        JsonNode value=object.path(field);if(value.isMissingNode()&&!required)return "";
        if(!value.isTextual())throw invalid("补充资料字段无效："+field);
        String text=value.asText().strip();
        if((required&&text.isEmpty())||text.length()>limit||text.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))
            throw invalid("补充资料字段无效："+field);
        return text;
    }
    private static ClassroomException invalid(String message){return new ClassroomException(422,message);}
}
