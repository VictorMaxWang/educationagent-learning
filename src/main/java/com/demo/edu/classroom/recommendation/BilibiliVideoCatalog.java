package com.demo.edu.classroom.recommendation;

import com.demo.service.tools.WebSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * 只读 Bilibili 元数据适配器，按 agent-reach video 文档的公开 search/all/v2 → view 路径核验。
 * 不下载视频、不模拟登录、不更换网络规避风控。详情可用时核对分P；详情受限时单独标明搜索所报整条资料。
 */
@Component
public class BilibiliVideoCatalog {
    public static final String VERSION="bilibili-whole-video-v1";
    static final Pattern BVID = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern WEB_VIDEO = Pattern.compile("https?://(?:www\\.)?bilibili\\.com/video/(BV[0-9A-Za-z]{10})(?=[/?#\\s]|$)");
    private static final Pattern PROMOTION = Pattern.compile("资料领取|领取资料|加微|加V|加v|加群|付费咨询|引流|购买课程|扫码领取|公众号领取|私信领取|点击购买|课程购买|资料包领取");
    private final ObjectMapper json;
    private final WebSearchService web;
    private final Transport transport;
    private final VerifiedVideoMetadataCache cache;

    public record Video(String id,String bvid,int part,String url,String title,String description,String partTitle,
                        String author,int durationSeconds,String checkedAt,ObjectNode metadataSources) {
        public String metadataText() { return title+"\n"+partTitle+"\n"+description; }
    }
    public record Search(List<Video> videos,List<ObjectNode> rejected,ObjectNode evidence) {}
    @FunctionalInterface interface Transport { JsonNode get(String url,long deadline) throws Exception; }

    public BilibiliVideoCatalog(ObjectMapper json,WebSearchService web) {
        this(json,web,new PublicTransport(json),null);
    }
    @Autowired public BilibiliVideoCatalog(ObjectMapper json,WebSearchService web,VerifiedVideoMetadataCache cache) {
        this(json,web,new PublicTransport(json),cache);
    }
    BilibiliVideoCatalog(ObjectMapper json,WebSearchService web,Transport transport) {
        this(json,web,transport,null);
    }
    BilibiliVideoCatalog(ObjectMapper json,WebSearchService web,Transport transport,VerifiedVideoMetadataCache cache) {
        this.json=json;this.web=web;this.transport=transport;this.cache=cache;
    }
    private record SearchIdentity(String title,String author,JsonNode row) {}
    private record WholeFallback(Video video,String rejection,boolean mayUseVerifiedCache) {}

