package com.demo.edu.classroom.recommendation;

import com.demo.edu.classroom.ClassroomCourseService;
import com.demo.edu.classroom.ClassroomException;
import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClassroomRecommendationServiceTest {
    final ObjectMapper json=new ObjectMapper();final ClassroomCourseService courses=mock(ClassroomCourseService.class);
    final ClassroomStudyStore store=mock(ClassroomStudyStore.class);final VideoTeachingClassifier classifier=mock(VideoTeachingClassifier.class);
    final BilibiliVideoCatalog catalog=mock(BilibiliVideoCatalog.class);final ResourceSemanticFeatures semantic=mock(ResourceSemanticFeatures.class);
    final ClassroomAiGateway ai=mock(ClassroomAiGateway.class);final Map<String,ObjectNode> saved=new HashMap<>();ClassroomRecommendationService service;
    ObjectNode course;
    @BeforeEach void setup(){course=json.createObjectNode().put("id","course").put("topic","沁园春长沙").put("title","沁园春长沙").put("grade",10).put("direction","academic");
        course.putArray("scenes").addObject().put("id","scene").put("title","朗诵节奏").put("type","slide").putObject("content");
        when(courses.studySnapshot("course")).thenAnswer(c->course.deepCopy());when(semantic.identity()).thenReturn(json.createObjectNode().put("model","a").put("dimension",2));
        when(store.find(anyString(),anyString())).thenAnswer(c->Optional.ofNullable(saved.get(c.getArgument(0)+":"+c.getArgument(1))).map(ObjectNode::deepCopy));
        when(store.list(anyString(),anyString(),anyInt())).thenReturn(List.of());
        when(store.listForScene(anyString(),anyString(),anyString(),anyInt())).thenReturn(List.of());
        doAnswer(c->{saved.put(c.getArgument(0)+":"+c.getArgument(1),((ObjectNode)c.getArgument(3)).deepCopy());return null;}).when(store).save(anyString(),anyString(),anyString(),any());
        when(store.create(anyString(),anyString(),anyString(),any())).thenAnswer(c->saved.putIfAbsent(c.getArgument(0)+":"+c.getArgument(1),((ObjectNode)c.getArgument(3)).deepCopy())==null);
        service=new ClassroomRecommendationService(courses,store,json,classifier,catalog,semantic,ai,false);}
    @AfterEach void cleanup(){service.close();}
    @Test void identicalRefreshMergesAndClassroomGenerationHasPriority(){var request=new ClassroomRecommendationService.RefreshRequest("scene",10,null);
        String id=service.refresh("course",request).path("decisionId").asText();assertEquals(id,service.refresh("course",request).path("decisionId").asText());
        when(courses.hasActiveGeneration()).thenReturn(true);service.tick();assertEquals("queued",service.get("course","scene").path("status").asText());verifyNoInteractions(classifier,catalog,ai);}
    @Test void hiddenClassificationDoesNotSearchAndPublicResponseHidesAudit(){when(classifier.classify(any(),any())).thenReturn(new VideoTeachingClassifier.Classification("ineligible","","需要课堂练习",List.of(),"",json.createObjectNode().put("privatePrompt","secret"),json.createObjectNode()));
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null));service.tick();ObjectNode result=service.get("course","scene");
        assertEquals("hidden",result.path("status").asText());assertTrue(result.path("items").isEmpty());assertFalse(result.has("audit"));assertFalse(result.toString().contains("privatePrompt"));verifyNoInteractions(catalog);}
    @Test void progressAndRevisionDoNotInvalidateButContentDoes(){when(classifier.classify(any(),any())).thenReturn(new VideoTeachingClassifier.Classification("uncertain","","信息不足",List.of(),"",json.createObjectNode(),json.createObjectNode()));
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null));service.tick();course.put("revision",20);course.putObject("progress").put("sceneIndex",0).put("actionIndex",9);
        assertEquals("hidden",service.get("course","scene").path("status").asText());((ObjectNode)course.path("scenes").get(0)).put("title","新的课程主题");assertEquals("idle",service.get("course","scene").path("status").asText());}
    @Test void badBudgetRejectedAndPreferencesSaved(){assertThrows(ClassroomException.class,()->service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",9,null)));
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",5,"实景观察"));assertEquals(5,service.get("course","scene").path("budgetMinutes").asInt());}
    @Test void openedEventIsDeduplicatedAndCannotRewriteOtherSubmission(){var decision=json.createObjectNode().put("courseId","course").put("sceneId","scene").put("contentFingerprint","fp");decision.putArray("items").addObject().put("id","BV1r3411q7mV:1");
        saved.put(ClassroomRecommendationService.DECISION+":decision",decision);var request=new ClassroomRecommendationService.FeedbackRequest("decision","BV1r3411q7mV:1","opened","request-1");
        assertTrue(service.feedback("course",request).path("saved").asBoolean());assertTrue(service.feedback("course",request).path("saved").asBoolean());
        assertThrows(ClassroomException.class,()->service.feedback("course",new ClassroomRecommendationService.FeedbackRequest("decision","BV1r3411q7mV:1","tried","request-1")));
        verify(courses,times(3)).studySnapshot("course");verifyNoMoreInteractions(courses);}
    @Test void candidateMustBeCompatibleAndEvidenceMustActuallyExist(){var video=new BilibiliVideoCatalog.Video("v","BV1r3411q7mV",1,"url","沁园春朗诵","有节奏的朗诵","示范","up",100,"now",json.createObjectNode());
        ObjectNode response=json.createObjectNode();ObjectNode candidate=response.putArray("candidates").addObject().put("id","v").put("eligible",true).put("gradeCompatibility","compatible")
                .put("purpose","recitation").put("reason","补充节奏示范").put("metadataQuote","有节奏的朗诵").put("preferenceMatch",false);candidate.putArray("goals").add("朗诵节奏");
        assertDoesNotThrow(()->ClassroomRecommendationService.validateMatches(response,Set.of("朗诵节奏"),List.of(video)));
        candidate.put("metadataQuote","不存在的字幕");assertThrows(IllegalArgumentException.class,()->ClassroomRecommendationService.validateMatches(response,Set.of("朗诵节奏"),List.of(video)));
        candidate.put("metadataQuote","有节奏的朗诵").put("gradeCompatibility","unknown");assertThrows(IllegalArgumentException.class,()->ClassroomRecommendationService.validateMatches(response,Set.of("朗诵节奏"),List.of(video)));}
    @Test void fullDecisionCallsFiniteOptimizerAndReturnsOnlyVerifiedCards(){
        String id="BV1r3411q7mV:1";var video=new BilibiliVideoCatalog.Video(id,"BV1r3411q7mV",1,"https://www.bilibili.com/video/BV1r3411q7mV/?p=1","沁园春长沙朗诵","朗诵节奏","示范","up",103,java.time.Instant.now().toString(),json.createObjectNode());
        when(classifier.classify(any(),any())).thenReturn(new VideoTeachingClassifier.Classification("eligible","recitation","补充朗诵示范",List.of("朗诵节奏"),"沁园春长沙",json.createObjectNode(),json.createObjectNode()));
        when(catalog.search(eq("沁园春长沙 朗诵示范"),eq(600),any())).thenReturn(new BilibiliVideoCatalog.Search(List.of(video),List.of(),json.createObjectNode()));
        ObjectNode output=json.createObjectNode();var item=output.putArray("candidates").addObject().put("id",id).put("eligible",true).put("gradeCompatibility","compatible").put("purpose","recitation")
                .put("reason","体会朗诵节奏").put("metadataQuote","朗诵节奏").put("preferenceMatch",false);item.putArray("goals").add("朗诵节奏");
        when(ai.structured(anyString(),anyString(),anyString(),any(),any())).thenReturn(new ClassroomAiGateway.Result(output,json.createObjectNode().put("model","actual-model")));
        when(semantic.compute(anyString(),anyString(),anyList(),any())).thenReturn(new ResourceSemanticFeatures.Features(Map.of(id,.9),Map.of(),json.createObjectNode()));
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null));service.tick();var result=service.get("course","scene");
        assertEquals("ready",result.path("status").asText());assertEquals(id,result.path("items").get(0).path("id").asText());assertEquals("metadata",result.path("items").get(0).path("verificationScope").asText());
        assertEquals("bilibili-public-api",result.path("items").get(0).path("sourceKind").asText());assertEquals("已核验视频资料",result.path("items").get(0).path("sourceLabel").asText());
        ObjectNode decision=saved.get(ClassroomRecommendationService.DECISION+":"+result.path("decisionId").asText());assertTrue(decision.path("audit").path("verification").path("optimal").asBoolean());
        assertFalse(result.has("audit"));assertFalse(result.toString().contains("actual-model"));verify(catalog,times(1)).search(anyString(),anyInt(),any());
        var portable=service.portableRecommendations("course");assertEquals(1,portable.size());assertEquals("scene",portable.get(0).path("sceneId").asText());
        assertFalse(portable.toString().contains("actual-model"));assertFalse(portable.get(0).has("verificationScope"));
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null));service.tick();verify(catalog,times(1)).search(anyString(),anyInt(),any());
        String original=java.time.Instant.now().minusSeconds(120).toString();
        var cachedVideo=new BilibiliVideoCatalog.Video(id,video.bvid(),1,video.url(),video.title(),video.description(),video.partTitle(),video.author(),103,original,
                json.createObjectNode().put("source","verified-metadata-cache").put("detailFailure",412));
        when(catalog.search(anyString(),anyInt(),any())).thenReturn(new BilibiliVideoCatalog.Search(List.of(cachedVideo),List.of(),json.createObjectNode()));
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null,true));service.tick();var cachedCard=service.get("course","scene").path("items").get(0);
        assertEquals("verified-metadata-cache",cachedCard.path("sourceKind").asText());assertEquals("已核验资料缓存",cachedCard.path("sourceLabel").asText());
        assertEquals(original,cachedCard.path("checkedAt").asText());assertFalse(cachedCard.has("detailFailure"));}
    @Test void verifiedMissingGoalChangesOptimizerTargetsButInventedSourceCannot(){
        course.put("sourceText","朗诵要把握节奏，并表达豪迈情感。");
        var context=ClassroomStudyContext.from(course,"scene");
        var anchor=context.anchors().stream().filter(a->a.kind().equals("reference")).findFirst().orElseThrow();
        String id="BV1r3411q7mV:1";var video=new BilibiliVideoCatalog.Video(id,"BV1r3411q7mV",1,"https://www.bilibili.com/video/BV1r3411q7mV/?p=1",
                "沁园春长沙朗诵","朗诵节奏与豪迈情感","示范","up",103,java.time.Instant.now().toString(),json.createObjectNode());
        when(classifier.classify(any(),any())).thenReturn(new VideoTeachingClassifier.Classification("eligible","recitation","补充朗诵示范",List.of("朗诵节奏","豪迈情感"),"沁园春长沙",json.createObjectNode(),json.createObjectNode()));
        when(catalog.search(anyString(),anyInt(),any())).thenReturn(new BilibiliVideoCatalog.Search(List.of(video),List.of(),json.createObjectNode()));
        List<ObjectNode> matchingInputs=new ArrayList<>();
        when(ai.structured(anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{
            ObjectNode input=(ObjectNode)json.readTree((String)call.getArgument(2));matchingInputs.add(input);
            ObjectNode output=json.createObjectNode();var candidate=output.putArray("candidates").addObject().put("id",id).put("eligible",true).put("gradeCompatibility","compatible")
                    .put("purpose","recitation").put("reason","补充当前目标的诵读示范").put("metadataQuote","朗诵节奏与豪迈情感").put("preferenceMatch",false);
            candidate.set("goals",input.path("targetGoals").deepCopy());
            java.util.function.Consumer<JsonNode> validator=call.getArgument(4);validator.accept(output);
            return new ClassroomAiGateway.Result(output,json.createObjectNode());
        });
        when(semantic.compute(anyString(),anyString(),anyList(),any())).thenReturn(new ResourceSemanticFeatures.Features(Map.of(id,.9),Map.of(),json.createObjectNode()));
        var request=new ClassroomRecommendationService.RefreshRequest("scene",10,null);
        String baselineId=service.refresh("course",request).path("decisionId").asText();service.tick();
        var baseline=saved.get(ClassroomRecommendationService.DECISION+":"+baselineId);
        assertEquals(2,baseline.path("audit").path("optimizerInput").path("targetGoals").size());
        ObjectNode attempt=json.createObjectNode().put("courseId","course").put("sceneId","scene").put("contentFingerprint",context.fingerprint()).put("status","checked").put("id","attempt-a").put("createdAt","2026-10-16T01:00:00Z");
        var analysis=attempt.putArray("analysis").addObject().put("goal","豪迈情感").put("status","missing").put("anchorId",anchor.id()).put("quote","表达豪迈情感");
        when(store.listForScene(eq("learning_attempt"),eq("course"),eq("scene"),anyInt())).thenReturn(List.of(attempt));
        String focusedId=service.refresh("course",request).path("decisionId").asText();service.tick();
        var focused=saved.get(ClassroomRecommendationService.DECISION+":"+focusedId);
        assertNotEquals(baselineId,focusedId);assertEquals(json.valueToTree(List.of("豪迈情感")),focused.path("audit").path("optimizerInput").path("targetGoals"));
        assertEquals(2,focused.path("audit").path("contextGoals").size());assertEquals(2,matchingInputs.get(1).path("contextGoals").size());
        assertFalse(matchingInputs.get(1).path("classroomContext").path("anchors").isEmpty());assertEquals(10,matchingInputs.get(1).path("grade").asInt());
        analysis.put("quote","不存在的资料原文");
        var invalid=service.refresh("course",request);assertEquals(baselineId,invalid.path("decisionId").asText());
        assertEquals(2,saved.get(ClassroomRecommendationService.DECISION+":"+invalid.path("decisionId").asText()).path("audit").path("optimizerInput").path("targetGoals").size());
        // A 的旧 missing 随提示刷新 updatedAt；B 是较新的 covered。必须按表述创建时间取 B。
        analysis.put("quote","表达豪迈情感");attempt.put("updatedAt","2026-10-16T03:00:00Z").put("hintLevel",4);
        ObjectNode newer=attempt.deepCopy().put("id","attempt-b").put("createdAt","2026-10-16T02:00:00Z").put("updatedAt","2026-10-16T02:00:00Z");
        ((ObjectNode)newer.path("analysis").get(0)).put("status","covered");
        when(store.listForScene(eq("learning_attempt"),eq("course"),eq("scene"),anyInt())).thenReturn(List.of(attempt,newer));
        var afterHint=service.refresh("course",request);assertEquals(baselineId,afterHint.path("decisionId").asText());
        assertEquals(2,saved.get(ClassroomRecommendationService.DECISION+":"+afterHint.path("decisionId").asText()).path("audit").path("optimizerInput").path("targetGoals").size());
        assertEquals(2,matchingInputs.size(),"旧提示更新不得再生成反馈优先推荐");
    }
    ObjectNode completedCacheFixture(){when(classifier.classify(any(),any())).thenReturn(new VideoTeachingClassifier.Classification("uncertain","","资料待核对",List.of(),"",json.createObjectNode(),json.createObjectNode()));
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null));service.tick();
        return saved.entrySet().stream().filter(e->e.getKey().startsWith(ClassroomRecommendationService.CACHE+":")).findFirst().orElseThrow().getValue();}
    @Test void readyCacheExpiresAtEarliestOriginalVerificationAndCanPrepareAgain(){var cached=completedCacheFixture();String oldId=cached.path("decisionId").asText();
        cached.put("status","ready");var item=cached.putArray("items").addObject().put("id","v").put("checkedAt",java.time.Instant.now().minusSeconds(23*3600).toString());
        assertEquals("ready",service.get("course","scene").path("status").asText());
        item.put("checkedAt",java.time.Instant.now().minusSeconds(24*3600+1).toString());cached.put("updatedAt",java.time.Instant.now().toString());
        assertEquals("idle",service.get("course","scene").path("status").asText());
        var queued=service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null));assertEquals("queued",queued.path("status").asText());assertNotEquals(oldId,queued.path("decisionId").asText());}
    @Test void manualRefreshBypassesFreshReadyHiddenAndErrorButMergesInFlight(){completedCacheFixture();
        for(String status:List.of("ready","hidden","error")){
            var cached=saved.entrySet().stream().filter(e->e.getKey().startsWith(ClassroomRecommendationService.CACHE+":")).findFirst().orElseThrow().getValue();
            cached.put("status",status).put("updatedAt",java.time.Instant.now().toString());cached.putArray("items");
            if(status.equals("ready"))cached.withArray("items").addObject().put("id","v").put("checkedAt",java.time.Instant.now().toString());
            String oldId=cached.path("decisionId").asText();assertEquals(status,service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null)).path("status").asText());
            var queued=service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null,true));assertEquals("queued",queued.path("status").asText());assertNotEquals(oldId,queued.path("decisionId").asText());
            assertEquals(queued.path("decisionId"),service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null,true)).path("decisionId"));service.tick();
        }}
}
