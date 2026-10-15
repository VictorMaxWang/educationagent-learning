package com.demo.edu.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 只给 {@code /api/edu/teacher/**} 挂角色门。
 *
 * <p>刻意<b>不</b>使用 {@code addInterceptor(…).addPathPatterns("/api/**")} 通配：
 * 那会拦下全部 25 个控制器，把"教育域加一道门"变成"改整个系统的鉴权行为"。
 * 学生端（{@code /api/edu/student/**}）与知识库 REST 都不经过这道门。
 */
@Slf4j
@Configuration
public class EduWebConfig implements WebMvcConfigurer {

    private final EduAuthInterceptor eduAuthInterceptor;

    public EduWebConfig(EduAuthInterceptor eduAuthInterceptor) {
        this.eduAuthInterceptor = eduAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(eduAuthInterceptor)
                .addPathPatterns("/api/edu/teacher/**")
                // 登录/登出本身不能要求 token，否则永远登不进来
                .excludePathPatterns("/api/edu/teacher/login", "/api/edu/teacher/logout");
        log.info("🔐 教育域教师端角色门已挂载：/api/edu/teacher/**（口令头 X-Edu-Token；login/logout 除外）");
    }
}
