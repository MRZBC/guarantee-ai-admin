package com.guarantee.ai.tool;

import com.guarantee.common.security.Roles;

import java.util.List;

/**
 * 工具层字段分级脱敏策略（SYS-P-11 / TEST-12 / TEST-20）。
 *
 * <p><b>白名单式组装</b>：返回给模型的字段集按角色**显式列出**，而不是"序列化 VO 再删字段"。
 * 理由见 RK-08——某天有人给 VO 加了一个 {@code phone}，删字段式的实现会静默泄漏，
 * 白名单式实现则天然不含。</p>
 *
 * <table>
 *   <caption>字段集</caption>
 *   <tr><th>角色</th><th>phone</th><th>email</th><th>lastLoginAt</th></tr>
 *   <tr><td>ADMIN / OPERATOR</td><td>已掩码 138****5678</td><td>已掩码 a***@x.com</td><td>返回</td></tr>
 *   <tr><td>ANALYST / VIEWER</td><td>不返回</td><td>不返回</td><td>不返回</td></tr>
 * </table>
 *
 * <p>注意本策略只作用于**助手侧**。系统管理页面的 {@code GET /api/system/users} 行为按
 * Q-15 暂缓口径保持不变（页面仍返回明文），该不一致是被有意接受的（RK-11）。</p>
 */
public final class ToolFieldPolicy {

    private ToolFieldPolicy() {
    }

    /** ADMIN / OPERATOR：可含掩码后的手机号与邮箱，以及最近登录时间。 */
    public static boolean canSeeContact(List<String> roles) {
        return roles != null && (roles.contains(Roles.ADMIN) || roles.contains(Roles.OPERATOR));
    }
}
