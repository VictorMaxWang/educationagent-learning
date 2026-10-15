package com.demo.edu.knowledge;

import com.demo.edu.mapper.EduDomainAccessMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClassroomKnowledgeAccessTest {
    @Test
    void localClassroomExceptionIsOptInAndDoesNotGrantSchoolOrTeacherAccess() {
        var settings = new EduKnowledgeAccessProperties();
        settings.setDomainLevels(Map.of("sexedu", 2, "school", 2, "psychology", 3));
        var users = mock(EduUserClassMapper.class);
        var domains = mock(EduDomainAccessMapper.class);
        var service = new EduKnowledgeAccessService(settings, users, domains);
        assertFalse(service.check("sexedu", "classroom_puberty").allowed());
        settings.setSingleUserClassroomEnabled(true);
        assertTrue(service.check("sexedu", "classroom_puberty").allowed());
        assertFalse(service.check("school", "classroom").allowed());
        assertFalse(service.check("psychology", "classroom_general").allowed());
        assertFalse(service.check("sexedu", "classroom_someone_else").allowed());
        assertFalse(service.check("sexedu", "ordinary-anonymous").allowed());
    }
}
