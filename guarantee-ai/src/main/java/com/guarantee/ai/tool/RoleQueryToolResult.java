package com.guarantee.ai.tool;

import java.util.List;

/**
 * {@code queryRole} 的工具返回值（SYS-Q-04 / SYS-Q-07）。
 *
 * <p>三种 mode 的出参不同：{@code ROLE} 出角色、{@code PERMISSION} 出权限主数据、
 * {@code ROLE_PERMISSION} 出"某角色有哪些权限"。未使用的字段返回 null / 空列表，
 * 避免模型把不相干的数据串起来。</p>
 *
 * <p>{@code denied} 用于 {@code roleCode=ADMIN} 且当前用户无 {@code system:role:view} 的场景：
 * 返回**明确的无权限说明**而不是空结果（SYS-Q-04）。</p>
 */
public record RoleQueryToolResult(
        String mode,
        long total,
        List<RoleItem> roles,
        List<PermissionItem> permissions,
        RolePermissionMapping rolePermission,
        ToolResultMeta meta) {

    /** ROLE 模式出参。 */
    public record RoleItem(
            Long id,
            String roleCode,
            String roleName,
            String description,
            Integer status,
            String statusName,
            Integer permissionCount,
            Integer userCount,
            /** 是否已逻辑删除：1=已删除（仅 includeDeleted=true 时可能出现），0/null=未删除。 */
            Integer isDeleted,
            /** 删除时间（ISO-8601 字符串）；未删除时为 null。 */
            String deletedAt) {
    }

    /** PERMISSION 模式出参。 */
    public record PermissionItem(
            Long id,
            String permCode,
            String permName,
            String permType,
            String path,
            Long parentId) {
    }

    /** ROLE_PERMISSION 模式出参。 */
    public record RolePermissionMapping(
            String roleCode,
            String roleName,
            List<PermissionItem> permissions) {
    }

    public static RoleQueryToolResult denied(String mode, String reason) {
        return new RoleQueryToolResult(mode, 0L, List.of(), List.of(), null, ToolResultMeta.denied(reason));
    }
}
