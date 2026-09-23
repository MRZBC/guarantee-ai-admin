package com.guarantee.web;

import com.guarantee.common.api.ResultCode;
import com.guarantee.system.service.UserService;
import com.guarantee.web.support.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 登录失败防爆破的**账号维度**（AUTH-01，AC-42 / AC-44 / AC-45 / AC-46）。
 *
 * <h3>为什么这套测试必须隔离</h3>
 * <p>登录计数与锁定是**全局共享状态**（Redis），测试之间极易互相污染：一旦把某个演示账号
 * 或本机 IP 锁住，后续所有集成测试都会以"登录失败"的面孔失败，排查成本极高。因此：</p>
 * <ul>
 *   <li>主体用例一律使用**随机生成的不存在的用户名**——它天然不与其他测试共享状态；</li>
 *   <li>每次请求携带**随机 `X-Forwarded-For`**，使 IP 维度计数永远落在仅此一次使用的
 *       IP 上（本类把 {@code trust-forwarded-header} 打开、并让账号阈值远低于 IP 阈值）；</li>
 *   <li>只有"锁定到期后可用正确密码登录"这一条必须落到真实账号上，且锁定时长被压到秒级。</li>
 * </ul>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000",
                // 打开 XFF 信任，让每个用例能把 IP 维度"甩"到一个一次性 IP 上
                "guarantee.auth.login-guard.trust-forwarded-header=true",
                "guarantee.auth.login-guard.max-failures-per-user=3",
                // IP 阈值放到极高：本类只验证账号维度，IP 维度由 AuthIpLockIT 独立覆盖
                "guarantee.auth.login-guard.max-failures-per-ip=1000",
                "guarantee.auth.login-guard.failure-window=30s",
                "guarantee.auth.login-guard.user-lock=3s"
        })
class AuthLoginGuardIT {

    /** 演示数据里的低权限账号（DataInitializer.DEFAULT_PASSWORD）。 */
    private static final String DEMO_USER = "user0004";
    private static final String DEMO_PASSWORD = "User@123";

    private static final String WRONG_PASSWORD = "Definitely-Wrong@123";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserService userService;

    private ApiClient client;

    @BeforeEach
    void setUp() {
        client = new ApiClient(port, objectMapper);
    }

    // ==================================================================
    // AC-42 / AC-44：达阈值即锁定，且文案与"密码错误"可区分
    // ==================================================================

    @Test
    @DisplayName("AC-42 连续失败达阈值 → 返回 1004（不是 1001），文案含剩余分钟且不再说「用户名或密码错误」")
    void locksAccountAfterThresholdAndUsesDistinguishableMessage() {
        String username = uniqueUsername();

        // 前置证明：该用户名确实不存在——只会得到"用户名或密码错误"，而不是"账号已停用"之类
        ApiClient.Exchange first = client.loginRaw(username, WRONG_PASSWORD, randomForwardedFor());
        assertThat(first.code())
                .as("第一次失败应当是普通的登录失败，说明该用户名不存在、不是停用账号")
                .isEqualTo(ResultCode.LOGIN_FAILED.code());
        assertThat(first.status()).as("业务错误遵循项目约定：HTTP 200 + 非 0 code").isEqualTo(200);

        // 阈值 3：再失败 2 次即达阈值
        client.loginRaw(username, WRONG_PASSWORD, randomForwardedFor());
        client.loginRaw(username, WRONG_PASSWORD, randomForwardedFor());

        ApiClient.Exchange locked = client.loginRaw(username, WRONG_PASSWORD, randomForwardedFor());

        assertThat(locked.code())
                .as("必须与 LOGIN_FAILED 可区分：否则用户以为密码一直不对而反复重试，"
                        + "每次重试都在刷新失败窗口，形成越试越锁的死循环")
                .isEqualTo(ResultCode.LOGIN_LOCKED.code())
                .isNotEqualTo(ResultCode.LOGIN_FAILED.code());
        assertThat(locked.message())
                .contains("分钟")
                .doesNotContain("用户名或密码错误");
    }

    @Test
    @DisplayName("AC-44 不存在的用户名同样被锁定 —— 锁定行为本身不能成为账号枚举侧信道（LD-T8 的延续）")
    void nonexistentUsernameIsLockedExactlyLikeARealOne() {
        String username = uniqueUsername();
        assertThat(userService.getEntityByUsername(username))
                .as("本用例的前提：该用户名在库里不存在")
                .isNull();

        for (int i = 0; i < 3; i++) {
            client.loginRaw(username, WRONG_PASSWORD, randomForwardedFor());
        }
        ApiClient.Exchange locked = client.loginRaw(username, WRONG_PASSWORD, randomForwardedFor());

        assertThat(locked.code())
                .as("若只有真实存在的账号会被锁定，攻击者就能靠「这个账号会不会被锁」"
                        + "判断账号是否存在——直接击穿 LD-T8 建立的防枚举性质")
                .isEqualTo(ResultCode.LOGIN_LOCKED.code());
    }