    public Search search(String query,int budgetSeconds) {
        return search(query,budgetSeconds,Duration.ofSeconds(40));
    }
    public Search search(String query,int budgetSeconds,Duration budget) {
        if(query==null||query.isBlank()||query.length()>100||query.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("视频查询词无效");
        if(budget==null||budget.isZero()||budget.isNegative()||budget.compareTo(Duration.ofSeconds(40))>0)throw new IllegalArgumentException("视频查询预算无效");
        long started=System.nanoTime(),deadline=started+budget.toNanos();
        List<ObjectNode> rejected=new ArrayList<>();
        LinkedHashSet<String> ids=new LinkedHashSet<>();
        Map<String,SearchIdentity> currentSearch=new LinkedHashMap<>();Set<String> ambiguousSearchIds=new HashSet<>();boolean publicSearchVerified=false;
        String searchCheckedAt=null,searchUrl="https://api.bilibili.com/x/web-interface/search/all/v2?keyword="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&page=1";
        ObjectNode evidence=json.createObjectNode().put("query",query).put("scope","metadata")
                .put("checkedAt",Instant.now().toString()).put("searchSource","bilibili-public");
        try {
            JsonNode response=transport.get(searchUrl,deadline);
            if(!integerEquals(response.path("code"),0))throw new CatalogFailure("SEARCH_UNAVAILABLE");
            publicSearchVerified=true;searchCheckedAt=Instant.now().toString();evidence.put("searchCheckedAt",searchCheckedAt);
            for(JsonNode group:response.path("data").path("result")) {
                if(!"video".equals(group.path("result_type").asText()))continue;
                for(JsonNode row:group.path("data")) {
                    String bvid=row.path("bvid").asText();
                    if(BVID.matcher(bvid).matches()&&(ids.size()<20||ids.contains(bvid))) {
                        ids.add(bvid);SearchIdentity identity=new SearchIdentity(row.path("title").asText(),row.path("author").asText(),row.deepCopy());
                        SearchIdentity old=currentSearch.putIfAbsent(bvid,identity);
                        if(old!=null&&(!VerifiedVideoMetadataCache.normalized(old.title()).equals(VerifiedVideoMetadataCache.normalized(identity.title()))
                                ||!VerifiedVideoMetadataCache.normalized(old.author()).equals(VerifiedVideoMetadataCache.normalized(identity.author()))
                                ||!searchIdentityFields(old.row()).equals(searchIdentityFields(identity.row()))))ambiguousSearchIds.add(bvid);
                    }
                }
            }
        } catch(Exception unavailable) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            evidence.put("publicSearchFailure",failureKind(unavailable));
        }
        // 回退只使用现有网页搜索服务取得的真实 B站 URL；不能把搜索页或文字中的 BV 猜成资源。
        if(ids.isEmpty()&&web!=null&&System.nanoTime()<deadline) {
            evidence.put("searchSource","existing-web-search");
            FutureTask<String> fallback=new FutureTask<>(()->web.search("site:bilibili.com/video/ "+query,10));
            Thread.ofVirtual().name("video-web-fallback").start(fallback);
            try {
                String results=fallback.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                var matches=WEB_VIDEO.matcher(results==null?"":results);
                while(matches.find()&&ids.size()<20)ids.add(matches.group(1));
            }catch(Exception unavailable){
                if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
                if(Thread.currentThread().isInterrupted())throw new CancellationException();
                evidence.put("fallbackFailure","SEARCH_UNAVAILABLE");
            }finally{if(!fallback.isDone())fallback.cancel(true);}
        }
        List<Video> videos=new ArrayList<>();boolean detailBlocked=false;int liveParts=0;var reused=evidence.putArray("cachedMetadata");var searchFallbacks=evidence.putArray("searchMetadata");
        for(String bvid:ids) {
            if(System.nanoTime()>=deadline) { rejected.add(rejection(bvid,"SEARCH_BUDGET_EXHAUSTED"));break; }
            SearchIdentity current=currentSearch.get(bvid);
            if(ambiguousSearchIds.contains(bvid)){rejected.add(rejection(bvid,"AMBIGUOUS_SEARCH_IDENTITY"));continue;}
            String unsafe=current==null?null:unsafeSearchRow(current.row());
            if(unsafe!=null){rejected.add(rejection(bvid,unsafe));continue;}
            boolean detailRequestMade=!detailBlocked;
            if(!detailBlocked)try {
                    JsonNode response=transport.get("https://api.bilibili.com/x/web-interface/view?bvid="+bvid,deadline);
                    if(integerEquals(response.path("code"),-412)||integerEquals(response.path("code"),412))throw new CatalogFailure("API_412");
                    List<Video> direct=parseView(bvid,response,budgetSeconds,20-videos.size(),rejected);videos.addAll(direct);liveParts+=direct.size();
                    if(cache!=null&&integerEquals(response.path("code"),0)&&integerEquals(response.path("data").path("state"),0)&&!restrictedView(response.path("data")))
                        try{cache.saveLiveView(bvid,response,direct.isEmpty()?Instant.now().toString():direct.getFirst().checkedAt());}
                    catch(RuntimeException unavailableCache){evidence.put("cacheWrite","unavailable");}
                } catch(Exception unavailable) {
                    if(Thread.currentThread().isInterrupted())throw new CancellationException();
                    String reason=failureKind(unavailable);rejected.add(rejection(bvid,reason));
                    if("HTTP_412".equals(reason)||"API_412".equals(reason)){detailBlocked=true;evidence.put("detailFailure",412).put("detailFailureKind",reason).put("detailFailureScope","job-endpoint");}
                }
            if(detailBlocked&&publicSearchVerified&&current!=null) {
                WholeFallback whole=parseSearchWhole(bvid,current.row(),budgetSeconds,searchCheckedAt,searchUrl);
                if(whole.video()!=null) {
                    videos.add(whole.video());searchFallbacks.addObject().put("bvid",bvid).put("source","bilibili-search-metadata")
                            .put("durationScope","whole-video").put("checkedAt",searchCheckedAt).put("detailFailure",412).put("detailRequestMade",detailRequestMade);
                } else {
                    rejected.add(rejection(bvid,whole.rejection()));
                    if(whole.mayUseVerifiedCache()&&cache!=null) {
                    Optional<ObjectNode> saved;
                    try{saved=cache.findFreshMatching(bvid,current.title(),current.author());}catch(RuntimeException unavailableCache){saved=Optional.empty();}
                    if(saved.isPresent()) {
                        List<Video> cached=parseCached(bvid,saved.get(),budgetSeconds,20-videos.size(),rejected);videos.addAll(cached);
                        if(!cached.isEmpty())reused.addObject().put("bvid",bvid).put("source","verified-metadata-cache").put("originalVerifiedAt",saved.get().path("verifiedAt").asText())
                                .put("currentSearchVerified",true).put("detailFailure",412).put("detailRequestMade",detailRequestMade);
                    }else rejected.add(rejection(bvid,"NO_MATCHING_FRESH_VERIFIED_METADATA"));
                    }
                }
            }
            if(videos.size()>=20)break;
        }
        evidence.put("retrievedVideoIds",ids.size()).put("verifiedParts",videos.stream().filter(v->v.part()>0).count()).put("wholeVideoResults",videos.stream().filter(v->v.part()==0).count()).put("candidateCount",videos.size())
                .put("currentSearchVerified",publicSearchVerified).put("source",!searchFallbacks.isEmpty()?(liveParts==0&&reused.isEmpty()?"bilibili-search-metadata":"mixed-metadata-sources")
                        :reused.isEmpty()?"bilibili-live":liveParts==0?"verified-metadata-cache":"mixed-live-and-verified-cache")
                .put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        return new Search(List.copyOf(videos),List.copyOf(rejected),evidence);
    }

