package com.guarantee.system.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.SessionInfo;
import com.guarantee.common.security.SessionRegistry;
import com.guarantee.system.dto.SessionDto;
import com.guarantee.system.vo.SessionKickVO;
import com.guarantee.system.vo.SessionVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线会话服务（AUTH-05）。
 *
 * <p><b>权限判定不在这里</b>：{@code @PreAuthorize} 是安全边界，由 Controller 承担（SYS-P-01）；
 * 本服务只做过滤、分页、VO 转换与审计。</p>
 *
 * <p><b>分页在内存里做</b>：数据源是 Redis 的在线会话集合（管理员后台量级，数十条），
 * 不值得为它引入 Redis 侧分页的复杂度。若未来在线人数进入千级，再考虑分页下沉。</p>
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    /** 审计用的目标类型（AUTH-05 §4.5.7）。 */
    private static final String TARGET_TYPE = "SESSION";

    private static final String ACTION_KICK = "KICK";
    private static final String ACTION_KICK_ALL = "KICK_ALL";

    private final ObjectProvider<SessionRegistry> registryProvider;
    private final WebAuditor webAuditor;

    public SessionService(ObjectProvider<SessionRegistry> registryProvider, WebAuditor webAuditor) {
        this.registryProvider = registryProvider;
        this.webAuditor = webAuditor;
    }

    /**
     * 取会话注册表实现。
     *
     * <p><b>为什么用 {@link ObjectProvider} 而不是直接注入</b>：实现位于 {@code guarantee-auth}
     * （它依赖 Redis 与令牌白名单），只有完整应用（guarantee-web）才装配。
     * {@code guarantee-system} 自己的模块级集成测试用的是一个只扫描本模块的切片上下文，
     * 那里没有该 Bean——直接注入会让**整个切片上下文都建不起来**（连不相关的用例一起失败）。
     * 这与 {@link WebAuditor} 对 {@code OperationAuditPort} 的处理是同一个手法。</p>
     *
     * <p>与 {@code WebAuditor} 的差别在于：审计缺失时静默跳过是安全的（写操作会经其它路径留痕），
     * 而"查看在线会话"缺失时返回空列表会让管理员误以为真的没人在线。因此这里**显式失败**。</p>
     */
    private SessionRegistry registry() {
        SessionRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            throw new BizException("在线会话能力未装配（缺少 SessionRegistry 实现）。"
                    + "该能力由 guarantee-auth 提供，仅完整应用（guarantee-web）可用。");
        }
        return registry;
    }

    /**
     * 在线会话分页列表。
     *
     * @param query      过滤条件
     * @param currentJti 当前请求所用的 jti，用于标记 {@code current} 列
     */
    public PageResult<SessionVO> page(SessionDto.Query query, String currentJti) {
        List<SessionInfo> matched = registry().listAll().stream()
                .filter(info -> matches(info, query))
                .toList();

        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.getPageSize(), matched.size());
        List<SessionVO> list = matched.subList(from, to).stream()
                .map(info -> toVO(info, currentJti))
                .toList();
        return PageResult.of(query.getPageNum(), query.getPageSize(), matched.size(), list);
    }

    /**
     * 踢出单个会话。
     *
     * <p><b>没有共享事务可用</b>：会话状态在 Redis，审计在 MySQL，两者无法同事务。
     * 因此顺序固定为"先踢、后审计"——若反过来，一旦踢出失败就会留下一条"KICK 成功"的
     * 假审计；而"操作已生效但接口报错"至少不会污染审计的可信度（审计的全部价值在于
     * 每条记录都真实发生过）。</p>
     */
    public SessionKickVO kick(String jti, String currentJti) {
        SessionInfo info = find(jti);
        boolean self = jti != null && jti.equals(currentJti);

        boolean terminated = registry().terminate(jti);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("jti", jti);
        after.put("terminated", terminated);
        after.put("selfKicked", self);
        if (info != null) {
            after.put("loginIp", info.loginIp());
            after.put("loginAt", String.valueOf(info.loginAt()));
        }
        webAuditor.success(ACTION_KICK, TARGET_TYPE,
                info == null ? null : info.userId(),
                info == null ? "未知会话" : info.username(),
                null, after);

        log.info("强制下线单个会话 jti={} 目标用户={} 是否为自己={} 实际终止={}",
                jti, info == null ? null : info.userId(), self, terminated);
        return new SessionKickVO(terminated ? 1 : 0, self);
    }

    /** 踢出某用户的全部会话。 */
    public SessionKickVO kickAllOfUser(Long userId, String currentJti) {
        List<SessionInfo> targets = registry().listAll().stream()
                .filter(info -> userId != null && userId.equals(info.userId()))
                .toList();
        boolean self = targets.stream().anyMatch(info -> info.jti().equals(currentJti));

        int kicked = registry().terminateAllForUsers(List.of(userId));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("targetUserId", userId);
        after.put("kicked", kicked);
        after.put("selfKicked", self);
        webAuditor.success(ACTION_KICK_ALL, TARGET_TYPE, userId,
                targets.isEmpty() ? String.valueOf(userId) : targets.get(0).username(),
                null, after);

        log.info("强制下线用户 {} 的全部会话，实际终止 {} 个，是否包含自己={}", userId, kicked, self);
        return new SessionKickVO(kicked, self);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private SessionInfo find(String jti) {
        if (jti == null) {
            return null;
        }
        return registry().listAll().stream()
                .filter(info -> jti.equals(info.jti()))
                .findFirst()
                .orElse(null);
    }

    private static boolean matches(SessionInfo info, SessionDto.Query query) {
        if (query.getUserId() != null && !query.getUserId().equals(info.userId())) {
            return false;
        }
        if (StringUtils.hasText(query.getUsername())
                && !query.getUsername().equals(info.username())) {
            return false;
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            return contains(info.username(), keyword) || contains(info.realName(), keyword);
        }
        return true;
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.contains(keyword);
    }

    private static SessionVO toVO(SessionInfo info, String currentJti) {
        SessionVO vo = new SessionVO();
        vo.setJti(info.jti());
        vo.setUserId(info.userId());
        vo.setUsername(info.username());
        vo.setRealName(info.realName());
        vo.setLoginAt(toLocalDateTime(info.loginAt()));
        vo.setIdleExpiresAt(toLocalDateTime(info.idleExpiresAt()));
        vo.setAbsoluteExpiresAt(toLocalDateTime(info.absoluteExpiresAt()));
        vo.setLoginIp(info.loginIp());
        vo.setUserAgent(info.userAgent());
        vo.setCurrent(info.jti() != null && info.jti().equals(currentJti));
        return vo;
    }

    private static LocalDateTime toLocalDateTime(java.time.Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
