package com.guarantee.common.api;

/**
 * 统一业务状态码。
 *
 * <p>0 表示成功；4xx/5xx 与 HTTP 语义对齐，方便前端统一拦截；1xxx 为业务语义错误。</p>
 */
public enum ResultCode {

    SUCCESS(0, "成功"),

    BAD_REQUEST(400, "请求参数不合法"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有访问权限"),
    NOT_FOUND(404, "资源不存在"),

    INTERNAL_ERROR(500, "系统内部错误"),

    BIZ_ERROR(1000, "业务处理失败"),
    LOGIN_FAILED(1001, "用户名或密码错误"),
    ACCOUNT_DISABLED(1002, "账号已被停用"),
    DUPLICATE_KEY(1003, "数据已存在"),
    AI_ERROR(2000, "AI 服务异常"),
    AI_TOOL_ERROR(2001, "AI 工具执行失败");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
