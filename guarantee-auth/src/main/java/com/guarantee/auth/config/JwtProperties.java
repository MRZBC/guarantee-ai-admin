package com.guarantee.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置，前缀 {@code guarantee.auth.jwt}。
 *
 * <p><b>令牌有两个独立的时间边界</b>（AUTH-04）：</p>
 * <ul>
 *   <li>{@link #absoluteExpireMinutes} —— 写进 JWT 的 {@code exp}，自登录起算的**绝对上限**，
 *       无论多活跃都不会超过；</li>
 *   <li>{@link #idleTimeoutMinutes} —— Redis 白名单（{@code guarantee:auth:token:{jti}}）的 TTL，
 *       即**空闲超时**，有请求就续期。</li>
 * </ul>
 *
 * <p>两者相等时退化为"固定有效期"（即 AUTH-04 之前的行为）。</p>
 *
 * <p>{@code ignoreUnknownFields = false}：与 {@link LoginGuardProperties} 同一策略——
 * 安全相关配置的键名写错必须启动失败，而不是被静默忽略后沿用默认值。</p>
 */
@ConfigurationProperties(prefix = "guarantee.auth.jwt", ignoreUnknownFields = false)
public class JwtProperties {

    /**
     * 内置的本地开发密钥。
     *
     * <p>它是**公开在代码仓库里**的，任何人都能据此伪造任意用户的令牌（含 ADMIN）。
     * 因此 {@link #rejectKnownDefault} 为 true 时，一旦检测到它就直接拒绝启动。</p>
     */
    public static final String DEFAULT_DEV_SECRET =
            "guarantee-ai-admin-local-dev-secret-key-please-change-in-production";

    /** HMAC 签名密钥，长度必须 >= 32 字节（HS256 要求 256 bit）。 */
    private String secret = DEFAULT_DEV_SECRET;

    /** 令牌绝对有效期（分钟）。写入 JWT 的 {@code exp}。 */
    private long absoluteExpireMinutes = 720;

    /** 空闲超时（分钟）。作为 Redis 白名单的 TTL，有请求则续期。 */
    private long idleTimeoutMinutes = 240;

    /**
     * 是否拒绝内置默认密钥。
     *
     * <p>本地开发（{@code application.yml}）显式置为 false 以便零配置启动；
     * 生产（{@code application-prod.yml}）置为 true，防止"忘记设置 JWT_SECRET"时
     * 用一个公开密钥给所有令牌签名。</p>
     */
    private boolean rejectKnownDefault = true;

    /** 签发者。 */
    private String issuer = "guarantee-ai-admin";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAbsoluteExpireMinutes() {
        return absoluteExpireMinutes;
    }

    public void setAbsoluteExpireMinutes(long absoluteExpireMinutes) {
        this.absoluteExpireMinutes = absoluteExpireMinutes;
    }

    /**
     * 兼容旧配置名 {@code expire-minutes}（AUTH-04 已改名为 {@code absolute-expire-minutes}）。
     *
     * <p><b>为什么要显式抛异常而不是静默忽略</b>：Spring Boot 对未知属性默认不报错，
     * 若此处不实现该 setter，运维在 yml 里写 {@code expire-minutes: 120} 不会得到任何反馈，
     * 令牌却仍然按 720 分钟有效——这是一个"配置看似生效、实际完全没生效"的静默故障。
     * 宁可启动失败，也不要让人以为改成功了（RK-22）。</p>
     */
    @Deprecated
    public void setExpireMinutes(long expireMinutes) {
        throw new IllegalStateException(
                "配置项 guarantee.auth.jwt.expire-minutes 已更名为 absolute-expire-minutes（AUTH-04）。"
                        + "请把它改为 absolute-expire-minutes，"
                        + "并按需新增 idle-timeout-minutes 控制空闲超时。");
    }

    public long getIdleTimeoutMinutes() {
        return idleTimeoutMinutes;
    }

    public void setIdleTimeoutMinutes(long idleTimeoutMinutes) {
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    public boolean isRejectKnownDefault() {
        return rejectKnownDefault;
    }

    public void setRejectKnownDefault(boolean rejectKnownDefault) {
        this.rejectKnownDefault = rejectKnownDefault;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
