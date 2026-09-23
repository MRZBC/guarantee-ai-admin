package com.guarantee.auth.controller;

import com.guarantee.auth.dto.LoginRequest;
import com.guarantee.auth.security.JwtTokenProvider;
import com.guarantee.auth.security.LoginAttemptGuard;
import com.guarantee.auth.service.AuthService;
import com.guarantee.auth.vo.CurrentUserVO;
import com.guarantee.auth.vo.LoginResponse;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final JwtTokenProvider tokenProvider;
    private final LoginAttemptGuard loginAttemptGuard;

    public AuthController(AuthService authService,
                          JwtTokenProvider tokenProvider,
                          LoginAttemptGuard loginAttemptGuard) {
        this.authService = authService;
        this.tokenProvider = tokenProvider;
        this.loginAttemptGuard = loginAttemptGuard;
    }

    /**
     * 账号密码登录。
     *
     * <p>客户端 IP 由本层解析后传入服务层：具体取值策略（是否信任 {@code X-Forwarded-For}）
     * 属于 Web 层关注点，由 {@link LoginAttemptGuard} 统一持有（AUTH-01 §4.1.5）。</p>
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        return Result.ok(authService.login(request, loginAttemptGuard.resolveClientIp(httpRequest)));
    }

    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return Result.ok(authService.currentUser(userId));
    }

    /**
     * 登出：把当前令牌加入撤销列表。**即使令牌已过期也返回成功**（AUTH-06）。
     *
     * <p>两点保证这个承诺成立：</p>
     * <ol>
     *   <li>{@code SecurityConfig} 已把本路径放进 {@code permitAll}，令牌失效时请求不会被
     *       拦成 401。否则前端会走「登录已失效」分支并 {@code window.location.reload()}，
     *       用户主动登出反而看到报错 + 整页刷新；</li>
     *   <li>过期令牌用 {@link ExpiredJwtException#getClaims()} 仍能取到 {@code jti}
     *       —— 异常是在校验 {@code exp} 时抛出的，claims 本身已经解析完成。据此清理会话记录，
     *       否则"过期令牌登出"会在在线会话列表里留下幽灵记录（AUTH-05）。</li>
     * </ol>
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                Claims claims = tokenProvider.parse(token);
                authService.logout(JwtTokenProvider.tokenId(claims));
            } catch (ExpiredJwtException ex) {
                // 过期令牌：claims 仍然可用，照常撤销（令牌早已无法通过校验，但会话记录需要清理）
                authService.logout(JwtTokenProvider.tokenId(ex.getClaims()));
            } catch (JwtException | IllegalArgumentException ex) {
                // 签名错误 / 格式非法：取不出 jti，也没有可清理的会话
            }
        }
        return Result.ok();
    }
}
