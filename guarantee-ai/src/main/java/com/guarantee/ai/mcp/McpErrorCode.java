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

    /**
     * 服务账号缺少 {@code ai:mcp:read}（MCP 入口权限）。
     *
     * <p>MCP 是**外部面**：入口权限缺失时不是"少几个工具"，而是整个受控取数面拒绝服务
     * （清单为空 + 调用拒绝）。这与"聊天链路登录即可见 4 个公开只读工具"不是同一层
     * —— 聊天有登录与页面上下文，MCP 只有一把机器凭据。</p>
     */
    PERMISSION_REQUIRED(403),

    /** 服务账号不存在或已停用（Token 仍在有效期内，但归属账号已不能代表机器身份）。 */
    ACCOUNT_DISABLED(403),

    /** 超出每 Token 的 QPS 上限（Redis 固定窗口）。 */
    RATE_LIMITED(429),

    /** 超出每 Token 的每日调用配额。 */
    DAILY_QUOTA_EXCEEDED(429),

    /** 限流依赖（Redis）不可用：外部面 fail-closed，宁可拒绝也不放行。 */
    LIMITER_UNAVAILABLE(503),

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
