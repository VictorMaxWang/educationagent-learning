package com.demo.edu.classroom.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ClassroomStudyStoreMaintenanceTest {
    final ObjectMapper json=new ObjectMapper();JdbcTemplate jdbc;ClassroomStudyStore store;
    @BeforeEach void setup(){var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:study_cache_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE edu_classroom_study_state(kind VARCHAR(40),entry_id VARCHAR(160),course_id VARCHAR(64),scene_id VARCHAR(80) DEFAULT '',document CLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(kind,entry_id))");store=new ClassroomStudyStore(jdbc,json);}
    @Test void batchLookupIsBoundedKindSpecificAndHasIndependentDocuments(){
        store.save("course_publication","a","a",json.createObjectNode().put("visible",false));store.save("other_kind","b","b",json.createObjectNode().put("secret","not-publication"));
        var found=store.findMany("course_publication",List.of("a","b","a"));assertEquals(1,found.size());assertFalse(found.get("a").path("visible").asBoolean());
        found.get("a").put("visible",true);assertFalse(store.find("course_publication","a").orElseThrow().path("visible").asBoolean());
        assertTrue(store.findMany("course_publication",List.of()).isEmpty());assertThrows(IllegalArgumentException.class,()->store.findMany("course_publication",java.util.Collections.nCopies(201,"a")));
    }
    @Test void expiredCacheRemovalIsBoundedAndCannotDeleteLearningOrAudit(){
        for(int i=0;i<5;i++)store.save("recommendation_cache","old-"+i,"course",json.createObjectNode());
        store.save("learning_attempt","learning","course",json.createObjectNode());store.save("recommendation_decision","audit","course",json.createObjectNode());
        jdbc.update("UPDATE edu_classroom_study_state SET updated_at=?",Timestamp.from(Instant.now().minus(Duration.ofDays(3))));
        store.save("recommendation_cache","fresh","course",json.createObjectNode());
        assertEquals(2,store.purgeExpiredCache("recommendation_cache",Instant.now().minus(Duration.ofDays(1)),2));
        assertTrue(store.find("recommendation_cache","fresh").isPresent());assertTrue(store.find("learning_attempt","learning").isPresent());assertTrue(store.find("recommendation_decision","audit").isPresent());
        assertThrows(IllegalArgumentException.class,()->store.purgeExpiredCache("learning_attempt",Instant.now(),200));
    }
    @Test void oldIdentityVectorsAreRemovedAcrossKeysetBatchesOnly(){
        var oldIdentity=json.createObjectNode().put("model","old");var keepIdentity=json.createObjectNode().put("model","keep");
        for(int i=0;i<415;i++){var value=json.createObjectNode();value.set("identity",i<410?oldIdentity:keepIdentity);value.putArray("values").add(.1).add(.2);store.save("recommendation_vector",String.format("vector-%04d",i),"course",value);}
        JdbcTemplate inspected=org.mockito.Mockito.spy(jdbc);store=new ClassroomStudyStore(inspected,json);
        assertEquals(410,store.deleteRecommendationVectors(oldIdentity));assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_classroom_study_state WHERE kind='recommendation_vector'",Integer.class));
        var selects=org.mockito.Mockito.mockingDetails(inspected).getInvocations().stream().filter(call->call.getArguments().length>0&&call.getArgument(0) instanceof String sql&&sql.startsWith("SELECT entry_id,document")).map(call->(String)call.getArgument(0)).toList();
        assertFalse(selects.isEmpty());assertTrue(selects.stream().allMatch(sql->sql.contains("entry_id>?")&&sql.contains("LIMIT 200")),"旧实现的无界整表SELECT必须被回归断言捕获");
        assertTrue(org.mockito.Mockito.mockingDetails(inspected).getInvocations().stream().anyMatch(call->call.getMethod().getName().equals("batchUpdate")),"每批删除不能退回逐行数据库往返");
    }
}
