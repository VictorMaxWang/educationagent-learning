package com.demo.edu.classroom.recommendation;

import com.demo.service.tools.WebSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BilibiliVideoCatalogTest {
    final ObjectMapper json=new ObjectMapper();final String bv="BV1r3411q7mV";
    ObjectNode view(){ObjectNode response=json.createObjectNode().put("code",0);ObjectNode data=response.putObject("data").put("bvid",bv).put("state",0)
        .put("title","<em>沁园春</em>长沙朗诵").put("desc","朗诵节奏示范");data.putObject("owner").put("name","朗诵课堂");
        data.putArray("pages").addObject().put("page",1).put("part","诵读示范").put("duration",103);return response;}
    @Test void cardUsesVerifiedPartDurationAndCanonicalLink(){var catalog=new BilibiliVideoCatalog(json,null,(url,deadline)->json.createObjectNode());var rejects=new ArrayList<ObjectNode>();
        var result=catalog.parseView(bv,view(),600,20,rejects);assertEquals(1,result.size());var video=result.getFirst();assertEquals(103,video.durationSeconds());
        assertEquals("沁园春长沙朗诵",video.title());assertEquals("https://www.bilibili.com/video/"+bv+"/?p=1",video.url());assertEquals("not-retrieved",video.metadataSources().path("subtitle").asText());}
    @Test void unknownLongDeletedAndPromotionalVideosAreRejected(){var catalog=new BilibiliVideoCatalog(json,null,(url,deadline)->json.createObjectNode());
        var unknown=view();((ObjectNode)unknown.path("data").path("pages").get(0)).put("duration",0);
        assertTrue(catalog.parseView(bv,unknown,600,20,new ArrayList<>()).isEmpty());assertTrue(catalog.parseView(bv,view(),60,20,new ArrayList<>()).isEmpty());
        var deleted=view();((ObjectNode)deleted.path("data")).put("state",-4);assertTrue(catalog.parseView(bv,deleted,600,20,new ArrayList<>()).isEmpty());
        var ad=view();((ObjectNode)ad.path("data")).put("desc","私信领取资料");assertTrue(catalog.parseView(bv,ad,600,20,new ArrayList<>()).isEmpty());}
    @Test void changedVideoIdentityDoesNotCreateLink(){var catalog=new BilibiliVideoCatalog(json,null,(url,deadline)->json.createObjectNode());
        assertTrue(catalog.parseView("BV1x11111111",view(),600,20,new ArrayList<>()).isEmpty());}
    @Test void searchRowsAreDeduplicatedAndMustPassView(){List<String> requested=new ArrayList<>();var result=json.createObjectNode().put("code",0);
        var group=result.putObject("data").putArray("result").addObject().put("result_type","video");group.putArray("data").addObject().put("bvid",bv);
        ((com.fasterxml.jackson.databind.node.ArrayNode)group.path("data")).addObject().put("bvid",bv);
        var catalog=new BilibiliVideoCatalog(json,null,(url,deadline)->{requested.add(url);return url.contains("search/all")?result:view();});
        assertEquals(1,catalog.search("沁园春长沙 朗诵示范",600).videos().size());assertEquals(2,requested.size());}
    @Test void fallbackDoesNotInventVideoFromSearchPageOrBareBv(){WebSearchService web=mock(WebSearchService.class);
        when(web.search(anyString(),anyInt())).thenReturn("https://search.bilibili.com/all?keyword="+bv+" 裸编号 "+bv);
        var catalog=new BilibiliVideoCatalog(json,web,(url,deadline)->{throw new BilibiliVideoCatalog.CatalogFailure("HTTP_412");});
        assertTrue(catalog.search("沁园春长沙 朗诵示范",600).videos().isEmpty());verify(web,times(1)).search(anyString(),eq(10));}
    @Test void slowLegacyFallbackCannotOccupyRecommendationWorkerPastBudget(){WebSearchService web=mock(WebSearchService.class);
        when(web.search(anyString(),anyInt())).thenAnswer(call->{Thread.sleep(2000);return "";});
        var catalog=new BilibiliVideoCatalog(json,web,(url,deadline)->{throw new BilibiliVideoCatalog.CatalogFailure("HTTP_412");});
        long start=System.nanoTime();var result=catalog.search("沁园春长沙 朗诵示范",600,java.time.Duration.ofMillis(150));
        assertTrue(result.videos().isEmpty());assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()<800);
        assertEquals("SEARCH_UNAVAILABLE",result.evidence().path("fallbackFailure").asText());verify(web,times(1)).search(anyString(),eq(10));}
    @Test void malformedViewSuccessCodeAndFractionalDurationCannotBecomeVerifiedParts(){
        var catalog=new BilibiliVideoCatalog(json,null,(url,deadline)->json.createObjectNode());
        for(var code:List.of(json.getNodeFactory().booleanNode(false),json.getNodeFactory().textNode("0"),json.getNodeFactory().numberNode(0.5))){
            var response=view();response.set("code",code);assertTrue(catalog.parseView(bv,response,600,20,new ArrayList<>()).isEmpty());
        }
        var fractional=view();((ObjectNode)fractional.path("data").path("pages").get(0)).put("duration",103.9);
        assertTrue(catalog.parseView(bv,fractional,600,20,new ArrayList<>()).isEmpty());
    }
}
