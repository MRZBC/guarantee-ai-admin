package com.guarantee.auth.security;

import com.guarantee.auth.config.JwtProperties;
import com.guarantee.system.entity.SysUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT 签发与校验。
 *
 * <p>权限编码直接写入令牌，避免每个请求都回查数据库；
 * 令牌失效通过 jti + Redis 撤销列表控制（见 {@link TokenRevocationService}）。</p>
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_REAL_NAME = "name";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMISSIONS = "perms";

    private final JwtProperties properties;
    private final SecretKey secretKey;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        byte[] keyBytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "guarantee.auth.jwt.secret 长度不足 32 字节，无法用于 HS256 签名");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public long getExpireSeconds() {
        return properties.getExpireMinutes() * 60;
    }

    /**
     * 签发结果：令牌本体 + jti + 过期时间（登录时用于登记撤销列表）。
     */
    public record IssuedToken(String token, String tokenId, Date expiresAt) {
    }

    /**
     * 签发令牌。
     */
    public IssuedToken createToken(SysUser user, List<String> roles, List<String> permissions) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + properties.getExpireMinutes() * 60_000);
        String tokenId = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(tokenId)
                .subject(user.getUsername())
                .issuer(properties.getIssuer())
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_REAL_NAME, user.getRealName())
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_PERMISSIONS, permissions)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
        return new IssuedToken(token, tokenId, expiry);
    }

    /**
     * 解析并校验令牌。
     *
     * @throws JwtException 令牌非法或已过期
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static Long userId(Claims claims) {
        Object value = claims.get(CLAIM_USER_ID);
        return value instanceof Number number ? number.longValue() : null;
    }

    public static String realName(Claims claims) {
        Object value = claims.get(CLAIM_REAL_NAME);
        return value == null ? null : value.toString();
    }

    public static String tokenId(Claims claims) {
        return claims.getId();
    }

    public static Date expiration(Claims claims) {
        return claims.getExpiration();
    }

    /** 令牌签发时间（毫秒）；缺失时返回 0，由调用方按"最保守"处理。 */
    public static long issuedAtMillis(Claims claims) {
        Date issuedAt = claims.getIssuedAt();
        return issuedAt == null ? 0L : issuedAt.getTime();
    }

    @SuppressWarnings("unchecked")
    public static List<String> roles(Claims claims) {
        Object value = claims.get(CLAIM_ROLES);
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<String> permissions(Claims claims) {
        Object value = claims.get(CLAIM_PERMISSIONS);
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
