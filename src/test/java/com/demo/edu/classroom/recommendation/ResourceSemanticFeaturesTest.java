package com.demo.edu.classroom.recommendation;

import com.demo.chroma.base.EmbeddingService;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceSemanticFeaturesTest {
    final ObjectMapper json=new ObjectMapper();
    EmbeddingService.BatchResult batch(List<List<Float>> vectors){return new EmbeddingService.BatchResult(vectors,json.createObjectNode().put("httpStatus",200).put("model","fixture-returned-model").put("attemptCount",1));}
    @Test void sameDimensionDifferentModelCannotReuseCache(){var embedding=mock(EmbeddingService.class);var store=mock(ClassroomStudyStore.class);Map<String,ObjectNode> values=new HashMap<>();
        when(store.find(anyString(),anyString())).thenAnswer(c->Optional.ofNullable(values.get(c.getArgument(1))));
        doAnswer(c->{values.put(c.getArgument(1),((ObjectNode)c.getArgument(3)).deepCopy());return null;}).when(store).save(anyString(),anyString(),anyString(),any());
        when(embedding.describeIdentity()).thenReturn(new EmbeddingService.Identity("one","model-a",2));when(embedding.generateEmbeddingsOnceWithEvidence(anyList(),any())).thenReturn(batch(List.of(List.of(1f,0f))));
        var feature=new ResourceSemanticFeatures(embedding,store,json);feature.compute("course","topic",List.of());feature.compute("course","topic",List.of());
        verify(embedding,times(1)).generateEmbeddingsOnceWithEvidence(eq(List.of("topic")),any());when(embedding.describeIdentity()).thenReturn(new EmbeddingService.Identity("one","model-b",2));
        feature.compute("course","topic",List.of());verify(embedding,times(2)).generateEmbeddingsOnceWithEvidence(eq(List.of("topic")),any());assertEquals(2,values.size());
        when(embedding.describeIdentity()).thenReturn(new EmbeddingService.Identity("one","model-b",2,"different-endpoint"));
        var changed=feature.compute("course","topic",List.of());verify(embedding,times(3)).generateEmbeddingsOnceWithEvidence(eq(List.of("topic")),any());assertEquals(3,values.size());
        assertEquals(200,changed.evidence().path("batches").get(0).path("httpStatus").asInt());
        var cached=feature.compute("course","topic",List.of());assertTrue(cached.evidence().path("batches").isEmpty());assertEquals(1,cached.evidence().path("cacheHitCount").asInt());
        assertTrue(cached.evidence().path("cachedOrigins").get(0).path("cacheHit").asBoolean());}
    @Test void invalidVectorAndUnexpectedDimensionDoNotBecomeZeroRelevance(){var embedding=mock(EmbeddingService.class);var store=mock(ClassroomStudyStore.class);
        when(store.find(anyString(),anyString())).thenReturn(Optional.empty());when(embedding.describeIdentity()).thenReturn(new EmbeddingService.Identity("one","model-a",2));
        when(embedding.generateEmbeddingsOnceWithEvidence(anyList(),any())).thenReturn(batch(List.of(List.of(0f,0f))));var feature=new ResourceSemanticFeatures(embedding,store,json);
        assertThrows(IllegalStateException.class,()->feature.compute("course","topic",List.of()));
        when(embedding.generateEmbeddingsOnceWithEvidence(anyList(),any())).thenReturn(batch(List.of(List.of(1f,0f,0f))));assertThrows(IllegalStateException.class,()->feature.compute("course","topic",List.of()));}
    @Test void semanticSimilarityClampsAndQuantizes(){assertEquals(1,ResourceSemanticFeatures.cosine01(List.of(1f,0f),List.of(1f,0f)));
        assertEquals(0,ResourceSemanticFeatures.cosine01(List.of(1f,0f),List.of(-1f,0f)));assertEquals(.7071,ResourceSemanticFeatures.cosine01(List.of(1f,0f),List.of(1f,1f)));}
    @Test void batchesShareRemainingBudgetInsteadOfResettingEachBatch(){var embedding=mock(EmbeddingService.class);var store=mock(ClassroomStudyStore.class);
        when(store.find(anyString(),anyString())).thenReturn(Optional.empty());when(embedding.describeIdentity()).thenReturn(new EmbeddingService.Identity("one","model-a",2));
        List<java.time.Duration> budgets=new ArrayList<>();List<Integer> batchSizes=new ArrayList<>();
        when(embedding.generateEmbeddingsOnceWithEvidence(anyList(),any())).thenAnswer(call->{List<String> texts=call.getArgument(0);budgets.add(call.getArgument(1));batchSizes.add(texts.size());Thread.sleep(20);return batch(texts.stream().map(t->List.of(1f,0f)).toList());});
        List<BilibiliVideoCatalog.Video> videos=new ArrayList<>();for(int i=0;i<12;i++)videos.add(new BilibiliVideoCatalog.Video("v"+i,"b"+i,1,"url","主题"+i,"介绍","分P","up",20,"now",json.createObjectNode()));
        new ResourceSemanticFeatures(embedding,store,json).compute("course","topic",videos,java.time.Duration.ofMillis(500));
        assertEquals(List.of(10,3),batchSizes);assertEquals(2,budgets.size());assertTrue(budgets.get(1).compareTo(budgets.get(0))<0);assertTrue(budgets.get(0).toMillis()<=500);}
    @Test void unknownVectorResponsePreservesActualHttpEvidence(){var embedding=mock(EmbeddingService.class);var store=mock(ClassroomStudyStore.class);
        when(store.find(anyString(),anyString())).thenReturn(Optional.empty());when(embedding.describeIdentity()).thenReturn(new EmbeddingService.Identity("legacy-adapter","configured-model",2,"endpoint"));
        ObjectNode trace=json.createObjectNode().put("httpStatus",429).put("requestId","actual-failed-id").put("attemptCount",1);trace.putNull("model");trace.putNull("tokenUsage");
        when(embedding.generateEmbeddingsOnceWithEvidence(anyList(),any())).thenReturn(new EmbeddingService.BatchResult(null,trace));
        var failure=assertThrows(ResourceSemanticFeatures.Failure.class,()->new ResourceSemanticFeatures(embedding,store,json).compute("course","topic",List.of()));
        assertEquals(429,failure.evidence().path("batches").get(0).path("httpStatus").asInt());assertEquals("actual-failed-id",failure.evidence().path("batches").get(0).path("requestId").asText());
        assertTrue(failure.evidence().path("batches").get(0).path("model").isNull());verify(store,never()).save(anyString(),anyString(),anyString(),any());}
    @Test void changedActualModelAgainstCacheAbortsAndClearsOnlyThisIdentity(){var embedding=mock(EmbeddingService.class);var store=mock(ClassroomStudyStore.class);
        Map<String,ObjectNode> values=new HashMap<>();when(store.find(anyString(),anyString())).thenAnswer(c->Optional.ofNullable(values.get(c.getArgument(1))));
        doAnswer(c->{values.put(c.getArgument(1),((ObjectNode)c.getArgument(3)).deepCopy());return null;}).when(store).save(anyString(),anyString(),anyString(),any());
        when(store.deleteRecommendationVectors(any())).thenAnswer(c->{ObjectNode identity=c.getArgument(0);int before=values.size();values.entrySet().removeIf(e->e.getValue().path("identity").equals(identity));return before-values.size();});
        when(embedding.describeIdentity()).thenReturn(new EmbeddingService.Identity("adapter","stable-alias",2,"endpoint"));
        when(embedding.generateEmbeddingsOnceWithEvidence(anyList(),any())).thenReturn(new EmbeddingService.BatchResult(List.of(List.of(1f,0f)),json.createObjectNode().put("model","actual-a").put("attemptCount",1)));
        var features=new ResourceSemanticFeatures(embedding,store,json);features.compute("course","topic",List.of());
        ObjectNode current=values.values().iterator().next().deepCopy();values.put("another-course-same-identity",current);
        ObjectNode unrelated=current.deepCopy();((ObjectNode)unrelated.path("identity")).put("endpointFingerprint","another-endpoint");values.put("unrelated",unrelated);
        when(embedding.generateEmbeddingsOnceWithEvidence(anyList(),any())).thenReturn(new EmbeddingService.BatchResult(List.of(List.of(1f,0f)),json.createObjectNode().put("model","actual-b").put("attemptCount",1)));
        var video=new BilibiliVideoCatalog.Video("v","b",1,"url","另一个文本","介绍","分P","up",20,"now",json.createObjectNode());
        var failure=assertThrows(ResourceSemanticFeatures.Failure.class,()->features.compute("course","topic",List.of(video)));
        assertEquals("MODEL_SPACE_CHANGED",failure.getMessage());assertEquals("changed",failure.evidence().path("versionVerification").asText());
        assertEquals(json.valueToTree(List.of("actual-a","actual-b")),failure.evidence().path("actualModels"));
        assertTrue(failure.evidence().path("cacheInvalidation").path("cleared").asBoolean());assertEquals(2,failure.evidence().path("cacheInvalidation").path("removedRows").asInt());
        assertEquals(Set.of("unrelated"),values.keySet());verify(embedding,times(2)).generateEmbeddingsOnceWithEvidence(anyList(),any());}
    @Test void unknownAndKnownActualModelArePartialNotAnInventedVersionConflict(){var embedding=mock(EmbeddingService.class);var store=mock(ClassroomStudyStore.class);
        Map<String,ObjectNode> values=new HashMap<>();when(store.find(anyString(),anyString())).thenAnswer(c->Optional.ofNullable(values.get(c.getArgument(1))));
        doAnswer(c->{values.put(c.getArgument(1),((ObjectNode)c.getArgument(3)).deepCopy());return null;}).when(store).save(anyString(),anyString(),anyString(),any());
        when(embedding.describeIdentity()).thenReturn(new EmbeddingService.Identity("adapter","requested-alias",2,"endpoint"));
        ObjectNode unknown=json.createObjectNode().put("requestedModel","requested-alias");unknown.putNull("model");
        when(embedding.generateEmbeddingsOnceWithEvidence(anyList(),any())).thenReturn(new EmbeddingService.BatchResult(List.of(List.of(1f,0f)),unknown));
        var features=new ResourceSemanticFeatures(embedding,store,json);assertEquals("unknown",features.compute("course","topic",List.of()).evidence().path("versionVerification").asText());
        when(embedding.generateEmbeddingsOnceWithEvidence(anyList(),any())).thenReturn(new EmbeddingService.BatchResult(List.of(List.of(1f,0f)),json.createObjectNode().put("model","actual-a")));
        var video=new BilibiliVideoCatalog.Video("v","b",1,"url","另一个文本","介绍","分P","up",20,"now",json.createObjectNode());
        var mixed=features.compute("course","topic",List.of(video));assertEquals("partial",mixed.evidence().path("versionVerification").asText());
        assertEquals(json.valueToTree(List.of("actual-a")),mixed.evidence().path("actualModels"));assertTrue(mixed.evidence().path("cachedOrigins").get(0).path("model").isNull());
        verify(store,never()).deleteRecommendationVectors(any());}
}
