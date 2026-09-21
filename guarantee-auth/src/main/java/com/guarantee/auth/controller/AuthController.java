package com.guarantee.auth.controller;

import com.guarantee.auth.dto.LoginRequest;
import com.guarantee.auth.security.JwtTokenProvider;
import com.guarantee.auth.service.AuthService;
import com.guarantee.auth.vo.CurrentUserVO;
import com.guarantee.auth.vo.LoginResponse;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
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

    public AuthController(AuthService authService, JwtTokenProvider tokenProvider) {
        this.authService = authService;
        this.tokenProvider = tokenProvider;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return Result.ok(authService.currentUser(userId));
    }

    /** 登出：把当前令牌加入撤销列表。即使令牌已过期也返回成功。 */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                authService.logout(JwtTokenProvider.tokenId(tokenProvider.parse(token)));
            } catch (JwtException | IllegalArgumentException ignored) {
                // 令牌已失效，无需撤销
            }
        }
        return Result.ok();
    }
}
