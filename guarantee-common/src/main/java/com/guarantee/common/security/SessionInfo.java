package com.guarantee.common.security;

import java.time.Instant;

/**
 * 一条在线会话（AUTH-05）。
 *
 * <p>{@code jti} 是会话标识，来自 JWT 的 {@code jti} claim。**允许前端可见**：
 * 它本身不是凭据，无法据此构造出可通过签名校验的令牌。</p>
 *
 * @param jti               令牌 id（会话标识）
 * @param userId            归属用户
 * @param username          登录账号
 * @param realName          姓名
 * @param loginAt           登录时间（会话创建时间）
 * @param absoluteExpiresAt 绝对上限到期时间（登录时固定，写入 JWT 的 {@code exp}）
 * @param idleExpiresAt     空闲到期时间（有请求即顺延）；读取失败时为 {@code null}
 * @param loginIp           登录来源 IP
 * @param userAgent         登录时的 User-Agent
 */
public record SessionInfo(
        String jti,
        Long userId,
        String username,
        String realName,
        Instant loginAt,
        Instant absoluteExpiresAt,
        Instant idleExpiresAt,
        String loginIp,
        String userAgent) {
}
