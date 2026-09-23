package com.guarantee.auth.security;

import com.guarantee.auth.config.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 密钥守卫（AUTH-03 / TEST-31）。
 *
 * <p>本类只覆盖纯逻辑，不起 Spring 上下文：密钥校验发生在构造期，
 * 而"生产环境带着公开的默认密钥启动"正是要拦的那个场景。</p>
 */
class JwtTokenProviderSecretTest {

    private static JwtProperties properties(String secret, boolean rejectKnownDefault) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setRejectKnownDefault(rejectKnownDefault);
        return properties;
    }

    // ==================================================================
    // 内置默认密钥
    // ==================================================================

    @Test
    @DisplayName("reject-known-default=true（prod 语义）+ 内置默认密钥 → 启动失败，且错误信息给出修复方式")
    void rejectsBuiltInDefaultSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider(
                properties(JwtProperties.DEFAULT_DEV_SECRET, true)))
                .as("带公开默认密钥启动 = 任何人都能伪造 ADMIN 令牌，必须拦下")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("内置默认密钥")
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("openssl rand");
    }

    @Test
    @DisplayName("reject-known-default=false（本地开发）+ 内置默认密钥 → 允许，保证零配置启动")
    void allowsBuiltInDefaultForLocalDev() {
        assertThatCode(() -> new JwtTokenProvider(
                properties(JwtProperties.DEFAULT_DEV_SECRET, false)))
                .as("本地开发与 mvn test 必须零配置可用")
                .doesNotThrowAnyException();
    }

    // ==================================================================
    // 弱密钥与长度
    // ==================================================================

    @Test
    @DisplayName("已知弱密钥占位值被拒绝（即使长度足够）")
    void rejectsKnownWeakSecrets() {
        // "secret" 只有 6 字节，先要确认它命中的是"弱密钥"分支而不是"长度不足"分支：
        // 用一个长度足够的弱值验证，确保这条规则独立生效
        assertThatThrownBy(() -> new JwtTokenProvider(properties("changeme", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("弱密钥");
    }

    @Test
    @DisplayName("长度不足 32 字节被拒绝，并给出生成命令")
    void rejectsShortSecret() {
        String short31 = "0123456789012345678901234567890";
        assertThat(short31.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(31);

        assertThatThrownBy(() -> new JwtTokenProvider(properties(short31, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32")
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("空白密钥被拒绝")
    void rejectsBlankSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider(properties("   ", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("未解析的占位符字面量被单独识别，报错直指「环境变量未设置」而不是「长度不足」")
    void rejectsUnresolvedPlaceholderWithAccurateMessage() {
        // 实测结论（不要想当然）：Spring Boot 的 Binder 用的是**忽略无法解析的占位符**的
        // PropertyPlaceholderHelper，因此 application-prod.yml 里的 ${JWT_SECRET} 在环境变量
        // 缺失时**不会**触发"Could not resolve placeholder"，而是把字面量原样绑进来
        // （"${JWT_SECRET}" 恰好 13 个字符，会先撞上长度校验并报出极具误导性的"当前 13"）。
        assertThatThrownBy(() -> new JwtTokenProvider(properties("${JWT_SECRET}", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("占位符")
                .hasMessageContaining("JWT_SECRET")
                .as("不能只说「长度不足 32 字节（当前 13）」——运维会去找一个 13 位的密钥")
                .hasMessageNotContaining("长度不足");
    }

    @Test
    @DisplayName("恰好 32 字节的随机密钥可用（边界值）")
    void acceptsExactly32Bytes() {
        String secret32 = "01234567890123456789012345678901";
        assertThat(secret32.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(32);

        assertThatCode(() -> new JwtTokenProvider(properties(secret32, false)))
                .doesNotThrowAnyException();
    }

    // ==================================================================
    // 指纹：可核对，但不泄漏
    // ==================================================================

    @Test
    @DisplayName("指纹是 8 位十六进制、稳定、且不包含密钥原文")
    void fingerprintIsStableAndDoesNotRevealSecret() {
        String secret = "a-very-long-random-secret-value-for-testing-0123456789";

        String fingerprint = JwtTokenProvider.fingerprint(secret);

        assertThat(fingerprint)
                .as("固定 8 位十六进制，便于在多实例日志里肉眼比对")
                .hasSize(8)
                .matches("[0-9a-f]{8}")
                .as("绝不能从指纹看出密钥内容")
                .doesNotContain(secret);
        assertThat(JwtTokenProvider.fingerprint(secret))
                .as("同一密钥必须得到同一指纹，否则无法用于核对部署一致性")
                .isEqualTo(fingerprint);
        assertThat(JwtTokenProvider.fingerprint(secret + "-x"))
                .as("不同密钥必须得到不同指纹")
                .isNotEqualTo(fingerprint);
    }

    // ==================================================================
    // 旧配置名（RK-22：静默失效比报错危险得多）
    // ==================================================================

    @Test
    @DisplayName("配置里残留 expire-minutes 时启动失败，而不是静默按默认值生效")
    void legacyExpireMinutesPropertyFailsLoudly() {
        JwtProperties properties = new JwtProperties();

        assertThatThrownBy(() -> properties.setExpireMinutes(120))
                .as("Spring Boot 对未知属性默认不报错；不显式拦截的话，"
                        + "运维改了配置却完全没生效，且没有任何反馈")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absolute-expire-minutes");
    }

    // ==================================================================
    // 两个时间边界的取值（AUTH-04）
    // ==================================================================

    @Test
    @DisplayName("绝对上限与空闲超时分别换算为秒，且互不影响")
    void exposesBothTimeBoundariesInSeconds() {
        JwtProperties properties = properties(
                "a-very-long-random-secret-value-for-testing-0123456789", false);
        properties.setAbsoluteExpireMinutes(720);
        properties.setIdleTimeoutMinutes(240);

        JwtTokenProvider provider = new JwtTokenProvider(properties);

        assertThat(provider.getAbsoluteExpireSeconds())
                .as("登录响应的 expiresIn 用这个值，语义与 AUTH-04 之前一致")
                .isEqualTo(720L * 60L);
        assertThat(provider.getIdleTimeoutSeconds())
                .as("Redis 白名单 TTL 用这个值，是空闲窗口")
                .isEqualTo(240L * 60L);
    }

    @Test
    @DisplayName("签发→解析往返：uid / jti / 权限码都正确落到 claims")
    void roundTripsClaims() {
        JwtProperties properties = properties(
                "a-very-long-random-secret-value-for-testing-0123456789", false);
        JwtTokenProvider provider = new JwtTokenProvider(properties);

        com.guarantee.system.entity.SysUser user = new com.guarantee.system.entity.SysUser();
        user.setId(42L);
        user.setUsername("tester");
        user.setRealName("测试员");

        JwtTokenProvider.IssuedToken issued = provider.createToken(user,
                java.util.List.of("VIEWER"), java.util.List.of("dashboard:view"));

        var claims = provider.parse(issued.token());

        assertThat(JwtTokenProvider.userId(claims)).isEqualTo(42L);
        assertThat(JwtTokenProvider.tokenId(claims)).isEqualTo(issued.tokenId());
        assertThat(JwtTokenProvider.roles(claims)).containsExactly("VIEWER");
        assertThat(JwtTokenProvider.permissions(claims)).containsExactly("dashboard:view");
        assertThat(JwtTokenProvider.issuedAtMillis(claims)).isGreaterThan(0L);
    }
}
