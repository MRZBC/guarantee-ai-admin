package com.guarantee.common.exception;

import com.guarantee.common.api.ResultCode;

/**
 * 业务异常。用于表达可预期的业务失败，由 {@link GlobalExceptionHandler} 统一转换为响应体。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(ResultCode.BIZ_ERROR, message);
    }

    public BizException(ResultCode resultCode) {
        this(resultCode, resultCode.message());
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.code();
    }

    public BizException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.code = resultCode.code();
    }

    public int getCode() {
        return code;
    }

    public static BizException notFound(String message) {
        return new BizException(ResultCode.NOT_FOUND, message);
    }

    public static BizException badRequest(String message) {
        return new BizException(ResultCode.BAD_REQUEST, message);
    }
}
