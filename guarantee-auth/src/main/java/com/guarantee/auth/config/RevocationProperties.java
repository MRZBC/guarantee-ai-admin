package com.guarantee.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 令牌撤销的 Redis 故障策略，前缀 {@code guarantee.auth.revocation}（AUTH-02）。
 *
 * <p>开启本项后 Redis 从"可选缓存"升级为**鉴权链路的强依赖**：{@code FAIL_CLOSED} 下
 * Redis 不可用会导致所有需要认证的请求返回 401。这是刻意的取舍——
 * 故障态不应该是"静默退化为无鉴权"。生产部署必须为 Redis 提供哨兵 / 集群并配套告警。</p>
 *
 * <p>{@code ignoreUnknownFields = false}：与 {@link LoginGuardProperties} 同一策略——
 * 安全相关配置的键名写错必须启动失败，而不是被静默忽略后沿用默认值。</p>
 */
@ConfigurationProperties(prefix = "guarantee.auth.revocation", ignoreUnknownFields = false)
public class RevocationProperties {

    /** Redis 不可用时的行为。 */
    public enum FailureMode {
        /** 放行。可用性优先，仅限应急，切换需重启。 */
        FAIL_OPEN,
        /** 拒绝。安全优先，默认值。 */
        FAIL_CLOSED
    }

    private FailureMode failureMode = FailureMode.FAIL_CLOSED;

    public FailureMode getFailureMode() {
        return failureMode;
    }

    public void setFailureMode(FailureMode failureMode) {
        this.failureMode = failureMode;
    }

    /** 读路径（校验令牌是否有效）在 Redis 故障时是否应判为"无效"。 */
    public boolean isFailClosed() {
        return failureMode == FailureMode.FAIL_CLOSED;
    }
}
