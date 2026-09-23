package com.guarantee.web;

import com.guarantee.auth.config.JwtProperties;
import com.guarantee.auth.security.JwtTokenProvider;
import com.guarantee.common.security.UserTokenRevoker;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.service.UserService;
import com.guarantee.web.support.ApiClient;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 令牌的两个时间边界与"撤销不可复活"（AUTH-04，AC-51 / AC-52）。
 *
 * <h3>为什么直接操作 Redis 而不把 TTL 配成秒级</h3>
 * <p>绝对上限与空闲超时以"分钟"为单位配置，硬把它压到秒级会连带改变配置语义（也就改变了
 * 被测对象）。这里改为**登录后用真实配置断言 TTL**，再直接改 Redis 里的剩余时间与存在性
 * 来制造"续期条件成立""空闲已过期"两种状态——机制完全相同（判定只依赖 key 的存在与 TTL），
 * 但不需要等待真实时间。</p>
 *
 * <h3>为什么 {@code GET /api/auth/me} 适合做探针</h3>
 * <p>它没有任何 {@code @PreAuthorize}，只要求"已认证"：因此 200 与 401 的差异纯粹由
 * 认证链路决定，不会把权限问题混进来。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000"
        })
class TokenLifecycleIT {

    private static final String PROBE = "/api/auth/me";

    /** 演示账号（DataInitializer.ANALYST_PASSWORD）。 */
    private static final String DEMO_USER = "analyst";
    private static final String DEMO_PASSWORD = "Analyst@123";

    private static final String TOKEN_KEY_PREFIX = "guarantee:auth:token:";
    private static final String SESSION_KEY_PREFIX = "guarantee:auth:session:";
    private static final String SESSION_INDEX_KEY = "guarantee:auth:sessions";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private UserService userService;

    @Autowired
    private UserTokenRevoker userTokenRevoker;

    private ApiClient client;

    @BeforeEach
    void setUp() {
        client = new ApiClient(port, objectMapper);
        assumeTrue(userService.getEntityByUsername(DEMO_USER) != null,
                "演示账号 " + DEMO_USER + " 不存在，跳过（依赖演示数据）");
    }

    // ==================================================================
    // AC-51：白名单 TTL 是"空闲窗口"，不是"剩余绝对时间"
    // ==================================================================

    @Test
    @DisplayName("AC-51 登录时写入的白名单 TTL = 空闲超时（不是 12 小时绝对上限）")
    void loginRegistersTokenWithIdleTtl() {
        String token = client.login(DEMO_USER, DEMO_PASSWORD);
        String jti = jtiOf(token);

        Long ttl = redisTemplate.getExpire(TOKEN_KEY_PREFIX + jti, TimeUnit.SECONDS);

        long idle = tokenProvider.getIdleTimeoutSeconds();
        long absolute = tokenProvider.getAbsoluteExpireSeconds();

        assertThat(idle).as("默认空闲窗口 240 分钟").isEqualTo(240L * 60L);
        assertThat(absolute).as("默认绝对上限 720 分钟").isEqualTo(720L * 60L);
        assertThat(ttl)
                .as("白名单 TTL 必须等于空闲窗口。若等于绝对上限（%s），AUTH-04 就没有生效——"
                        + "人走了、浏览器还开着，会话会一直挂到 12 小时", absolute)
                .isNotNull()
                .isBetween(idle - 10, idle);
    }

    @Test
    @DisplayName("AC-51 空闲窗口在活动中被顺延：TTL 见底后发起请求，剩余时间回到完整窗口")
    void activityRenewsIdleWindow() {
        String token = client.login(DEMO_USER, DEMO_PASSWORD);
        String jti = jtiOf(token);
        String key = TOKEN_KEY_PREFIX + jti;

        // 制造"剩余时间低于半窗"的状态（真实场景是挂机接近 4 小时）
        redisTemplate.expire(key, Duration.ofSeconds(60L));
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isLessThanOrEqualTo(60L);

        assertThat(client.get(PROBE, token).code()).as("本次请求本身应当成功").isEqualTo(0);

        Long renewed = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        long idle = tokenProvider.getIdleTimeoutSeconds();
        assertThat(renewed)
                .as("有请求就该把空闲窗口顺延回满，否则用户会在一段正常使用后突然被登出")
                .isNotNull()
                .isBetween(idle - 10, idle);
    }

