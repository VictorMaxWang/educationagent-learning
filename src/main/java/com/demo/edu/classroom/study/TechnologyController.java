package com.demo.edu.classroom.study;

import com.demo.chroma.base.EmbeddingService;
import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.demo.edu.classroom.recommendation.RecommendationOptimizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** 公开的技术选型摘要；只读取配置身份，不触发付费调用，也不公开请求证据和服务地址。 */
@RestController
@RequestMapping("/api/classroom/technology")
public class TechnologyController {
    private final ClassroomAiGateway ai;
    private final EmbeddingService embeddings;
    private final ObjectNode manifest;

    public TechnologyController(ClassroomAiGateway ai, EmbeddingService embeddings, ObjectMapper json) {
        this.ai = ai;
        this.embeddings = embeddings;
        try (InputStream source = new ClassPathResource("classroom/technology-decisions.json").getInputStream()) {
            manifest = (ObjectNode) json.readTree(source);
        } catch (IOException error) {
            throw new IllegalStateException("技术选型清单无法读取", error);
        }
    }

    /** 配置快照不是在线健康探测；实际调用及结果由各业务任务自己的证据记录负责。 */
    @GetMapping
    public ObjectNode overview() {
        ObjectNode result = manifest.deepCopy();
        ObjectNode runtime = result.putObject("runtime");
        ObjectNode capability = ai.capabilityStatus();
        ObjectNode text = runtime.putObject("textModel");
        boolean configured = capability.path("configured").asBoolean(false);
        text.put("configured", configured).put("status", configured ? "configured" : "unavailable")
                .put("liveVerified", false).put("maxStructuredAttempts", 2);
        text.put("requestedModel", publicLabel(capability.path("requestedModel").asText("")));
        EmbeddingService.Identity identity = embeddings.describeIdentity();
        ObjectNode vector = runtime.putObject("embedding");
        vector.put("provider", identity.provider() != null && Set.of("ollama", "siliconflow", "dashscope").contains(identity.provider()) ? identity.provider() : "unknown")
                .put("model", publicLabel(identity.model())).put("dimension", identity.dimension())
                .put("status", "identity-only").put("liveVerified", false);
        runtime.put("algorithmVersion", RecommendationOptimizer.ALGORITHM_VERSION)
                .put("notice", "配置已读取不代表在线验证成功；模型是否完成某次任务，以该次任务的真实调用与校验记录为准。");
        return result;
    }

    private String publicLabel(String value) {
        // 模型允许组织/名称形式，拒绝URL、凭据样式、查询串及控制字符。
        return value != null && value.length() <= 120 && value.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")
                && !value.startsWith("sk-") && !value.contains("//") ? value : "未公开";
    }
}
