package com.example.report.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把异常统一转成 Result，前端 http.js 只看 code 是否为 0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result<Void>> handleApi(ApiException e) {
        // 业务异常 HTTP 仍返回 200，由 code 表达语义，避免 axios 走网络错误分支
        return ResponseEntity.ok(Result.fail(e.getCode(), com.example.report.operations.SensitiveData.text(e.getMessage())));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Result<Void>> handleHeader(MissingRequestHeaderException e) {
        return ResponseEntity.ok(Result.fail(400, "缺少请求头 " + e.getHeaderName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.OK).body(Result.fail(500, "服务器内部错误，请稍后重试或联系管理员"));
    }
}
