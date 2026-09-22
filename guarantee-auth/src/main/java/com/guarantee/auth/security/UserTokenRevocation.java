package com.guarantee.auth.security;

import com.guarantee.common.security.UserTokenRevoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;

/**
 * 用户级令牌撤销（{@link UserTokenRevoker} 的 Redis 实现，AC-21）。
 *
 * <p><b>为什么需要"用户级"撤销</b>：登录时的实现把每个 jti 写进 Redis，{@link #revoke(String)}
 * 只能删掉**单个**令牌。但用户在 12 小时有效期内可以持有多个令牌（多标签页、多设备、
 * 重新登录前的旧令牌），停用用户或调整其角色后仅撤销当前 jti 无法覆盖其它令牌，
 * 权限变更就不会"立即生效"（SYS-P-05 / SYS-C-07）。</p>
 *
 * <p><b>实现</b>：记录 {@code guarantee:auth:user-revoked:{userId} = 撤销时刻}，
 * 校验时比较令牌的 {@code iat}：签发时间早于撤销时刻即视为失效。
 * TTL 取略大于令牌最长有效期，使记录自然过期、无需清理任务。</p>
 */
@Component
public class UserTokenRevocation implements UserTokenRevoker {

    private static final Logger log = LoggerFactory.getLogger(UserTokenRevocation.class);

    private static final String KEY_PREFIX = "guarantee:auth:user-revoked:";

    /** 记录保留时长，需 >= 令牌最大有效期（默认 720 分钟）并留出余量。 */
    private static final Duration TTL = Duration.ofHours(13);

    private final StringRedisTemplate redisTemplate;

    public UserTokenRevocation(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public int revokeUsers(Collection<Long> userIds, String reason) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int revoked = 0;
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            try {
                redisTemplate.opsForValue().set(KEY_PREFIX + userId, String.valueOf(now), TTL);
                revoked++;
            } catch (RuntimeException ex) {
                // 撤销失败不阻断业务：数据变更已提交，用户重新登录的要求通过返回值告知操作者
                log.warn("撤销用户 {} 的令牌失败（原因：{}）：{}", userId, reason, ex.getMessage());
            }
        }
        if (revoked > 0) {
            log.info("已撤销 {} 个用户的令牌，原因：{}", revoked, reason);
        }
        return revoked;
    }

    /**
     * 令牌是否在指定用户被撤销之后签发。
     *
     * <p>Redis 不可用时放行（与既有 {@link TokenRevocationService} 的 fail-open 策略一致），
     * 避免缓存故障导致整个后台无法访问。</p>
     *
     * @param userId    令牌所属用户
     * @param issuedAt  令牌签发时间（毫秒）
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
            log.warn("校验用户级令牌撤销状态失败，按放行处理：{}", ex.getMessage());
            return true;
        }
    }
}
