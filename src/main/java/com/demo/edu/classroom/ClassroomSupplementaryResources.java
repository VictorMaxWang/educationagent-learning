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
            String part=match.group(2),scope=text(raw,"durationScope",30,false),sourceKind=text(raw,"sourceKind",50,false),verification=text(raw,"verificationScope",30,false);
            if(!scope.isEmpty()&&!Set.of("part","whole-video").contains(scope))throw invalid("补充资料时长范围无效");
            if(!sourceKind.isEmpty()&&!Set.of("bilibili-public-api","verified-metadata-cache","bilibili-search-metadata").contains(sourceKind))throw invalid("补充资料来源无效");
            if(!verification.isEmpty()&&!Set.of("metadata","search-metadata").contains(verification))throw invalid("补充资料核验范围无效");
            boolean whole=part==null;
            if((whole&&scope.equals("part"))||(!whole&&(scope.equals("whole-video")||sourceKind.equals("bilibili-search-metadata")||verification.equals("search-metadata"))))
                throw invalid("整条时长不能对应某个分P地址");
            if(verification.equals("search-metadata")&&!sourceKind.equals("bilibili-search-metadata"))throw invalid("搜索资料来源与范围不一致");
            if(raw.has("part")&&(!raw.path("part").isIntegralNumber()||!raw.path("part").canConvertToInt()||raw.path("part").asInt(-1)!=(whole?0:Integer.parseInt(part))))throw invalid("补充资料分P与地址不一致");
            if(sourceKind.equals("bilibili-search-metadata")&&!verification.isEmpty()&&!verification.equals("search-metadata"))throw invalid("搜索资料来源与范围不一致");
            String id=match.group(1)+":"+(whole?"whole":part);
            JsonNode duration=raw.path("durationSeconds");
            if(!duration.isIntegralNumber()||!duration.canConvertToInt()||duration.asInt()<=0||duration.asInt()>86400)
                throw invalid("补充资料缺少有效时长");
            if(!seen.add(sceneId+":"+id))continue;
            ObjectNode row=result.addObject().put("sceneId",sceneId).put("id",id)
                .put("url","https://www.bilibili.com/video/"+match.group(1)+"/"+(whole?"":"?p="+part))
                .put("title",text(raw,"title",300,true)).put("author",text(raw,"author",200,true))
                .put("durationSeconds",duration.asInt()).put("purpose",text(raw,"purpose",200,false))
                .put("reason",text(raw,"reason",1000,false)).put("resourceStatus","historical");
            // 无p参数不等于P1，历史链接也不能把可能的整条时长静默赋给首P。
            if(whole||raw.has("durationScope")||raw.has("part"))row.put("durationScope",whole?"whole-video":"part").put("part",whole?0:Integer.parseInt(part));
            if(!sourceKind.isEmpty())row.put("sourceKind",sourceKind).put("sourceLabel",sourceKind.equals("bilibili-search-metadata")?"B站搜索资料":sourceKind.equals("verified-metadata-cache")?"已核验资料缓存":"已核验视频资料");
            if(!verification.isEmpty())row.put("verificationScope",verification);
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
