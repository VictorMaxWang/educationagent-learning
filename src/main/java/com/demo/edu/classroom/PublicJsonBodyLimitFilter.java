package com.demo.edu.classroom;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 在 JSON 转换器读取前按实际字节限流；未知长度请求也不能先占满 JVM 内存。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PublicJsonBodyLimitFilter extends OncePerRequestFilter {
    static final long ORDINARY_LIMIT = 1024L * 1024;
    static final long SCENE_LIMIT = 8 * ORDINARY_LIMIT;
    static final long IMPORT_LIMIT = 64 * ORDINARY_LIMIT;
    // 保留现有代理 42MB 例外，容纳 30MiB 文件的 Base64 与 JSON 外壳。
    static final long UPLOAD_LIMIT = 42_000_000L;
    static final String LIMITED = PublicJsonBodyLimitFilter.class.getName()+".limited";
    static final String EXCEEDED = PublicJsonBodyLimitFilter.class.getName()+".exceeded";
    static final String MESSAGE = "请求内容超过允许大小，请缩小后重试";
    private static final Set<String> WRITES = Set.of("POST","PUT","PATCH","DELETE");
    private static final Set<String> EXACT_PUBLIC = Set.of("/api/classroom", "/api/desktop/file/upload",
        "/api/maimemo/config", "/api/maimemo/speech-preset", "/api/maimemo/grammar-quiz",
        "/api/maimemo/speech-evaluate", "/api/maimemo/speech-dialogue",
        "/api/desktop/watermark/add", "/api/desktop/watermark/remove");
    private static final Pattern SCENE_OR_RUNTIME = Pattern.compile("^/api/classroom/courses/[^/]+/(scenes/[^/]+|runtime)/?$");

    static long limitFor(HttpServletRequest request) {
        if(!WRITES.contains(request.getMethod())) return -1;
        String contentType=request.getContentType();
        if(contentType==null) return -1;
        String media=contentType.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        if(!media.equals("application/json") && !(media.startsWith("application/")&&media.endsWith("+json"))) return -1;
        String path=request.getRequestURI().substring(request.getContextPath().length());
        boolean publiclyRouted=EXACT_PUBLIC.contains(path) || path.startsWith("/api/classroom/")
            || path.startsWith("/api/desktop/quiz/") || path.startsWith("/api/desktop/psychology/")
            || path.startsWith("/api/desktop/lifesafety/") || path.startsWith("/api/desktop/sexedu/");
        if(!publiclyRouted) return -1;
        if(path.equals("/api/desktop/file/upload")) return UPLOAD_LIMIT;
        if(request.getMethod().equals("POST")&&path.equals("/api/classroom/courses/import")) return IMPORT_LIMIT;
        if(Set.of("PUT","PATCH").contains(request.getMethod())&&SCENE_OR_RUNTIME.matcher(path).matches()) return SCENE_LIMIT;
        return ORDINARY_LIMIT;
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        long limit=limitFor(request);
        if(limit<0) { chain.doFilter(request,response); return; }
        if(request.getContentLengthLong()>limit) { reject(response); return; }
        request.setAttribute(LIMITED,Boolean.TRUE);
        var wrapped=new HttpServletRequestWrapper(request) {
            private ServletInputStream counted;
            @Override public ServletInputStream getInputStream() throws IOException {
                if(counted==null) counted=new BoundedInput(super.getInputStream(),limit,request);
                return counted;
            }
            @Override public BufferedReader getReader() throws IOException {
                Charset charset=getCharacterEncoding()==null?StandardCharsets.UTF_8:Charset.forName(getCharacterEncoding());
                return new BufferedReader(new InputStreamReader(getInputStream(),charset));
            }
        };
        try { chain.doFilter(wrapped,response); }
        catch(ServletException|IOException|RuntimeException failure) {
            if(Boolean.TRUE.equals(request.getAttribute(EXCEEDED))&&!response.isCommitted()) reject(response);
            else throw failure;
        }
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.resetBuffer(); response.setStatus(413); response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\""+MESSAGE+"\"}");
    }

    /** 与 Spring 的异常处理配合返回 413，不把超限误报为 JSON 语法错误。 */
    static final class BodyTooLargeException extends RuntimeException {
        BodyTooLargeException() { super(MESSAGE); }
    }

    private static final class BoundedInput extends ServletInputStream {
        private final ServletInputStream source;
        private final long limit;
        private final HttpServletRequest request;
        private long received;
        BoundedInput(ServletInputStream source,long limit,HttpServletRequest request) { this.source=source; this.limit=limit; this.request=request; }
        private void account(int count) {
            if(count>0) received+=count;
            if(received>limit) { request.setAttribute(EXCEEDED,Boolean.TRUE); throw new BodyTooLargeException(); }
        }
        @Override public int read() throws IOException { int value=source.read(); account(value<0?0:1); return value; }
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            if(length==0) return 0;
            // 最多多读一个字节以区分“刚好等于限制”和“已经超过”，不缓存完整请求。
            int read=source.read(bytes,offset,(int)Math.min(length,Math.max(1,limit-received+1)));
            account(read); return read;
        }
        @Override public boolean isFinished() { return source.isFinished(); }
        @Override public boolean isReady() { return source.isReady(); }
        @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
        @Override public void close() throws IOException { source.close(); }
    }
}
