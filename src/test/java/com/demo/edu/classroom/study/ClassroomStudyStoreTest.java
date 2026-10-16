package com.demo.edu.classroom.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ClassroomStudyStoreTest {
    private final ObjectMapper json=new ObjectMapper();
    private ClassroomStudyStore store;
    @BeforeEach void database() {
        JdbcDataSource data=new JdbcDataSource();data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE edu_classroom_study_state(kind VARCHAR(40),entry_id VARCHAR(160),course_id VARCHAR(64),scene_id VARCHAR(80) DEFAULT '',document CLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(kind,entry_id))");
        store=new ClassroomStudyStore(jdbc,json);
    }
    @Test void duplicateFirstSubmissionCannotRewriteOriginalText() {
        assertTrue(store.create("learning_attempt","a1","c1",json.createObjectNode().put("understanding","第一次表述")));
        assertFalse(store.create("learning_attempt","a1","c1",json.createObjectNode().put("understanding","覆盖原文")));
        assertEquals("第一次表述",store.find("learning_attempt","a1").orElseThrow().path("understanding").asText());
    }
    @Test void differentCourseAndKindNeverLeakIntoHistory() {
        store.save("learning_attempt","a1","c1",json.createObjectNode().put("id","a1"));
        store.save("learning_attempt","a2","c2",json.createObjectNode().put("id","a2"));
        store.save("recommendation","a3","c1",json.createObjectNode().put("id","a3"));
        assertEquals(1,store.list("learning_attempt","c1",10).size());
        assertEquals("a1",store.list("learning_attempt","c1",10).getFirst().path("id").asText());
    }
    @Test void explicitStateUpdateDoesNotCreateExtraHistoryRows() {
        store.save("recommendation","r1","c1",json.createObjectNode().put("status","running"));
        store.save("recommendation","r1","c1",json.createObjectNode().put("status","ready"));
        assertEquals(1,store.list("recommendation","c1",10).size());
        assertEquals("ready",store.find("recommendation","r1").orElseThrow().path("status").asText());
    }
    @Test void sceneFilteringPrecedesHistoryLimit() {
        store.save("learning_attempt","first","course",json.createObjectNode().put("sceneId","scene-a").put("id","first"));
        for(int i=0;i<205;i++)store.save("learning_attempt","later-"+i,"course",json.createObjectNode().put("sceneId","scene-b"));
        assertEquals("first",store.listForScene("learning_attempt","course","scene-a",10).getFirst().path("id").asText());
    }
    @Test void onlyRebuildableVectorsWithExactModelSpaceIdentityCanBeRemoved() {
        var identity=json.createObjectNode().put("provider","compat").put("model","m").put("endpointFingerprint","one");
        var other=identity.deepCopy().put("endpointFingerprint","two");
        var vector=json.createObjectNode();vector.set("identity",identity);
        var foreign=json.createObjectNode();foreign.set("identity",other);
        store.save("recommendation_vector","v1","c1",vector);store.save("recommendation_vector","v2","c2",vector);
        store.save("recommendation_vector","v3","c1",foreign);store.save("learning_attempt","a1","c1",vector);
        assertEquals(2,store.deleteRecommendationVectors(identity));assertTrue(store.find("recommendation_vector","v1").isEmpty());
        assertTrue(store.find("recommendation_vector","v3").isPresent());assertTrue(store.find("learning_attempt","a1").isPresent());
        assertThrows(IllegalArgumentException.class,()->store.deleteRecommendationVectors(json.createObjectNode()));
    }
}
