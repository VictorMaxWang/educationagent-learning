package com.demo.edu.classroom;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.OutputStream;
import java.util.Arrays;

/** 无正文参数的取消、生成等操作也须先验证请求大小，不能靠“不读取正文”绕过限制。 */
@Configuration
public class PublicJsonBodyLimitConfiguration implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UnreadBodyInterceptor());
    }

    static final class UnreadBodyInterceptor implements HandlerInterceptor {
        @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws Exception {
            if(!Boolean.TRUE.equals(request.getAttribute(PublicJsonBodyLimitFilter.LIMITED)) || !(handler instanceof HandlerMethod method)) return true;
            boolean convertedBody=Arrays.stream(method.getMethodParameters()).anyMatch(parameter->
                parameter.hasParameterAnnotation(RequestBody.class)||HttpEntity.class.isAssignableFrom(parameter.getParameterType()));
            // 有正文参数的端点交给流式过滤器和 RequestBodyAdvice，避免提前消耗它们的输入。
            if(!convertedBody) request.getInputStream().transferTo(OutputStream.nullOutputStream());
            return true;
        }
    }
}
