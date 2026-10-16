package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClassroomDocumentValidatorTest {
    @Test void fiveTypesAcceptScalarAnswersAndRequireRealReferenceEvidence() throws Exception {
        var json=new ObjectMapper();
        var questions=json.readTree("""
          [{"id":"j","type":"judge","question":"判断","answer":"错"},
           {"id":"f","type":"fill","question":"填空","answer":0},
           {"id":"s","type":"short_answer","question":"说明理由","gradingPoints":["基准一致"]}]
          """);
        assertDoesNotThrow(()->ClassroomDocumentValidator.questions(questions));
        ((com.fasterxml.jackson.databind.node.ObjectNode)questions.get(0)).put("answer","unknown");
        assertThrows(ClassroomException.class,()->ClassroomDocumentValidator.questions(questions));
        ((com.fasterxml.jackson.databind.node.ObjectNode)questions.get(0)).put("answer","对");
        ((com.fasterxml.jackson.databind.node.ObjectNode)questions.get(1)).set("answer",json.createArrayNode().add("A"));
        assertThrows(ClassroomException.class,()->ClassroomDocumentValidator.questions(questions));
    }
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
