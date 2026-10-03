package com.example.report.common;

/**
 * 可安全返回给调用方的业务异常；携带 HTTP/业务错误码，由统一异常处理器转换为响应。异常消息不得包含凭据或底层数据库详情。
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
