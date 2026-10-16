package com.demo.edu.classroom.study.feedback;

import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.demo.edu.classroom.recommendation.VideoTeachingClassifier;
import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningSourceCoverageTest {
    ObjectMapper json=new ObjectMapper();
    ClassroomStudyContext.Snapshot partial() {
        return new ClassroomStudyContext.Snapshot("c","s","fp","面积","面积",5,"数学","academic",1,
            List.of(new ClassroomStudyContext.Anchor("s1","资料","长乘宽。","reference")),"长乘宽。",List.of(),false,List.of("资料尾部超出范围"));
    }
    @Test void partialSourceCannotBeTurnedIntoConsistencyByModelAssertion() {
        var value=json.createObjectNode().put("status","consistent").put("reason","全部一致");value.putArray("evidence");
        assertEquals("insufficient",LearningFeedbackPolicy.checkedSourceAssessment(value,partial(),json).path("status").asText());
    }
    @Test void knownIncompleteScopeAbstainsWithoutAnExtraModelRequest() {
        var ai=mock(ClassroomAiGateway.class);
        var service=new LearningFeedbackService(null,null,ai,null,json,mock(PlatformTransactionManager.class));
        var result=service.evaluateUnderstanding(partial(),"长乘宽。","");
        assertEquals("uncertain",result.value().path("analysis").get(0).path("status").asText());
        assertEquals("rule",result.evidence().path("mode").asText());assertEquals(0,result.evidence().path("attemptCount").asInt());
        var classifier=new VideoTeachingClassifier(ai,json);assertEquals("uncertain",classifier.classify(partial()).status());
        verifyNoInteractions(ai);
    }
}
