package com.guarantee.ai.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * MCP 调用的限流与配额（REQ-MCP-03 / TEST-MCP-06）。
 *
 * <p><b>两条独立的闸门</b>：</p>
 * <ol>
 *   <li><b>QPS（固定窗口）</b>：键 {@code guarantee:ai:mcp:qps:<token前缀>:<epoch秒>}，
 *       窗口 1 秒。固定窗口的实现代价最低，而这里的目的是"挡住把平台当免费数据口的
 *       高频抓取"，不是精确整形（令牌桶的额外精度对本场景无收益）。</li>
 *   <li><b>每日配额</b>：键 {@code guarantee:ai:mcp:quota:<token前缀>:<yyyyMMdd>}，
 *       {@code INCR} 后按"距当日 24:00 的秒数"设 TTL。用当日 TTL 而不是固定 24h：
 *       固定 24h 会让配额跨日滑动，管理员看到的"每天 N 次"与实际口径不符。</li>
 * </ol>
 *
 * <p><b>键里只放 Token 前缀，不放明文与哈希</b>：Redis 的 key 可能被 dump / 出现在
 * 慢查询日志与监控里。前缀本身不足以反推凭据（明文有 256 位熵），而哈希是"可用凭据的
 * 等值判据"——放哈希等于把可验证的凭据指纹写进缓存。</p>
 *
 * <p><b>Redis 不可用时 fail-closed</b>：MCP 是外部面，限流是它挡在天真调用者前面的廉价闸门。
 * 缓存故障时放行，等于故障期间对外取数完全不限量；而拒绝的代价只是"外部 Agent 稍后重试"，
 * 不影响平台自身（REQ §7 可靠性）。此处的取舍与登录（{@code LoginAttemptGuard} 的 fail-open）
 * <b>刻意相反</b>：登录是被动入口，MCP 是主动取数。</p>
 */
@Component
public class McpRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(McpRateLimiter.class);

    /** QPS 计数键前缀。 */
    static final String QPS_KEY_PREFIX = "guarantee:ai:mcp:qps:";
    /** 每日配额键前缀。 */
    static final String QUOTA_KEY_PREFIX = "guarantee:ai:mcp:quota:";

    /** QPS 窗口：1 秒；TTL 多给 1 秒，避免边界秒的计数被提前清掉。 */
    static final long QPS_TTL_SECONDS = 2L;

    /** 前缀最大长度（展示前缀实际是 {@code mcp_} + 6，留足余量即可）。 */
    private static final int MAX_PREFIX_LENGTH = 32;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 原子自增并在首次创建时设置过期时间。
     *
     * <p>与 {@code LoginAttemptGuard} 同款：{@code INCR} 与 {@code EXPIRE} 必须原子完成，
     * 否则两步之间进程被杀会留下**永不过期的计数器**——QPS 键会永久卡死该 Token，
     * 配额键则让"每日配额"变成"终身配额"。</p>
     */
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final int qpsLimit;
    private final long dailyQuota;

    /** 鉴权窗口取时用的时钟；单测用 {@link #withClock} 注入固定时钟。 */
    Clock clock = Clock.systemDefaultZone();

    public McpRateLimiter(StringRedisTemplate redisTemplate,
                          @Value("${guarantee.ai.mcp.qps-limit:5}") int qpsLimit,
                          @Value("${guarantee.ai.mcp.daily-quota:10000}") long dailyQuota) {
        this.redisTemplate = redisTemplate;
        this.qpsLimit = Math.max(1, qpsLimit);
        this.dailyQuota = Math.max(1L, dailyQuota);
    }

    /** 测试/嵌入用：指定时钟的实例（不参与 Spring 装配）。 */
    static McpRateLimiter withClock(StringRedisTemplate redisTemplate, int qpsLimit, long dailyQuota, Clock clock) {
        McpRateLimiter limiter = new McpRateLimiter(redisTemplate, qpsLimit, dailyQuota);
        limiter.clock = clock == null ? Clock.systemDefaultZone() : clock;
        return limiter;
    }

    /** 当前生效的 QPS 上限（供日志与页面对账）。 */
    public int qpsLimit() {
        return qpsLimit;
    }

    /** 当前生效的每日配额（供日志与页面对账）。 */
    public long dailyQuota() {
        return dailyQuota;
    }

    /**
     * 记一次调用并校验两条闸门；超限或依赖不可用时抛**可读**的 {@link McpException}。
     *
     * <p>先配额后 QPS：配额是"当天还能不能用"，QPS 是"这一刻能不能用"。配额已满时
     * 不再消耗 QPS 计数，日志里的拒绝原因也就不会被"限流"掩盖。</p>
     *
     * @param tokenPrefix Token 展示前缀（{@code mcp_xxxxxx}）——键里只用它
     */
    public void check(String tokenPrefix) {
        String prefix = normalizePrefix(tokenPrefix);

        long quota = increment(QUOTA_KEY_PREFIX + prefix + ":" + today(), secondsUntilTomorrow());
        if (quota > dailyQuota) {
            throw new McpException(McpErrorCode.DAILY_QUOTA_EXCEEDED,
                    "已达每日调用配额上限（" + dailyQuota + " 次/天，本次为当日第 " + quota
                            + " 次）：请次日重试，或让管理员调整该 Token 的配额");
        }

        long qps = increment(QPS_KEY_PREFIX + prefix + ":" + currentSecond(), QPS_TTL_SECONDS);
        if (qps > qpsLimit) {
            throw new McpException(McpErrorCode.RATE_LIMITED,
                    "调用过于频繁：每 Token 每秒最多 " + qpsLimit + " 次 MCP 调用（本秒第 " + qps
                            + " 次）：请降低频率后重试");
        }
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 自增并返回计数；Redis 故障 → fail-closed（拒绝调用）。 */
    private long increment(String key, long ttlSeconds) {
        try {
            Long count = redisTemplate.execute(INCR_WITH_TTL, List.of(key),
                    String.valueOf(Math.max(1L, ttlSeconds)));
            return count == null ? 0L : count;
        } catch (RuntimeException ex) {
            log.error("MCP 限流计数失败（Redis 不可用），按 fail-closed 拒绝本次调用：key={} err={}",
                    key, ex.getMessage());
            throw new McpException(McpErrorCode.LIMITER_UNAVAILABLE,
                    "限流服务暂不可用，本次 MCP 调用已被拒绝（外部面 fail-closed，请稍后重试）", ex);
        }
    }

    private long currentSecond() {
        return java.time.Instant.now(clock).getEpochSecond();
    }

    private String today() {
        return LocalDate.now(clock).format(DAY);
    }

    /** 距当日 24:00 的秒数（至少 1 秒，避免 TTL=0 立即过期）。 */
    private long secondsUntilTomorrow() {
        LocalDateTime now = LocalDateTime.now(clock);
        return Math.max(1L, Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toSeconds());
    }

    /** 前缀归一：null/空白按"未知凭据"聚合（不会与真实前缀撞车，也不会撑爆 key）。 */
    static String normalizePrefix(String tokenPrefix) {
        if (tokenPrefix == null || tokenPrefix.isBlank()) {
            return "unknown";
        }
        String trimmed = tokenPrefix.trim();
        return trimmed.length() <= MAX_PREFIX_LENGTH ? trimmed : trimmed.substring(0, MAX_PREFIX_LENGTH);
    }
}
