package com.demo.edu.classroom;

import com.demo.edu.classroom.code.CodeJobController;
import com.demo.edu.classroom.code.CodeJobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 经过真实 Servlet 输入流、Jackson 与 MVC 控制器，确认超限发生在业务方法之前。 */
class PublicJsonBodyLimitFilterTest {
    private static final long MIB=1_048_576L;
    private static final String CODE_PATH="/api/classroom/code/jobs";
    private static final String SMALL="{\"language\":\"python\",\"code\":\"print(1)\"}";
    private final PublicJsonBodyLimitFilter filter=new PublicJsonBodyLimitFilter();
    private final AtomicInteger echoes=new AtomicInteger();
    private CodeJobService code;
    private MockMvc mvc;

    @BeforeEach void setup() {
        code=mock(CodeJobService.class);
        when(code.create(anyString(),any())).thenReturn(new ObjectMapper().createObjectNode().put("id","job"));
        when(code.cancel(anyString(),anyString())).thenReturn(new ObjectMapper().createObjectNode().put("status","cancelled"));
        mvc=MockMvcBuilders.standaloneSetup(new CodeJobController(code),new EchoController(echoes))
            .setControllerAdvice(new PublicJsonBodyLimitAdvice()).addFilters(filter)
            .addInterceptors(new PublicJsonBodyLimitConfiguration.UnreadBodyInterceptor()).build();
    }

    @Test void knownOversizeIsRejectedWithoutReadingOrEnteringCodeService() throws Exception {
        var reads=new AtomicLong();
        mvc.perform(request("POST",CODE_PATH,"application/json",bytes(SMALL),MIB+1,reads))
            .andExpect(status().is(413)).andExpect(jsonPath("$.error").value(PublicJsonBodyLimitFilter.MESSAGE));
        assertEquals(0,reads.get()); verifyNoInteractions(code);
        mvc.perform(request("POST",CODE_PATH,"application/json",bytes(SMALL),SMALL.length(),new AtomicLong()))
            .andExpect(status().isAccepted());
        verify(code).create(anyString(),any());
    }

    @Test void chunkedUnicodeBodyCountsBytesAndNeverCallsCodeService() throws Exception {
        String body="{\"language\":\"python\",\"code\":\"print(1)\",\"extra\":\""+"陶".repeat(350_000)+"\"}";
        assertTrue(body.length()<MIB);
        var reads=new AtomicLong();
        mvc.perform(request("POST",CODE_PATH,"application/problem+json; charset=UTF-8",bytes(body),-1,reads))
            .andExpect(status().is(413));
        assertEquals(MIB+1,reads.get()); verifyNoInteractions(code);
    }

    @Test void trailingChunkedBytesAreCheckedBeforeBusinessAndExactLimitStillWorks() throws Exception {
        var reads=new AtomicLong();
        mvc.perform(request("POST",CODE_PATH,"application/json",paddedJson(SMALL,MIB+1),-1,reads))
            .andExpect(status().is(413));
        assertEquals(MIB+1,reads.get()); verifyNoInteractions(code);
        mvc.perform(request("POST",CODE_PATH,"application/json",paddedJson(SMALL,MIB),-1,new AtomicLong()))
            .andExpect(status().isAccepted());
        verify(code).create(anyString(),any());
    }

    @Test void onlySceneAndRuntimeEditsImportAndFileUploadReceiveLargerBudgets() throws Exception {
        long aboveDefault=MIB+1;
        for(String path:new String[]{"/api/classroom/courses/c/scenes/s","/api/classroom/courses/c/runtime"}) {
            for(String method:new String[]{"PUT","PATCH"}) {
                mvc.perform(request(method,path,"Application/JSON; charset=utf-8",paddedJson("{}",aboveDefault),-1,new AtomicLong()))
                    .andExpect(status().isOk());
                var reads=new AtomicLong();
                mvc.perform(request(method,path,"application/json",bytes("{}"),8*MIB+1,reads))
                    .andExpect(status().is(413));
                assertEquals(0,reads.get());
            }
            mvc.perform(request("POST",path,"application/json",bytes("{}"),aboveDefault,new AtomicLong())).andExpect(status().is(413));
        }
        mvc.perform(request("POST","/api/classroom/courses/import","application/json",paddedJson("{}",aboveDefault),-1,new AtomicLong()))
            .andExpect(status().isOk());
        mvc.perform(request("POST","/api/classroom/courses/import","application/json",bytes("{}"),64*MIB+1,new AtomicLong()))
            .andExpect(status().is(413));
        mvc.perform(request("POST","/api/desktop/file/upload","application/json",paddedJson("{}",aboveDefault),-1,new AtomicLong()))
            .andExpect(status().isOk());
        mvc.perform(request("POST","/api/desktop/file/upload","application/json",bytes("{}"),42_000_000L+1,new AtomicLong()))
            .andExpect(status().is(413));
        assertEquals(6,echoes.get());
    }

