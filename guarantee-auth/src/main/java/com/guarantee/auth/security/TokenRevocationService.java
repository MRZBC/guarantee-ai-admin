package com.guarantee.auth.security;

import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;

/**
 * 令牌撤销列表（Redis）。
 *
 * <p>登录时把 jti 写入 Redis，登出时删除；有状态可撤销，避免 JWT 无法主动失效的问题。</p>
 *
 * <p><b>降级策略</b>：Redis 不可用时记录告警并放行（fail-open），
 * 保证缓存故障不会导致整个后台无法登录。生产环境如需强一致，应改为 fail-closed。</p>
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private static final String KEY_PREFIX = "guarantee:auth:token:";

    private final StringRedisTemplate redisTemplate;

    public TokenRevocationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 登录成功后登记令牌。 */
    public void register(String tokenId, Long userId, Date expiration) {
        try {
            long ttlSeconds = Math.max(60L, (expiration.getTime() - System.currentTimeMillis()) / 1000);
            redisTemplate.opsForValue().set(KEY_PREFIX + tokenId, String.valueOf(userId),
                    Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException ex) {
            log.warn("登记令牌到 Redis 失败（不影响本次登录）：{}", ex.getMessage());
        }
    }

    /** 校验令牌是否仍然有效。Redis 不可用时放行。 */
    public boolean isActive(String tokenId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
        } catch (RuntimeException ex) {
            log.warn("校验令牌状态失败，按放行处理：{}", ex.getMessage());
            return true;
        }
    }

    /** 登出：撤销令牌。 */
    public void revoke(String tokenId) {
        try {
            redisTemplate.delete(KEY_PREFIX + tokenId);
        } catch (RuntimeException ex) {
            log.warn("撤销令牌失败：{}", ex.getMessage());
        }
    }

    /** 便于过滤器统一处理：解析出的 Claims 是否仍然有效。 */
    public boolean isActive(Claims claims) {
        String tokenId = JwtTokenProvider.tokenId(claims);
        return tokenId == null || isActive(tokenId);
    }
}
