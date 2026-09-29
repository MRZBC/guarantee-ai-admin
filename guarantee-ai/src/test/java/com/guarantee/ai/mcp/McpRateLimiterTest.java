package com.guarantee.ai.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * MCP 限流与配额（TEST-MCP-06 的单测部分）。
 *
 * <p>用一个**手写的 {@link StringRedisTemplate} 替身**，而不是 Mockito：
 * 限流的核心断言之一是"Redis 键里到底放了什么"（只能放 Token 前缀，不能放明文/哈希），
 * 替身能直接暴露键集合；Mockito 只能断言"被调过"，证不了这件事。</p>
 */
class McpRateLimiterTest {

    private static final String PREFIX = "mcp_9f3c2a";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private FakeRedis redis;
    private McpRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
    }

    private McpRateLimiter limiter(int qpsLimit, long dailyQuota) {
        return McpRateLimiter.withClock(redis, qpsLimit, dailyQuota, FIXED);
    }

    // ==================================================================
    // 正常与超限
    // ==================================================================

    @Test
    @DisplayName("未超限：不抛异常，且 QPS 与配额各计一次")
    void underLimitPasses() {
        limiter = limiter(5, 100);

        for (int i = 0; i < 5; i++) {
            limiter.check(PREFIX);
        }

        assertThat(redis.counters.keySet())
                .as("必须同时统计 QPS（按秒）与每日配额（按日）")
                .anySatisfy(key -> assertThat(key).startsWith(McpRateLimiter.QPS_KEY_PREFIX + PREFIX + ":"))
                .anySatisfy(key -> assertThat(key).startsWith(McpRateLimiter.QUOTA_KEY_PREFIX + PREFIX + ":"));
    }

    @Test
    @DisplayName("QPS 超限：可读中文 + RATE_LIMITED(429)，不是 500")
    void qpsExceededIsReadable() {
        limiter = limiter(2, 1000);

        limiter.check(PREFIX);
        limiter.check(PREFIX);
        McpException ex = catchThrowableOfType(() -> limiter.check(PREFIX), McpException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(McpErrorCode.RATE_LIMITED);
        assertThat(ex.httpStatus()).isEqualTo(429);
        assertThat(ex.getMessage()).contains("调用过于频繁").contains("每秒最多 2 次");
    }

    @Test
    @DisplayName("每日配额超限：可读中文 + DAILY_QUOTA_EXCEEDED(429)，且优先于 QPS 报出")
    void dailyQuotaExceededIsReadable() {
        limiter = limiter(1000, 2);

        limiter.check(PREFIX);
        limiter.check(PREFIX);
        McpException ex = catchThrowableOfType(() -> limiter.check(PREFIX), McpException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(McpErrorCode.DAILY_QUOTA_EXCEEDED);
        assertThat(ex.httpStatus()).isEqualTo(429);
        assertThat(ex.getMessage()).contains("每日调用配额").contains("2 次/天");
    }

    @Test
    @DisplayName("不同 Token 前缀各自计数（限流按 Token 维度，不串号）")
    void countersAreIsolatedPerToken() {
        limiter = limiter(1, 1000);

        limiter.check("mcp_aaaaaa");
        limiter.check("mcp_bbbbbb");

        assertThat(redis.counters.keySet())
                .anySatisfy(key -> assertThat(key).contains("mcp_aaaaaa"))
                .anySatisfy(key -> assertThat(key).contains("mcp_bbbbbb"));
    }

    // ==================================================================
    // fail-closed
    // ==================================================================

    @Test
    @DisplayName("Redis 不可用 → fail-closed：可读拒绝，绝不放行")
    void redisFailureFailsClosed() {
        limiter = limiter(5, 100);
        redis.down = true;

        McpException ex = catchThrowableOfType(() -> limiter.check(PREFIX), McpException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(McpErrorCode.LIMITER_UNAVAILABLE);
        assertThat(ex.getMessage()).contains("限流服务暂不可用").contains("fail-closed");
    }

    // ==================================================================
    // 键的纪律（AC-MCP-08 的同类口径：不把凭据指纹写进缓存）
    // ==================================================================

    @Test
    @DisplayName("Redis 键只含 Token 前缀：不含明文、不含哈希")
    void keysContainOnlyPrefix() {
        limiter = limiter(5, 100);
        limiter.check(PREFIX);

        String hashLike = McpTokenService.sha256Hex("mcp_super_secret_plaintext_value");
        assertThat(redis.counters.keySet()).isNotEmpty();
        for (String key : redis.counters.keySet()) {
            assertThat(key).contains(PREFIX);
            assertThat(key).doesNotContain(hashLike);
            assertThat(key).doesNotContain("mcp_super_secret_plaintext_value");
        }
    }

    @Test
    @DisplayName("前缀归一：null/空白 → unknown；超长截断（不撑爆 key 空间）")
    void prefixIsNormalized() {
        assertThat(McpRateLimiter.normalizePrefix(null)).isEqualTo("unknown");
        assertThat(McpRateLimiter.normalizePrefix("   ")).isEqualTo("unknown");
        assertThat(McpRateLimiter.normalizePrefix(" mcp_abc ")).isEqualTo("mcp_abc");
        assertThat(McpRateLimiter.normalizePrefix("x".repeat(100))).hasSize(32);
    }

    @Test
    @DisplayName("配额键带当日 TTL 参数（避免固定 24h 让配额跨日滑动）")
    void quotaKeyGetsTtl() {
        limiter = limiter(5, 100);
        limiter.check(PREFIX);

        String quotaKey = redis.counters.keySet().stream()
                .filter(key -> key.startsWith(McpRateLimiter.QUOTA_KEY_PREFIX))
                .findFirst().orElseThrow();
        // 固定时钟 2026-09-30T03:00:00Z = 北京时间 11:00 → 距当日 24:00 还有 13 小时
        assertThat(redis.ttls.get(quotaKey)).isEqualTo(13 * 3600L);
    }

    // ==================================================================
    // 替身
    // ==================================================================

    /** 只实现 {@code execute(script, keys, args)} 这一条被限流用到的路径。 */
    static final class FakeRedis extends StringRedisTemplate {

        final Map<String, Long> counters = new LinkedHashMap<>();
        final Map<String, Long> ttls = new LinkedHashMap<>();
        boolean down;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            if (down) {
                throw new IllegalStateException("redis connection refused");
            }
            String key = keys.get(0);
            ttls.put(key, Long.parseLong(String.valueOf(args[0])));
            return (T) Long.valueOf(counters.merge(key, 1L, Long::sum));
        }
    }
}
