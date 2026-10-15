package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClassroomWechatBridgeTest {
    @TempDir Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unpairedSenderCannotReadCoursesAndOrdinaryChatPassesThrough() {
        var courses = mock(ClassroomCourseService.class);
        var bridge = new ClassroomWechatBridge(courses, mapper, dir, Clock.systemUTC());
        assertTrue(bridge.handle("今天数学课很难", "wx-a").isEmpty());
        assertTrue(bridge.handle("我的课堂", "wx-a").orElseThrow().contains("配对"));
        verifyNoInteractions(courses);
        assertFalse(bridge.status().containsKey("sender"));
    }

    @Test
    void pairingIsSingleUseAndBindingSurvivesRestart() {
        var courses = mock(ClassroomCourseService.class);
        var bridge = new ClassroomWechatBridge(courses, mapper, dir, Clock.systemUTC());
        var code = bridge.createPairing("https://192.168.1.8:1443/").get("code");
        assertTrue(bridge.handle("绑定课堂 " + code, "wx-a").orElseThrow().contains("已连接"));
        assertTrue(bridge.handle("绑定课堂 " + code, "wx-b").orElseThrow().contains("无效"));
        var restored = new ClassroomWechatBridge(courses, mapper, dir, Clock.systemUTC());
        assertEquals(true, restored.status().get("bound"));
        assertTrue(restored.handle("我的课堂", "wx-b").orElseThrow().contains("配对"));
        when(courses.list()).thenReturn(List.of(mapper.createObjectNode().put("id", "course-1").put("title", "认识分数")));
        assertTrue(restored.handle("我的课堂", "wx-a").orElseThrow().contains("https://192.168.1.8:1443/#/classroom/course-1"));
    }

    @Test
    void expiredPairingIsRejected() {
        var clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L, 300_001L);
        var bridge = new ClassroomWechatBridge(mock(ClassroomCourseService.class), mapper, dir, clock);
        var code = bridge.createPairing("https://192.168.1.8:1443").get("code");
        assertTrue(bridge.handle("绑定课堂 " + code, "wx-a").orElseThrow().contains("过期"));
        assertEquals(false, bridge.status().get("bound"));
    }

    @Test
    void pairingRejectsExecutableOrCredentialBearingLinks() {
        assertThrows(IllegalArgumentException.class, () -> ClassroomWechatBridge.validateBaseUrl("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> ClassroomWechatBridge.validateBaseUrl("https://user:password@example.org"));
        assertThrows(IllegalArgumentException.class, () -> ClassroomWechatBridge.validateBaseUrl("https://example.org/?redirect=evil"));
        assertEquals("http://localhost:1420", ClassroomWechatBridge.validateBaseUrl("http://localhost:1420/"));
    }
}
