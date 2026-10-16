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
    @Test void thinkingItemsCarryOnlyStemsAndAQuizMayBeAllThinkingItems() throws Exception {
        var json=new ObjectMapper();
        var empty=json.createArrayNode();
        var thinking=json.createArrayNode();
        thinking.addObject().put("id","t1").put("question","想一想：为什么先统一整体？");
        // 全页都是思考题是合法的：宁可只留思考题，也不把已知错误的标答当计分题下发。
        assertDoesNotThrow(()->ClassroomDocumentValidator.questions(empty,thinking));
        assertThrows(ClassroomException.class,()->ClassroomDocumentValidator.questions(empty,null));
        assertThrows(ClassroomException.class,()->ClassroomDocumentValidator.questions(empty,json.createArrayNode()));
        assertDoesNotThrow(()->ClassroomDocumentValidator.thinking(json.createArrayNode()));
        assertDoesNotThrow(()->ClassroomDocumentValidator.thinking(null));
        // 思考题不得携带选项或标答，否则等于把没核验的答案继续下发。
        var items=json.createArrayNode();
        var item=items.addObject().put("id","t1").put("question","想一想");
        item.putArray("answer").add("A");
        assertThrows(ClassroomException.class,()->ClassroomDocumentValidator.thinking(items));
        item.remove("answer");item.putArray("options");
        assertThrows(ClassroomException.class,()->ClassroomDocumentValidator.thinking(items));
        item.remove("options");
        assertDoesNotThrow(()->ClassroomDocumentValidator.thinking(items));
    }

    @Test void quizSceneAcceptsPerQuestionReviewAndUnknownFieldsDoNotFailValidation() throws Exception {
        var json=new ObjectMapper();
        var outline=json.createObjectNode().put("id","scene-1").put("type","quiz").put("order",0);
        var scene=json.createObjectNode().put("title","练一练").put("type","quiz");
        var questions=scene.putObject("content").put("type","quiz").putArray("questions");
        var question=questions.addObject().put("id","q1").put("type","single").put("question","单选")
                .put("analysis","解析").put("hasAnswer",true);
        var options=question.putArray("options");
        options.addObject().put("label","甲").put("value","A");
        options.addObject().put("label","乙").put("value","B");
        question.putArray("answer").add("A");
        question.putObject("review").put("verdict","PASS").put("method","model").put("reason","独立求解与标答一致");
        scene.putArray("actions");
        assertDoesNotThrow(()->ClassroomDocumentValidator.scene(scene,outline,"course-1"));
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