    List<Video> parseView(String requested,JsonNode response,int budgetSeconds,int remaining,List<ObjectNode> rejected) {
        if(remaining<=0)return List.of();
        JsonNode value=response.path("data");
        if(!integerEquals(response.path("code"),0)||!requested.equals(value.path("bvid").asText())||!integerEquals(value.path("state"),0)||restrictedView(value)) {
            rejected.add(rejection(requested,"VIDEO_UNAVAILABLE"));return List.of();
        }
        String title=plain(value.path("title").asText(),250),description=plain(value.path("desc").asText(),1500);
        String author=plain(value.path("owner").path("name").asText(),100);
        if(!value.path("title").isTextual()||!value.path("owner").path("name").isTextual()||title.isBlank()||author.isBlank()||PROMOTION.matcher(title+" "+description).find()) {
            rejected.add(rejection(requested,"MISSING_METADATA_OR_PROMOTION"));return List.of();
        }
        String checked=Instant.now().toString();List<Video> out=new ArrayList<>();Set<Integer> seen=new HashSet<>();
        for(JsonNode page:value.path("pages")) {
            int part=positiveInteger(page.path("page")),seconds=positiveInteger(page.path("duration"));
            if(part<1||part>10000||!seen.add(part)||seconds<=0||seconds>budgetSeconds) {
                rejected.add(rejection(requested+":"+part,"UNKNOWN_DURATION_OR_OVER_BUDGET"));continue;
            }
            String partTitle=plain(page.path("part").asText(),250);
            if(PROMOTION.matcher(partTitle).find()){rejected.add(rejection(requested+":"+part,"PROMOTION"));continue;}
            String endpoint="https://api.bilibili.com/x/web-interface/view?bvid="+requested;
            ObjectNode sources=json.createObjectNode().put("title",endpoint).put("description",endpoint)
                    .put("author",endpoint).put("partTitle",endpoint+"#pages["+(part-1)+"].part")
                    .put("durationSeconds",endpoint+"#pages["+(part-1)+"].duration").put("subtitle","not-retrieved");
            out.add(new Video(requested+":"+part,requested,part,"https://www.bilibili.com/video/"+requested+"/?p="+part,
                    title,description,partTitle,author,seconds,checked,sources));
            if(out.size()>=remaining)break;
        }
        return out;
    }

