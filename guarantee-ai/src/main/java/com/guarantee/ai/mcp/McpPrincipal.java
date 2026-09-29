package com.guarantee.ai.mcp;

import java.util.List;

/**
 * MCP 调用的主体（由 {@link McpTokenVerification} + 服务账号资料组装）。
 *
 * <p>{@code serviceAccountId} 会作为 {@code aiUserId} 写入 {@code ToolContext}：
 * 它既是权限快照的归属，也是数据范围（{@code DataScopeService}）与审计的归属。
 * 因此<b>绝不能</b>用"管理员 id 顶替"，否则数据范围与审计会全线失真。</p>
 *
 * @param serviceAccountId 服务账号 id（{@code sys_user.id}）
 * @param username         服务账号用户名（审计与日志用）
 * @param realName         展示名（确认卡/口径行可能用到）
 * @param permissions      权限快照（来自 Token，不是"角色推导"）
 * @param roles            角色编码（数据范围判定需要；来自服务账号绑定角色）
 * @param traceId          本次调用的 traceId（可为空；为空时不写入 ToolContext）
 * @param conversationId   会话 id：MCP 调用通常为 null（见 V10 待裁决说明）
 */
public record McpPrincipal(Long serviceAccountId,
                           String username,
                           String realName,
                           List<String> permissions,
                           List<String> roles,
                           String traceId,
                           Long conversationId) {

    /** 权限快照的空值归一：null → 空列表（fail-closed，交由注册裁剪决定可见范围）。 */
    public List<String> permissionsOrEmpty() {
        return permissions == null ? List.of() : permissions;
    }

    public List<String> rolesOrEmpty() {
        return roles == null ? List.of() : roles;
    }
}
