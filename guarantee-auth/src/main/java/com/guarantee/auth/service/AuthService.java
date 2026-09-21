package com.guarantee.auth.service;

import com.guarantee.auth.dto.LoginRequest;
import com.guarantee.auth.security.JwtTokenProvider;
import com.guarantee.auth.security.TokenRevocationService;
import com.guarantee.auth.vo.CurrentUserVO;
import com.guarantee.auth.vo.LoginResponse;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.service.UserService;
import com.guarantee.system.vo.UserVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 认证服务：登录、当前用户、登出。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final TokenRevocationService revocationService;

    public AuthService(UserService userService,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       TokenRevocationService revocationService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.revocationService = revocationService;
    }

    /**
     * 账号密码登录。
     *
     * <p>用户名不存在与密码错误返回同一提示，避免账号枚举。</p>
     */
    public LoginResponse login(LoginRequest request) {
        SysUser user = userService.getEntityByUsername(request.getUsername());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("登录失败 username={}", request.getUsername());
            throw new BizException(ResultCode.LOGIN_FAILED);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }

        List<String> roles = userService.listRoleCodesByUserId(user.getId());
        List<String> permissions = userService.listPermissionCodesByUserId(user.getId());

        JwtTokenProvider.IssuedToken issued = tokenProvider.createToken(user, roles, permissions);
        revocationService.register(issued.tokenId(), user.getId(), issued.expiresAt());
        userService.updateLastLoginAt(user.getId());

        log.info("登录成功 userId={} username={} roles={}", user.getId(), user.getUsername(), roles);
        return new LoginResponse(issued.token(), "Bearer", tokenProvider.getExpireSeconds(),
                buildCurrentUser(user.getId(), roles, permissions));
    }

    /** 当前登录用户。 */
    public CurrentUserVO currentUser(Long userId) {
        return buildCurrentUser(userId,
                userService.listRoleCodesByUserId(userId),
                userService.listPermissionCodesByUserId(userId));
    }

    /** 登出：撤销当前令牌。 */
    public void logout(String tokenId) {
        if (tokenId != null) {
            revocationService.revoke(tokenId);
        }
    }

    private CurrentUserVO buildCurrentUser(Long userId, List<String> roles, List<String> permissions) {
        UserVO user = userService.getById(userId);
        return new CurrentUserVO(user.getId(), user.getUsername(), user.getRealName(),
                user.getOrgId(), user.getOrgName(), user.getDeptId(), user.getDeptName(),
                roles, permissions);
    }
}
