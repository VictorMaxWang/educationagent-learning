package com.demo.edu.classroom.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.Cookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeJobControllerTest {
    @Test void createsHttpOnlySameSiteCredentialAndReusesValidBrowserCookie() {
        var service=mock(CodeJobService.class); var json=new ObjectMapper();
        when(service.create(anyString(),any())).thenReturn(json.createObjectNode().put("id","job"));
        var controller=new CodeJobController(service);
        var request=new MockHttpServletRequest(); request.setSecure(true);
        var response=new MockHttpServletResponse();
        assertEquals(202,controller.create(json.createObjectNode(),"invalid",request,response).getStatusCode().value());
        String cookie=response.getHeader("Set-Cookie"); assertNotNull(cookie);
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("Secure")); assertTrue(cookie.contains("SameSite=Strict"));
        assertTrue(cookie.contains("Path=/api/classroom/code"));
        var captured=ArgumentCaptor.forClass(String.class); verify(service).create(captured.capture(),any());
        assertTrue(captured.getValue().matches("[A-Za-z0-9_-]{43}"));
        var reuseResponse=new MockHttpServletResponse();
        controller.create(json.createObjectNode(),captured.getValue(),request,reuseResponse);
        assertNull(reuseResponse.getHeader("Set-Cookie"));
        verify(service,times(2)).create(eq(captured.getValue()),any());
    }

    @Test void privateTaskResponsesAreNotCacheableAndForwardOnlyCurrentCookie() throws Exception {
        var service=mock(CodeJobService.class); var json=new ObjectMapper();
        String owner="A".repeat(43);
        when(service.get(owner,"job")).thenReturn(json.createObjectNode().put("status","completed"));
        var mvc=MockMvcBuilders.standaloneSetup(new CodeJobController(service)).build();
        mvc.perform(get("/api/classroom/code/jobs/job").cookie(new Cookie("xiaotao_code_owner",owner)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).get(owner,"job");
    }
}
