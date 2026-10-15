package com.demo.edu.classroom;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ClassroomEvidenceOutcomeTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void missingAcknowledgementRemainsUnconfirmedAndCanLaterBeConfirmed() throws Exception {
        var course=json.createObjectNode();course.putObject("runtime");course.putArray("messages");
        var scene=json.createObjectNode().put("id","s");scene.putArray("actions").addObject().put("id","a").put("type","widget_reveal");
        var result=json.readTree("[{\"actionId\":\"a\",\"success\":false,\"status\":\"unconfirmed\",\"reason\":\"ACK_TIMEOUT\"}]");
        var next=ClassroomRuntimeSupport.merge(course,scene,null,null,null,result);
        assertEquals("unconfirmed",next.path("actionResults").get(0).path("status").asText());assertTrue(next.path("acknowledgedActionIds").isMissingNode());
        ((com.fasterxml.jackson.databind.node.ObjectNode)course.path("runtime")).set("s",next);
        var confirmed=json.readTree("[{\"actionId\":\"a\",\"success\":true,\"status\":\"confirmed\"}]");
        next=ClassroomRuntimeSupport.merge(course,scene,null,null,json.readTree("[\"a\"]"),confirmed);
        assertEquals("confirmed",next.path("actionResults").get(0).path("status").asText());assertEquals("a",next.path("acknowledgedActionIds").get(0).asText());
    }
    @Test void inconsistentEvidenceCannotBeMarkedAsSuccessful() throws Exception {
        var course=json.createObjectNode();course.putObject("runtime");course.putArray("messages");
        var scene=json.createObjectNode().put("id","s");scene.putArray("actions").addObject().put("id","a").put("type","widget_reveal");
        var invalid=json.readTree("[{\"actionId\":\"a\",\"success\":true,\"status\":\"unconfirmed\"}]");
        assertThrows(ClassroomException.class,()->ClassroomRuntimeSupport.merge(course,scene,null,null,null,invalid));
    }
}
