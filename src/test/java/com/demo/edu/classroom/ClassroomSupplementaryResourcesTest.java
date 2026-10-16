package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ClassroomSupplementaryResourcesTest {
    ObjectMapper json=new ObjectMapper();
    ObjectNode item() {
        var row=json.createObjectNode().put("sceneId","old-scene").put("id","client-claimed-id")
            .put("url","https://www.bilibili.com/video/BV1r3411q7mV/?p=1").put("title","沁园春诵读")
            .put("author","课程作者").put("durationSeconds",103).put("purpose","recitation");
        row.putArray("goals").add("诵读节奏");return row;
    }
    @Test void historicalReferenceRemapsSceneAndCannotImportReadinessOrAudit() {
        var raw=item().put("status","ready").put("verified",true);raw.putObject("audit").put("requestId","internal");
        var result=ClassroomSupplementaryResources.clean(json.createArrayNode().add(raw),Map.of("old-scene","new-scene"),json).get(0);
        assertEquals("new-scene",result.path("sceneId").asText());assertEquals("BV1r3411q7mV:1",result.path("id").asText());
        assertEquals("historical",result.path("resourceStatus").asText());assertFalse(result.has("audit"));assertFalse(result.has("verified"));assertFalse(result.has("status"));
    }
    @Test void invalidDestinationsCannotBecomeOfflineLinks() {
        for(String url:new String[]{"javascript:alert(1)","https://bilibili.com.evil.test/video/BV1r3411q7mV", "https://evil@bilibili.com/video/BV1r3411q7mV", "https://search.bilibili.com/all?keyword=x", "https://www.bilibili.com:444/video/BV1r3411q7mV", "https://www.bilibili.com/video/BV1r3411q7mV/?p=0"})
            assertThrows(ClassroomException.class,()->ClassroomSupplementaryResources.clean(json.createArrayNode().add(item().put("url",url)),Map.of("old-scene","new-scene"),json));
    }
    @Test void knownDurationAndSceneAreRequiredAndDuplicatePartsCollapse() {
        assertThrows(ClassroomException.class,()->ClassroomSupplementaryResources.clean(json.createArrayNode().add(item().put("durationSeconds",0)),Map.of("old-scene","new-scene"),json));
        assertThrows(ClassroomException.class,()->ClassroomSupplementaryResources.clean(json.createArrayNode().add(item()),Map.of("other","new-scene"),json));
        ArrayNode values=json.createArrayNode().add(item()).add(item().put("url","https://bilibili.com/video/BV1r3411q7mV"));
        assertEquals(1,ClassroomSupplementaryResources.clean(values,Map.of("old-scene","new-scene"),json).size());
    }
    @Test void realPortableImportPreservesLinksButRebuildsSceneIdentity() {
        ClassroomCourseServiceTest fixture=new ClassroomCourseServiceTest();fixture.setup();String id=fixture.readyCourse();
        var envelope=fixture.service.portable(id);ObjectNode course=(ObjectNode)envelope.path("course");
        course.putArray("supplementaryResources").add(item().put("sceneId","step-1"));
        var imported=fixture.service.importPortable(envelope);var link=imported.path("supplementaryResources").get(0);
        assertNotEquals("step-1",link.path("sceneId").asText());assertEquals(imported.path("scenes").get(0).path("id").asText(),link.path("sceneId").asText());
        assertEquals("historical",link.path("resourceStatus").asText());
        assertEquals(1,fixture.service.portable(imported.path("id").asText()).path("course").path("supplementaryResources").size());
    }
}
