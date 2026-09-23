package com.guarantee.auth.security;

import com.guarantee.auth.config.LoginGuardProperties;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 登录失败计数与临时锁定（AUTH-01）。
 *
 * <p><b>双维度</b>：账号（阈值 5）+ 客户端 IP（阈值 20），任一命中即拒绝登录。</p>
 *
 * <h3>四条不可违反的约束</h3>
 * <ol>
 *   <li><b>计数只依赖请求参数</b>，不依赖用户是否真实存在。若只有存在的账号会被锁定，
 *       攻击者就能通过"这个账号会不会被锁"判断账号是否存在——直接击穿 LD-T8 建立的
 *       防账号枚举性质。</li>
 *   <li><b>锁定判定必须早于密码校验</b>（{@code passwordEncoder.matches}）。
 *       否则攻击者仍能强制服务端为每次尝试做一次 BCrypt，形成 CPU 耗尽型拒绝服务。</li>
 *   <li><b>计数用 Lua 原子完成 INCR + 首次 EXPIRE</b>。若 INCR 成功而 EXPIRE 失败
 *       （进程被杀 / 连接中断），会留下永不过期的计数器，账号被**永久锁定**且无法自愈。</li>
 *   <li><b>Redis 不可用时 fail-open</b>：放行并记 ERROR。登录是全站唯一入口，
 *       缓存故障不应把所有人挡在门外（与 {@code TokenRevocationService} 的既有降级策略一致）。</li>
 * </ol>
 */
