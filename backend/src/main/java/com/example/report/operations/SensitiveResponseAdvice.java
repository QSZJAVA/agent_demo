package com.example.report.operations;

import com.example.report.common.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 在 HTTP 响应出口统一脱敏文本与结构化载荷，保持响应类型契约；不改变持久化业务状态。
 */
@RestControllerAdvice(basePackages="com.example.report.web")
public class SensitiveResponseAdvice implements ResponseBodyAdvice<Object> {
    public boolean supports(MethodParameter type,Class<? extends HttpMessageConverter<?>> converter) {
        return Result.class.isAssignableFrom(type.getParameterType());
    }
    public Object beforeBodyWrite(Object body,MethodParameter type,MediaType media,
            Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response) {
        return SensitiveData.value(body);
    }
}
