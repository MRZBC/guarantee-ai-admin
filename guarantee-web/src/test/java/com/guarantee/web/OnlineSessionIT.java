package com.guarantee.web;

import com.guarantee.auth.config.JwtProperties;
import com.guarantee.auth.security.JwtTokenProvider;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.security.SessionInfo;
import com.guarantee.common.security.SessionRegistry;
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
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 在线会话与强制下线（AUTH-05 / AUTH-06，AC-53 / AC-54 / AC-55）。
 *
 * <p>走真实 HTTP：会话列表的 {@code current} 标记依赖过滤器写入的请求属性，
 * 踢出依赖 Controller 的 {@code @PreAuthorize}——两者都只有在真实的请求链路里才会生效，
 * 直接调 Service 会把它们绕过。</p>
 *
 * <p><b>刻意不测"踢出某用户全部会话"</b>：那会把同一账号在其它测试里持有的令牌一并杀掉，
 * 让测试之间产生跨类耦合。{@code terminateAllForUsers} 由
 * {@code TokenLifecycleIT} 的撤销用例间接覆盖。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000"
        })
class OnlineSessionIT {

    private static final String PROBE = "/api/auth/me";
    private static final String SESSIONS = "/api/system/sessions";

    private static final String ADMIN_PASSWORD = "Admin@123";
    private static final String ANALYST_PASSWORD = "Analyst@123";
    private static final String OPERATOR_PASSWORD = "Operator@123";

    /** 一次取足，避免分页把目标会话切到第二页。 */
    private static final String ALL_PAGE = "?pageNum=1&pageSize=200";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private UserService userService;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ApiClient client;

    @BeforeEach
    void setUp() {
        client = new ApiClient(port, objectMapper);
        assumeTrue(userService.getEntityByUsername("admin") != null,
                "演示账号 admin 不存在，跳过（依赖演示数据）");
    }

    // ==================================================================
    // AC-53：登录即登记，且能认出"这就是我自己"
    // ==================================================================

    @Test
    @DisplayName("AC-53 登录后会话出现在列表中，字段完整，且自身那条被标记为 current")
    void loginRegistersSessionAndMarksCurrentOne() {
        String token = client.login("admin", ADMIN_PASSWORD);
        String jti = jtiOf(token);

        ApiClient.Exchange page = client.get(SESSIONS + ALL_PAGE, token);
        assertThat(page.code()).isEqualTo(ResultCode.SUCCESS.code());

        Map<String, Object> mine = find(page.pageList(), jti);
        assertThat(mine).as("刚登录的会话必须出现在列表里（jti=%s）", jti).isNotNull();
        assertThat(mine.get("username")).isEqualTo("admin");
        assertThat(mine.get("current"))
                .as("current 用于前端提示「你正在踢出自己」。它靠过滤器写入的请求属性判定，"
                        + "这也是本用例必须走真实 HTTP 的原因")
                .isEqualTo(Boolean.TRUE);
        assertThat(mine.get("loginAt")).isNotNull();
        assertThat(mine.get("loginIp")).isNotNull();
        assertThat(mine.get("idleExpiresAt")).as("空闲到期时间").isNotNull();
        assertThat(mine.get("absoluteExpiresAt")).as("绝对上限到期时间").isNotNull();

        // 两个边界必须都展示：只显示其中一个会让人误判会话还能用多久
        LocalDateTime idle = parseTime(mine.get("idleExpiresAt"));
        LocalDateTime absolute = parseTime(mine.get("absoluteExpiresAt"));
        assertThat(idle)
                .as("空闲窗口（240 分钟）必须早于绝对上限（720 分钟）")
                .isBefore(absolute);
    }

    @Test
    @DisplayName("AC-53 只有当前请求的那条会话被标记 current，其它会话不是")
    void onlyTheRequestingSessionIsMarkedCurrent() {
        String mine = client.login("admin", ADMIN_PASSWORD);
        String other = client.login("admin", ADMIN_PASSWORD);
        String mineJti = jtiOf(mine);
        String otherJti = jtiOf(other);
        assertThat(mineJti).as("两次登录必须是两条独立会话").isNotEqualTo(otherJti);

        ApiClient.Exchange page = client.get(SESSIONS + ALL_PAGE, mine);

        assertThat(find(page.pageList(), mineJti).get("current")).isEqualTo(Boolean.TRUE);
        assertThat(find(page.pageList(), otherJti).get("current"))
                .as("用 A 的令牌查询时，B 不能被标成 current——否则前端会把别人的会话当成自己的")
                .isEqualTo(Boolean.FALSE);
    }

    // ==================================================================
    // AC-53：踢出只影响目标会话
    // ==================================================================

