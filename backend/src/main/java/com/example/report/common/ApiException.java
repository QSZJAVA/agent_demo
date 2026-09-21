package com.example.report.common;

/**
 * 业务异常：携带 HTTP 语义的错误码（400 参数/状态错误，403 无权限，404 不存在），由 GlobalExceptionHandler 统一转成 Result
 */
public class ApiException extends RuntimeException {

    private final int code;

    public ApiException(String message) {
        this(400, message);
    }

    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }
}
