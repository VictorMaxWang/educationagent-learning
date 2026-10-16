package com.demo.edu.classroom.code;

import com.demo.edu.classroom.ClassroomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** 匿名浏览器使用 HttpOnly 随机归属凭据，其他浏览器不能读取源码、输入或运行轨迹。 */
@RestController
@RequestMapping("/api/classroom/code/jobs")
public class CodeJobController {
    private static final String COOKIE="xiaotao_code_owner";
    private final CodeJobService service;
    public CodeJobController(CodeJobService service) { this.service=service; }
    /** 轨迹和输出属于当前浏览器，禁止代理或浏览器把它当作可复用的公共响应缓存。 */
    @ModelAttribute public void privateResponse(HttpServletResponse response) { response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store"); }
    @PostMapping public ResponseEntity<ObjectNode> create(@RequestBody JsonNode body,
            @CookieValue(name=COOKIE,required=false) String owner,HttpServletRequest request,HttpServletResponse response) {
        if(owner==null||!owner.matches("[A-Za-z0-9_-]{43}")) {
            byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); owner=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            boolean secure=request.isSecure()||"https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
            response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(COOKIE,owner).httpOnly(true).secure(secure).sameSite("Strict")
                .path("/api/classroom/code").maxAge(Duration.ofDays(1)).build().toString());
        }
        return ResponseEntity.accepted().body(service.create(owner,body));
    }
    @GetMapping("/{id}") public ObjectNode get(@PathVariable String id,@CookieValue(name=COOKIE,defaultValue="") String owner) { return service.get(owner,id); }
    @DeleteMapping("/{id}") public ObjectNode cancel(@PathVariable String id,@CookieValue(name=COOKIE,defaultValue="") String owner) { return service.cancel(owner,id); }
    @ExceptionHandler(ClassroomException.class) public ResponseEntity<Map<String,String>> error(ClassroomException error) { return ResponseEntity.status(error.status()).body(Map.of("error",error.getMessage())); }
}
