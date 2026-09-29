package com.guarantee.ai.mcp;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 机器凭据（{@code ai_mcp_token}）。
 *
 * <p><b>明文永不入库</b>：本表只存 {@code token_hash}（SHA-256 十六进制）与
 * {@code token_prefix}（明文前若干字符，仅供页面展示与人工核对）。明文只在
 * {@link McpTokenService#issue} 返回一次，之后任何接口（含列表）都拿不到它。</p>
 *
 * <p><b>为什么是 SHA-256 而不是 BCrypt</b>：鉴权要按哈希**反查**行
 * （{@code WHERE token_hash = ?}），BCrypt 每次加盐结果不同、无法反查；
 * 而 Token 本身是 256 位 {@code SecureRandom}，暴力枚举不可行，因此快速哈希在这里
 * 是正确选择（密码才必须用慢哈希 + 随机盐）。</p>
 *
 * <p>失效有两套并存、互不依赖的机制：{@code revoked_at}（显式撤销，即时生效）
 * 与逻辑删除三列（行级下架）。两者都非空时以 {@code revoked_at} 优先报错。</p>
 */
@Data
public class McpToken {

    private Long id;

    /** 服务账号（{@code sys_user.id}，{@code account_type=SERVICE}）。 */
    private Long serviceAccountId;

    /** 明文前缀（如 {@code mcp_9f3c2a}），仅用于展示与核对；**不是**凭据的一部分。 */
    private String tokenPrefix;

    /** SHA-256(明文) 小写十六进制，64 字符。 */
    private String tokenHash;

    /** 权限范围，逗号分隔（例如 {@code ai:mcp:read,system:order:tender:view}）。 */
    private String permissions;

    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;

    /** 显式撤销时间；非空即不可用（与逻辑删除相互独立）。 */
    private LocalDateTime revokedAt;
    private String revokedBy;

    private String createdBy;
    private LocalDateTime createdAt;

    private Integer isDeleted;
    private LocalDateTime deletedAt;
    private String deletedBy;
}
