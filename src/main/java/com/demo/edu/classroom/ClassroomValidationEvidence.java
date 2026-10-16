package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 将应用层检查记录绑定到课件输入。
 * 借鉴 M001 固定输入后独立复核的边界：这里记录结构检查，既不是科学证书，也不是浏览器执行成功。
 */
final class ClassroomValidationEvidence {
    private static final String SCHEMA = "classroom-evidence-v1";
    private static final Set<String> ORIGINS = Set.of("generate", "import", "edit", "media-repair");
    private static final List<String> DOCUMENT_FIELDS = List.of("title", "type", "content", "actions", "whiteboards");

    private ClassroomValidationEvidence() {}

    /**
     * 在调用方完成结构校验和媒体引用整理后，覆盖当前检查记录。
     * @param scene 已通过调用方校验的课件场景；仅替换其 validation 字段
     * @param revision 本次检查对应的课程修订号，必须非负
     * @param origin generate、import、edit 或 media-repair
     * @throws IllegalArgumentException 输入为空、修订号为负或来源未知
     */
    static void record(ObjectNode scene, long revision, String origin) {
        if (scene == null || revision < 0 || origin == null || !ORIGINS.contains(origin)) {
            throw new IllegalArgumentException("校验记录需要课件、非负修订号与已知来源");
        }
        ObjectNode evidence = JsonNodeFactory.instance.objectNode();
        evidence.put("schema", SCHEMA);
        evidence.put("revision", revision);
        evidence.put("origin", origin);
        evidence.put("documentSha256", fingerprint(scene));
        ObjectNode checks = evidence.putObject("checks");
        checks.put("schema", "PASS");
        JsonNode warnings = scene.path("mediaWarnings");
        boolean hasWarnings = !warnings.isMissingNode() && !warnings.isNull()
                && (warnings.isContainerNode() ? !warnings.isEmpty() : !warnings.asText().isBlank());
        checks.put("mediaReferences", hasWarnings ? "WARN" : "PASS");
        // 导入的 PASS 和生成器自报的运行结果不可信；本记录器没有启动浏览器。
        checks.put("browserRuntime", "NOT_RUN");
        checks.put("quizReview", reviewOutcome(scene));
        evidence.put("checkedAt", Instant.now().toString());
        scene.set("validation", evidence);
    }

    /**
     * 习题审核结论，按最坏情况取值：非练习页 NOT_RUN；有题目被降级为思考题或规则丢题为 FAILED；
     * 复核没跑出结论为 WARN；其余为 PASS。
     *
     * <p>与 browserRuntime 同样只声明"检查到了哪一步"，<b>不宣称答案正确</b>：
     * PASS 仅表示第二次独立求解也同意；UNVERIFIED 是与 FAILED 同级的独立结论，绝不能当成通过。
     * 复核未启用（题目没有 review 字段）时记 NOT_RUN，而不是伪装成已通过。</p>
     */
    private static String reviewOutcome(ObjectNode scene) {
        if (!"quiz".equals(scene.path("type").asText())) return "NOT_RUN";
        JsonNode content = scene.path("content");
        if (!content.isObject()) return "NOT_RUN";
        JsonNode thinking = content.path("thinking");
        boolean sawVerdict = false;
        boolean unverified = false;
        for (JsonNode question : content.path("questions")) {
            String verdict = question.path("review").path("verdict").asText("");
            if (verdict.isBlank()) continue;
            sawVerdict = true;
            if ("RISKY_THINK".equals(verdict)) return "FAILED";
            if ("UNVERIFIED".equals(verdict)) unverified = true;
        }
        // 复核未启用时题目不带 review 字段，也不应有降级题：如实记 NOT_RUN。
        if (!sawVerdict && thinking.isEmpty()) return "NOT_RUN";
        if (content.path("review").path("ruleDropped").asInt(0) > 0) return "FAILED";
        if (thinking.isArray() && !thinking.isEmpty()) return "FAILED";
        return unverified ? "WARN" : "PASS";
    }

    /** 仅判断记录是否仍绑定同一份文档；不验证记录签名，也不重新执行结构/媒体/浏览器检查。 */
    static boolean matches(ObjectNode scene) {
        if (scene == null) return false;
        JsonNode evidence = scene.path("validation");
        return evidence.isObject() && SCHEMA.equals(evidence.path("schema").asText())
                && evidence.path("documentSha256").isTextual()
                && fingerprint(scene).equals(evidence.path("documentSha256").asText());
    }

    private static String fingerprint(ObjectNode scene) {
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        // 检查时间、来源、运行回执等元数据不会改变课件；数组次序和实际文档内容仍参与指纹。
        for (String field : DOCUMENT_FIELDS) {
            if (scene.has(field)) document.set(field, scene.get(field));
        }
        return ClassroomRuntimeSupport.fingerprint(document);
    }
}
