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
        doAnswer(c->{for(ClassroomStudyStore.Write write:(List<ClassroomStudyStore.Write>)c.getArgument(0))store.save(write.kind(),write.entryId(),write.courseId(),write.document());return null;}).when(store).saveBatch(anyList());
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
    @Test void rapidBudgetChangesKeepOnlyLatestQueuedRequestAndDoNotPayForSupersededOnes(){
        when(classifier.classify(any(),any())).thenReturn(new VideoTeachingClassifier.Classification("uncertain","","资料待核对",List.of(),"",json.createObjectNode(),json.createObjectNode()));
        String first=service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null)).path("decisionId").asText();
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",5,null));
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",20,null));service.tick();service.tick();
        assertEquals(20,service.get("course","scene").path("budgetMinutes").asInt());verify(classifier,times(1)).classify(any(),any());
        assertEquals("NEWER_PAGE_REQUEST",saved.get(ClassroomRecommendationService.DECISION+":"+first).path("audit").path("failureKind").asText());
    }
    @Test void fullQueueRejectsWithoutChangingTheRequestedPageSettings(){
        for(int index=0;index<32;index++){String id="queued-"+index;course.withArray("scenes").addObject().put("id",id).put("title","朗诵"+index).put("type","slide").putObject("content");service.refresh("course",new ClassroomRecommendationService.RefreshRequest(id,10,null));}
        String settings=ClassroomStudyContext.hash(json.valueToTree(List.of("course","scene")));saved.put(ClassroomRecommendationService.SETTINGS+":"+settings,json.createObjectNode().put("budgetMinutes",5).put("preference",""));
        assertEquals(429,assertThrows(ClassroomException.class,()->service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",20,null))).status());
        assertEquals(5,saved.get(ClassroomRecommendationService.SETTINGS+":"+settings).path("budgetMinutes").asInt());verifyNoInteractions(classifier,catalog,ai);
    }
    @Test void restartRetainsUnknownIntentAndOnlyManualRequestCanDispatchAgain(){
        String first=service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null)).path("decisionId").asText();service.close();
        service=new ClassroomRecommendationService(courses,store,json,classifier,catalog,semantic,ai,false);
        assertEquals("error",service.get("course","scene").path("status").asText());
        assertEquals(first,service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null)).path("decisionId").asText());verifyNoInteractions(classifier,catalog,ai);
        var manual=service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null,true));assertEquals("queued",manual.path("status").asText());assertNotEquals(first,manual.path("decisionId").asText());
    }
    @Test void queueDeadlineExpiresEvenWhileClassroomGenerationNeverFinishes(){
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"clock",java.time.Clock.fixed(java.time.Instant.now().plusSeconds(301),java.time.ZoneOffset.UTC));
        when(courses.hasActiveGeneration()).thenReturn(true);service.tick();assertEquals("error",service.get("course","scene").path("status").asText());
        assertEquals("error",service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null)).path("status").asText());verifyNoInteractions(classifier,catalog,ai);
    }
    @Test void changingModelIdentityAfterRestartCannotHideAnUnknownPageCallBehindANewCacheKey(){
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null));service.close();
        when(semantic.identity()).thenReturn(json.createObjectNode().put("model","new-model").put("dimension",2));
        service=new ClassroomRecommendationService(courses,store,json,classifier,catalog,semantic,ai,false);
        assertEquals("error",service.get("course","scene").path("status").asText());
        assertEquals("error",service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null)).path("status").asText());verifyNoInteractions(classifier,catalog,ai);
        assertEquals("queued",service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null,true)).path("status").asText());
    }
    @Test void persistenceFailureDoesNotDiscardPreviousQueuedBudget(){
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",5,null));doThrow(new ClassroomException(503,"store unavailable")).when(store).saveBatch(anyList());
        assertThrows(ClassroomException.class,()->service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",20,null)));
        assertEquals(5,service.get("course","scene").path("budgetMinutes").asInt());assertEquals("queued",service.get("course","scene").path("status").asText());verifyNoInteractions(classifier,catalog,ai);
    }

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
        assertFalse(portable.toString().contains("actual-model"));assertEquals("metadata",portable.get(0).path("verificationScope").asText());
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null));service.tick();verify(catalog,times(1)).search(anyString(),anyInt(),any());
        String original=java.time.Instant.now().minusSeconds(120).toString();
        var cachedVideo=new BilibiliVideoCatalog.Video(id,video.bvid(),1,video.url(),video.title(),video.description(),video.partTitle(),video.author(),103,original,
                json.createObjectNode().put("source","verified-metadata-cache").put("detailFailure",412));
        when(catalog.search(anyString(),anyInt(),any())).thenReturn(new BilibiliVideoCatalog.Search(List.of(cachedVideo),List.of(),json.createObjectNode()));
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null,true));service.tick();var cachedCard=service.get("course","scene").path("items").get(0);
        assertEquals("verified-metadata-cache",cachedCard.path("sourceKind").asText());assertEquals("已核验资料缓存",cachedCard.path("sourceLabel").asText());
        assertEquals(original,cachedCard.path("checkedAt").asText());assertFalse(cachedCard.has("detailFailure"));
        String wholeId=video.bvid()+":whole";var whole=new BilibiliVideoCatalog.Video(wholeId,video.bvid(),0,"https://www.bilibili.com/video/"+video.bvid()+"/",video.title(),video.description(),"",video.author(),166,original,
                json.createObjectNode().put("source","bilibili-search-metadata").put("durationScope","whole-video"));
        when(catalog.search(anyString(),anyInt(),any())).thenReturn(new BilibiliVideoCatalog.Search(List.of(whole),List.of(),json.createObjectNode()));
        item.put("id",wholeId);when(semantic.compute(anyString(),anyString(),anyList(),any())).thenReturn(new ResourceSemanticFeatures.Features(Map.of(wholeId,.9),Map.of(),json.createObjectNode()));
        service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null,true));service.tick();var wholeCard=service.get("course","scene").path("items").get(0);
        assertEquals(wholeId,wholeCard.path("id").asText());assertEquals("search-metadata",wholeCard.path("verificationScope").asText());assertEquals("B站搜索资料",wholeCard.path("sourceLabel").asText());
        assertEquals("whole-video",wholeCard.path("durationScope").asText());assertEquals(0,wholeCard.path("part").asInt());assertFalse(wholeCard.path("url").asText().contains("?p="));
        assertEquals("whole-video",service.portableRecommendations("course").get(0).path("durationScope").asText());}
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
    @Test void matchingInputKeepsAllCandidatesAndClassroomButExcludesRawTransportMetadata(){
        course.put("sourceText","本课完整资料：体会朗诵节奏，并对照停顿表达情感。");
        List<BilibiliVideoCatalog.Video> videos=new ArrayList<>();
        for(int index=0;index<3;index++){
            String bvid=List.of("BV1r3411q7mV","BV1NE411o7P1","BV1Fb7h6iEeD").get(index);
            videos.add(new BilibiliVideoCatalog.Video(bvid+":whole",bvid,0,"https://www.bilibili.com/video/"+bvid+"/",index==0?"沁园春长沙朗诵":"其他内容"+index,
                    index==0?"观察朗诵节奏":"无关主题","","课程作者",103,java.time.Instant.now().toString(),json.createObjectNode().put("rawTrace","RAW_METADATA_SENTINEL").put("url","https://transport.invalid/internal")));
        }
        when(classifier.classify(any(),any())).thenReturn(new VideoTeachingClassifier.Classification("eligible","recitation","补充朗诵示范",List.of("朗诵节奏"),"沁园春长沙",json.createObjectNode(),json.createObjectNode()));
        when(catalog.search(anyString(),anyInt(),any())).thenReturn(new BilibiliVideoCatalog.Search(videos,List.of(),json.createObjectNode()));
        List<ObjectNode> received=new ArrayList<>();
        when(ai.structured(anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{
            ObjectNode input=(ObjectNode)json.readTree((String)call.getArgument(2));received.add(input);
            java.time.Duration budget=call.getArgument(3);assertTrue(budget.compareTo(java.time.Duration.ofSeconds(45))<=0);
            ObjectNode answer=json.createObjectNode();var candidates=answer.putArray("candidates");
            var allowed=candidates.addObject().put("id",videos.getFirst().id()).put("eligible",true).put("gradeCompatibility","compatible").put("purpose","recitation")
                    .put("reason","体会停顿与节奏").put("metadataQuote","观察朗诵节奏").put("preferenceMatch",false);allowed.putArray("goals").add("朗诵节奏");
            for(int i=1;i<videos.size();i++)candidates.addObject().put("id",videos.get(i).id()).put("eligible",false);
            java.util.function.Consumer<JsonNode> validator=call.getArgument(4);validator.accept(answer);
            return new ClassroomAiGateway.Result(answer,json.createObjectNode());
        });
        when(semantic.compute(anyString(),anyString(),anyList(),any())).thenReturn(new ResourceSemanticFeatures.Features(Map.of(videos.getFirst().id(),.9),Map.of(),json.createObjectNode()));
        String decisionId=service.refresh("course",new ClassroomRecommendationService.RefreshRequest("scene",10,null)).path("decisionId").asText();service.tick();
        assertEquals("ready",service.get("course","scene").path("status").asText());assertEquals(1,received.size());var input=received.getFirst();
        assertEquals(3,input.path("videos").size());assertTrue(input.path("classroomContext").path("anchors").toString().contains("本课完整资料"));assertEquals(json.valueToTree(List.of("朗诵节奏")),input.path("targetGoals"));
        Set<String> fields=Set.of("id","title","partTitle","description","author","part","durationSeconds","durationScope");
        for(JsonNode video:input.path("videos")){Set<String> actual=new HashSet<>();video.fieldNames().forEachRemaining(actual::add);assertEquals(fields,actual);assertEquals("whole-video",video.path("durationScope").asText());}
        assertFalse(input.toString().contains("RAW_METADATA_SENTINEL"));assertFalse(input.path("videos").toString().contains("checkedAt"));assertFalse(input.path("videos").toString().contains("https://"));
        assertTrue(saved.get(ClassroomRecommendationService.DECISION+":"+decisionId).path("audit").path("metadata").toString().contains("RAW_METADATA_SENTINEL"));
    }
    @Test void compactRejectionsStillRequireCompleteCoverageAndStrictEligibleEvidence(){
        var first=new BilibiliVideoCatalog.Video("one","BV1r3411q7mV",0,"url","朗诵节奏","观察停顿","","老师",103,"now",json.createObjectNode());
        var second=new BilibiliVideoCatalog.Video("two","BV1NE411o7P1",0,"url","其他内容","其他主题","","老师",120,"now",json.createObjectNode());
        var output=json.createObjectNode();var items=output.putArray("candidates");var good=items.addObject().put("id","one").put("eligible",true).put("gradeCompatibility","compatible")
                .put("purpose","recitation").put("reason","观察停顿").put("metadataQuote","观察停顿").put("preferenceMatch",false);good.putArray("goals").add("朗诵节奏");items.addObject().put("id","two").put("eligible",false);
        assertDoesNotThrow(()->ClassroomRecommendationService.validateMatches(output,Set.of("朗诵节奏"),List.of(first,second)));
        good.put("gradeCompatibility","unknown");assertThrows(IllegalArgumentException.class,()->ClassroomRecommendationService.validateMatches(output,Set.of("朗诵节奏"),List.of(first,second)));
        good.put("gradeCompatibility","compatible").put("metadataQuote","资料中没有这句话");assertThrows(IllegalArgumentException.class,()->ClassroomRecommendationService.validateMatches(output,Set.of("朗诵节奏"),List.of(first,second)));
        good.put("metadataQuote","观察停顿").putArray("goals").add("不属于本课的知识点");assertThrows(IllegalArgumentException.class,()->ClassroomRecommendationService.validateMatches(output,Set.of("朗诵节奏"),List.of(first,second)));
        good.putArray("goals").add("朗诵节奏");items.remove(1);assertThrows(IllegalArgumentException.class,()->ClassroomRecommendationService.validateMatches(output,Set.of("朗诵节奏"),List.of(first,second)));
    }
}