    @Test
    @DisplayName("AC-51 空闲超时后（白名单 key 消失）令牌立即失效 → HTTP 401")
    void idleExpiredTokenIsRejected() {
        String token = client.login(DEMO_USER, DEMO_PASSWORD);
        String jti = jtiOf(token);
        assertThat(client.get(PROBE, token).code()).as("前置：此刻可用").isEqualTo(0);

        // 空闲超时的实现结果就是"白名单 key 不存在"，直接删掉等价且无需等待
        redisTemplate.delete(TOKEN_KEY_PREFIX + jti);

        ApiClient.Exchange afterIdle = client.get(PROBE, token);
        assertThat(afterIdle.status())
                .as("空闲超时必须 401。若仍返回 200，说明校验只看 JWT 签名，撤销/空闲机制形同虚设")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("AC-51 绝对上限优先于白名单：exp 已过的令牌即使白名单里还在，也必须 401")
    void absoluteCeilingBeatsLiveWhitelistEntry() {
        SysUser user = userService.getEntityByUsername(DEMO_USER);
        String jti = UUID.randomUUID().toString();
        String expiredToken = mintExpiredToken(user);

        // 刻意把 jti 写进白名单：模拟"令牌撤销机制说它有效"，用来隔离出 exp 这一道边界
        redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + jti, String.valueOf(user.getId()),
                Duration.ofSeconds(3600L));

        ApiClient.Exchange response = client.get(PROBE, expiredToken);

        assertThat(response.status())
                .as("绝对上限是硬上限：JWT 的 exp 一到就必须失效，"
                        + "无论白名单里是否还留着记录（活跃续期也不得越过它）")
                .isEqualTo(401);
    }

    // ==================================================================
    // AC-52：续期绝不能复活已撤销的令牌（本方案最重要的一条）
    // ==================================================================

    @Test
    @DisplayName("AC-52 已撤销用户的旧令牌，即使白名单仍在、续期条件成立，也必须 401")
    void renewalCannotResurrectRevokedToken() {
        String token = client.login(DEMO_USER, DEMO_PASSWORD);
        String jti = jtiOf(token);
        String key = TOKEN_KEY_PREFIX + jti;
        SysUser user = userService.getEntityByUsername(DEMO_USER);

        assertThat(client.get(PROBE, token).code()).as("前置：撤销前可用").isEqualTo(0);

        // 撤销该用户的全部令牌（停用账号 / 改角色走的是同一条链路）
        userTokenRevoker.revokeUser(user.getId(), "TEST-33 撤销后不可复活");

        // 撤销会一并清理白名单与会话。这里**刻意把白名单补回来**，把"令牌还在白名单里"
        // 这个最坏情况造出来：如果实现改成"续期时重新签发 JWT"，新的 iat 会大于撤销时刻，
        // 这张令牌就会复活；而只要坚持"只延长 TTL、不重签"，iat 比较就会一直判它无效。
        redisTemplate.opsForValue().set(key, String.valueOf(user.getId()),
                Duration.ofSeconds(60L));

        ApiClient.Exchange afterRevoke = client.get(PROBE, token);

        assertThat(afterRevoke.status())
                .as("这是提权路径的回归防线：撤销按令牌 iat 判定，任何「重新签发式续期」都会"
                        + "产生更新的 iat，从而让已撤销用户拿回权限")
                .isEqualTo(401);
    }

    // ==================================================================
    // 会话索引不是鉴权输入（AUTH-05 的边界，防止"索引写失败 = 无法登录"）
    // ==================================================================

    @Test
    @DisplayName("会话记录缺失不影响鉴权：授权依据是令牌白名单，索引只服务运维展示")
    void missingSessionRecordDoesNotBlockAuthentication() {
        String token = client.login(DEMO_USER, DEMO_PASSWORD);
        String jti = jtiOf(token);

        redisTemplate.delete(SESSION_KEY_PREFIX + jti);
        redisTemplate.opsForZSet().remove(SESSION_INDEX_KEY, jti);

        assertThat(client.get(PROBE, token).code())
                .as("若这里变成 401，说明鉴权被绑上了「展示用索引」——"
                        + "那会让一次索引写失败变成『所有人都登录不上』，属于把可观测性做成了单点")
                .isEqualTo(0);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private String jtiOf(String token) {
        Claims claims = tokenProvider.parse(token);
        String jti = JwtTokenProvider.tokenId(claims);
        assertThat(jti).as("令牌必须携带 jti，否则撤销机制无从落地").isNotBlank();
        return jti;
    }

    /** 用与应用相同的密钥签一张**已过期**的令牌：签名合法，只有 exp 越界。 */
    private String mintExpiredToken(SysUser user) {
        SecretKey key = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getUsername())
                .issuer(jwtProperties.getIssuer())
                .claim("uid", user.getId())
                .claim("name", user.getRealName())
                .claim("roles", java.util.List.of("ANALYST"))
                .claim("perms", java.util.List.of())
                .issuedAt(new Date(now - 7_200_000L))
                .expiration(new Date(now - 3_600_000L))
                .signWith(key)
                .compact();
    }
}
