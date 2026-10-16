package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ClassroomWechatControllerTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();

    @Test void configuredServerCanPairWithoutTrustingClientOrigin() {
        var bridge = new ClassroomWechatBridge(mock(ClassroomCourseService.class), json, dir, Clock.systemUTC(),
                "https://smartchildcareagent.cn", host -> false);
        var controller = new ClassroomWechatController(bridge);
        var response = controller.pairing(null);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("https://smartchildcareagent.cn", ((Map<?, ?>) response.getBody()).get("baseUrl"));
        assertFalse(controller.status().containsKey("sender"));
    }

    @Test void localLoopbackAndForeignPublicOriginReturnReadableClientErrors() {
        var bridge = new ClassroomWechatBridge(mock(ClassroomCourseService.class), json, dir, Clock.systemUTC(), "", host -> false);
        var controller = new ClassroomWechatController(bridge);
        var loopback = controller.pairing(Map.of("baseUrl", "http://localhost:1420"));
        assertEquals(400, loopback.getStatusCode().value());
        assertTrue(((Map<?, ?>) loopback.getBody()).get("error").toString().contains("手机无法打开"));
        var arbitraryPublic = controller.pairing(Map.of("baseUrl", "https://smartchildcareagent.cn"));
        assertEquals(400, arbitraryPublic.getStatusCode().value());
        assertTrue(((Map<?, ?>) arbitraryPublic.getBody()).get("error").toString().contains("不能把本机课程"));
        assertEquals(400, controller.pairing(null).getStatusCode().value());
    }
}
