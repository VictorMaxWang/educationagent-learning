package com.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 覆盖全部 /api/**（desktop 桌面端 + maimemo 英语 + 未来新增接口）
        registry.addMapping("/api/**")
                // 允许前端开发地址（127.0.0.1 ≠ localhost，浏览器视作不同源需一并放行）+ Tauri正式地址
                .allowedOrigins(
                        "http://localhost:1420",
                        "http://127.0.0.1:1420",
                        "tauri://localhost",
                        "http://tauri.localhost"
                )
                .allowCredentials(true)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .maxAge(3600);

        // 历史对话侧边栏接口 /chat/**
        registry.addMapping("/chat/**")
                .allowedOrigins(
                        "http://localhost:1420",
                        "http://127.0.0.1:1420",
                        "tauri://localhost",
                        "http://tauri.localhost"
                )
                .allowCredentials(true)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .maxAge(3600);
    }
}