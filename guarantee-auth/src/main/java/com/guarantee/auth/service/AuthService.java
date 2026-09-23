package com.guarantee.auth.service;

import com.guarantee.auth.dto.LoginRequest;
import com.guarantee.auth.security.JwtTokenProvider;
import com.guarantee.auth.security.LoginAttemptGuard;
import com.guarantee.auth.security.TokenRevocationService;
import com.guarantee.auth.vo.CurrentUserVO;
import com.guarantee.auth.vo.LoginResponse;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.SessionInfo;
import com.guarantee.common.security.SessionRegistry;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.service.UserService;
import com.guarantee.system.vo.UserVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private final LoginAttemptGuard loginAttemptGuard;
    private final SessionRegistry sessionRegistry;

    public AuthService(UserService userService,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       TokenRevocationService revocationService,
                       LoginAttemptGuard loginAttemptGuard,
                       SessionRegistry sessionRegistry) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.revocationService = revocationService;
        this.loginAttemptGuard = loginAttemptGuard;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * 账号密码登录。
     *
     * <p>用户名不存在与密码错误返回同一提示，避免账号枚举（LD-T8）。</p>
     *
     * <p><b>执行顺序有硬性要求</b>（AUTH-01）：</p>
     * <ol>
     *   <li><b>先判锁定</b>——必须早于 {@code passwordEncoder.matches}，否则攻击者仍能强制
     *       服务端为每次尝试做一次 BCrypt，形成 CPU 耗尽型拒绝服务；</li>
     *   <li>再做既有校验（用户查询 → 密码 → 逻辑删除 → 停用），逻辑与顺序保持不变；</li>
     *   <li>失败一律计数（**包括用户名不存在的情况**），否则"会不会被锁"就成了账号枚举侧信道；</li>
     *   <li>成功清除该账号的失败计数。</li>
     * </ol>
     *
     * @param request  登录请求
     * @param clientIp 客户端 IP（由 Controller 解析，见 {@code LoginAttemptGuard#resolveClientIp}）
     */
    public LoginResponse login(LoginRequest request, String clientIp) {
        String username = request.getUsername();
        loginAttemptGuard.assertNotLocked(username, clientIp);

        SysUser user = userService.getEntityByUsername(username);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptGuard.recordFailure(username, clientIp);
            log.warn("登录失败 username={}", username);
            throw new BizException(ResultCode.LOGIN_FAILED);
        }
        // LD-05：已删除用户不可登录，且必须返回**与密码错误完全相同**的提示，避免账号枚举。
        // 注意顺序：删除校验放在停用校验之前——"已删除且已停用"的账号也必须只说"用户名或密码错误"。
        // 另外 selectByUsername 已加 is_deleted = 0（LD-05b），这里是第二道保险。
        if (user.getIsDeleted() != null && user.getIsDeleted() == 1) {
            // 计入失败次数：对攻击者而言"已删除账号"与"密码错误"必须完全不可区分，
            // 包括在锁定行为上。若这里不计数，就能靠"试几次会不会被锁"识别出已删除账号。
            loginAttemptGuard.recordFailure(username, clientIp);
            log.warn("登录失败（账号已逻辑删除） username={}", username);
            throw new BizException(ResultCode.LOGIN_FAILED);
        }
        // 停用账号**不计入失败次数**：密码是正确的，不存在需要爆破的对象；
        // 且 ACCOUNT_DISABLED 本就已明确暴露账号存在（既有行为），无需在此维持不可区分性。
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }

        List<String> roles = userService.listRoleCodesByUserId(user.getId());
        List<String> permissions = userService.listPermissionCodesByUserId(user.getId());

        JwtTokenProvider.IssuedToken issued = tokenProvider.createToken(user, roles, permissions);
        // 白名单 TTL = 空闲超时，但不得超过绝对上限（AUTH-04）
        long idleTtlSeconds = Math.min(tokenProvider.getIdleTimeoutSeconds(),
                tokenProvider.getAbsoluteExpireSeconds());
        // fail-closed 下登记失败会抛 AUTH_UNAVAILABLE —— 这是必需的：未登记的令牌在
        // 白名单模式下等于无效令牌，静默吞掉会让用户陷入"登录成功但每个请求都 401"的死循环（AUTH-02）
        revocationService.register(issued.tokenId(), user.getId(), idleTtlSeconds);

        // 会话记录只服务运维展示，不是鉴权输入；登记失败不影响登录（实现内部已记 WARN）
        sessionRegistry.register(new SessionInfo(
                issued.tokenId(), user.getId(), user.getUsername(), user.getRealName(),
                Instant.now(), issued.expiresAt().toInstant(), null, clientIp, null),
                idleTtlSeconds);

        userService.updateLastLoginAt(user.getId());
        loginAttemptGuard.recordSuccess(username, clientIp);

        log.info("登录成功 userId={} username={} roles={}", user.getId(), user.getUsername(), roles);
        return new LoginResponse(issued.token(), "Bearer",
                tokenProvider.getAbsoluteExpireSeconds(), tokenProvider.getIdleTimeoutSeconds(),
                buildCurrentUser(user.getId(), roles, permissions));
    }

    /** 当前登录用户。 */
    public CurrentUserVO currentUser(Long userId) {
        return buildCurrentUser(userId,
                userService.listRoleCodesByUserId(userId),
                userService.listPermissionCodesByUserId(userId));
    }

    /**
     * 登出：撤销当前令牌并清除其在线会话记录。
     *
     * <p><b>不抛出异常</b>（AC-55）：登出必须幂等且恒成功。若 Redis 不可用，令牌本就已经
     * 无法通过校验（fail-closed），此时向用户报错没有任何补救价值，只会让前端多一个
     * 需要处理的分支。失败只记日志。</p>
     */
    public void logout(String tokenId) {
        if (tokenId == null) {
            return;
        }
        try {
            sessionRegistry.terminate(tokenId);
        } catch (RuntimeException ex) {
            log.warn("登出时撤销令牌失败（令牌本身即将失效，不影响登出结果）：jti={} {}",
                    tokenId, ex.getMessage());
        }
    }

    private CurrentUserVO buildCurrentUser(Long userId, List<String> roles, List<String> permissions) {
        UserVO user = userService.getById(userId);
        return new CurrentUserVO(user.getId(), user.getUsername(), user.getRealName(),
                user.getDeptId(), user.getDeptName(),
                roles, permissions);
    }
}
