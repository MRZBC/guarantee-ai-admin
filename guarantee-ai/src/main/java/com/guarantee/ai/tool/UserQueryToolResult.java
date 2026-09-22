package com.guarantee.ai.tool;

import java.util.List;

/**
 * {@code queryUser} 的工具返回值（SYS-Q-03 / SYS-P-11 / SYS-Q-07）。
 *
 * <p><b>字段分级</b>：{@code phone} / {@code email} 只对 ADMIN / OPERATOR 返回，
 * 且**已在服务端掩码**；ANALYST / VIEWER 的这三个字段恒为 null
 * （{@code lastLoginAt} 同理）。模型看不到的字段不可能被它输出，这是 D-4 之外
 * 面向模型的一条独立防线。</p>
 */
public record UserQueryToolResult(
        long total,
        List<UserItem> items,
        boolean ambiguous,
        String ambiguousHint,
        ToolResultMeta meta) {

    /**
     * 单条用户。
     *
     * <p>刻意不包含 {@code password}——VO 就没有该字段，白名单式组装也不会引入它。</p>
     */
    public record UserItem(
            Long id,
            String username,
            String realName,
            Long deptId,
            String deptName,
            List<String> roleCodes,
            List<String> roleNames,
            Integer status,
            String statusName,
            /** 已掩码：138****5678；无权限时为 null。 */
            String phone,
            /** 已掩码：a***@guarantee.com；无权限时为 null。 */
            String email,
            /** 无权限时为 null。 */
            String lastLoginAt,
            String createdAt,
            /** 是否已逻辑删除：1=已删除（仅 includeDeleted=true 时可能出现），0/null=未删除。 */
            Integer isDeleted,
            /** 删除时间（ISO-8601 字符串）；未删除时为 null。 */
            String deletedAt) {
    }

    public static UserQueryToolResult denied(String reason) {
        return new UserQueryToolResult(0L, List.of(), false, null, ToolResultMeta.denied(reason));
    }
}