    @Test
    @DisplayName("AC-53 踢出指定会话 → 该会话下一个请求 401，其它会话不受影响，列表同步消失")
    void kickTerminatesOnlyTheTargetedSession() {
        String victim = client.login("admin", ADMIN_PASSWORD);
        String victimJti = jtiOf(victim);
        String actor = client.login("admin", ADMIN_PASSWORD);
        assertThat(client.get(PROBE, victim).code()).as("前置：受害者会话可用").isEqualTo(0);

        ApiClient.Exchange kick = client.delete(SESSIONS + "/" + victimJti, actor);

        assertThat(kick.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(kick.data().get("kicked")).isEqualTo(1);
        assertThat(kick.data().get("selfKicked")).isEqualTo(Boolean.FALSE);

        assertThat(client.get(PROBE, victim).status())
                .as("被踢的会话必须在下一个请求就失效")
                .isEqualTo(401);
        assertThat(client.get(PROBE, actor).code())
                .as("踢出是会话级的：执行者自己的会话不能受牵连")
                .isEqualTo(0);
        assertThat(find(client.get(SESSIONS + ALL_PAGE, actor).pageList(), victimJti))
                .as("已终止的会话不应再出现在列表里（幽灵会话）")
                .isNull();
    }

    @Test
    @DisplayName("AC-53 / D5 踢出自己的当前会话：允许，但必须回传 selfKicked=true 以便前端提示")
    void kickingOwnSessionReportsSelfKicked() {
        String token = client.login("admin", ADMIN_PASSWORD);
        String jti = jtiOf(token);

        ApiClient.Exchange kick = client.delete(SESSIONS + "/" + jti, token);

        assertThat(kick.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(kick.data().get("selfKicked"))
                .as("为 true 前端才能提示并跳登录页；否则用户看到「操作成功」却紧接着被踢到登录页，"
                        + "不知道发生了什么")
                .isEqualTo(Boolean.TRUE);
        assertThat(client.get(PROBE, token).status()).isEqualTo(401);
    }

    // ==================================================================
    // AC-53 / AC-55：登出
    // ==================================================================

    @Test
    @DisplayName("AC-53 登出后会话从列表消失且令牌立即失效")
    void logoutRemovesSessionAndInvalidatesToken() {
        String token = client.login("admin", ADMIN_PASSWORD);
        String jti = jtiOf(token);
        String observer = client.login("admin", ADMIN_PASSWORD);
        assertThat(find(client.get(SESSIONS + ALL_PAGE, observer).pageList(), jti)).isNotNull();

        ApiClient.Exchange logout = client.post("/api/auth/logout", null, token);

        assertThat(logout.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(client.get(PROBE, token).status()).isEqualTo(401);
        assertThat(find(client.get(SESSIONS + ALL_PAGE, observer).pageList(), jti))
                .as("登出必须同时清理会话记录，否则在线列表会长期显示已经登出的人")
                .isNull();
    }

    @Test
    @DisplayName("AC-55 登出恒返回成功：过期令牌、非法令牌、甚至不带令牌都不返回 401")
    void logoutIsAlwaysSuccessful() {
        // 过期令牌：若 /api/auth/logout 不在 permitAll 白名单里，这里会得到 401，
        // 前端随即走「登录已失效」分支并 window.location.reload()——用户主动登出反而看到报错 + 整页刷新
        ApiClient.Exchange expired = client.post("/api/auth/logout", null,
                mintExpiredToken(UUID.randomUUID().toString(), userService.getEntityByUsername("admin")));
        assertThat(expired.status()).as("过期令牌登出不能是 401").isEqualTo(200);
        assertThat(expired.code()).isEqualTo(ResultCode.SUCCESS.code());

        ApiClient.Exchange garbage = client.post("/api/auth/logout", null, "not-a-jwt-at-all");
        assertThat(garbage.status()).as("非法令牌登出也不能是 401").isEqualTo(200);
        assertThat(garbage.code()).isEqualTo(ResultCode.SUCCESS.code());

        ApiClient.Exchange anonymous = client.post("/api/auth/logout", null, (String) null);
        assertThat(anonymous.status()).as("不带令牌登出同样要成功").isEqualTo(200);
        assertThat(anonymous.code()).isEqualTo(ResultCode.SUCCESS.code());
    }

    @Test
    @DisplayName("AC-55 过期令牌登出仍能清理会话记录（靠 ExpiredJwtException 携带的 claims 取到 jti）")
    void expiredTokenLogoutStillCleansSessionRecord() {
        SysUser admin = userService.getEntityByUsername("admin");
        String observer = client.login("admin", ADMIN_PASSWORD);

        // 造一条"JWT 已过期、但会话记录还在"的历史会话：这正是本功能上线前的存量令牌形态，
        // 也是"过期令牌登出"最容易留下幽灵记录的场景
        String jti = UUID.randomUUID().toString();
        sessionRegistry.register(new SessionInfo(jti, admin.getId(), "admin", admin.getRealName(),
                Instant.now(), Instant.now().plusSeconds(3600), null, "127.0.0.1", "OnlineSessionIT"),
                3600);
        assertThat(find(client.get(SESSIONS + ALL_PAGE, observer).pageList(), jti))
                .as("前置：该会话已在列表中").isNotNull();

        ApiClient.Exchange logout = client.post("/api/auth/logout", null,
                mintExpiredToken(jti, admin));

        assertThat(logout.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(find(client.get(SESSIONS + ALL_PAGE, observer).pageList(), jti))
                .as("过期令牌的 claims 是可读的（异常在校验 exp 时抛出，claims 已解析完成），"
                        + "据此必须清掉会话记录")
                .isNull();
    }

    // ==================================================================
    // AC-54：权限与审计
    // ==================================================================

    @Test
    @DisplayName("AC-54 会话接口仅 ADMIN：ANALYST / OPERATOR 查询与踢出都被拒为 403")
    void sessionEndpointsAreAdminOnly() {
        String analyst = client.login("analyst", ANALYST_PASSWORD);
        String operator = client.login("operator", OPERATOR_PASSWORD);

        assertThat(client.get(SESSIONS + ALL_PAGE, analyst).code())
                .as("列表含全员登录 IP 与 User-Agent，属运维级信息")
                .isEqualTo(ResultCode.FORBIDDEN.code());
        assertThat(client.delete(SESSIONS + "/some-jti", analyst).code())
                .as("踢出是影响他人的写操作，风险等级与删除相当")
                .isEqualTo(ResultCode.FORBIDDEN.code());

        assertThat(client.get(SESSIONS + ALL_PAGE, operator).code())
                .as("OPERATOR 可写机构/部门/险种，但不应能踢人下线")
                .isEqualTo(ResultCode.FORBIDDEN.code());
        assertThat(client.delete(SESSIONS + "/some-jti", operator).code())
                .isEqualTo(ResultCode.FORBIDDEN.code());
    }

    @Test
    @DisplayName("AC-54 踢出落审计：source=WEB、target_type=SESSION、操作者是踢出者，且快照含目标 jti")
    void kickIsAuditedAsWebOperationByTheActor() {
        String victim = client.login("admin", ADMIN_PASSWORD);
        String victimJti = jtiOf(victim);
        String actor = client.login("admin", ADMIN_PASSWORD);

        assertThat(client.delete(SESSIONS + "/" + victimJti, actor).code())
                .isEqualTo(ResultCode.SUCCESS.code());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT source, action, target_type, operator_username, result, after_value "
                        + "FROM ai_operation_audit "
                        + "WHERE action = 'KICK' AND target_type = 'SESSION' "
                        + "ORDER BY id DESC LIMIT 1");

        assertThat(row.get("source")).as("页面直连渠道").isEqualTo("WEB");
        assertThat(row.get("action")).isEqualTo("KICK");
        assertThat(row.get("target_type")).isEqualTo("SESSION");
        assertThat(row.get("result")).isEqualTo("SUCCESS");
        assertThat(row.get("operator_username"))
                .as("审计的价值在于每条记录都能定位到人")
                .isEqualTo("admin");
        assertThat(String.valueOf(row.get("after_value")))
                .as("快照要能定位到被踢的到底是哪一条会话")
                .contains(victimJti);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private String jtiOf(String token) {
        Claims claims = tokenProvider.parse(token);
        String jti = JwtTokenProvider.tokenId(claims);
        assertThat(jti).isNotBlank();
        return jti;
    }

    private static Map<String, Object> find(List<Map<String, Object>> sessions, String jti) {
        return sessions.stream()
                .filter(item -> jti.equals(item.get("jti")))
                .findFirst()
                .orElse(null);
    }

    /**
     * 解析时间字段。
     *
     * <p>不假设序列化格式：既接受 ISO（{@code 2026-09-23T10:00:00}），
     * 也接受项目配置的 {@code yyyy-MM-dd HH:mm:ss}——把空格换成 {@code T} 后两者都能用同一个
     * 解析器处理，避免测试因为一个展示格式的调整而假失败。</p>
     */
    private static LocalDateTime parseTime(Object value) {
        String text = String.valueOf(value).replace(' ', 'T');
        return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** 用与应用相同的密钥签一张**已过期**的令牌：签名合法，只有 exp 越界。 */
    private String mintExpiredToken(String jti, SysUser user) {
        SecretKey key = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(jti)
                .subject(user.getUsername())
                .issuer(jwtProperties.getIssuer())
                .claim("uid", user.getId())
                .claim("name", user.getRealName())
                .claim("roles", List.of("ADMIN"))
                .claim("perms", List.of())
                .issuedAt(new Date(now - 7_200_000L))
                .expiration(new Date(now - 3_600_000L))
                .signWith(key)
                .compact();
    }
}
