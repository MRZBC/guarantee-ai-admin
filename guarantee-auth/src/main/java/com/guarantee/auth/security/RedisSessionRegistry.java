package com.guarantee.auth.security;

import com.guarantee.common.security.SessionInfo;
import com.guarantee.common.security.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在线会话注册表（AUTH-05，{@link SessionRegistry} 的 Redis 实现）。
 *
 * <h3>数据结构</h3>
 * <table>
 *   <tr><th>Key</th><th>类型</th><th>内容</th></tr>
 *   <tr><td>{@code guarantee:auth:session:{jti}}</td><td>Hash</td>
 *       <td>userId / username / realName / loginAt / absoluteExpiresAt / loginIp / userAgent</td></tr>
 *   <tr><td>{@code guarantee:auth:sessions}</td><td>ZSet</td>
 *       <td>member = jti，score = **空闲到期时刻**（epoch 秒）</td></tr>
 * </table>
 *
 * <h3>为什么用 ZSet 而不是"每用户一个 Set"</h3>
 * <p>原方案设计了一个用户维度索引 {@code user-sessions:{userId}}。实现时改为**单个以到期时刻
 * 为 score 的 ZSet**，收益有三：</p>
 * <ol>
 *   <li><b>过期自清理</b>：读时 {@code ZREMRANGEBYSCORE -inf now} 即可剔除失效会话，
 *       不需要定时任务，也不需要给索引设 TTL（"给一个集合设 TTL"本身就与"成员各自过期"矛盾）；</li>
 *   <li><b>少一份索引就少一类不一致</b>：两份索引之间的漂移正是"幽灵会话"的根源；</li>
 *   <li>枚举全部在线会话只需一次 {@code ZRANGE}，不必 {@code KEYS} / {@code SCAN}。</li>
 * </ol>
 * <p>"某用户的全部会话"由 {@code listAll()} 过滤得出——在线会话规模是管理员后台量级
 * （数十条），不值得为它引入第二份索引。</p>
 *
 * <h3>失败语义</h3>
 * <ul>
 *   <li>{@link #register} / {@link #touch}：**尽力而为**。会话记录只服务运维展示，
 *       不是鉴权输入（授权依据是令牌白名单），因此写失败只记 WARN，不影响登录与请求。</li>
 *   <li>{@link #terminate}：**不吞异常**。管理员点"踢出"必须得到真实结果，
 *       静默失败会让他以为已经踢掉。</li>
 *   <li>{@link #terminateAllForUsers}：尽力而为，与 {@code UserTokenRevoker} 的契约一致。</li>
 *   <li>{@link #listAll}：不吞异常。查询失败要让调用方看到错误，而不是返回"当前无人在线"。</li>
 * </ul>
 */
@Component
public class RedisSessionRegistry implements SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionRegistry.class);

    private static final String SESSION_PREFIX = "guarantee:auth:session:";
    private static final String INDEX_KEY = "guarantee:auth:sessions";

    private static final String F_USER_ID = "userId";
    private static final String F_USERNAME = "username";
    private static final String F_REAL_NAME = "realName";
    private static final String F_LOGIN_AT = "loginAt";
    private static final String F_ABSOLUTE_EXPIRES_AT = "absoluteExpiresAt";
    private static final String F_LOGIN_IP = "loginIp";
    private static final String F_USER_AGENT = "userAgent";

    private final StringRedisTemplate redisTemplate;
    private final TokenRevocationService revocationService;

    public RedisSessionRegistry(StringRedisTemplate redisTemplate,
                                TokenRevocationService revocationService) {
        this.redisTemplate = redisTemplate;
        this.revocationService = revocationService;
    }

    @Override
    public void register(SessionInfo info, long idleTtlSeconds) {
        if (info == null || info.jti() == null) {
            return;
        }
        long ttl = Math.max(1L, idleTtlSeconds);
        try {
            String key = sessionKey(info.jti());
            Map<String, String> fields = new java.util.HashMap<>();
            fields.put(F_USER_ID, info.userId() == null ? "" : String.valueOf(info.userId()));
            fields.put(F_USERNAME, nullSafe(info.username()));
            fields.put(F_REAL_NAME, nullSafe(info.realName()));
            fields.put(F_LOGIN_AT, String.valueOf(epochMillis(info.loginAt())));
            fields.put(F_ABSOLUTE_EXPIRES_AT, String.valueOf(epochMillis(info.absoluteExpiresAt())));
            fields.put(F_LOGIN_IP, nullSafe(info.loginIp()));
            fields.put(F_USER_AGENT, truncate(nullSafe(info.userAgent()), 512));
            redisTemplate.opsForHash().putAll(key, fields);
            // 先写 TTL 再入索引：即使此处失败，listAll 也会按 score 过期把它清掉，不会留下永久残留
            redisTemplate.expire(key, java.time.Duration.ofSeconds(ttl));
            redisTemplate.opsForZSet().add(INDEX_KEY, info.jti(), idleExpiryScore(ttl));
        } catch (RuntimeException ex) {
            log.warn("登记在线会话失败（仅影响在线列表可见性，不影响登录）：jti={} {}",
                    info.jti(), ex.getMessage());
        }
    }

    @Override
    public void touch(String jti, long idleTtlSeconds) {
        if (jti == null) {
            return;
        }
        long ttl = Math.max(1L, idleTtlSeconds);
        try {
            // 先探活：会话记录不存在时（例如本功能上线前签发的旧令牌）不得创建残缺记录。
            // EXPIRE 对不存在的 key 返回 false，正好用作存在性判定，无需额外往返。
            Boolean existed = redisTemplate.expire(sessionKey(jti), java.time.Duration.ofSeconds(ttl));
            if (Boolean.TRUE.equals(existed)) {
                redisTemplate.opsForZSet().add(INDEX_KEY, jti, idleExpiryScore(ttl));
            }
        } catch (RuntimeException ex) {
            log.warn("会话空闲续期失败（仅影响在线列表的到期时间显示）：jti={} {}", jti, ex.getMessage());
        }
    }

    @Override
    public boolean terminate(String jti) {
        if (jti == null) {
            return false;
        }
        // 顺序很重要：先让令牌失效（这是真正的鉴权依据），再清会话记录。
        // 若反过来，中途失败会得到"列表里已消失、但令牌仍然可用"的最坏组合。
        revocationService.revoke(jti);
        redisTemplate.delete(sessionKey(jti));
        Long removed = redisTemplate.opsForZSet().remove(INDEX_KEY, jti);
        return removed != null && removed > 0;
    }

    @Override
    public int terminateAllForUsers(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        Set<Long> targets = new LinkedHashSet<>(userIds);
        int removed = 0;
        // 不吞异常：调用方分两类——"踢出某用户全部会话"必须让管理员看到真实结果，
        // 而"用户级撤销"是尽力而为的，由 UserTokenRevocation 自己包 try-catch。
        // 在这里吞掉会让前者静默失败（AUTH-05）。
        for (SessionInfo info : listAll()) {
            if (info.userId() != null && targets.contains(info.userId())) {
                terminate(info.jti());
                removed++;
            }
        }
        return removed;
    }

    @Override
    public List<SessionInfo> listAll() {
        long nowSeconds = Instant.now().getEpochSecond();
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .rangeWithScores(INDEX_KEY, 0, -1);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }

        Set<String> expired = new LinkedHashSet<>();
        List<SessionInfo> result = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String jti = tuple.getValue();
            Double score = tuple.getScore();
            if (jti == null) {
                continue;
            }
            if (score == null || score <= nowSeconds) {
                expired.add(jti);
                continue;
            }
            Map<Object, Object> fields = redisTemplate.opsForHash().entries(sessionKey(jti));
            if (fields.isEmpty()) {
                // 索引成员对应的记录已不存在（TTL 先到 / 写入中途失败）：惰性自愈
                expired.add(jti);
                continue;
            }
            result.add(toSessionInfo(jti, fields, score));
        }

        if (!expired.isEmpty()) {
            redisTemplate.opsForZSet().remove(INDEX_KEY, expired.toArray());
            redisTemplate.delete(expired.stream().map(RedisSessionRegistry::sessionKey).toList());
        }
        // 登录时间倒序：最近登录的排前面
        result.sort((a, b) -> compareDesc(a.loginAt(), b.loginAt()));
        return result;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static SessionInfo toSessionInfo(String jti, Map<Object, Object> fields, double score) {
        return new SessionInfo(
                jti,
                parseLong(fields.get(F_USER_ID)),
                string(fields.get(F_USERNAME)),
                string(fields.get(F_REAL_NAME)),
                parseInstant(fields.get(F_LOGIN_AT)),
                parseInstant(fields.get(F_ABSOLUTE_EXPIRES_AT)),
                Instant.ofEpochSecond((long) score),
                string(fields.get(F_LOGIN_IP)),
                string(fields.get(F_USER_AGENT)));
    }

    private static int compareDesc(Instant a, Instant b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return b.compareTo(a);
    }

    private static String sessionKey(String jti) {
        return SESSION_PREFIX + jti;
    }

    private static double idleExpiryScore(long ttlSeconds) {
        return Instant.now().getEpochSecond() + ttlSeconds;
    }

    private static long epochMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static Instant parseInstant(Object value) {
        Long millis = parseLong(value);
        return millis == null || millis <= 0L ? null : Instant.ofEpochMilli(millis);
    }

    private static Long parseLong(Object value) {
        String text = string(value);
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
