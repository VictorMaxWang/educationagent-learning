package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClassroomDocumentValidatorTest {
    @Test
    void mathematicalInteractiveMayContainLocalFontsButRemainsBounded() {
        var json = new ObjectMapper();
        var outline = json.createObjectNode().put("id", "scene-1").put("type", "interactive").put("order", 0);
        var scene = json.createObjectNode().put("title", "分数互动").put("type", "interactive");
        var content = scene.putObject("content").put("type", "interactive");
        content.put("html", "<html>" + "x".repeat(600000) + "</html>");
        scene.putArray("actions");
        assertDoesNotThrow(() -> ClassroomDocumentValidator.scene(scene, outline, "course-1"));
        content.put("html", "x".repeat(1200001));
        assertThrows(ClassroomException.class, () -> ClassroomDocumentValidator.scene(scene, outline, "course-1"));
    }
}
