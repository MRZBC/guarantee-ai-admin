package com.guarantee.web;

import com.guarantee.auth.config.JwtProperties;
import com.guarantee.common.api.ResultCode;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.service.UserService;
import com.guarantee.web.support.ApiClient;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * Redis 不可用时的 fail-closed 行为（AUTH-02，AC-47 / AC-48 / AC-49）。
 *
 * <p><b>为什么替换 Bean 而不是把 Redis 停掉</b>：真把 Redis 弄挂会同时影响同一个 Maven 反应堆里
 * 其它测试类与开发环境。用一个"所有操作都抛连接异常"的 {@code StringRedisTemplate} 替身，
 * 效果等价（这才是应用实际看到的形态），但作用域仅限本上下文。</p>
 *
 * <p><b>fail-open 那一半不在这里</b>：它只改变"故障时返回 true 还是 false"这一处分支，
 * 由 {@code TokenRevocationServiceFailureModeTest} 直接覆盖，不必再起一个完整上下文。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000",
                // 显式声明，避免将来默认值变化让本类悄悄失去意义
                "guarantee.auth.revocation.failure-mode=fail-closed"
        })
class RevocationFailClosedIT {

    private static final String ADMIN_PASSWORD = "Admin@123";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private UserService userService;

    /** 替身：所有 Redis 操作都表现为"连不上"。 */
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    private ApiClient client;

    @BeforeEach
    void setUp() {
        client = new ApiClient(port, objectMapper);
        assumeTrue(userService.getEntityByUsername("admin") != null,
                "演示账号 admin 不存在，跳过（依赖演示数据）");
    }

    // ==================================================================
    // AC-48：登记失败必须让登录失败（否则会出现"登录成功但每个请求都 401"的死循环）
    // ==================================================================

    @Test
    @DisplayName("AC-48 fail-closed + Redis 不可用 → 登录返回 1005（认证服务不可用），绝不返回成功")
    void loginFailsWithAuthUnavailableInsteadOfSucceeding() {
        given(redisTemplate.opsForValue())
                .willThrow(new RedisConnectionFailureException("simulated outage"));

        ApiClient.Exchange response = client.loginRaw("admin", ADMIN_PASSWORD, java.util.Map.of());

        assertThat(response.code())
                .as("若这里返回 0（登录成功），用户随后每个请求都会 401，"
                        + "且重新登录也无法自救——前端还会陷入 401 → 跳登录页 → reload 的循环")
                .isEqualTo(ResultCode.AUTH_UNAVAILABLE.code())
                .isNotEqualTo(ResultCode.SUCCESS.code());
        assertThat(response.message()).contains("认证服务");
    }

    // ==================================================================
    // AC-47：读路径也必须拒绝，而不是静默退化为"只验签名"
    // ==================================================================

    @Test
    @DisplayName("AC-47 fail-closed + Redis 不可用 → 签名合法且未过期的令牌同样被拒为 401")
    void validLookingTokenIsRejectedWhenWhitelistIsUnreachable() {
        given(redisTemplate.getExpire(anyString(), any(TimeUnit.class)))
                .willThrow(new RedisConnectionFailureException("simulated outage"));
        given(redisTemplate.hasKey(anyString()))
                .willThrow(new RedisConnectionFailureException("simulated outage"));

        String token = mintValidToken(userService.getEntityByUsername("admin"));

        assertThat(client.get("/api/auth/me", token).status())
                .as("故障态不能退化为「只验签名」——那会让所有已登出、已踢出、"
                        + "因角色变更而应失效的令牌全部复活，而系统看起来一切正常")
                .isEqualTo(401);
    }

    // ==================================================================
    // AC-49：降级必须可见（否则运维只看到"大家说登录不上"，定位不到根因）
    // ==================================================================

    @Test
    @DisplayName("AC-49 Redis 不可用时 /actuator/health 变为 DOWN，且不泄漏连接信息")
    void healthReportsDownWithoutLeakingConnectionDetails() {
        given(redisTemplate.hasKey(anyString()))
                .willThrow(new RedisConnectionFailureException("simulated outage"));

        ApiClient.Exchange health = client.get("/actuator/health", null);

        assertThat(String.valueOf(health.body().get("status")))
                .as("fail-closed 让 Redis 成为鉴权链路的强依赖，这个降级必须能被监控看到")
                .isEqualTo("DOWN");
        assertThat(String.valueOf(health.body()))
                .as("未认证的调用者不应从健康端点读到 Redis 地址、端口或凭据")
                .doesNotContain("simulated outage")
                .doesNotContain("redis://")
                .doesNotContain("6379");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 签名合法、未过期的令牌——用来证明"拒绝"来自白名单不可达，而不是令牌本身有问题。 */
    private String mintValidToken(SysUser user) {
        SecretKey key = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getUsername())
                .issuer(jwtProperties.getIssuer())
                .claim("uid", user.getId())
                .claim("name", user.getRealName())
                .claim("roles", List.of("ADMIN"))
                .claim("perms", List.of())
                .issuedAt(new Date(now))
                .expiration(new Date(now + 3_600_000L))
                .signWith(key)
                .compact();
    }
}