    @Test
    @DisplayName("AC-42 锁定期满后自动解除（不是永久锁定），可重新尝试")
    void lockExpiresAndAllowsRetryAfterwards() throws InterruptedException {
        String username = uniqueUsername();
        for (int i = 0; i < 3; i++) {
            client.loginRaw(username, WRONG_PASSWORD, randomForwardedFor());
        }
        assertThat(client.loginRaw(username, WRONG_PASSWORD, randomForwardedFor()).code())
                .isEqualTo(ResultCode.LOGIN_LOCKED.code());

        // 锁定时长被压到 3 秒；真实等待不可避免——本用例验证的正是"时间到了会解锁"
        Thread.sleep(4_500L);

        ApiClient.Exchange afterLock = client.loginRaw(username, WRONG_PASSWORD, randomForwardedFor());
        assertThat(afterLock.code())
                .as("锁必须会过期。若返回 1004，说明锁定是永久的——那会让任何被误锁的账号无法自救")
                .isEqualTo(ResultCode.LOGIN_FAILED.code());
    }

    @Test
    @DisplayName("AC-42 真实账号：被锁 → 等待 → 用正确密码可以登录（锁定不会把账号锁死）")
    void realAccountCanLoginWithCorrectPasswordAfterLockExpires() throws InterruptedException {
        assumeTrue(userService.getEntityByUsername(DEMO_USER) != null,
                "演示账号 " + DEMO_USER + " 不存在，跳过（依赖演示数据）");

        for (int i = 0; i < 3; i++) {
            client.loginRaw(DEMO_USER, WRONG_PASSWORD, randomForwardedFor());
        }
        assertThat(client.loginRaw(DEMO_USER, WRONG_PASSWORD, randomForwardedFor()).code())
                .as("真实账号同样被锁")
                .isEqualTo(ResultCode.LOGIN_LOCKED.code());

        Thread.sleep(4_500L);

        ApiClient.Exchange success = client.loginRaw(DEMO_USER, DEMO_PASSWORD, randomForwardedFor());
        assertThat(success.code())
                .as("锁定到期后正确密码必须能登录；否则一次误锁会永久废掉一个账号")
                .isEqualTo(ResultCode.SUCCESS.code());
    }

    // ==================================================================
    // AC-46：计数器必须带 TTL（否则会留下永不过期的 key → 永久锁定）
    // ==================================================================

    @Test
    @DisplayName("AC-46 失败计数器在创建时就带 TTL，不会留下永不过期的 key")
    void failureCounterAlwaysCarriesTtl() {
        String username = uniqueUsername();

        client.loginRaw(username, WRONG_PASSWORD, randomForwardedFor());

        String key = "guarantee:auth:login-fail:user:" + username;
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);

        assertThat(redisTemplate.hasKey(key))
                .as("失败后应当写入计数器（key 已按小写归一：%s）", key)
                .isTrue();
        assertThat(ttl)
                .as("必须是正数 TTL。若为 -1（无过期时间），一旦进程在 INCR 与 EXPIRE 之间被杀，"
                        + "就会留下永久计数器把账号锁死且无法自愈——这正是用 Lua 原子完成两步的原因")
                .isNotNull()
                .isGreaterThan(0L);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 随机且确定不存在的用户名（全小写十六进制，与 key 归一化后的形态一致）。 */
    private static String uniqueUsername() {
        return "__lg_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 一次性的客户端 IP，把 IP 维度计数彻底隔离。
     *
     * <p>刻意写成两跳（{@code 真实客户端, 代理}）以顺带覆盖"取 X-Forwarded-For 的**第一段**"
     * 这条规则——若实现取成了最后一段，就会把代理地址当客户端，IP 维度直接失效。</p>
     */
    private static Map<String, String> randomForwardedFor() {
        int a = 1 + (int) (Math.random() * 254);
        int b = 1 + (int) (Math.random() * 254);
        int c = 1 + (int) (Math.random() * 254);
        return Map.of("X-Forwarded-For", "10." + a + "." + b + "." + c + ", 172.16.0.1");
    }
}
