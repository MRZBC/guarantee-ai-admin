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
import org.springframework.web.bind.annotation.PutMapping;
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
     * 用户自助修改自己的密码（P-10 / §5.5）。
     *
     * <p><b>本路径是"强制改密闸门"的白名单之一</b>：处于首次登录强制改密状态的用户，
     * 除改密 / 登出 / 读自己外的一切请求都会被 {@code PasswordChangeRequiredFilter} 拒绝。
     * 若漏掉这里，用户被要求改密却没有任何入口——账号直接变砖。</p>
     *
     * <p><b>不接受任何"改谁的密码"参数</b>：目标恒为当前登录用户。一旦接受该参数，
     * 越权就只是传错一个参数的事。</p>
     *
     * <p>成功后服务端会<b>撤销该用户全部令牌</b>，因此前端应提示"请用新密码重新登录"。
     * 这样做是必需的：旧令牌里的 {@code mcp} claim 恒为 true，不撤销的话用户改完密码
     * 仍会被闸门拦住（表现为"改了但没生效"）。</p>
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        authService.changePassword(userId, request.oldPassword(), request.newPassword());
        return Result.ok();
    }

    /**
     * 自助改密请求体。
     *
     * <p>这是全系统**唯一**会出现明文密码的请求体（用户自己的凭据）。密码不落库明文，
     * 只存 BCrypt 散列；审计与日志都不记录它。</p>
     */
    public record ChangePasswordRequest(
            @jakarta.validation.constraints.NotBlank(message = "原密码不能为空")
            String oldPassword,
            @jakarta.validation.constraints.NotBlank(message = "新密码不能为空")
            String newPassword) {
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