    @Test void ordinaryParseErrorsStayBadRequestsAndMultipartOrPrivateRoutesRemainUntouched() throws Exception {
        mvc.perform(request("POST",CODE_PATH,"application/json",bytes("{"),1,new AtomicLong())).andExpect(status().isBadRequest());
        verifyNoInteractions(code);
        for(String[] target:new String[][]{{"/api/classroom/materials","multipart/form-data; boundary=fixture"},
                {"/api/internal/course-publication","application/json"},{CODE_PATH,"text/plain"}}) {
            var original=new MockHttpServletRequest("POST",target[0]); original.setContentType(target[1]);
            original.addHeader("Content-Length",100_000_000);
            var passed=new AtomicInteger();
            filter.doFilter(original,new MockHttpServletResponse(),(request,response)->{ assertSame(original,request); passed.incrementAndGet(); });
            assertEquals(1,passed.get());
        }
    }

    @Test void aWriteEndpointWithoutRequestBodyMustRejectChunkedOversizeBeforeItsSideEffect() throws Exception {
        var reads=new AtomicLong();
        mvc.perform(request("DELETE",CODE_PATH+"/job","application/json",paddedJson("{}",MIB+1),-1,reads))
            .andExpect(status().is(413));
        assertEquals(MIB+1,reads.get()); verifyNoInteractions(code);
        mvc.perform(request("DELETE",CODE_PATH+"/job","application/json",bytes("{}"),-1,new AtomicLong()))
            .andExpect(status().isOk());
        verify(code).cancel(anyString(),eq("job"));
    }

    private static InputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    /** 大尾部按需生成；测试本身也不分配 64MiB 缓冲。 */
    private static InputStream paddedJson(String json,long total) {
        byte[] prefix=json.getBytes(StandardCharsets.UTF_8);
        return new InputStream() {
            long position;
            @Override public int read() { if(position>=total) return -1; return position<prefix.length?prefix[(int)position++]&255:advanceSpace(); }
            private int advanceSpace() { position++; return ' '; }
            @Override public int read(byte[] bytes,int offset,int length) {
                if(length==0) return 0; if(position>=total) return -1;
                int count=(int)Math.min(length,total-position); Arrays.fill(bytes,offset,offset+count,(byte)' ');
                if(position<prefix.length) System.arraycopy(prefix,(int)position,bytes,offset,(int)Math.min(count,prefix.length-position));
                position+=count; return count;
            }
        };
    }
    private static RequestBuilder request(String method,String path,String type,InputStream input,long declared,AtomicLong read) {
        return context->new ProbeRequest(context,method,path,type,input,declared,read);
    }
    private static final class ProbeRequest extends MockHttpServletRequest {
        private final long declared;
        private final ServletInputStream input;
        ProbeRequest(ServletContext context,String method,String path,String type,InputStream source,long declared,AtomicLong read) {
            super(context,method,path); this.declared=declared; setContentType(type); setCharacterEncoding("UTF-8");
            if(declared<0) addHeader("Transfer-Encoding","chunked"); else addHeader("Content-Length",declared);
            input=new ServletInputStream() {
                boolean eof;
                @Override public int read() throws IOException { int value=source.read(); if(value<0) eof=true; else read.incrementAndGet(); return value; }
                @Override public int read(byte[] bytes,int offset,int length) throws IOException { int count=source.read(bytes,offset,length); if(count<0) eof=true; else read.addAndGet(count); return count; }
                @Override public boolean isFinished() { return eof; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("synchronous fixture"); }
                @Override public void close() throws IOException { source.close(); }
            };
        }
        @Override public long getContentLengthLong() { return declared; }
        @Override public int getContentLength() { return (int)declared; }
        @Override public ServletInputStream getInputStream() { return input; }
    }
    @RestController static final class EchoController {
        private final AtomicInteger calls;
        EchoController(AtomicInteger calls) { this.calls=calls; }
        @RequestMapping(value={"/api/classroom/courses/{course}/scenes/{scene}","/api/classroom/courses/{course}/runtime",
            "/api/classroom/courses/import","/api/desktop/file/upload"},method={RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH})
        public Map<String,Boolean> echo(@RequestBody JsonNode body) { calls.incrementAndGet(); return Map.of("accepted",true); }
    }
}
