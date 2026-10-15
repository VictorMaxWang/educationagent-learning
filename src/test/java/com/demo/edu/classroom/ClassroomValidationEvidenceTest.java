package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 固定输入、导入声明和内容变化的契约检查；不调用模型或浏览器。 */
class ClassroomValidationEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void importingDoesNotInheritClaimedBrowserSuccess() throws Exception {
        ObjectNode scene = scene();
        ObjectNode imported = scene.putObject("validation");
        imported.put("scientificCertificate", true).putObject("checks").put("browserRuntime", "PASS");
        assertFalse(ClassroomValidationEvidence.matches(scene));

        ClassroomValidationEvidence.record(scene, 7, "import");

        var evidence = scene.path("validation");
        assertEquals("classroom-evidence-v1", evidence.path("schema").asText());
        assertEquals(7, evidence.path("revision").asLong());
        assertEquals("import", evidence.path("origin").asText());
        assertEquals("PASS", evidence.path("checks").path("schema").asText());
        assertEquals("NOT_RUN", evidence.path("checks").path("browserRuntime").asText());
        assertFalse(evidence.has("scientificCertificate"));
        assertDoesNotThrow(() -> Instant.parse(evidence.path("checkedAt").asText()));
        assertTrue(ClassroomValidationEvidence.matches(scene));
    }

    @Test void objectKeyOrderAndUnrelatedRuntimeMetadataDoNotInvalidateDocument() throws Exception {
        ObjectNode original = scene();
        ClassroomValidationEvidence.record(original, 1, "generate");
        ObjectNode reordered = (ObjectNode) json.readTree("""
                {"whiteboards":[],"actions":[{"target":"#x","type":"widget_highlight","id":"a"}],
                 "content":{"html":"<p id='x'>一</p>","type":"interactive"},
                 "type":"interactive","title":"实验","id":"another-id"}
                """);
        reordered.set("validation", original.path("validation").deepCopy());
        reordered.putObject("runtime").put("acknowledged", true);
        reordered.put("updatedAt", "later").put("revision", 99);
        assertTrue(ClassroomValidationEvidence.matches(reordered));
    }

    @Test void contentActionsTitlesTypesAndWhiteboardsInvalidatePriorEvidence() throws Exception {
        ObjectNode original = scene();
        ClassroomValidationEvidence.record(original, 1, "generate");
        ObjectNode changed = original.deepCopy();
        ((ObjectNode) changed.path("content")).put("html", "<p id='x'>二</p>");
        assertFalse(ClassroomValidationEvidence.matches(changed));
        changed = original.deepCopy();
        ((ObjectNode) changed.path("actions").get(0)).put("target", "#y");
        assertFalse(ClassroomValidationEvidence.matches(changed));
        changed = original.deepCopy();
        changed.put("title", "另一实验");
        assertFalse(ClassroomValidationEvidence.matches(changed));
        changed = original.deepCopy();
        changed.put("type", "slide");
        assertFalse(ClassroomValidationEvidence.matches(changed));
        changed = original.deepCopy();
        ((ArrayNode) changed.path("whiteboards")).addObject().put("id", "board-1");
        assertFalse(ClassroomValidationEvidence.matches(changed));
    }

    @Test void sequenceOrderMattersAndRecheckingBindsToNewContent() throws Exception {
        ObjectNode scene = scene();
        ArrayNode actions = (ArrayNode) scene.path("actions");
        actions.addObject().put("id", "b").put("type", "widget_reveal").put("target", "#x");
        ClassroomValidationEvidence.record(scene, 1, "generate");
        String previous = scene.path("validation").path("documentSha256").asText();
        var first = actions.remove(0);
        actions.add(first);
        assertFalse(ClassroomValidationEvidence.matches(scene));

        ClassroomValidationEvidence.record(scene, 2, "edit");
        assertNotEquals(previous, scene.path("validation").path("documentSha256").asText());
        assertTrue(ClassroomValidationEvidence.matches(scene));
        assertEquals("NOT_RUN", scene.path("validation").path("checks").path("browserRuntime").asText());
    }

    @Test void mediaWarningsArePreservedAsWarningsAndUnknownEvidenceFailsClosed() throws Exception {
        ObjectNode scene = scene();
        scene.putArray("mediaWarnings");
        ClassroomValidationEvidence.record(scene, 1, "generate");
        assertEquals("PASS", scene.path("validation").path("checks").path("mediaReferences").asText());
        ((ArrayNode) scene.path("mediaWarnings")).add("配图暂不可用");
        ClassroomValidationEvidence.record(scene, 2, "media-repair");
        assertEquals("WARN", scene.path("validation").path("checks").path("mediaReferences").asText());
        ((ObjectNode) scene.path("validation")).put("schema", "unknown-version");
        assertFalse(ClassroomValidationEvidence.matches(scene));
        assertFalse(ClassroomValidationEvidence.matches(null));
        assertThrows(IllegalArgumentException.class, () -> ClassroomValidationEvidence.record(scene, -1, "edit"));
        assertThrows(IllegalArgumentException.class, () -> ClassroomValidationEvidence.record(scene, 2, "claimed-browser-pass"));
    }

    private ObjectNode scene() throws Exception {
        return (ObjectNode) json.readTree("""
                {"id":"s","title":"实验","type":"interactive",
                 "content":{"type":"interactive","html":"<p id='x'>一</p>"},
                 "actions":[{"id":"a","type":"widget_highlight","target":"#x"}],"whiteboards":[]}
                """);
    }
}