@Component
public class LoginAttemptGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);

    private static final String FAIL_USER_PREFIX = "guarantee:auth:login-fail:user:";
    private static final String FAIL_IP_PREFIX = "guarantee:auth:login-fail:ip:";
    private static final String LOCK_USER_PREFIX = "guarantee:auth:login-lock:user:";
    private static final String LOCK_IP_PREFIX = "guarantee:auth:login-lock:ip:";

    /** 账号/IP 标识的最大长度，避免超长用户名撑爆 Redis key。 */
    private static final int MAX_KEY_PART_LENGTH = 128;

    /**
     * 原子自增并在首次创建时设置过期时间。
     *
     * <p>用 Lua 而不是"先 INCR 再 EXPIRE"两次调用：后者在两步之间进程被杀时，
     * 会留下一个**没有 TTL 的计数器**，达到阈值后永远锁定该账号，
     * 且没有任何自动恢复途径（约束 3）。</p>
     */
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final LoginGuardProperties properties;

    public LoginAttemptGuard(StringRedisTemplate redisTemplate, LoginGuardProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 登录前调用：账号或 IP 处于锁定期则直接拒绝。
     *
     * <p>必须在密码校验之前调用（约束 2）。</p>
     *
     * @throws BizException {@link ResultCode#LOGIN_LOCKED}，文案含剩余分钟数
     */
    public void assertNotLocked(String username, String clientIp) {
        if (!properties.isEnabled()) {
            return;
        }
        long userRemaining = remainingLockSeconds(LOCK_USER_PREFIX + keyPart(username));
        if (userRemaining > 0) {
            log.warn("账号处于锁定期，拒绝登录 username={} ip={} 剩余={}s",
                    username, clientIp, userRemaining);
            throw new BizException(ResultCode.LOGIN_LOCKED, lockedMessage(userRemaining));
        }
        if (StringUtils.hasText(clientIp)) {
            long ipRemaining = remainingLockSeconds(LOCK_IP_PREFIX + keyPart(clientIp));
            if (ipRemaining > 0) {
                log.warn("IP 处于锁定期，拒绝登录 username={} ip={} 剩余={}s",
                        username, clientIp, ipRemaining);
                throw new BizException(ResultCode.LOGIN_LOCKED, lockedMessage(ipRemaining));
            }
        }
    }

    /**
     * 记录一次登录失败；达到阈值即锁定。
     *
     * <p>失败计数不可用（Redis 故障）时不阻断登录流程，只记 ERROR。</p>
     */
    public void recordFailure(String username, String clientIp) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            long userFailures = increment(FAIL_USER_PREFIX + keyPart(username),
                    properties.getFailureWindow());
            if (userFailures >= properties.getMaxFailuresPerUser()) {
                lock(LOCK_USER_PREFIX + keyPart(username), properties.getUserLock());
                clear(FAIL_USER_PREFIX + keyPart(username));
                log.warn("账号连续登录失败 {} 次，已锁定 {} 分钟 username={} ip={}",
                        userFailures, properties.getUserLock().toMinutes(), username, clientIp);
            } else {
                log.warn("登录失败 username={} ip={} 窗口内失败次数={}/{}",
                        username, clientIp, userFailures, properties.getMaxFailuresPerUser());
            }

            if (StringUtils.hasText(clientIp)) {
                long ipFailures = increment(FAIL_IP_PREFIX + keyPart(clientIp),
                        properties.getFailureWindow());
                if (ipFailures >= properties.getMaxFailuresPerIp()) {
                    lock(LOCK_IP_PREFIX + keyPart(clientIp), properties.getIpLock());
                    clear(FAIL_IP_PREFIX + keyPart(clientIp));
                    log.warn("IP 连续登录失败 {} 次，已锁定 {} 分钟 ip={}",
                            ipFailures, properties.getIpLock().toMinutes(), clientIp);
                }
            }
        } catch (RuntimeException ex) {
            log.error("记录登录失败次数失败（Redis 不可用），本次失败未计数：{}", ex.getMessage());
        }
    }

    /**
     * 登录成功后调用：清除该账号的失败计数。
     *
     * <p><b>刻意不清 IP 计数</b>：攻击者若持有任意一个有效账号，成功登录一次就能重置
     * IP 维度的累计值，使 IP 限流形同虚设。IP 计数只随窗口自然过期。</p>
     */
    public void recordSuccess(String username, String clientIp) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            clear(FAIL_USER_PREFIX + keyPart(username));
            clear(LOCK_USER_PREFIX + keyPart(username));
        } catch (RuntimeException ex) {
            log.warn("登录成功后清理失败计数失败：{}", ex.getMessage());
        }
    }

    /**
     * 解析客户端 IP（AUTH-01 §4.1.5）。
     *
     * <p>默认只用 {@code getRemoteAddr()}。仅当 {@code trust-forwarded-header=true}
     * 时才读 {@code X-Forwarded-For} 的第一段——该头可被伪造，只有在可信反向代理
     * 之后才可信任。</p>
     */
    public String resolveClientIp(HttpServletRequest request) {
        if (properties.isTrustForwardedHeader()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                String first = forwarded.split(",")[0].trim();
                if (StringUtils.hasText(first)) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 剩余锁定秒数；Redis 不可用时返回 0（fail-open，约束 4）。 */
    private long remainingLockSeconds(String lockKey) {
        try {
            String value = redisTemplate.opsForValue().get(lockKey);
            if (value == null) {
                return 0L;
            }
            long until = Long.parseLong(value);
            return Math.max(0L, (until - System.currentTimeMillis()) / 1000L);
        } catch (RuntimeException ex) {
            log.error("查询登录锁定状态失败（Redis 不可用），按放行处理：{}", ex.getMessage());
            return 0L;
        }
    }

    private long increment(String counterKey, Duration window) {
        Long count = redisTemplate.execute(INCR_WITH_TTL,
                java.util.List.of(counterKey),
                String.valueOf(Math.max(1L, window.toSeconds())));
        return count == null ? 0L : count;
    }

    private void lock(String lockKey, Duration duration) {
        long until = System.currentTimeMillis() + duration.toMillis();
        redisTemplate.opsForValue().set(lockKey, String.valueOf(until), duration);
    }

    private void clear(String key) {
        redisTemplate.delete(key);
    }

    /** 锁定文案：向上取整到分钟，避免出现"请 0 分钟后重试"。 */
    private static String lockedMessage(long remainingSeconds) {
        long minutes = Math.max(1L, (remainingSeconds + 59L) / 60L);
        return ResultCode.LOGIN_LOCKED.message() + "，请 " + minutes + " 分钟后重试";
    }

    /** 截断超长标识，避免异常长的用户名/IP 撑爆 key 空间。 */
    private static String keyPart(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim().toLowerCase();
        return trimmed.length() <= MAX_KEY_PART_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_KEY_PART_LENGTH);
    }
}
