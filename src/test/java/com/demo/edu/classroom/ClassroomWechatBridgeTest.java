package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClassroomWechatBridgeTest {
    @TempDir Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unpairedSenderCannotReadCoursesAndOrdinaryChatPassesThrough() {
        var courses = mock(ClassroomCourseService.class);
        var bridge = localBridge(courses);
        assertTrue(bridge.handle("今天数学课很难", "wx-a").isEmpty());
        assertTrue(bridge.handle("我的课堂", "wx-a").orElseThrow().contains("配对"));
        verifyNoInteractions(courses);
        assertFalse(bridge.status().containsKey("sender"));
    }

    @Test
    void pairingIsSingleUseAndBindingSurvivesRestart() {
        var courses = mock(ClassroomCourseService.class);
        var bridge = localBridge(courses);
        var code = bridge.createPairing("https://192.168.1.8:1443/").get("code");
        assertTrue(bridge.handle("绑定课堂 " + code, "wx-a").orElseThrow().contains("已连接"));
        assertTrue(bridge.handle("绑定课堂 " + code, "wx-b").orElseThrow().contains("无效"));
        var restored = localBridge(courses);
        assertEquals(true, restored.status().get("bound"));
        assertTrue(restored.handle("我的课堂", "wx-b").orElseThrow().contains("配对"));
        when(courses.list()).thenReturn(List.of(mapper.createObjectNode().put("id", "course-1").put("title", "认识分数")));
        assertTrue(restored.handle("我的课堂", "wx-a").orElseThrow().contains("https://192.168.1.8:1443/#/classroom/course-1"));
    }

    @Test
    void expiredPairingIsRejected() {
        var clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L, 300_001L);
        var bridge = new ClassroomWechatBridge(mock(ClassroomCourseService.class), mapper, dir, clock, "", host -> host.equals("192.168.1.8"));
        var code = bridge.createPairing("https://192.168.1.8:1443").get("code");
        assertTrue(bridge.handle("绑定课堂 " + code, "wx-a").orElseThrow().contains("过期"));
        assertEquals(false, bridge.status().get("bound"));
    }

    @Test
    void pairingRejectsExecutableOrCredentialBearingLinks() {
        assertThrows(IllegalArgumentException.class, () -> ClassroomWechatBridge.validateBaseUrl("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> ClassroomWechatBridge.validateBaseUrl("https://user:password@example.org"));
        assertThrows(IllegalArgumentException.class, () -> ClassroomWechatBridge.validateBaseUrl("https://example.org/?redirect=evil"));
        assertThrows(IllegalArgumentException.class, () -> ClassroomWechatBridge.validateBaseUrl("http://localhost:1420/"));
        assertEquals("https://smartchildcareagent.cn", ClassroomWechatBridge.validateBaseUrl("https://smartchildcareagent.cn/"));
    }

    @Test void authoritativeServerUrlOverridesClientLocalhostWithoutRequiringSameWifi() {
        var courses = mock(ClassroomCourseService.class);
        when(courses.list()).thenReturn(List.of(mapper.createObjectNode().put("id", "course-1").put("title", "认识分数")));
        var bridge = new ClassroomWechatBridge(courses, mapper, dir, Clock.systemUTC(), "https://smartchildcareagent.cn/", host -> false);
        var pairing = bridge.createPairing("http://localhost:1420");
        assertEquals("https://smartchildcareagent.cn", pairing.get("baseUrl"));
        assertTrue(pairing.get("linkNote").toString().contains("服务端"));
        assertTrue(bridge.handle("绑定课堂 " + pairing.get("code"), "wx-a").orElseThrow().contains("已连接"));
        var message = bridge.handle("我的课堂", "wx-a").orElseThrow();
        assertTrue(message.contains("https://smartchildcareagent.cn/#/classroom/course-1"));
        assertFalse(message.contains("http://localhost"));
        assertTrue(message.contains("无需与电脑连接同一 Wi-Fi"));
        assertEquals("public", bridge.status().get("linkMode"));
    }

    @Test void oldStoredLocalhostBecomesPublicOnlyWithServerDeploymentConfiguration() throws Exception {
        Files.writeString(dir.resolve("wechat.json"), mapper.writeValueAsString(Map.of("sender", "wx-a", "baseUrl", "http://localhost:1420", "selectedCourse", "")));
        var courses = mock(ClassroomCourseService.class);
        when(courses.list()).thenReturn(List.of(mapper.createObjectNode().put("id", "course-1").put("title", "旧课")));
        var local = localBridge(courses);
        String blocked = local.handle("我的课堂", "wx-a").orElseThrow();
        assertFalse(blocked.contains("http://localhost")); assertTrue(blocked.contains("手机无法打开"));
        assertEquals("", local.status().get("baseUrl")); assertEquals(false, local.status().get("linkReady"));
        var deployed = new ClassroomWechatBridge(courses, mapper, dir, Clock.systemUTC(), "https://smartchildcareagent.cn", host -> false);
        assertTrue(deployed.handle("我的课堂", "wx-a").orElseThrow().contains("https://smartchildcareagent.cn/#/classroom/course-1"));
        assertEquals(true, deployed.status().get("bound"));
        assertFalse(deployed.status().containsKey("sender"));
    }

    @Test void clientCannotRelabelLocalCourseAsAnArbitraryPublicSite() {
        var bridge = localBridge(mock(ClassroomCourseService.class));
        var error = assertThrows(IllegalArgumentException.class, () -> bridge.createPairing("https://smartchildcareagent.cn"));
        assertTrue(error.getMessage().contains("不能把本机课程仅换成公网域名"));
        assertEquals(false, bridge.status().get("bound"));
        assertThrows(IllegalArgumentException.class, () -> bridge.createPairing("http://192.168.1.99:1420"));
        assertThrows(IllegalArgumentException.class, () -> bridge.createPairing("http://localhost:1420"));
    }

    @Test void inheritedUnverifiedPublicAddressIsNotAcceptedWithoutServerConfiguration() throws Exception {
        Files.writeString(dir.resolve("wechat.json"), mapper.writeValueAsString(Map.of("sender", "wx-a", "baseUrl", "https://arbitrary.example.org", "selectedCourse", "")));
        var courses = mock(ClassroomCourseService.class);
        when(courses.list()).thenReturn(List.of(mapper.createObjectNode().put("id", "local-only").put("title", "本机课")));
        var bridge = localBridge(courses);
        assertFalse(bridge.handle("我的课堂", "wx-a").orElseThrow().contains("https://arbitrary.example.org"));
        assertEquals(false, bridge.status().get("linkReady"));
    }

    @Test void lostLanInterfaceDisablesStaleLinkButKeepsSenderBound() {
        var courses = mock(ClassroomCourseService.class);
        var bridge = localBridge(courses);
        bind(bridge, "http://192.168.1.8:1420", "wx-a");
        var movedNetwork = new ClassroomWechatBridge(courses, mapper, dir, Clock.systemUTC(), "", host -> false);
        assertEquals(true, movedNetwork.status().get("bound"));
        assertEquals(false, movedNetwork.status().get("linkReady"));
        assertEquals("", movedNetwork.status().get("baseUrl"));
        assertTrue(movedNetwork.status().get("linkNote").toString().contains("不属于当前课堂服务器"));
    }

    @Test void invalidServerConfigurationDisablesLinksInsteadOfFallingBackToClient() {
        var bridge = new ClassroomWechatBridge(mock(ClassroomCourseService.class), mapper, dir, Clock.systemUTC(), "https://localhost:8888", host -> true);
        assertEquals("unavailable", bridge.status().get("linkMode"));
        assertTrue(bridge.status().get("linkNote").toString().contains("CLASSROOM_PUBLIC_BASE_URL"));
        assertThrows(IllegalArgumentException.class, () -> bridge.createPairing("https://192.168.1.8:1420"));
        assertEquals("", bridge.status().get("baseUrl"));
    }

    @Test void pendingPairingDoesNotChangeExistingBindingOrLink() {
        var courses = mock(ClassroomCourseService.class);
        when(courses.list()).thenReturn(List.of(mapper.createObjectNode().put("id", "local-course").put("title", "本机课")));
        var bridge = new ClassroomWechatBridge(courses, mapper, dir, Clock.systemUTC(), "", host -> List.of("192.168.1.8", "10.0.0.8").contains(host));
        bind(bridge, "http://192.168.1.8:1420", "wx-a");
        var second = bridge.createPairing("http://10.0.0.8:1420");
        assertEquals("http://192.168.1.8:1420", bridge.status().get("baseUrl"));
        assertTrue(bridge.handle("我的课堂", "wx-a").orElseThrow().contains("http://192.168.1.8:1420/#/classroom/local-course"));
        assertTrue(bridge.handle("我的课堂", "wx-b").orElseThrow().contains("配对"));
        bridge.handle("绑定课堂 " + second.get("code"), "wx-b");
        assertEquals("http://10.0.0.8:1420", bridge.status().get("baseUrl"));
        assertTrue(bridge.handle("我的课堂", "wx-a").orElseThrow().contains("配对"));
    }

    @Test void failedBindingSaveRollsBackSenderAndUrlTogether() throws Exception {
        var courses = mock(ClassroomCourseService.class);
        when(courses.list()).thenReturn(List.of(mapper.createObjectNode().put("id", "local-course").put("title", "本机课")));
        var bridge = new ClassroomWechatBridge(courses, mapper, dir, Clock.systemUTC(), "", host -> true);
        bind(bridge, "http://192.168.1.8:1420", "wx-a");
        var second = bridge.createPairing("http://10.0.0.8:1420");
        // 在JUnit独占临时目录制造不可替换的目标，可靠触发落盘失败，不改变宿主权限或全局配置。
        Files.delete(dir.resolve("wechat.json")); Files.createDirectory(dir.resolve("wechat.json"));
        Files.writeString(dir.resolve("wechat.json").resolve("blocker"), "test");
        String result = bridge.handle("绑定课堂 " + second.get("code"), "wx-b").orElseThrow();
        assertTrue(result.contains("未能保存"));
        assertEquals("http://192.168.1.8:1420", bridge.status().get("baseUrl"));
        assertTrue(bridge.handle("我的课堂", "wx-a").orElseThrow().contains("http://192.168.1.8:1420/#/classroom/local-course"));
        assertTrue(bridge.handle("我的课堂", "wx-b").orElseThrow().contains("配对"));
    }

    @Test void loopbackUnspecifiedReservedAndUnsafePortsAreNeverPhoneEntries() {
        for (String value : List.of("http://127.0.0.1:1420", "http://127.9.1.1", "http://0.0.0.0:1420", "http://[::1]:1420",
                "http://[::]:1420", "http://[::ffff:127.0.0.1]", "http://169.254.1.2", "http://224.0.0.1", "http://192.0.2.1",
                "http://100.64.0.1", "https://smartchildcareagent.cn:65536", "https://smartchildcareagent.cn:0",
                "http://2130706433", "http://127.1", "http://foo.local", "http://LOCALHOST."))
            assertThrows(IllegalArgumentException.class, () -> ClassroomWechatBridge.validateBaseUrl(value), value);
        assertEquals("http://[fd12::8]:1420", ClassroomWechatBridge.validateBaseUrl("http://[fd12::8]:1420/"));
        assertEquals("https://192.168.1.8:1443", ClassroomWechatBridge.validateBaseUrl("https://192.168.1.8:1443/"));
    }

    private ClassroomWechatBridge localBridge(ClassroomCourseService courses) {
        return new ClassroomWechatBridge(courses, mapper, dir, Clock.systemUTC(), "", host -> host.equals("192.168.1.8"));
    }
    private void bind(ClassroomWechatBridge bridge, String url, String sender) {
        var pairing = bridge.createPairing(url);
        assertTrue(bridge.handle("绑定课堂 " + pairing.get("code"), sender).orElseThrow().contains("已连接"));
    }
}
