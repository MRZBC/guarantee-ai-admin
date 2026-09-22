package com.guarantee.web.init;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限码与角色-权限矩阵的**唯一权威定义**（SYS-P-13）。
 *
 * <p>同一份定义被两个入口消费，必须保持一致：</p>
 * <ul>
 *   <li>{@code DataInitializer#seedRolesAndPermissions}：空库初始化；</li>
 *   <li>{@link PermissionSyncInitializer}：**存量库**幂等补数（{@code DataInitializer}
 *       只在 {@code sys_user} 为空时执行，存量库永远不会跑到权限种子代码）。</li>
 * </ul>
 *
 * <p>把清单抽到独立类，而不是让两个初始化器各维护一份，是为了避免"矩阵改了但只改了一处"
 * 导致 ANALYST 静默漏授或 VIEWER 静默误授（SYS-P-26 / RK-03）。</p>
 */
public final class PermissionCatalog {

    /** 权限编码 / 权限名称 / 前端路由（null 表示按钮级权限，无独立路由）。 */
    public static final String[][] PERMISSIONS = {
            {"dashboard:view", "首页", "/dashboard"},
            {"order:tender:view", "投标订单", "/orders/tender"},
            {"order:performance:view", "履约订单", "/orders/performance"},
            {"analysis:overview:view", "数据概览", "/analysis/overview"},
            {"project:view", "项目管理", "/projects"},
            {"enterprise:view", "企业管理", "/enterprises"},

            {"system:insurance:view", "险种配置", "/system/insurance-types"},
            {"system:insurance:create", "险种新增", null},
            {"system:insurance:update", "险种修改", null},
            {"system:insurance:disable", "险种启停", null},
            {"system:insurance:delete", "险种删除", null},

            {"system:org:view", "机构配置", "/system/orgs"},
            {"system:org:create", "机构新增", null},
            {"system:org:update", "机构修改", null},
            {"system:org:disable", "机构启停", null},
            {"system:org:delete", "机构删除", null},

            {"system:dept:view", "部门配置", "/system/departments"},
            {"system:dept:create", "部门新增", null},
            {"system:dept:update", "部门修改", null},
            {"system:dept:disable", "部门启停", null},
            {"system:dept:delete", "部门删除", null},

            {"system:user:view", "用户配置", "/system/users"},
            {"system:user:update", "用户修改", null},
            {"system:user:disable", "用户启停", null},
            {"system:user:assign-role", "用户角色分配", null},
            {"system:user:delete", "用户删除", null},

            {"system:role:view", "角色配置", "/system/roles"},
            {"system:role:create", "角色新增", null},
            {"system:role:update", "角色修改", null},
            {"system:role:assign-permission", "角色授权", null},
            {"system:role:disable", "角色启停", null},
            {"system:role:delete", "角色删除", null},

            {"system:permission:view", "权限配置", null},
            {"system:audit:view", "操作审计", null},

            {"ai:chat", "AI 业务助手", null},
            {"ai:system:query", "AI 系统管理查询", null},
            {"ai:system:write", "AI 系统管理写操作", null},
    };

    /** 角色编码 / 角色名称 / 描述，数组下标 + 1 即角色 id（与初始化顺序一致）。 */
    public static final String[][] ROLES = {
            {"ADMIN", "超级管理员", "拥有全部权限"},
            {"OPERATOR", "运营人员", "订单与基础配置的日常运营"},
            {"ANALYST", "数据分析师", "业务分析与 AI 助手（系统管理只读）"},
            {"VIEWER", "只读用户", "仅可查看"},
    };

    /** ADMIN：全部权限。 */
    public static final Set<String> ADMIN_PERMISSIONS = java.util.Arrays.stream(PERMISSIONS)
            .map(p -> p[0]).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * OPERATOR：除角色/权限管理与操作审计外的全部权限。
     * 可写机构/部门/险种与用户，**不可写角色**（5.5.3 矩阵）。
     */
    public static final Set<String> OPERATOR_PERMISSIONS = Set.of(
            "dashboard:view",
            "order:tender:view", "order:performance:view",
            "analysis:overview:view", "project:view", "enterprise:view",
            "system:insurance:view", "system:insurance:create",
            "system:insurance:update", "system:insurance:disable", "system:insurance:delete",
            "system:org:view", "system:org:create", "system:org:update", "system:org:disable",
            "system:org:delete",
            "system:dept:view", "system:dept:create", "system:dept:update", "system:dept:disable",
            "system:dept:delete",
            "system:user:view",
            "ai:chat", "ai:system:query");

    /**
     * ANALYST（D-1）：业务分析 + 系统管理**只读**。
     * 显式补入 {@code system:*:view} 与 {@code ai:system:query}；
     * **不含** {@code system:audit:view}（D-1a）、不含任何写权限与 {@code ai:system:write}。
     */
    public static final Set<String> ANALYST_PERMISSIONS = Set.of(
            "dashboard:view",
            "order:tender:view", "order:performance:view",
            "analysis:overview:view", "project:view", "enterprise:view",
            "system:insurance:view", "system:org:view", "system:dept:view",
            "system:user:view", "system:role:view",
            "ai:chat", "ai:system:query");

    /**
     * VIEWER：所有 {@code :view} + {@code ai:chat}。
     * **显式排除** {@code system:audit:view}（D-1a），不使用 {@code endsWith(":view")} 兜底。
     */
    public static final Set<String> VIEWER_PERMISSIONS = Set.of(
            "dashboard:view",
            "order:tender:view", "order:performance:view",
            "analysis:overview:view", "project:view", "enterprise:view",
            "system:insurance:view", "system:org:view", "system:dept:view",
            "system:user:view", "system:role:view", "system:permission:view",
            "ai:chat");

    /** 角色编码 -> 期望权限集合。 */
    public static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "ADMIN", ADMIN_PERMISSIONS,
            "OPERATOR", OPERATOR_PERMISSIONS,
            "ANALYST", ANALYST_PERMISSIONS,
            "VIEWER", VIEWER_PERMISSIONS);

    private PermissionCatalog() {
    }

    /** 角色编码 -> 角色名称。 */
    public static String roleName(String roleCode) {
        for (String[] role : ROLES) {
            if (role[0].equals(roleCode)) {
                return role[1];
            }
        }
        return roleCode;
    }

    /** 场景化断言：写权限清单，ANALYST / VIEWER 必须为空（SYS-P-12）。 */
    public static List<String> writePermissions(Set<String> permissions) {
        return permissions.stream().filter(PermissionCatalog::isWritePermission).sorted().toList();
    }

    private static boolean isWritePermission(String code) {
        return code.endsWith(":create") || code.endsWith(":update") || code.endsWith(":disable")
                || code.endsWith(":delete")
                || code.endsWith(":assign-role") || code.endsWith(":assign-permission")
                || "ai:system:write".equals(code);
    }
}
