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

    /**
     * 登录失败次数过多，账号/IP 被临时锁定（AUTH-01）。
     *
     * <p><b>必须与 {@link #LOGIN_FAILED} 可区分</b>：若继续返回"用户名或密码错误"，
     * 用户会认为密码一直不对而反复重试，每次重试都刷新失败窗口，
     * 形成"越试越锁"的自我锁定死循环。</p>
     */
    LOGIN_LOCKED(1004, "登录失败次数过多，账号已被临时锁定"),

    /**
     * 认证服务不可用（AUTH-02）。
     *
     * <p>用于 Redis 故障且 {@code failure-mode=fail-closed} 的场景。**登录接口必须返回它而不是成功**：
     * 否则会出现"返回登录成功、随后每个请求都 401"的死循环——用户无法通过重新登录自救。</p>
     */
    AUTH_UNAVAILABLE(1005, "认证服务暂时不可用，请稍后重试"),

    /**
     * 首次登录必须修改初始密码（P-10 / D1=C）。
     *
     * <p><b>必须独立于 {@link #FORBIDDEN} 可区分</b>：前端拦截器对 403 的默认处理是
     * 提示"没有权限"并跳登录页。若把本状态混进普通 403 走同一条路，处于强制改密状态的用户
     * 会被踢回登录页，登录后又被闸门拦回来——形成死循环。因此这里给出独立业务码，
     * 前端据 {@code code} 分流到改密页（且**不清 token**，改密还要用它）。</p>
     */
    PASSWORD_CHANGE_REQUIRED(1006, "首次登录需先修改初始密码"),

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
