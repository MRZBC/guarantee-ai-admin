package com.guarantee.ai.service;

import com.guarantee.system.scope.DataScope;

/**
 * 提案执行上下文（确认接口线程上的身份与范围）。
 *
 * <p>执行发生在**带 JWT 的独立 HTTP 请求**上（Web 线程），因此这里有完整的
 * {@code CurrentUser} 与 Spring Security 上下文——这正是"写工具只产出提案、
 * 执行走独立接口"的原因（7.1）。</p>
 *
 * @param userId      操作者用户 id
 * @param username    操作者账号
 * @param realName    操作者姓名
 * @param roles       操作者当前角色（来自当前 token）
 * @param permissions 操作者当前权限（来自当前 token，SYS-C-04 复核用）
 * @param scope       操作者当前数据范围
 * @param traceId     与会话一致的 trace_id（T-07）
 *
 * <p><b>为什么没有 {@code orgId}</b>：机构的归属维度已从用户与部门上移除
 * （PLAN-移除用户与部门的机构归属 §8 Q3），"操作者机构"没有数据来源，
 * 审计的 {@code operator_org_id} 列也已删除，因此不再保留一个恒为 null 的字段——
 * 恒空字段会让后来读代码的人以为这里还有一条可用的机构数据通路。</p>
 */
public record ProposalExecutionContext(
        Long userId,
        String username,
        String realName,
        java.util.List<String> roles,
        java.util.List<String> permissions,
        DataScope scope,
        String traceId) {

    public boolean isAdmin() {
        return roles != null && roles.contains(com.guarantee.common.security.Roles.ADMIN);
    }
}
