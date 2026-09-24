package com.guarantee.auth.security;

import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 「首次登录强制改密」闸门（P-10 / §5.4，D1=C）。
 *
 * <p><b>这是 D1=C 唯一真正的安全边界。</b>前端守卫（登录分流 / 路由守卫 / 403-1006 拦截）
 * 都只是体验优化——用户直接调接口照样能绕过。因此必须有一个服务端强制的闸门：
 * 处于强制改密状态的令牌，除白名单外的一切请求一律拒绝。</p>
 *
 * <p><b>触发条件</b>：{@link JwtAuthenticationFilter} 解析 JWT 的 {@code mcp} claim 为 true 时，
 * 会为 Authentication 追加 {@link #AUTHORITY_MUST_CHANGE_PASSWORD} authority。本过滤器只认这个
 * authority，不自己解析令牌，也不回查数据库。</p>
 *
 * <p><b>为什么用 claim 而不是查库</b>：与 {@code JwtTokenProvider} 的既有取向一致
 * （"权限编码直接写入令牌，避免每个请求都回查数据库"）。查库方案会给每个请求加一次查询。</p>
 *
 * <p><b>白名单必须完整</b>：漏掉任何一项都会让用户被困死，且只能靠清浏览器存储自救——
 * 例如漏掉登出，用户被关在改密页却连退出都做不到。</p>
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeRequiredFilter.class);

    /** 由 {@link JwtAuthenticationFilter} 在 mcp=true 时附加到 Authentication 上。 */
    public static final String AUTHORITY_MUST_CHANGE_PASSWORD = "PWD_CHANGE_REQUIRED";

    /**
     * 强制改密状态下仍必须放行的路径。
     *
     * <ul>
     *   <li>{@code /api/auth/password}：改密本身——不放行就是死锁（用户被要求改密却没有改密入口）；</li>
     *   <li>{@code /api/auth/logout}：不能把用户困住。与 SecurityConfig 里对登出放行的既有理由一致
     *       （"登出的语义承诺是即使令牌已过期也返回成功"）；</li>
     *   <li>{@code /api/auth/me}：前端需要读 {@code mustChangePassword} 才能正确分流。</li>
     * </ul>
     */
    private static final List<String> ALLOWED_PATHS = List.of(
            "/api/auth/password", "/api/auth/logout", "/api/auth/me");

    private final ObjectMapper objectMapper;

    public PasswordChangeRequiredFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!requiresPasswordChange()) {
            filterChain.doFilter(request, response);
            return;
        }
        // CORS 预检不带业务语义，必须放行：否则浏览器在拿到 403 时连错误响应体都读不到，
        // 前端只能落到兜底文案。SecurityConfig 已对 OPTIONS 全放行，这里保持一致。
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (ALLOWED_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.debug("拒绝请求：账号处于强制改密状态 path={}", path);
        writePasswordChangeRequired(response);
    }

    private static boolean requiresPasswordChange() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (AUTHORITY_MUST_CHANGE_PASSWORD.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 直接写响应，不继续过滤器链。
     *
     * <p>用 {@link ResultCode#PASSWORD_CHANGE_REQUIRED}（业务码 1006）而不是裸 403：
     * 前端拦截器对 403 的默认处理是"提示没有权限 + 跳登录页"，若与本状态混同，
     * 用户会被踢回登录页、登录后又被闸门拦回来——形成死循环。</p>
     */
    private void writePasswordChangeRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> body = Result.fail(ResultCode.PASSWORD_CHANGE_REQUIRED);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
