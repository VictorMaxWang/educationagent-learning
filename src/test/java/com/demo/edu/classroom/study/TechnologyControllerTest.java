package com.demo.edu.classroom.study;

import com.demo.chroma.base.EmbeddingService;
import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.demo.edu.classroom.recommendation.RecommendationOptimizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TechnologyControllerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void exposesDecisionsAndConfigurationWithoutLiveHealthClaim() {
        ClassroomAiGateway ai = mock(ClassroomAiGateway.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(ai.capabilityStatus()).thenReturn(json.createObjectNode().put("configured", true).put("requestedModel", "qwen-test")
                .put("liveVerified", true).put("endpoint", "https://private.invalid").put("apiKey", "private-key"));
        when(embedding.describeIdentity()).thenReturn(new EmbeddingService.Identity("dashscope", "text-embedding-v4", 1024));
        TechnologyController controller = new TechnologyController(ai, embedding, json);
        ObjectNode result = controller.overview();
        assertEquals(6, result.path("decisions").size());
        assertEquals(8, result.path("sources").size());
        assertTrue(result.path("runtime").path("textModel").path("configured").asBoolean());
        assertFalse(result.path("runtime").path("textModel").path("liveVerified").asBoolean());
        assertFalse(result.path("runtime").path("embedding").path("liveVerified").asBoolean());
        assertEquals(RecommendationOptimizer.ALGORITHM_VERSION, result.path("runtime").path("algorithmVersion").asText());
        assertFalse(result.toString().contains("private-key")); assertFalse(result.toString().contains("private.invalid"));
        verify(ai).capabilityStatus(); verifyNoMoreInteractions(ai);
        verify(embedding).describeIdentity(); verifyNoMoreInteractions(embedding);
        result.remove("decisions");
        assertEquals(6, controller.overview().path("decisions").size());
    }

    @Test void hidesEndpointLikeModelLabelsAndKeepsUnconfiguredExplicit() {
        ClassroomAiGateway ai = mock(ClassroomAiGateway.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(ai.capabilityStatus()).thenReturn(json.createObjectNode().put("configured", false).put("requestedModel", "https://private.invalid/model"));
        when(embedding.describeIdentity()).thenReturn(new EmbeddingService.Identity("https://private.invalid", "sk-secret", 1024));
        ObjectNode result = new TechnologyController(ai, embedding, json).overview();
        assertEquals("unavailable", result.path("runtime").path("textModel").path("status").asText());
        assertEquals("未公开", result.path("runtime").path("textModel").path("requestedModel").asText());
        assertEquals("unknown", result.path("runtime").path("embedding").path("provider").asText());
        assertEquals("未公开", result.path("runtime").path("embedding").path("model").asText());
        assertFalse(result.toString().contains("private.invalid"));
    }
}
