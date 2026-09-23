package com.guarantee.web.health;

import com.guarantee.auth.config.RevocationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 令牌撤销链路（Redis）的健康检查（AUTH-02 §4.2.5）。
 *
 * <p><b>为什么必须有它</b>：{@code failure-mode=fail-closed} 让 Redis 从"可选缓存"变成
 * 鉴权链路的**强依赖**——它一挂，所有需要认证的请求都是 401。而这个降级**不能是静默的**，
 * 否则运维看到的现象只是"大家在说登录不上"，定位不到根因。</p>
 *
 * <p>组件名 {@code authRevocation}（Spring Boot 会去掉 {@code HealthIndicator} 后缀），
 * 出现在 {@code GET /actuator/health} 中——该端点已是 {@code permitAll}。</p>
 *
 * <p><b>放在 guarantee-web 而非 guarantee-auth</b>：actuator 依赖只声明在启动模块，
 * 而实现细节（Redis、故障策略）来自 auth。这样既让 auth 保持"不依赖 actuator"的干净依赖，
 * 又能在完整的应用上下文里注册到健康端点。</p>
 *
 * <p><b>不泄漏连接信息</b>：只上报状态、{@code failureMode} 与异常类型，不回显 Redis 地址 /
 * 凭据。同时依赖 Spring Boot 默认的 {@code management.endpoint.health.show-details=never}
 * ——未认证的调用者只能看到总体 {@code status}。</p>
 */
@Component
public class AuthRevocationHealthIndicator implements HealthIndicator {

    /**
     * 探活 key。刻意用一个**永不写入**的 key：{@code hasKey} 本身即是一次真实的 Redis 往返，
     * 既能探活又不会污染数据（也避开了不同 Spring Data Redis 版本对 {@code ping()} 返回类型的差异）。
     */
    private static final String PROBE_KEY = "guarantee:auth:health-probe";

    private final StringRedisTemplate redisTemplate;
    private final RevocationProperties revocationProperties;

    public AuthRevocationHealthIndicator(StringRedisTemplate redisTemplate,
                                         RevocationProperties revocationProperties) {
        this.redisTemplate = redisTemplate;
        this.revocationProperties = revocationProperties;
    }

    @Override
    public Health health() {
        String failureMode = revocationProperties.getFailureMode().name();
        try {
            redisTemplate.hasKey(PROBE_KEY);
            return Health.up().withDetail("failureMode", failureMode).build();
        } catch (RuntimeException ex) {
            return Health.down()
                    .withDetail("failureMode", failureMode)
                    .withDetail("reason", ex.getClass().getSimpleName())
                    .build();
        }
    }
}