    /** 仅从本次成功搜索取整条资源；没有pages/state，就明确不声称详情或分P核验成功。 */
    private WholeFallback parseSearchWhole(String bvid,JsonNode row,int budgetSeconds,String checkedAt,String searchUrl) {
        String title=plain(row.path("title").asText(),250),author=plain(row.path("author").asText(),100);
        if(!bvid.equals(row.path("bvid").asText())||!"video".equals(row.path("type").asText())||title.isBlank()||author.isBlank()
                ||!row.path("title").isTextual()||!row.path("author").isTextual()||!knownZero(row.path("is_pay")))
            return new WholeFallback(null,"INCOMPLETE_SEARCH_METADATA",false);
        Integer seconds=parseSearchDuration(row.path("duration"));
        if(seconds==null)return new WholeFallback(null,"UNKNOWN_SEARCH_DURATION",true);
        if(seconds>budgetSeconds)return new WholeFallback(null,"WHOLE_VIDEO_OVER_BUDGET",false);
        String descriptionField=null,description="";
        for(String field:List.of("description","desc"))if(row.path(field).isTextual()) {
            String candidate=plain(row.path(field).asText(),1500);
            if(!candidate.isBlank()){descriptionField=field;description=candidate;break;}
        }
        ObjectNode sources=json.createObjectNode().put("source","bilibili-search-metadata").put("durationScope","whole-video")
                .put("currentSearchVerified",true).put("detailsVerified",false).put("partsVerified",false).put("detailFailure",412)
                .put("checkedAt",checkedAt).put("searchResultBvid",bvid).put("rawDuration",row.path("duration").asText()).put("parsedDurationSeconds",seconds)
                .put("title",searchUrl+"#video.title").put("author",searchUrl+"#video.author").put("durationSeconds",searchUrl+"#video.duration")
                .put("description",descriptionField==null?"not-provided":searchUrl+"#video."+descriptionField).put("subtitle","not-retrieved");
        return new WholeFallback(new Video(bvid+":whole",bvid,0,"https://www.bilibili.com/video/"+bvid+"/",title,description,"",author,seconds,checkedAt,sources),null,false);
    }

    static Integer parseSearchDuration(JsonNode value) {
        if(!value.isTextual())return null;String text=value.asText().strip();
        if(!text.matches("[0-9]{1,8}:[0-5][0-9](?::[0-5][0-9])?"))return null;
        String[] units=text.split(":");
        // 三段表示小时:分:秒，第二段由正则限定0..59；两段的首段允许总分钟数。
        long seconds=0;for(String unit:units){seconds=seconds*60+Long.parseLong(unit);if(seconds>Integer.MAX_VALUE)return null;}
        return seconds>0?(int)seconds:null;
    }
    private static boolean knownZero(JsonNode value){return value.isBoolean()&&!value.asBoolean()||value.isIntegralNumber()&&value.canConvertToInt()&&value.asInt()==0;}
    /** code/state/分P时长是整数字段，不能让false、字符串或小数被asInt静默转换。 */
    private static boolean integerEquals(JsonNode value,int expected){return value.isIntegralNumber()&&value.canConvertToInt()&&value.intValue()==expected;}
    private static int positiveInteger(JsonNode value){return value.isIntegralNumber()&&value.canConvertToInt()&&value.intValue()>0?value.intValue():0;}
    private String unsafeSearchRow(JsonNode row) {
        if(row.has("type")&&!"video".equals(row.path("type").asText()))return "NON_VIDEO_SEARCH_RESULT";
        for(String flag:List.of("is_pay","is_charge_video","is_upower_exclusive","is_upower_preview"))
            if(row.has(flag)&&!knownZero(row.path(flag)))return "PAID_OR_UNKNOWN_ACCESS_FLAG";
        if(row.hasNonNull("badgepay")) {
            JsonNode badge=row.path("badgepay");boolean absent=badge.isArray()&&badge.isEmpty()||badge.isObject()&&badge.isEmpty()||badge.isTextual()&&badge.asText().isBlank()||knownZero(badge);
            if(!absent)return "PAID_OR_UNKNOWN_ACCESS_FLAG";
        }
        // 两种描述字段可能同时存在；空description不能遮住desc中的推广内容。
        String descriptions=plain(row.path("description").asText(),20000)+" "+plain(row.path("desc").asText(),20000);
        if(PROMOTION.matcher(plain(row.path("title").asText(),20000)+" "+descriptions).find())return "PROMOTION";
        return null;
    }
    private ObjectNode searchIdentityFields(JsonNode row) {
        ObjectNode result=json.createObjectNode();for(String field:List.of("type","duration","is_pay","is_charge_video","is_upower_exclusive","is_upower_preview","badgepay"))result.set(field,row.path(field));
        result.put("description",VerifiedVideoMetadataCache.normalized(row.path("description").asText()));
        result.put("desc",VerifiedVideoMetadataCache.normalized(row.path("desc").asText()));return result;
    }
    private static boolean restrictedView(JsonNode data) {
        if(data.has("rights")&&!data.path("rights").isObject())return true;
        for(String field:List.of("pay","ugc_pay","arc_pay","ugc_pay_preview"))if(data.path("rights").has(field)&&!knownZero(data.path("rights").path(field)))return true;
        for(String field:List.of("is_upower_exclusive","is_upower_preview"))if(data.has(field)&&!knownZero(data.path(field)))return true;
        return false;
    }

