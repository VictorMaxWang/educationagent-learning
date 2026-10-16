package com.demo.edu.classroom.recommendation;

import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 复用确实读取过的公开 view 元数据，不把缓存伪装成本次 HTTP 成功。
 * 只在本次公开搜索仍返回相同 BV、标题和作者时读取；无公开写接口，批量导入由已授权运维完成。
 */
@Component
public class VerifiedVideoMetadataCache {
    public static final String KIND="bili_verified_metadata";
    public static final String OWNER="bilibili-metadata-cache";
    private static final Duration MAX_AGE=Duration.ofHours(24);
    private final ClassroomStudyStore store;private final ObjectMapper json;
    public VerifiedVideoMetadataCache(ClassroomStudyStore store,ObjectMapper json){this.store=store;this.json=json;}

    public Optional<ObjectNode> findFreshMatching(String bvid,String currentTitle,String currentAuthor) {
        if(!validId(bvid)||normalized(currentTitle).isBlank()||normalized(currentAuthor).isBlank())return Optional.empty();
        Optional<ObjectNode> saved=store.find(KIND,bvid);
        if(saved.isEmpty()||!valid(saved.get(),Instant.now())||!bvid.equals(saved.get().path("bvid").asText()))return Optional.empty();
        ObjectNode value=saved.get();
        if(!normalized(currentTitle).equals(normalized(value.path("title").asText()))
                ||!normalized(currentAuthor).equals(normalized(value.path("author").asText())))return Optional.empty();
        return Optional.of(value.deepCopy());
    }

    /** 正常公开 view 成功时保存原始元数据；不保存被预算过滤后的不完整分P列表。 */
    public boolean saveLiveView(String bvid,JsonNode response,String verifiedAt) {
        JsonNode value=response.path("data");
        if(response.path("code").asInt(-1)!=0||!bvid.equals(value.path("bvid").asText())||value.path("state").asInt(-1)!=0)return false;
        ObjectNode document=json.createObjectNode().put("bvid",bvid).put("title",value.path("title").asText())
                .put("description",value.path("desc").asText()).put("author",value.path("owner").path("name").asText())
                .put("verifiedAt",verifiedAt).put("metadataVerified",true).put("source","bilibili-public-api").put("verifiedOn","server")
                .put("viewHttpStatus",200).put("viewApiCode",0);
        document.set("durationSeconds",value.path("duration"));var pages=document.putArray("pages");
        for(JsonNode page:value.path("pages")){ObjectNode item=pages.addObject().put("title",page.path("part").asText());item.set("page",page.path("page"));item.set("durationSeconds",page.path("duration"));}
        if(!valid(document,Instant.now()))return false;
        store.save(KIND,bvid,OWNER,document);return true;
    }

    /** 种子与正常在线缓存使用同一严格格式；24h从原verifiedAt算起，读取不续期。 */
    public static boolean valid(JsonNode value,Instant now) {
        if(value==null||!value.isObject()||!validId(value.path("bvid").asText())||!value.path("metadataVerified").isBoolean()||!value.path("metadataVerified").asBoolean()
                ||!"bilibili-public-api".equals(value.path("source").asText())||!java.util.Set.of("local","server").contains(value.path("verifiedOn").asText())
                ||!value.path("viewHttpStatus").isIntegralNumber()||value.path("viewHttpStatus").asInt()!=200
                ||!value.path("viewApiCode").isIntegralNumber()||value.path("viewApiCode").asInt()!=0)return false;
        for(String key:java.util.List.of("title","author"))if(!value.path(key).isTextual()||normalized(value.path(key).asText()).isBlank()||value.path(key).asText().length()>500)return false;
        if(!value.path("description").isTextual()||value.path("description").asText().length()>20000)return false;
        try{Instant timestamp=Instant.parse(value.path("verifiedAt").asText());if(timestamp.isAfter(now)||!timestamp.plus(MAX_AGE).isAfter(now))return false;}catch(RuntimeException invalid){return false;}
        JsonNode total=value.path("durationSeconds"),pages=value.path("pages");
        if(!positiveSeconds(total)||!pages.isArray()||pages.isEmpty()||pages.size()>10000)return false;
        long sum=0;int expectedPage=1;
        for(JsonNode page:pages){
            if(!page.path("page").isIntegralNumber()||page.path("page").asInt()!=expectedPage++||!positiveSeconds(page.path("durationSeconds"))
                    ||!page.path("title").isTextual()||normalized(page.path("title").asText()).isBlank()||page.path("title").asText().length()>500)return false;
            sum+=page.path("durationSeconds").asInt();if(sum>Integer.MAX_VALUE)return false;
        }
        return sum==total.asInt();
    }
    private static boolean positiveSeconds(JsonNode value){return value.isIntegralNumber()&&value.canConvertToInt()&&value.asInt()>0;}
    private static boolean validId(String bvid){return bvid!=null&&BilibiliVideoCatalog.BVID.matcher(bvid).matches();}
    public static String normalized(String raw){return Normalizer.normalize(Jsoup.parse(raw==null?"":raw).text(),Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+"," ").strip();}
}
