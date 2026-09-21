package com.guarantee.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置，前缀 {@code guarantee.auth.jwt}。
 */
@ConfigurationProperties(prefix = "guarantee.auth.jwt")
public class JwtProperties {

    /** HMAC 签名密钥，长度必须 >= 32 字节（HS256 要求 256 bit）。 */
    private String secret = "guarantee-ai-admin-local-dev-secret-key-please-change-in-production";

    /** 令牌有效期（分钟）。 */
    private long expireMinutes = 720;

    /** 签发者。 */
    private String issuer = "guarantee-ai-admin";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpireMinutes() {
        return expireMinutes;
    }

    public void setExpireMinutes(long expireMinutes) {
        this.expireMinutes = expireMinutes;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
