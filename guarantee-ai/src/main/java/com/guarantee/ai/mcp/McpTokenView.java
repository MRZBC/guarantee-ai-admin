package com.guarantee.ai.mcp;

import java.time.LocalDateTime;

/**
 * 凭据列表视图——**刻意不包含** {@code tokenHash} 与明文。
 *
 * <p>为什么连哈希也不给：哈希虽然不是明文，但它是"可用凭据的等值判据"，
 * 泄漏后无法被使用者察觉；而列表接口只需要回答"有哪些凭据、谁的、什么时候到期、
 * 有没有被撤销、上次用是什么时候"。</p>
 */
public record McpTokenView(Long id,
                           Long serviceAccountId,
                           String tokenPrefix,
                           String permissions,
                           LocalDateTime expiresAt,
                           LocalDateTime lastUsedAt,
                           LocalDateTime revokedAt,
                           String revokedBy,
                           String createdBy,
                           LocalDateTime createdAt) {

    /** 从实体投影：**只搬安全字段**，新增字段时必须显式决定是否展示。 */
    public static McpTokenView from(McpToken token) {
        if (token == null) {
            return null;
        }
        return new McpTokenView(
                token.getId(),
                token.getServiceAccountId(),
                token.getTokenPrefix(),
                token.getPermissions(),
                token.getExpiresAt(),
                token.getLastUsedAt(),
                token.getRevokedAt(),
                token.getRevokedBy(),
                token.getCreatedBy(),
                token.getCreatedAt());
    }
}
