package com.guarantee.web;

import com.guarantee.common.api.ResultCode;
import com.guarantee.web.support.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录失败防爆破的**IP 维度**（AUTH-01，AC-43）。
 *
 * <h3>为什么要单独一个测试类</h3>
 * <p>IP 维度与账号维度共用同一套代码，但**阈值不同、作用域不同**，需要独立的配置
 * （IP 阈值压到 2）才能验证。同时这里必须把账号阈值放大，否则先触发的是账号锁定，
 * 就分不清到底哪一维度起了作用。</p>
 *
 * <h3>为什么要指定 {@code X-Forwarded-For}</h3>
 * <p>测试全部来自 127.0.0.1。若按真实 {@code getRemoteAddr()} 计数，本用例会锁住**整个
 * 测试进程的来源 IP**，后续所有集成测试都会连带失败。用一次性 IP 把作用域隔离，
 * 正是{@code trust-forwarded-header} 存在的意义（也顺带验证了该开关确实生效）。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000",
                "guarantee.auth.login-guard.trust-forwarded-header=true",
                // 账号维度放大：本类只验证 IP 维度，避免账号先被锁而干扰判断
                "guarantee.auth.login-guard.max-failures-per-user=1000",
                "guarantee.auth.login-guard.max-failures-per-ip=2",
                "guarantee.auth.login-guard.failure-window=30s",
                "guarantee.auth.login-guard.ip-lock=3s"
        })
class AuthIpLockIT {

    private static final String WRONG_PASSWORD = "Definitely-Wrong@123";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient client;

    @BeforeEach
    void setUp() {
        client = new ApiClient(port, objectMapper);
    }

    @Test
    @DisplayName("AC-43 同一 IP 在窗口内失败达阈值 → 换用户名也照样被拒绝（IP 维度独立生效）")
    void locksIpAfterThresholdAcrossDifferentUsernames() {
        String ip = uniqueForwardedFor();

        // 两个不同的用户名各失败一次：账号维度（阈值 1000）不可能触发，
        // 因此若之后被拒绝，只可能是 IP 维度起了作用
        assertThat(client.loginRaw(uniqueUsername(), WRONG_PASSWORD, of(ip)).code())
                .isEqualTo(ResultCode.LOGIN_FAILED.code());
        assertThat(client.loginRaw(uniqueUsername(), WRONG_PASSWORD, of(ip)).code())
                .isEqualTo(ResultCode.LOGIN_FAILED.code());

        ApiClient.Exchange third = client.loginRaw(uniqueUsername(), WRONG_PASSWORD, of(ip));

        assertThat(third.code())
                .as("攻击者靠换用户名绕过账号维度，IP 维度就是这一层的兜底")
                .isEqualTo(ResultCode.LOGIN_LOCKED.code());
        assertThat(third.message()).contains("分钟");
    }

    @Test
    @DisplayName("AC-43 IP 锁定是**按 IP 生效**的：另一个 IP 不受影响（不能变成全局封锁）")
    void ipLockDoesNotBlockOtherClients() {
        String blockedIp = uniqueForwardedFor();
        client.loginRaw(uniqueUsername(), WRONG_PASSWORD, of(blockedIp));
        client.loginRaw(uniqueUsername(), WRONG_PASSWORD, of(blockedIp));
        assertThat(client.loginRaw(uniqueUsername(), WRONG_PASSWORD, of(blockedIp)).code())
                .isEqualTo(ResultCode.LOGIN_LOCKED.code());

        ApiClient.Exchange otherIp = client.loginRaw(uniqueUsername(), WRONG_PASSWORD,
                of(uniqueForwardedFor()));

        assertThat(otherIp.code())
                .as("另一个 IP 必须仍能正常尝试。若这里也返回 1004，说明锁写成了全局的——"
                        + "一个攻击者就能把全站用户挡在门外（放大版的 DoS）")
                .isEqualTo(ResultCode.LOGIN_FAILED.code());
    }

    @Test
    @DisplayName("AC-43 IP 锁定期满后自动解除")
    void ipLockExpires() throws InterruptedException {
        String ip = uniqueForwardedFor();
        client.loginRaw(uniqueUsername(), WRONG_PASSWORD, of(ip));
        client.loginRaw(uniqueUsername(), WRONG_PASSWORD, of(ip));
        assertThat(client.loginRaw(uniqueUsername(), WRONG_PASSWORD, of(ip)).code())
                .isEqualTo(ResultCode.LOGIN_LOCKED.code());

        Thread.sleep(4_500L);

        assertThat(client.loginRaw(uniqueUsername(), WRONG_PASSWORD, of(ip)).code())
                .as("IP 锁同样必须可自动恢复，否则办公网出口被误锁后整栋楼都进不来")
                .isEqualTo(ResultCode.LOGIN_FAILED.code());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static String uniqueUsername() {
        return "__ip_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 一次性的客户端 IP。
     *
     * <p>取 10.0.0.0/8 私网段：空间足够大，同一个类里几个用例几乎不可能撞到同一个值，
     * 也就不会出现"上一个用例把 IP 锁了、下一个用例莫名被拒"的假失败。</p>
     */
    private static String uniqueForwardedFor() {
        return "10." + (1 + (int) (Math.random() * 254))
                + "." + (1 + (int) (Math.random() * 254))
                + "." + (1 + (int) (Math.random() * 254));
    }

    private static Map<String, String> of(String forwardedFor) {
        return Map.of("X-Forwarded-For", forwardedFor);
    }
}
