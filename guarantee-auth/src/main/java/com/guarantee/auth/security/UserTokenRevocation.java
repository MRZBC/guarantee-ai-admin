package com.guarantee.auth.security;

import com.guarantee.auth.config.RevocationProperties;
import com.guarantee.common.security.SessionRegistry;
import com.guarantee.common.security.UserTokenRevoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 用户级令牌撤销（{@link UserTokenRevoker} 的 Redis 实现，AC-21）。
 *
 * <p><b>为什么需要"用户级"撤销</b>：登录时的实现把每个 jti 写进 Redis，仅撤销单个 jti
 * 无法覆盖该用户已签发的其它令牌。但用户在有效期内可以持有多个令牌（多标签页、多设备、
 * 重新登录前的旧令牌），停用用户或调整其角色后只撤当前 jti 无法覆盖其它令牌，
 * 权限变更就不会"立即生效"（SYS-P-05 / SYS-C-07）。</p>
 *
 * <p><b>实现</b>：记录 {@code guarantee:auth:user-revoked:{userId} = 撤销时刻}，
 * 校验时比较令牌的 {@code iat}：签发时间早于撤销时刻即视为失效。
 * TTL 取略大于令牌最长有效期，使记录自然过期、无需清理任务。</p>
 *
 * <p><b>为什么按 iat 比较，而不是直接删令牌</b>：白名单里只有 {@code jti → userId}，
 * 没有反向索引，无法列出"某用户的全部 jti"。用时间戳比较等价且不需要额外索引——
 * 代价是**任何"重新签发令牌"的续期方案都会破坏它**（新 iat 会大于撤销时刻，
 * 使已撤销用户的令牌复活）。这正是 AUTH-04 坚持"只延长 TTL、不重签 JWT"的原因。</p>
 */
@Component
public class UserTokenRevocation implements UserTokenRevoker {

    private static final Logger log = LoggerFactory.getLogger(UserTokenRevocation.class);

    private static final String KEY_PREFIX = "guarantee:auth:user-revoked:";

    /** 记录保留时长，需 >= 令牌绝对有效期（默认 720 分钟）并留出余量。 */
    private static final Duration TTL = Duration.ofHours(13);

    private final StringRedisTemplate redisTemplate;
    private final RevocationProperties revocationProperties;
    private final SessionRegistry sessionRegistry;

    public UserTokenRevocation(StringRedisTemplate redisTemplate,
                               RevocationProperties revocationProperties,
                               SessionRegistry sessionRegistry) {
        this.redisTemplate = redisTemplate;
        this.revocationProperties = revocationProperties;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public int revokeUsers(Collection<Long> userIds, String reason) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        List<Long> revokedIds = new ArrayList<>();
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            try {
                redisTemplate.opsForValue().set(KEY_PREFIX + userId, String.valueOf(now), TTL);
                revokedIds.add(userId);
            } catch (RuntimeException ex) {
                // 撤销失败不阻断业务：数据变更已提交，用户重新登录的要求通过返回值告知操作者
                log.warn("撤销用户 {} 的令牌失败（原因：{}）：{}", userId, reason, ex.getMessage());
            }
        }
        if (revokedIds.isEmpty()) {
            return 0;
        }

        // AUTH-05：同步清理在线会话记录。不做这一步，在线列表会显示"已被强制下线但仍在线上"
        // 的幽灵会话——这是最容易被漏掉的一致性维护点（本方法原先只写一个时间戳）。
        // 按 UserTokenRevoker 的契约，这里必须尽力而为：会话记录只服务展示，
        // 清理失败不得让已经提交的业务变更回滚，也不得把撤销结论改成失败。
        int cleaned = 0;
        try {
            cleaned = sessionRegistry.terminateAllForUsers(revokedIds);
        } catch (RuntimeException ex) {
            log.warn("清理已撤销用户的在线会话记录失败（撤销本身已生效）：{}", ex.getMessage());
        }

        log.info("已撤销 {} 个用户的令牌（同步清理 {} 个在线会话），原因：{}",
                revokedIds.size(), cleaned, reason);
        return revokedIds.size();
    }

    /**
     * 令牌是否在指定用户被撤销之后签发。
     *
     * <p>Redis 不可用时按 {@code failure-mode} 处理（AUTH-02）：{@code fail-closed} 下判为
     * "未在撤销之后签发"（即视为已撤销 → 401），{@code fail-open} 下放行。</p>
     *
     * @param userId   令牌所属用户
     * @param issuedAt 令牌签发时间（毫秒）
     */
    public boolean issuedAfterRevocation(Long userId, long issuedAt) {
        if (userId == null) {
            return true;
        }
        try {
            String value = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
            if (value == null) {
                return true;
            }
            return issuedAt > Long.parseLong(value);
        } catch (RuntimeException ex) {
            if (revocationProperties.isFailClosed()) {
                log.error("校验用户级撤销状态失败（Redis 不可用），failure-mode=fail-closed → 判为已撤销：{}",
                        ex.getMessage());
                return false;
            }
            log.warn("校验用户级撤销状态失败（Redis 不可用），failure-mode=fail-open → 放行：{}",
                    ex.getMessage());
            return true;
        }
    }
}