    /** 缓存有自己的解析路径和来源标签，不能包装为 code=0 假装详情端点刚刚成功。 */
    private List<Video> parseCached(String bvid,ObjectNode snapshot,int budgetSeconds,int remaining,List<ObjectNode> rejected) {
        if(remaining<=0)return List.of();String title=plain(snapshot.path("title").asText(),250),description=plain(snapshot.path("description").asText(),1500);
        String author=plain(snapshot.path("author").asText(),100),checked=snapshot.path("verifiedAt").asText();
        if(PROMOTION.matcher(title+" "+description).find()){rejected.add(rejection(bvid,"PROMOTION"));return List.of();}
        List<Video> result=new ArrayList<>();
        for(JsonNode page:snapshot.path("pages")) {
            int part=page.path("page").asInt(),seconds=page.path("durationSeconds").asInt();String partTitle=plain(page.path("title").asText(),250);
            if(seconds>budgetSeconds||PROMOTION.matcher(partTitle).find())continue;
            String endpoint="https://api.bilibili.com/x/web-interface/view?bvid="+bvid;
            ObjectNode sources=json.createObjectNode().put("source","verified-metadata-cache").put("originalSource",snapshot.path("source").asText())
                    .put("originalVerifiedAt",checked).put("verifiedOn",snapshot.path("verifiedOn").asText()).put("currentSearchVerified",true).put("detailFailure",412)
                    .put("title",endpoint).put("description",endpoint).put("author",endpoint).put("partTitle",endpoint+"#pages["+(part-1)+"].part")
                    .put("durationSeconds",endpoint+"#pages["+(part-1)+"].duration").put("subtitle","not-retrieved");
            result.add(new Video(bvid+":"+part,bvid,part,"https://www.bilibili.com/video/"+bvid+"/?p="+part,title,description,partTitle,author,seconds,checked,sources));
            if(result.size()>=remaining)break;
        }
        return result;
    }
    private ObjectNode rejection(String id,String reason) { return json.createObjectNode().put("id",id).put("reason",reason); }
    static String plain(String raw,int limit) { String text=Jsoup.parse(raw==null?"":raw).text().replaceAll("\\s+"," ").trim();return text.substring(0,Math.min(text.length(),limit)); }
    private static String failureKind(Exception error) { return error instanceof CatalogFailure failure?failure.getMessage():"NETWORK_OR_RESPONSE_UNAVAILABLE"; }
    static class CatalogFailure extends RuntimeException { CatalogFailure(String reason){super(reason);} }

    /** Cookie 仅在进程内存，正文接收中即限制大小；连接、正文读取共用同一检索截止时间。 */
    private static final class PublicTransport implements Transport {
        private final ObjectMapper json;
        private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
        private boolean initialized;
        PublicTransport(ObjectMapper json){this.json=json;}
        public JsonNode get(String url,long deadline)throws Exception {
            if(!initialized) {
                initialized=true;
                // 官方首页匿名 cookie；失败即继续普通公开读取，不伪造登录身份或重试风控。
                try{request("https://www.bilibili.com/",deadline);}catch(Exception ignored){if(ignored instanceof InterruptedException)Thread.currentThread().interrupt();if(Thread.currentThread().isInterrupted())throw ignored;}
            }
            return json.readTree(request(url,deadline));
        }
        private byte[] request(String url,long deadline)throws Exception {
            long remaining=deadline-System.nanoTime();if(remaining<=0)throw new CatalogFailure("SEARCH_BUDGET_EXHAUSTED");
            HttpRequest request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(10).toNanos())))
                    .header("User-Agent","Mozilla/5.0 XiaotaoLearning/1.0").header("Referer","https://www.bilibili.com/")
                    .header("Accept-Encoding","identity").GET().build();
            var pending=http.sendAsync(request,ignored->new BoundedBody());
            try {
                HttpResponse<byte[]> response=pending.get(remaining,TimeUnit.NANOSECONDS);
                if(response.statusCode()!=200)throw new CatalogFailure("HTTP_"+response.statusCode());
                return response.body();
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
            finally {if(!pending.isDone())pending.cancel(true);}
        }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> chunks){for(ByteBuffer chunk:chunks){if(chunk.remaining()>2_000_000-bytes.size()){
            subscription.cancel();result.completeExceptionally(new CatalogFailure("RESPONSE_TOO_LARGE"));return;}
            byte[] buffer=new byte[chunk.remaining()];chunk.get(buffer);bytes.writeBytes(buffer);}subscription.request(1);}
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
