package com.guarantee.auth.security;

import com.guarantee.auth.config.RevocationProperties;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 令牌白名单（Redis）。
 *
 * <p>登录时把 jti 写入 Redis，登出/踢出时删除；有状态可撤销，避免 JWT 无法主动失效的问题。</p>
 *
 * <h3>两个边界（AUTH-04）</h3>
 * <ul>
 *   <li>白名单 TTL = <b>空闲超时</b>：有请求就续期（{@link #validate}）；</li>
 *   <li>JWT 的 {@code exp} = <b>绝对上限</b>：不会因为活跃而被延长。</li>
 * </ul>
 *
 * <h3>故障策略（AUTH-02）</h3>
 * <p>默认 {@code fail-closed}：Redis 不可用时校验判为"无效"。这是刻意取舍——
 * 故障态不应该是"静默退化为只验签名"。{@code fail-open} 仅作需重启的应急开关。</p>
 *
 * <p><b>因此本服务成为鉴权链路的强依赖</b>：生产部署必须为 Redis 提供哨兵/集群与告警，
 * 否则一次缓存抖动会导致全站 401。</p>
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private static final String KEY_PREFIX = "guarantee:auth:token:";

    private final StringRedisTemplate redisTemplate;
    private final RevocationProperties revocationProperties;
    private final JwtTokenProvider tokenProvider;

    public TokenRevocationService(StringRedisTemplate redisTemplate,
                                  RevocationProperties revocationProperties,
                                  JwtTokenProvider tokenProvider) {
        this.redisTemplate = redisTemplate;
        this.revocationProperties = revocationProperties;
        this.tokenProvider = tokenProvider;
    }

    /**
     * 令牌校验结果。
     *
     * @param active            是否仍然有效
     * @param renewed           本次是否执行了空闲续期（调用方据此同步会话记录的 TTL）
     * @param renewedTtlSeconds 续期后的 TTL；未续期时为 0
     */
    public record Validation(boolean active, boolean renewed, long renewedTtlSeconds) {

        static Validation invalid() {
            return new Validation(false, false, 0L);
        }

        static Validation valid() {
            return new Validation(true, false, 0L);
        }

        static Validation renewed(long ttlSeconds) {
            return new Validation(true, true, ttlSeconds);
        }
    }

    /**
     * 登录成功后登记令牌。
     *
     * <p><b>{@code fail-closed} 下写入失败必须抛出</b>（AUTH-02 §4.2.4）：未登记的令牌
     * 在白名单模式下等同于无效令牌。若这里静默吞掉异常，用户会看到"登录成功"，
     * 随后每一个请求都 401，且无法通过重新登录自救——前端还会陷入
     * "401 → 跳登录页 → reload"的循环。</p>
     *
     * @param tokenId    令牌 jti
     * @param userId     归属用户
     * @param ttlSeconds 白名单 TTL（空闲超时，AUTH-04）
     */
    public void register(String tokenId, Long userId, long ttlSeconds) {
        long ttl = Math.max(1L, ttlSeconds);
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + tokenId, String.valueOf(userId),
                    Duration.ofSeconds(ttl));
        } catch (RuntimeException ex) {
            String message = "登记令牌失败：Redis 不可用";
            if (revocationProperties.isFailClosed()) {
                log.error("{}（failure-mode=fail-closed，登录将失败）：{}", message, ex.getMessage());
                throw new BizException(ResultCode.AUTH_UNAVAILABLE, ResultCode.AUTH_UNAVAILABLE.message(), ex);
            }
            log.warn("{}（failure-mode=fail-open，本次登录继续）：{}", message, ex.getMessage());
        }
    }

    /** 校验令牌是否仍然有效（不带续期）。Redis 不可用时按策略处理。 */
    public boolean isActive(String tokenId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
        } catch (RuntimeException ex) {
            return onReadFailure("校验令牌状态", ex);
        }
    }

    /**
     * 校验并（必要时）续期空闲窗口（AUTH-04）。
     *
     * <p><b>续期只延长白名单 TTL，绝不重新签发 JWT</b>：用户级撤销按令牌 {@code iat} 比较，
     * 重新签发会产生新的 {@code iat}，使已被撤销用户的旧令牌复活——那是一条真实的提权路径。</p>
     *
     * <p>节流：仅当剩余 TTL 低于空闲窗口的一半时才写 Redis，避免每个请求一次写操作。</p>
     *
     * @param tokenId                  令牌 jti
     * @param absoluteRemainingSeconds 距 JWT {@code exp} 的剩余秒数（绝对上限）
     */
    public Validation validate(String tokenId, long absoluteRemainingSeconds) {
        String key = KEY_PREFIX + tokenId;
        Long remaining;
        try {
            remaining = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        } catch (RuntimeException ex) {
            return onReadFailure("校验令牌状态", ex) ? Validation.valid() : Validation.invalid();
        }
        // -2 = key 不存在（已登出/已踢出/空闲超时）；-1 = 无 TTL（不应出现）
        if (remaining == null || remaining < 0) {
            return Validation.invalid();
        }

        long idleSeconds = tokenProvider.getIdleTimeoutSeconds();
        if (remaining >= idleSeconds / 2) {
            return Validation.valid();
        }
        long ttl = Math.min(idleSeconds, absoluteRemainingSeconds);
        if (ttl <= 0) {
            return Validation.valid();
        }
        try {
            redisTemplate.expire(key, Duration.ofSeconds(ttl));
        } catch (RuntimeException ex) {
            // 续期失败不影响本次请求：令牌本身仍然有效，只是空闲窗口没被顺延。
            // 若把它升级为 401，一次瞬时的 Redis 写失败就会把在线用户踢下线。
            log.warn("令牌空闲续期失败（不影响本次请求）：{}", ex.getMessage());
            return Validation.valid();
        }
        return Validation.renewed(ttl);
    }

    /**
     * 撤销令牌（登出 / 踢出）。
     *
     * <p>刻意**不吞异常**：调用方对失败的处理不同——登出可以吞掉（令牌本就快没用了），
     * 而"踢出"必须让管理员看到失败（否则他以为踢掉了、实际没踢掉）。</p>
     */
    public void revoke(String tokenId) {
        if (tokenId == null) {
            return;
        }
        redisTemplate.delete(KEY_PREFIX + tokenId);
    }

    /** 便于过滤器统一处理：解析出的 Claims 是否仍然有效（不带续期）。 */
    public boolean isActive(Claims claims) {
        String tokenId = JwtTokenProvider.tokenId(claims);
        return tokenId == null || isActive(tokenId);
    }

    /** 读路径故障：fail-closed 返回 false（判为无效），fail-open 返回 true（放行）。 */
    private boolean onReadFailure(String action, RuntimeException ex) {
        if (revocationProperties.isFailClosed()) {
            log.error("{}失败（Redis 不可用），failure-mode=fail-closed → 判为无效：{}",
                    action, ex.getMessage());
            return false;
        }
        log.warn("{}失败（Redis 不可用），failure-mode=fail-open → 放行：{}",
                action, ex.getMessage());
        return true;
    }
}
