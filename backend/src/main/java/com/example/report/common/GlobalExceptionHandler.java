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

    /** 当前请求字段或JSON结构无效时返回400；不回显原始请求，客户端应按当前协议重新提交。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleMalformedRequest(Exception e) {
        return ResponseEntity.badRequest().body(Result.fail(400, "请求格式不符合当前接口协议"));
    }

    /** 已移除或不存在的接口返回404，不作为服务器故障重试。 */
    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> handleMissingEndpoint(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail(404, "接口不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.OK).body(Result.fail(500, "服务器内部错误，请稍后重试或联系管理员"));
    }
}
