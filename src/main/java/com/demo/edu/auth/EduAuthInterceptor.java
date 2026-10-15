package com.demo.edu.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 教师端角色门：只拦 {@code /api/edu/teacher/**}。
 *
 * <h3>为什么开关关掉时是"全部 401"而不是"全部放行"</h3>
 * 很多原型会写成"鉴权未启用 → 跳过校验"，结果是<b>线上把开关一关，任何人都能看班级数据</b>——
 * 一个配置项就把隐私设计清零。这里反过来：关掉开关等于教师端不可用。
 * 失败方向是"教师打不开"，而不是"谁都能看"，这是有意的取舍。
 */
@Slf4j
@Component
public class EduAuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "X-Edu-Token";

    private final EduAuthProperties properties;
    private final EduTeacherService teacherService;

    public EduAuthInterceptor(EduAuthProperties properties, EduTeacherService teacherService) {
        this.properties = properties;
        this.teacherService = teacherService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // CORS 预检不带自定义头；放行给 CORS 处理器，真正请求再校验
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        if (!properties.isEnabled()) {
            log.warn("教师端访问被拒：edu.auth.enabled=false（关掉开关＝不可用，而不是人人可见）");
            deny(response, "教师端鉴权未启用（edu.auth.enabled=false），已拒绝访问");
            return false;
        }

        String token = request.getHeader(HEADER);
        String classId = null;
        String teacher = null;
        boolean legacy = false;

        // ① 主路径：登录签发的 token（与班级绑定、带有效期）
        classId = teacherService.classIdOfToken(token);
        teacher = teacherService.usernameOfToken(token);
        // ② 演示后门：共享口令直接当 token（无身份、无有效期，可配置关闭）
        if (classId == null && properties.isLegacyTokenEnabled() && token != null && !token.isBlank()) {
            classId = properties.getTeacherClasses().get(token.trim());
            legacy = classId != null;
            // 共享口令没有身份，用常量占位——**绝不把口令本身写进去**（那会把口令落到数据库列里）
            teacher = legacy ? "shared-token" : null;
        }

        if (classId == null || classId.isBlank()) {
            log.warn("教师端鉴权失败：缺少或无效的 {}（token 前缀={}）", HEADER, maskToken(token));
            deny(response, "缺少或无效的 " + HEADER + "（请先 POST /api/edu/teacher/login 登录）");
            return false;
        }

        // ★ 班级只来自这里；控制器不接收 classId 参数，所以改 URL / 改 body 都拿不到别的班
        EduContext.setClassId(classId);
        EduContext.setTeacher(teacher == null ? "unknown" : teacher);
        log.info("教师端访问放行 → 班级={} 教师={}{}", classId, EduContext.getTeacher(),
                legacy ? "（共享口令，演示后门）" : "");
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程池会复用线程，不清就会把上一个请求的班级泄漏给下一个请求
        EduContext.clear();
    }

    private static void deny(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"" + message + "\",\"data\":null}");
    }

    /** 日志只留口令前缀，别把口令整串写进日志。 */
    private static String maskToken(String token) {
        if (token == null || token.isBlank()) return "<空>";
        return token.length() <= 3 ? "***" : token.substring(0, 3) + "***";
    }
}
