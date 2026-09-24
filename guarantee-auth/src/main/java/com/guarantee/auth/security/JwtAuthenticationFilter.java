package com.guarantee.auth.security;

import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.SessionAttributes;
import com.guarantee.common.security.SessionRegistry;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Date;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>解析 {@code Authorization: Bearer <token>}，同时写入 Spring Security 上下文
 * 与业务侧 {@link CurrentUser}，使业务模块无需依赖 Spring Security。</p>
 *
 * <p>校验通过后还会做两件事：把当前 {@code jti} 写入请求属性（供在线会话列表标记
 * "这就是我自己"，AUTH-05），以及按需顺延空闲窗口（AUTH-04）。</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final TokenRevocationService revocationService;
    private final UserTokenRevocation userTokenRevocation;
    private final SessionRegistry sessionRegistry;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   TokenRevocationService revocationService,
                                   UserTokenRevocation userTokenRevocation,
                                   SessionRegistry sessionRegistry) {
        this.tokenProvider = tokenProvider;
        this.revocationService = revocationService;
        this.userTokenRevocation = userTokenRevocation;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            authenticate(request);
            filterChain.doFilter(request, response);
        } finally {
            // 必须在请求结束时清理，避免线程复用导致的身份串号
            CurrentUser.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return;
        }
        try {
            Claims claims = tokenProvider.parse(token);
            String tokenId = JwtTokenProvider.tokenId(claims);
            if (tokenId == null) {
                return;
            }

            // 一次 Redis 往返完成"是否有效 + 是否需要续期"（AUTH-02 / AUTH-04）
            TokenRevocationService.Validation validation =
                    revocationService.validate(tokenId, absoluteRemainingSeconds(claims));
            if (!validation.active()) {
                log.debug("令牌已失效（已登出、已踢出，或空闲超时；Redis 故障时按 fail-closed 判定）");
                return;
            }

            Long userId = JwtTokenProvider.userId(claims);
            // 用户级撤销：停用用户 / 调整角色或权限后，其**全部**已签发令牌立即失效（SYS-C-07 / AC-21）
            if (!userTokenRevocation.issuedAfterRevocation(userId, JwtTokenProvider.issuedAtMillis(claims))) {
                log.debug("令牌已失效（用户 {} 的权限或状态已变更，需重新登录）", userId);
                return;
            }
            String username = claims.getSubject();
            if (userId == null || !StringUtils.hasText(username)) {
                return;
            }

            // 续期成功后同步会话记录的到期时间，避免在线列表显示的到期时间与真实值漂移。
            // 注意：这一步只延长 TTL，不会重新签发 JWT——重签会产生新的 iat，
            // 使已被撤销用户的令牌复活（AUTH-04 §4.4.3 红线）。
            if (validation.renewed()) {
                sessionRegistry.touch(tokenId, validation.renewedTtlSeconds());
            }

            // 供系统管理域标记"当前会话"（AUTH-05）。随请求自动销毁，无需清理。
            request.setAttribute(SessionAttributes.CURRENT_JTI, tokenId);

            // 权限码与角色必须一并写入 Principal：AI 工具线程拿不到 SecurityContext，
            // 只能依赖随 ToolContext 下传的这份快照（SYS-P-02 / SYS-P-03）。
            CurrentUser.set(new CurrentUser.Principal(userId, username,
                    JwtTokenProvider.realName(claims),
                    JwtTokenProvider.roles(claims), JwtTokenProvider.permissions(claims)));

            List<SimpleGrantedAuthority> authorities = JwtTokenProvider.permissions(claims).stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            // 首次登录强制改密（P-10）：把令牌里的 mcp 提升为一个 authority，
            // 由 PasswordChangeRequiredFilter 消费。
            //
            // 刻意**不**改 CurrentUser.Principal：那是个 record，被 AiController 构造
            // ProposalExecutionContext、以及 AI 的 ToolContext 装配读取；为一个只在 HTTP
            // 鉴权层使用的标志去改它，会把改动扩散到 AI 模块。用 authority 承载最收敛——
            // 鉴权层自己消费，业务层无感。
            if (JwtTokenProvider.mustChangePassword(claims)) {
                authorities.add(new SimpleGrantedAuthority(
                        PasswordChangeRequiredFilter.AUTHORITY_MUST_CHANGE_PASSWORD));
            }
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            authentication.setDetails(userId);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ex) {
            // 令牌非法/过期：保持匿名，交由 AuthenticationEntryPoint 返回 401
            log.debug("JWT 校验失败: {}", ex.getMessage());
        }
    }

    /**
     * 距 JWT {@code exp} 的剩余秒数（绝对上限）。
     *
     * <p>空闲续期不得越过这个上限，否则"绝对上限"形同虚设——活跃用户可以无限期续下去。</p>
     */
    private static long absoluteRemainingSeconds(Claims claims) {
        Date expiration = JwtTokenProvider.expiration(claims);
        if (expiration == null) {
            return 0L;
        }
        return Math.max(0L, (expiration.getTime() - System.currentTimeMillis()) / 1000L);
    }
}
