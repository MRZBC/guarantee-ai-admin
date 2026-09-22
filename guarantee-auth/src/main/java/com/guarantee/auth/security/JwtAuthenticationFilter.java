package com.guarantee.auth.security;

import com.guarantee.common.security.CurrentUser;
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
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>解析 {@code Authorization: Bearer <token>}，同时写入 Spring Security 上下文
 * 与业务侧 {@link CurrentUser}，使业务模块无需依赖 Spring Security。</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final TokenRevocationService revocationService;
    private final UserTokenRevocation userTokenRevocation;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   TokenRevocationService revocationService,
                                   UserTokenRevocation userTokenRevocation) {
        this.tokenProvider = tokenProvider;
        this.revocationService = revocationService;
        this.userTokenRevocation = userTokenRevocation;
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
            if (!revocationService.isActive(claims)) {
                log.debug("令牌已失效（已登出或已撤销）");
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
            // 权限码与角色必须一并写入 Principal：AI 工具线程拿不到 SecurityContext，
            // 只能依赖随 ToolContext 下传的这份快照（SYS-P-02 / SYS-P-03）。
            CurrentUser.set(new CurrentUser.Principal(userId, username,
                    JwtTokenProvider.realName(claims),
                    JwtTokenProvider.roles(claims), JwtTokenProvider.permissions(claims)));

            List<SimpleGrantedAuthority> authorities = JwtTokenProvider.permissions(claims).stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            authentication.setDetails(userId);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ex) {
            // 令牌非法/过期：保持匿名，交由 AuthenticationEntryPoint 返回 401
            log.debug("JWT 校验失败: {}", ex.getMessage());
        }
    }
}
