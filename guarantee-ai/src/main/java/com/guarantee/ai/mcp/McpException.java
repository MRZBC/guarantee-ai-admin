package com.guarantee.ai.mcp;

/**
 * 业务 MCP 的统一异常。
 *
 * <p>可读信息是给**外部 Agent 与它的使用者**看的：既要说清"为什么失败"，
 * 也不能泄漏平台内部细节（不回显 Token、不回显哈希、不带 SQL）。</p>
 */
public class McpException extends RuntimeException {

    private final McpErrorCode code;

    public McpException(McpErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public McpException(McpErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public McpErrorCode getCode() {
        return code;
    }

    /** 建议的 HTTP 状态码（控制器可直接用）。 */
    public int httpStatus() {
        return code.httpStatus();
    }
}
