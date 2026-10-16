package com.demo.edu.classroom.recommendation;

import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VerifiedVideoMetadataCacheTest {
    final ObjectMapper json=new ObjectMapper();final ClassroomStudyStore store=mock(ClassroomStudyStore.class);
    final String bv="BV1r3411q7mV",other="BV1NE411o7P1";final Map<String,ObjectNode> saved=new HashMap<>();
    VerifiedVideoMetadataCache cache(){when(store.find(eq(VerifiedVideoMetadataCache.KIND),anyString())).thenAnswer(c->Optional.ofNullable(saved.get(c.getArgument(1))).map(ObjectNode::deepCopy));
        doAnswer(c->{saved.put(c.getArgument(1),((ObjectNode)c.getArgument(3)).deepCopy());return null;}).when(store).save(eq(VerifiedVideoMetadataCache.KIND),anyString(),eq(VerifiedVideoMetadataCache.OWNER),any());
        return new VerifiedVideoMetadataCache(store,json);}
    ObjectNode seed(String id){var row=json.createObjectNode().put("bvid",id).put("title","沁园春长沙朗诵").put("description","").put("author","朗诵课堂")
            .put("durationSeconds",103).put("metadataVerified",true).put("viewHttpStatus",200).put("viewApiCode",0).put("source","bilibili-public-api").put("verifiedOn","local")
            .put("verifiedAt",Instant.now().minusSeconds(120).toString());row.putArray("pages").addObject().put("page",1).put("title","诵读示范").put("durationSeconds",103);return row;}
    ObjectNode search(String...ids){var response=json.createObjectNode().put("code",0);var rows=response.putObject("data").putArray("result").addObject().put("result_type","video").putArray("data");
        for(String id:ids)rows.addObject().put("bvid",id).put("type","video").put("is_pay",0).put("is_charge_video",0).put("title","<em>沁园春</em>长沙朗诵").put("author","朗诵课堂");return response;}
    @Test void freshMatchingCacheKeepsOriginalTimeAndSupportsNfkcAndHtmlNormalization(){var row=seed(bv);row.put("title","Ａ　Ｂ").put("author","讲师　甲");saved.put(bv,row);var cache=cache();
        var result=cache.findFreshMatching(bv,"<em>A</em> B","讲师 甲").orElseThrow();assertEquals(row.path("verifiedAt"),result.path("verifiedAt"));
        assertEquals("local",result.path("verifiedOn").asText());verify(store,never()).save(anyString(),anyString(),anyString(),any());}
    @Test void expiredFutureDifferentAuthorAndDifferentTitleAreNotReused(){var row=seed(bv);saved.put(bv,row);var cache=cache();
        assertTrue(cache.findFreshMatching(bv,"另一个标题","朗诵课堂").isEmpty());assertTrue(cache.findFreshMatching(bv,"沁园春长沙朗诵","另一个作者").isEmpty());
        row.put("verifiedAt",Instant.now().minus(Duration.ofHours(24)).toString());assertTrue(cache.findFreshMatching(bv,"沁园春长沙朗诵","朗诵课堂").isEmpty());
        row.put("verifiedAt",Instant.now().plusSeconds(60).toString());assertTrue(cache.findFreshMatching(bv,"沁园春长沙朗诵","朗诵课堂").isEmpty());}
    @Test void incompleteOrUnverifiedMetadataCannotBecomeCards(){var cache=cache();for(String problem:List.of("missing-time","wrong-total","missing-part","zero-part","not-verified","wrong-source","wrong-status")){
        var row=seed(bv);switch(problem){case "missing-time"->row.remove("verifiedAt");case "wrong-total"->row.put("durationSeconds",104);
            case "missing-part"->row.putArray("pages");case "zero-part"->((ObjectNode)row.path("pages").get(0)).put("durationSeconds",0);
            case "not-verified"->row.put("metadataVerified",false);case "wrong-source"->row.put("source","unverified-search");case "wrong-status"->row.put("viewHttpStatus",412);}
        saved.put(bv,row);assertTrue(cache.findFreshMatching(bv,"沁园春长沙朗诵","朗诵课堂").isEmpty(),problem);}}
    @Test void catalogCacheReuseHasExplicitSourceAndNeverRefreshesTimestamp(){var cache=cache();var row=seed(bv);saved.put(bv,row);AtomicInteger detailCalls=new AtomicInteger();
        var catalog=new BilibiliVideoCatalog(json,null,(url,deadline)->{if(url.contains("search/all"))return search(bv);detailCalls.incrementAndGet();throw new BilibiliVideoCatalog.CatalogFailure("HTTP_412");},cache);
        var result=catalog.search("沁园春长沙 朗诵示范",600);assertEquals(1,result.videos().size());assertEquals(1,detailCalls.get());
        assertEquals(row.path("verifiedAt").asText(),result.videos().getFirst().checkedAt());assertEquals("verified-metadata-cache",result.evidence().path("source").asText());
        assertEquals(412,result.evidence().path("detailFailure").asInt());assertTrue(result.evidence().path("currentSearchVerified").asBoolean());
        assertEquals("verified-metadata-cache",result.videos().getFirst().metadataSources().path("source").asText());
        assertEquals(row.path("verifiedAt"),result.videos().getFirst().metadataSources().path("originalVerifiedAt"));}
    @Test void failedSearchAndAbsentSearchVideoCannotUseStoredMetadata(){var cache=cache();saved.put(bv,seed(bv));
        var failed=new BilibiliVideoCatalog(json,null,(url,deadline)->{throw new BilibiliVideoCatalog.CatalogFailure("HTTP_412");},cache);
        assertTrue(failed.search("沁园春长沙 朗诵示范",600).videos().isEmpty());verify(store,never()).find(anyString(),anyString());
        var absent=new BilibiliVideoCatalog(json,null,(url,deadline)->{if(url.contains("search/all"))return search(other);throw new BilibiliVideoCatalog.CatalogFailure("HTTP_412");},cache);
        assertTrue(absent.search("沁园春长沙 朗诵示范",600).videos().isEmpty());verify(store,never()).find(VerifiedVideoMetadataCache.KIND,bv);}
    @Test void successfulLiveViewStoresTheCompleteOriginalMetadata(){var cache=cache();var response=json.createObjectNode().put("code",0);var data=response.putObject("data").put("bvid",bv).put("state",0)
            .put("title","沁园春长沙朗诵").put("desc","").put("duration",103);data.putObject("owner").put("name","朗诵课堂");data.putArray("pages").addObject().put("page",1).put("part","诵读示范").put("duration",103);
        var catalog=new BilibiliVideoCatalog(json,null,(url,deadline)->url.contains("search/all")?search(bv):response,cache);var result=catalog.search("沁园春长沙 朗诵示范",600);
        assertEquals(1,result.videos().size());assertEquals("bilibili-live",result.evidence().path("source").asText());
        assertEquals("server",saved.get(bv).path("verifiedOn").asText());assertEquals(result.videos().getFirst().checkedAt(),saved.get(bv).path("verifiedAt").asText());
        assertTrue(VerifiedVideoMetadataCache.valid(saved.get(bv),Instant.now()));}
    @Test void realHttp412IsMadeOnlyOncePerJobAndLaterCandidatesUseOnlyMatchingFreshCache()throws Exception{
        var cache=cache();saved.put(bv,seed(bv));saved.put(other,seed(other));AtomicInteger views=new AtomicInteger(),searches=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(workers);
        server.createContext("/search",exchange->{searches.incrementAndGet();byte[] body=search(bv,other).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/view",exchange->{views.incrementAndGet();byte[] body="limited".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(412,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try(HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build()){
            BilibiliVideoCatalog.Transport realTransport=(url,deadline)->{
                URI endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+(url.contains("search/all")?"/search":"/view"));
                var response=http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200)throw new BilibiliVideoCatalog.CatalogFailure("HTTP_"+response.statusCode());return json.readTree(response.body());
            };
            var result=new BilibiliVideoCatalog(json,null,realTransport,cache).search("沁园春长沙 朗诵示范",600);
            assertEquals(2,result.videos().size());assertEquals(1,views.get());assertEquals(1,searches.get());
            assertTrue(result.evidence().path("cachedMetadata").get(0).path("detailRequestMade").asBoolean());assertFalse(result.evidence().path("cachedMetadata").get(1).path("detailRequestMade").asBoolean());
        }finally{server.stop(0);workers.shutdownNow();}
    }
}
