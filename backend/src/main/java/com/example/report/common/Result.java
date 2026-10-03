package com.example.report.common;

/**
 * 普通 HTTP 接口的统一响应信封；前端以 code=0 判断成功并解包 data。
 * @param code 业务响应码，0成功，其他值表示失败
 * @param message 可展示给用户的结果摘要
 * @param data 成功载荷；没有业务数据或失败时可为空
 * @param <T> 业务载荷类型
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
