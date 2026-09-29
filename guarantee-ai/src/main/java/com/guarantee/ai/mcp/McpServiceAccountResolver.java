package com.guarantee.ai.mcp;

import com.guarantee.common.exception.BizException;
import com.guarantee.system.service.UserService;
import com.guarantee.system.vo.UserVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 机器身份守卫：把「Token 校验结果」补全成可执行工具调用的主体，并在签发前强校验
 * {@code account_type=SERVICE}（REQ-MCP-02 / AC-MCP-02/03）。
 *
 * <p><b>为什么必须回查账号，而不直接把 Token 里的信息当主体</b>：Token 只承载
 * "是不是这把凭据 + 权限范围"，不承载"账号现在还在不在、有没有被停用、还是不是机器账号"。
 * 账号被停用后若仍能凭 Token 取数，等于"停用一个服务账号"这个动作对外部通道无效——
 * 那正是 REQ-MCP-02 要求"服务账号停用 → 其 Token 一并失效"要禁止的。</p>
 *
 * <p><b>为什么用 {@code getAccountIdentity} 而不是 {@code getAccountType}</b>：后者对
 * **不存在/已删除**的账号读不到类型，会把"账号已删除"当成普通人（HUMAN）——
 * 那是把最该拒绝的情况误判成"只是类型不对"。{@code getAccountIdentity} 一次性返回
 * {@code id/username/status/account_type}（**不含 password**），不存在返回 {@code null}，
 * 三种失败因此都能给出**各自的**可读原因。</p>
 *
 * <p><b>分层</b>：只调 {@code guarantee-system} 的 {@link UserService}，**不碰 Mapper、
 * 不写 SQL**（AI 侧分层铁律）。角色编码取 {@code UserVO.roleCodes}（Service 已回填），
 * 随后写进 {@code ToolContext} 供 {@code DataScopeService} 判定——与页面/助手同源
 * （红线 §2.3-1）。</p>
 */
@Component
public class McpServiceAccountResolver {

    private static final Logger log = LoggerFactory.getLogger(McpServiceAccountResolver.class);

    private final UserService userService;

    public McpServiceAccountResolver(UserService userService) {
        this.userService = userService;
    }

    // ==================================================================
    // 强校验：只有 SERVICE 账号可以承载机器凭据
    // ==================================================================

    /**
     * 断言这是一台**可用的机器身份**（存在 + 启用 + {@code account_type=SERVICE}）。
     *
     * <p>三种失败各自给可读原因，绝不合并成一句"参数不合法"——管理员要据此判断
     * "是账号不存在、被停用，还是压根选错了人类账号"。</p>
     *
     * @throws McpException 账号不存在/已删除、已停用、或不是服务账号
     */
    public UserService.AccountIdentity requireMachineIdentity(Long serviceAccountId) {
        if (serviceAccountId == null) {
            throw new McpException(McpErrorCode.INVALID_ARGUMENT, "必须指定服务账号（serviceAccountId）");
        }
        UserService.AccountIdentity identity = userService.getAccountIdentity(serviceAccountId);
        if (identity == null) {
            throw new McpException(McpErrorCode.ACCOUNT_DISABLED,
                    "服务账号不存在或已被删除（id=" + serviceAccountId + "）：不能为它签发 MCP Token");
        }
        if (!identity.enabled()) {
            throw new McpException(McpErrorCode.ACCOUNT_DISABLED,
                    "服务账号 " + identity.username() + " 已被停用：停用的账号不能签发 MCP Token");
        }
        if (!identity.serviceAccount()) {
            throw new McpException(McpErrorCode.ACCOUNT_DISABLED,
                    "账号 " + identity.username() + " 不能签发 MCP Token：它是人类账号（account_type="
                            + identity.accountType() + "）。MCP 凭据只能绑定服务账号"
                            + "（account_type=SERVICE，不参与登录）");
        }
        return identity;
    }

    /**
     * 管理接口用：签发 Token 前确认目标账号存在、启用**且是服务账号**，并返回只读视图
     * （供审计快照写"给谁签了凭据"）。
     */
    public UserVO requireEnabledAccount(Long serviceAccountId) {
        requireMachineIdentity(serviceAccountId);
        return loadAccount(serviceAccountId);
    }

    // ==================================================================
    // 使用期：组装调用主体
    // ==================================================================

    /**
     * 校验 Token 归属的服务账号并把主体组装出来。
     *
     * <p>使用期同样要求"还是机器身份"：账号被停用、或被改回人类账号、或被删除之后，
     * 之前签发的 Token 不应继续取数（否则"停用账号"对外部通道无效）。</p>
     *
     * @param verification Token 校验结果（非空）
     * @param traceId      本次调用的 traceId（可为空）
     * @throws McpException 账号不存在（已删除）/ 已停用 / 已不是服务账号 —— 都是**可读拒绝**，不是 500
     */
    public McpPrincipal resolve(McpTokenVerification verification, String traceId) {
        if (verification == null || verification.serviceAccountId() == null) {
            throw new McpException(McpErrorCode.TOKEN_INVALID, "MCP Token 未绑定服务账号，凭据不可用");
        }
        UserService.AccountIdentity identity = requireMachineIdentity(verification.serviceAccountId());
        UserVO account = loadAccount(verification.serviceAccountId());
        return new McpPrincipal(
                identity.id(),
                account.getUsername() == null ? identity.username() : account.getUsername(),
                account.getRealName(),
                verification.permissions(),
                account.getRoleCodes() == null ? List.of() : List.copyOf(account.getRoleCodes()),
                traceId,
                // MCP 调用没有会话：V10 已把 ai_tool_call.conversation_id 放开为可空（方案 A）
                null);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private UserVO loadAccount(Long serviceAccountId) {
        try {
            return userService.getById(serviceAccountId);
        } catch (BizException ex) {
            log.warn("MCP 归属服务账号不可用 id={} reason={}", serviceAccountId, ex.getMessage());
            throw new McpException(McpErrorCode.ACCOUNT_DISABLED,
                    "MCP Token 归属的服务账号不存在或已被删除（id=" + serviceAccountId + "）");
        }
    }
}
