package com.guarantee.ai.mcp;

/**
 * 业务 MCP 的错误码（对外可读、可映射 HTTP 状态）。
 *
 * <p>为什么单独一套而不是复用 {@code ResultCode}：MCP 的失败语义比"通用业务失败"更细
 * ——"Token 无效 / 已撤销 / 已过期"三者都必须能被外部 Agent 区分（AC-MCP-02 要求撤销后
 * **立刻**给出可读错误），而通用的 401 说不清是哪种。控制器层再把它映射成
 * {@link #httpStatus()} 即可，不需要在业务层拼 HTTP 语义。</p>
 */
public enum McpErrorCode {

    /** 入参不合法（服务账号为空、权限为空、有效期已过…）。 */
    INVALID_ARGUMENT(400),

    /** Token 缺失、格式不合法、哈希对不上或未签发。 */
    TOKEN_INVALID(401),

    /** Token 已被显式撤销（即时生效）。 */
    TOKEN_REVOKED(401),

    /** Token 已过期。 */
    TOKEN_EXPIRED(401),

    /** 工具不在只读白名单里（含所有写工具与未知工具名）。 */
    TOOL_NOT_ALLOWED(403),

    /** 工具在契约上存在，但按当前权限快照未被注册（fail-closed：无权限 = 不可见）。 */
    TOOL_UNAVAILABLE(403),

    /** 工具执行失败（业务异常，已翻译成可读信息）。 */
    TOOL_FAILED(500);

    private final int httpStatus;

    McpErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** 建议的 HTTP 状态码（由控制器决定是否采用）。 */
    public int httpStatus() {
        return httpStatus;
    }
}
