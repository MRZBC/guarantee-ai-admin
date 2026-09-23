package com.guarantee.auth.security;

import com.guarantee.auth.config.JwtProperties;
import com.guarantee.auth.config.RevocationProperties;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 令牌白名单的故障策略与空闲续期（AUTH-02 / AUTH-04，TEST-29 / TEST-30 / TEST-32/33 的逻辑部分）。
 *
 * <p>用 Mockito 而不是真实 Redis：本类要验证的恰恰是"Redis 不可用时怎么办"，
 * 而把真 Redis 弄挂比替换一个 Bean 危险得多。</p>
 */
@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceFailureModeTest {

    private static final String JTI = "test-jti-0001";
    private static final String KEY = "guarantee:auth:token:" + JTI;

    /** 与 {@link #service} 里配置的空闲窗口保持一致。 */
    private static final long IDLE_SECONDS = 240L * 60L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    // ==================================================================
    // 基本校验
    // ==================================================================

    @Test
    @DisplayName("白名单里没有该 jti → 无效（-2 表示 key 不存在：已登出 / 已踢出 / 空闲超时）")
    void missingKeyIsInvalid() {
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(-2L);

        TokenRevocationService.Validation result = service(RevocationProperties.FailureMode.FAIL_CLOSED)
                .validate(JTI, 3600);

        assertThat(result.active()).isFalse();
        assertThat(result.renewed()).isFalse();
    }

    @Test
    @DisplayName("剩余时间充裕 → 有效且不写 Redis（半窗节流，避免每请求一次写）")
    void doesNotRenewWhenPlentyOfIdleTimeRemains() {
        long idle = IDLE_SECONDS;
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(idle * 3 / 4);

        TokenRevocationService.Validation result = service(RevocationProperties.FailureMode.FAIL_CLOSED)
                .validate(JTI, 100_000);

        assertThat(result.active()).isTrue();
        assertThat(result.renewed()).isFalse();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("剩余时间低于半窗 → 有效并续期到完整空闲窗口")
    void renewsWhenBelowHalfWindow() {
        long idle = IDLE_SECONDS;
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(idle / 4);

        TokenRevocationService.Validation result = service(RevocationProperties.FailureMode.FAIL_CLOSED)
                .validate(JTI, 100_000);

        assertThat(result.active()).isTrue();
        assertThat(result.renewed()).isTrue();
        assertThat(result.renewedTtlSeconds())
                .as("有请求就顺延到完整空闲窗口")
                .isEqualTo(idle);
        verify(redisTemplate).expire(KEY, Duration.ofSeconds(idle));
    }

    @Test
    @DisplayName("续期不得越过绝对上限：距 exp 只剩 30 秒时，TTL 只能续到 30 秒")
    void renewalNeverExceedsAbsoluteCeiling() {
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(1L);

        TokenRevocationService.Validation result = service(RevocationProperties.FailureMode.FAIL_CLOSED)
                .validate(JTI, 30);

        assertThat(result.renewedTtlSeconds())
                .as("否则活跃用户可以把会话无限续下去，绝对上限形同虚设")
                .isEqualTo(30);
        verify(redisTemplate).expire(KEY, Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("续期写失败不把用户踢下线：令牌本身仍然有效，只是窗口没顺延")
    void renewalWriteFailureDoesNotInvalidateToken() {
        long idle = IDLE_SECONDS;
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(1L);
        when(redisTemplate.expire(KEY, Duration.ofSeconds(idle)))
                .thenThrow(new RedisConnectionFailureException("down"));

        TokenRevocationService.Validation result = service(RevocationProperties.FailureMode.FAIL_CLOSED)
                .validate(JTI, 100_000);

        assertThat(result.active())
                .as("一次瞬时的 Redis 写失败不应该让在线用户掉线")
                .isTrue();
        assertThat(result.renewed()).isFalse();
    }

    // ==================================================================
    // 读路径的故障策略（AUTH-02）
    // ==================================================================

    @Test
    @DisplayName("fail-closed + Redis 读失败 → 判为无效（故障态不退化为只验签名）")
    void failClosedMakesReadFailureInvalid() {
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service(RevocationProperties.FailureMode.FAIL_CLOSED).validate(JTI, 3600).active())
                .as("已登出/已踢出的令牌绝不能因为缓存故障而复活")
                .isFalse();
    }

    @Test
    @DisplayName("fail-open + Redis 读失败 → 放行（可用性优先，仅限应急）")
    void failOpenMakesReadFailureValid() {
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service(RevocationProperties.FailureMode.FAIL_OPEN).validate(JTI, 3600).active())
                .isTrue();
    }

    @Test
    @DisplayName("isActive 在 fail-closed 下 Redis 故障时返回 false")
    void isActiveHonoursFailClosed() {
        when(redisTemplate.hasKey(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service(RevocationProperties.FailureMode.FAIL_CLOSED).isActive(JTI)).isFalse();
        assertThat(service(RevocationProperties.FailureMode.FAIL_OPEN).isActive(JTI)).isTrue();
    }

    // ==================================================================
    // 写路径：register（AUTH-02 §4.2.4，本方案最容易漏掉的一处）
    // ==================================================================

    @Test
    @DisplayName("fail-closed + 登记失败 → 登录必须失败（AUTH_UNAVAILABLE），不能返回成功")
    void failClosedMakesRegisterFailureFailLogin() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        BizException thrown = catchThrowableOfType(
                () -> service(RevocationProperties.FailureMode.FAIL_CLOSED).register(JTI, 1L, 3600),
                BizException.class);
        assertThat(thrown.getCode())
                .as("未登记的令牌在 fail-closed 下等同于无效令牌。若这里静默成功，"
                        + "用户会陷入「登录成功但每个请求都 401」且无法自救的死循环；"
                        + "必须是 AUTH_UNAVAILABLE(1005)，前端才不会误报成「密码错误」")
                .isEqualTo(ResultCode.AUTH_UNAVAILABLE.code());
    }

    @Test
    @DisplayName("fail-open + 登记失败 → 只记日志，登录继续")
    void failOpenAllowsRegisterFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> service(RevocationProperties.FailureMode.FAIL_OPEN)
                .register(JTI, 1L, 3600))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("register 写入的是空闲窗口 TTL，不是绝对上限")
    void registerUsesIdleTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service(RevocationProperties.FailureMode.FAIL_CLOSED).register(JTI, 7L, 240L * 60L);

        verify(valueOperations).set(KEY, "7", Duration.ofSeconds(240L * 60L));
    }

    @Test
    @DisplayName("已登出（revoke）后校验必然无效")
    void revokedTokenIsInvalid() {
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(-2L);
        TokenRevocationService service = service(RevocationProperties.FailureMode.FAIL_CLOSED);

        service.revoke(JTI);

        verify(redisTemplate).delete(KEY);
        assertThat(service.validate(JTI, 3600).active()).isFalse();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private TokenRevocationService service(RevocationProperties.FailureMode mode) {
        RevocationProperties revocationProperties = new RevocationProperties();
        revocationProperties.setFailureMode(mode);

        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("a-very-long-random-secret-value-for-testing-0123456789");
        jwtProperties.setRejectKnownDefault(false);
        jwtProperties.setIdleTimeoutMinutes(240);
        jwtProperties.setAbsoluteExpireMinutes(720);

        return new TokenRevocationService(redisTemplate, revocationProperties,
                new JwtTokenProvider(jwtProperties));
    }
}
