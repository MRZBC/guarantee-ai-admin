package com.guarantee.common.api;

import com.guarantee.common.trace.TraceContext;

/**
 * 统一响应结构：{@code {code, message, data, traceId}}。
 *
 * @param code    业务状态码，0 表示成功
 * @param message 提示信息
 * @param data    业务数据
 * @param traceId 请求链路标识，便于排查
 */
public record Result<T>(int code, String message, T data, String traceId) {

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.code(), ResultCode.SUCCESS.message(), data, TraceContext.currentTraceId());
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode.code(), resultCode.message());
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return fail(resultCode.code(), message);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, TraceContext.currentTraceId());
    }

    public boolean isSuccess() {
        return code == ResultCode.SUCCESS.code();
    }
}
