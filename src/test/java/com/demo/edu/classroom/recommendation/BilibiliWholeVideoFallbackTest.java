package com.demo.edu.classroom.recommendation;

import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 166秒是现场两P样本116+50的整条时长，不能伪装为P1的116秒。 */
class BilibiliWholeVideoFallbackTest {
    final ObjectMapper json=new ObjectMapper();final String bv="BV1Fb7h6iEeD";
    ObjectNode row(String bvid,String duration){return json.createObjectNode().put("bvid",bvid).put("type","video").put("title","诗文朗诵示范")
            .put("author","课程作者").put("description","观察诵读节奏").put("duration",duration).put("is_pay",0).put("is_charge_video",0);}
    ObjectNode response(ObjectNode...rows){var response=json.createObjectNode().put("code",0);var data=response.putObject("data").putArray("result").addObject().put("result_type","video").putArray("data");
        for(var row:rows)data.add(row);return response;}
    BilibiliVideoCatalog blocked(ObjectNode response,AtomicInteger calls,VerifiedVideoMetadataCache cache){return new BilibiliVideoCatalog(json,null,(url,deadline)->{
        if(url.contains("search/all"))return response;calls.incrementAndGet();throw new BilibiliVideoCatalog.CatalogFailure("HTTP_412");},cache);}
    @Test void currentSearchDurationRepresentsWholeVideoWithoutInventedPartOrViewCache(){var store=mock(ClassroomStudyStore.class);var cache=new VerifiedVideoMetadataCache(store,json);var calls=new AtomicInteger();
        var result=blocked(response(row(bv,"2:46")),calls,cache).search("诗文 朗诵示范",600);assertEquals(1,result.videos().size());var video=result.videos().getFirst();
        assertEquals(166,video.durationSeconds());assertEquals(0,video.part());assertEquals(bv+":whole",video.id());assertEquals("https://www.bilibili.com/video/"+bv+"/",video.url());
        assertEquals("bilibili-search-metadata",video.metadataSources().path("source").asText());assertEquals("whole-video",video.metadataSources().path("durationScope").asText());
        assertFalse(video.metadataSources().path("detailsVerified").asBoolean());assertFalse(video.metadataSources().path("partsVerified").asBoolean());
        assertFalse(video.metadataSources().has("pages"));assertFalse(video.metadataSources().has("state"));assertEquals(1,calls.get());verifyNoInteractions(store);}
    @Test void one412StopsFurtherDetailsAndUsesOnlyThisSuccessfulSearch(){var calls=new AtomicInteger();var result=blocked(response(row(bv,"2:46"),row("BV1NE411o7P1","1:36")),calls,null).search("诗文 朗诵示范",600);
        assertEquals(2,result.videos().size());assertEquals(1,calls.get());assertTrue(result.videos().stream().allMatch(v->v.part()==0));
        var bad=response(row(bv,"2:46")).put("code",-400);assertTrue(blocked(bad,new AtomicInteger(),null).search("诗文 朗诵示范",600).videos().isEmpty());}
    @Test void unknownBadDurationAndOverBudgetCannotBeInventedFromSearch(){for(String duration:List.of("","直播","1:99","-1:20","1:2:90","1.5分钟","999999999999999:00","0:00","20:01")){
        assertTrue(blocked(response(row(bv,duration)),new AtomicInteger(),null).search("诗文 朗诵示范",1200).videos().isEmpty(),duration);}}
    @Test void paidPromotionalAndConflictingSearchRowsAreFailClosed(){for(ObjectNode bad:List.of(row(bv,"2:46").put("is_pay",1),row(bv,"2:46").put("is_charge_video",true),
            row(bv,"2:46").put("is_pay","0"),row(bv,"2:46").put("description","私信领取资料"),row(bv,"2:46").put("author",""))){
        assertTrue(blocked(response(bad),new AtomicInteger(),null).search("诗文 朗诵示范",600).videos().isEmpty());}
        assertTrue(blocked(response(row(bv,"2:46"),row(bv,"1:56")),new AtomicInteger(),null).search("诗文 朗诵示范",600).videos().isEmpty());}
    @Test void nativeDeletedVideoFailureDoesNotBecomeSearchFallback(){var catalog=new BilibiliVideoCatalog(json,null,(url,deadline)->{
        if(url.contains("search/all"))return response(row(bv,"2:46"));return json.createObjectNode().put("code",-404);});
        assertTrue(catalog.search("诗文 朗诵示范",600).videos().isEmpty());}
    @Test void explicitHoursAreParsedButNumericAndMissingDurationAreUnknown(){assertEquals(3723,blocked(response(row(bv,"1:02:03")),new AtomicInteger(),null).search("诗文 朗诵示范",4000).videos().getFirst().durationSeconds());
        assertTrue(blocked(response(row(bv,"2:46").put("duration",166)),new AtomicInteger(),null).search("诗文 朗诵示范",600).videos().isEmpty());}
    @Test void successfulSearchRequiresActualIntegerZeroNotCoercibleValues(){
        for(var code:List.of(json.getNodeFactory().booleanNode(false),json.getNodeFactory().textNode("0"),json.getNodeFactory().numberNode(0.5),json.getNodeFactory().numberNode(0.0),json.getNodeFactory().numberNode(4294967296L))){
            var payload=response(row(bv,"2:46"));payload.set("code",code);var calls=new AtomicInteger();
            assertTrue(blocked(payload,calls,null).search("诗文 朗诵示范",600).videos().isEmpty());assertEquals(0,calls.get(),"无成功搜索不能启动详情或伪装资料");
        }}
    @Test void descriptionAndDescBothParticipateInPromotionChecks(){
        for(var bad:List.of(row(bv,"2:46").put("description","").put("desc","私信领取资料"),row(bv,"2:46").put("description","普通课程简介").put("desc","购买课程请加群"))){
            var calls=new AtomicInteger();assertTrue(blocked(response(bad),calls,null).search("诗文 朗诵示范",600).videos().isEmpty());assertEquals(0,calls.get());
        }
        var good=row(bv,"2:46").put("description","").put("desc","观察停顿与诵读节奏");
        var video=blocked(response(good),new AtomicInteger(),null).search("诗文 朗诵示范",600).videos().getFirst();
        assertEquals("观察停顿与诵读节奏",video.description());assertTrue(video.metadataSources().path("description").asText().endsWith("#video.desc"));
    }
    @Test void zeroDurationAndCoercibleZeroRemainUnknown(){
        for(var duration:List.of(json.getNodeFactory().textNode("0:00"),json.getNodeFactory().textNode("00:00:00"),json.getNodeFactory().textNode("0"),json.getNodeFactory().numberNode(0),
                json.getNodeFactory().numberNode(0.0),json.getNodeFactory().booleanNode(false),json.getNodeFactory().nullNode())){
            var candidate=row(bv,"2:46");candidate.set("duration",duration);
            assertTrue(blocked(response(candidate),new AtomicInteger(),null).search("诗文 朗诵示范",600).videos().isEmpty());
        }}
    @Test void malformedNativeCodeNeverCreatesVerifiedViewCache(){var cache=mock(VerifiedVideoMetadataCache.class);
        var nativeResponse=json.createObjectNode().put("code",false);nativeResponse.putObject("data").put("bvid",bv).put("state",0);
        var catalog=new BilibiliVideoCatalog(json,null,(url,deadline)->url.contains("search/all")?response(row(bv,"2:46")):nativeResponse,cache);
        assertTrue(catalog.search("诗文 朗诵示范",600).videos().isEmpty());verifyNoInteractions(cache);}
}
