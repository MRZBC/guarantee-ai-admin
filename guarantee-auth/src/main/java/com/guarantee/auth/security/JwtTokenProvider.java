package com.guarantee.auth.security;

import com.guarantee.auth.config.JwtProperties;
import com.guarantee.system.entity.SysUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JWT 签发与校验。
 *
 * <p>权限编码直接写入令牌，避免每个请求都回查数据库；
 * 令牌失效通过 jti + Redis 撤销列表控制（见 {@link TokenRevocationService}）。</p>
 *
 * <p><b>密钥守卫（AUTH-03）</b>：构造时拒绝内置默认密钥与已知弱密钥，
 * 并在启动日志打印密钥**指纹**（不是密钥本身），便于多实例部署时核对各实例是否用了同一把密钥。</p>
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_REAL_NAME = "name";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMISSIONS = "perms";

    /** HS256 要求的最小密钥长度（256 bit）。 */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 已知弱密钥（小写比较）。
     *
     * <p>刻意保持极小：这里只拦"一眼就知道是占位符"的值，不做密码强度评分——
     * 那属于密码策略，不在本期范围（N-4）。</p>
     */
    private static final Set<String> KNOWN_WEAK_SECRETS = Set.of(
            "secret", "changeme", "change-me", "123456", "password", "jwt-secret");

    private final JwtProperties properties;
    private final SecretKey secretKey;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        String secret = properties.getSecret();
        validateSecret(secret, properties.isRejectKnownDefault());
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        // 只打印指纹：多实例部署时最常见的密钥事故是"部分实例用环境变量、部分用默认值"，
        // 表现为令牌随机失效且极难定位。指纹让这种不一致一眼可见，同时不泄漏密钥。
        log.info("JWT 密钥已加载 指纹={} 长度={} 字节 绝对有效期={} 分钟 空闲超时={} 分钟",
                fingerprint(secret), keyBytes.length,
                properties.getAbsoluteExpireMinutes(), properties.getIdleTimeoutMinutes());
    }

    /**
     * 密钥校验（AUTH-03）。
     *
     * @param secret             配置的密钥
     * @param rejectKnownDefault 是否拒绝内置默认密钥（本地开发为 false）
     */
    private static void validateSecret(String secret, boolean rejectKnownDefault) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "guarantee.auth.jwt.secret 未配置。请在部署环境中设置 JWT_SECRET 环境变量。");
        }
        // 占位符未被解析：application-prod.yml 里写的是 secret: ${JWT_SECRET}，而环境变量没设置。
        //
        // 必须单独识别并给出准确提示（实测结论）：Spring Boot 的 Binder 用的是
        // **忽略无法解析的占位符** 的 PropertyPlaceholderHelper —— 也就是说它**不会**
        // 因为 JWT_SECRET 缺失而报错，而是把字面量字符串 "${JWT_SECRET}"（13 个字符）
        // 原样交给这里。若不做这一步，运维看到的是"密钥长度不足 32 字节（当前 13）"，
        // 会去找一个长度为 13 的密钥，而真正的原因是环境变量根本没注入。
        if (secret.startsWith("${") && secret.endsWith("}")) {
            throw new IllegalStateException(
                    "guarantee.auth.jwt.secret 解析后仍是占位符字面量 " + secret
                            + "，说明环境变量 JWT_SECRET 未设置（Spring Boot 会原样保留无法解析的占位符，"
                            + "不会自行报错）。请执行：export JWT_SECRET=$(openssl rand -base64 48)");
        }
        if (rejectKnownDefault && JwtProperties.DEFAULT_DEV_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "检测到内置默认密钥（guarantee-ai-admin-local-dev-secret-key-please-change-in-production）！"
                            + "该密钥公开在代码仓库中，任何人都可据此伪造管理员令牌。"
                            + "请生成并注入真实密钥：export JWT_SECRET=$(openssl rand -base64 48) 。"
                            + "若确为本机开发，请在 application.yml 中显式设置 "
                            + "guarantee.auth.jwt.reject-known-default: false 。");
        }
        if (KNOWN_WEAK_SECRETS.contains(secret.trim().toLowerCase())) {
            throw new IllegalStateException(
                    "guarantee.auth.jwt.secret 是已知的弱密钥占位值，请更换为随机密钥："
                            + "export JWT_SECRET=$(openssl rand -base64 48)");
        }
        int length = secret.getBytes(StandardCharsets.UTF_8).length;
        if (length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "guarantee.auth.jwt.secret 长度不足 " + MIN_SECRET_BYTES + " 字节（当前 " + length
                            + "），无法用于 HS256 签名。请生成足够的随机密钥："
                            + "export JWT_SECRET=$(openssl rand -base64 48)");
        }
    }

    /** 密钥指纹：SHA-256 前 8 位十六进制。不可由指纹反推密钥。 */
    static String fingerprint(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法，不可能走到这里
            return "unknown";
        }
    }

    /** 令牌绝对有效期（秒）；写入 JWT 的 {@code exp}，也是登录响应里的 {@code expiresIn}。 */
    public long getAbsoluteExpireSeconds() {
        return properties.getAbsoluteExpireMinutes() * 60;
    }

    /** 空闲超时（秒）；Redis 白名单 TTL，有请求则续期（AUTH-04）。 */
    public long getIdleTimeoutSeconds() {
        return properties.getIdleTimeoutMinutes() * 60;
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
        Date expiry = new Date(now.getTime() + properties.getAbsoluteExpireMinutes() * 60_000);
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
