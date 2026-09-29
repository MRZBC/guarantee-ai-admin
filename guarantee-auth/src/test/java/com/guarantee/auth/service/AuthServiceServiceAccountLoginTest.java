package com.guarantee.auth.service;

import com.guarantee.auth.dto.LoginRequest;
import com.guarantee.auth.security.JwtTokenProvider;
import com.guarantee.auth.security.LoginAttemptGuard;
import com.guarantee.auth.security.TokenRevocationService;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.SessionRegistry;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务账号**不允许登录**的单元测试（T5-06）。
 *
 * <p>服务账号（{@code account_type='SERVICE'}）只作为机器身份被签发 MCP 凭据。
 * 允许它用密码登录，等于把机器凭据面暴露到人类登录面：若该账号还持有
 * {@code ai:mcp:read} 一类权限，人只要拿到它的密码就能拿到机器权限，
 * 而"服务账号"这个词本身就意味着"没有人在用它登录"。</p>
 *
 * <p>本测试盯住三件事：① 服务账号密码正确也登不进；② 返回的提示/业务码与"密码错误"
 * <b>完全相同</b>（避免账号枚举：攻击者不能靠提示区分出哪一个是服务账号）；
 * ③ HUMAN 账号不受影响（强校验只拦 SERVICE，不能顺手把普通账号一起拦掉）。</p>
 */
class AuthServiceServiceAccountLoginTest {

    private UserService userService;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider tokenProvider;
    private TokenRevocationService revocationService;
    private LoginAttemptGuard loginAttemptGuard;
    private SessionRegistry sessionRegistry;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenProvider = mock(JwtTokenProvider.class);
        revocationService = mock(TokenRevocationService.class);
        loginAttemptGuard = mock(LoginAttemptGuard.class);
        sessionRegistry = mock(SessionRegistry.class);
        authService = new AuthService(userService, passwordEncoder, tokenProvider,
                revocationService, loginAttemptGuard, sessionRegistry);
    }

    private static LoginRequest request(String username) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword("Whatever@123");
        return request;
    }

    private static SysUser user(String username, String accountType) {
        SysUser user = new SysUser();
        user.setId(1001L);
        user.setUsername(username);
        user.setPassword("$2a$10$hash");
        user.setRealName("测试用户");
        user.setStatus(1);
        user.setIsDeleted(0);
        user.setAccountType(accountType);
        return user;
    }

    @Test
    @DisplayName("SERVICE 账号密码正确也登不进，且提示/业务码与密码错误完全一致")
    void serviceAccountCannotLogin() {
        when(userService.getEntityByUsername("svc_agent")).thenReturn(user("svc_agent", "SERVICE"));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        BizException serviceFailure = (BizException) org.assertj.core.api.Assertions
                .catchThrowable(() -> authService.login(request("svc_agent"), "127.0.0.1"));
        assertThat(serviceFailure).isNotNull();
        assertThat(serviceFailure.getCode()).isEqualTo(ResultCode.LOGIN_FAILED.code());
        assertThat(serviceFailure.getMessage()).isEqualTo(ResultCode.LOGIN_FAILED.message());

        // 与"密码错误"的失败完全一致：同样的业务码与文案（账号枚举防护）
        when(userService.getEntityByUsername("svc_agent")).thenReturn(user("svc_agent", "SERVICE"));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        Throwable wrongPassword = org.assertj.core.api.Assertions
                .catchThrowable(() -> authService.login(request("svc_agent"), "127.0.0.1"));
        assertThat(wrongPassword).isInstanceOf(BizException.class);
        assertThat(((BizException) wrongPassword).getCode()).isEqualTo(serviceFailure.getCode());
        assertThat(wrongPassword.getMessage()).isEqualTo(serviceFailure.getMessage());

        // 两次失败都必须计入失败次数（服务账号 1 次 + 密码错误 1 次），
        // 否则"试几次会不会被锁"就成了识别服务账号的侧信道
        verify(loginAttemptGuard, org.mockito.Mockito.times(2))
                .recordFailure("svc_agent", "127.0.0.1");
        // 一个令牌都没签发
        verify(tokenProvider, never()).createToken(any(), anyList(), anyList());
        verify(userService, never()).updateLastLoginAt(anyLong());
    }

    @Test
    @DisplayName("HUMAN 账号不受影响：服务账号强校验不会顺手拦掉普通账号")
    void humanAccountPassesServiceAccountCheck() {
        when(userService.getEntityByUsername("human_user")).thenReturn(user("human_user", "HUMAN"));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(userService.listRoleCodesByUserId(1001L)).thenReturn(List.of("VIEWER"));
        when(userService.listPermissionCodesByUserId(1001L)).thenReturn(List.of("dashboard:view"));
        // 用一个哨兵异常证明流程"走过了服务账号校验"（后面编排整条成功链路与本测试无关）
        when(tokenProvider.createToken(any(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("past-service-account-check"));

        assertThatThrownBy(() -> authService.login(request("human_user"), "127.0.0.1"))
                .hasMessage("past-service-account-check");

        verify(loginAttemptGuard, never()).recordFailure(anyString(), anyString());
    }

    @Test
    @DisplayName("account_type 缺失（NULL）按 HUMAN：老数据不会被误拒登录")
    void missingAccountTypeIsTreatedAsHuman() {
        SysUser legacy = user("legacy_user", null);
        when(userService.getEntityByUsername("legacy_user")).thenReturn(legacy);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(userService.listRoleCodesByUserId(1001L)).thenReturn(List.of("ADMIN"));
        when(userService.listPermissionCodesByUserId(1001L)).thenReturn(List.of("dashboard:view"));
        when(tokenProvider.createToken(any(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("past-service-account-check"));

        assertThatThrownBy(() -> authService.login(request("legacy_user"), "127.0.0.1"))
                .hasMessage("past-service-account-check");

        verify(loginAttemptGuard, never()).recordFailure(anyString(), anyString());
    }
}
