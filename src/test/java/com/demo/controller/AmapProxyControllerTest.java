package com.demo.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AmapProxyControllerTest {
    @Test void readonlyProxyKeepsConfiguredKeyOutOfLogsAndIgnoresClientKeyOverride() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(any(URI.class), eq(String.class))).thenReturn("{\"status\":\"1\",\"geocodes\":[]}");
        AmapProxyController controller = controller(http);
        Logger logger = (Logger) LoggerFactory.getLogger(AmapProxyController.class);
        ListAppender<ILoggingEvent> log = new ListAppender<>(); log.start(); logger.addAppender(log);
        try {
            assertEquals(200, controller.proxy("v3/geocode/geo", Map.of("path", "v3/geocode/geo", "address", "学校",
                    "key", "client-override")).getStatusCode().value());
            ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
            verify(http).getForObject(uri.capture(), eq(String.class));
            assertEquals("restapi.amap.com", uri.getValue().getHost());
            assertEquals("/v3/geocode/geo", uri.getValue().getPath());
            assertTrue(uri.getValue().getRawQuery().contains("key=test-provider-secret"));
            assertFalse(uri.getValue().toString().contains("client-override"));
            assertTrue(log.list.stream().noneMatch(event -> event.getFormattedMessage().contains("test-provider-secret")));
        } finally { logger.detachAppender(log); log.stop(); }
    }

    @Test void providerErrorsDoNotReturnOrLogCredentialBearingUrls() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(any(URI.class), eq(String.class))).thenThrow(new RestClientException(
                "GET https://restapi.amap.com/v3/ip?key=test-provider-secret failed"));
        AmapProxyController controller = controller(http);
        Logger logger = (Logger) LoggerFactory.getLogger(AmapProxyController.class);
        ListAppender<ILoggingEvent> log = new ListAppender<>(); log.start(); logger.addAppender(log);
        try {
            var response = controller.proxy("v3/ip", Map.of());
            assertEquals(502, response.getStatusCode().value());
            assertFalse(String.valueOf(response.getBody()).contains("test-provider-secret"));
            assertFalse(String.valueOf(response.getBody()).contains("restapi.amap.com"));
            assertTrue(log.list.stream().noneMatch(event -> event.getFormattedMessage().contains("test-provider-secret")));
        } finally { logger.detachAppender(log); log.stop(); }
    }

    @Test void unknownOrInjectedPathsNeverReachUpstream() {
        RestTemplate http = mock(RestTemplate.class);
        AmapProxyController controller = controller(http);
        for (String path : new String[]{"v3/../admin", "v3/ip?key=override", "https://other.example/test", "v3/arbitrary-admin"})
            assertEquals(400, controller.proxy(path, Map.of()).getStatusCode().value());
        verifyNoInteractions(http);
    }

    private AmapProxyController controller(RestTemplate http) {
        AmapProxyController controller = new AmapProxyController();
        ReflectionTestUtils.setField(controller, "amapKey", "test-provider-secret");
        ReflectionTestUtils.setField(controller, "restTemplate", http);
        return controller;
    }
}
