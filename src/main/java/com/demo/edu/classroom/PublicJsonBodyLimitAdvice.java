package com.demo.edu.classroom;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.Map;

/** 解析后的尾部也须计入总大小，避免“很小的 JSON + 大量空白”在业务调用后才被发现。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicJsonBodyLimitAdvice extends RequestBodyAdviceAdapter {
    @Override public boolean supports(MethodParameter parameter,Type targetType,Class<? extends HttpMessageConverter<?>> converterType) { return true; }

    @Override public Object afterBodyRead(Object body,HttpInputMessage input,MethodParameter parameter,
            Type targetType,Class<? extends HttpMessageConverter<?>> converterType) {
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                &&Boolean.TRUE.equals(attributes.getRequest().getAttribute(PublicJsonBodyLimitFilter.LIMITED))) {
            try { input.getBody().transferTo(OutputStream.nullOutputStream()); }
            catch(IOException failure) { throw new HttpMessageNotReadableException("请求内容无法读取",failure,input); }
        }
        return body;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,PublicJsonBodyLimitFilter.BodyTooLargeException.class})
    public ResponseEntity<Map<String,String>> tooLarge(Exception failure,HttpServletRequest request) throws Exception {
        if(!Boolean.TRUE.equals(request.getAttribute(PublicJsonBodyLimitFilter.EXCEEDED))) throw failure;
        return ResponseEntity.status(413).body(Map.of("error",PublicJsonBodyLimitFilter.MESSAGE));
    }
}
