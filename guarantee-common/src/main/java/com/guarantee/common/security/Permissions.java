package com.guarantee.common.security;

/**
 * 权限码常量（与 {@code sys_permission.perm_code} 一一对应）。
 *
 * <p>集中定义的原因：权限码同时被后端校验点（{@code @PreAuthorize}）、AI 工具注册裁剪
 * （SYS-P-12a）、数据初始化脚本（SYS-P-13）引用，字符串散落各处极易漂移。
 * 权限码一旦下发即视为契约，只能新增、不可改名。</p>
 */
public final class Permissions {

    // ---------------- 助手侧能力开关 ----------------

    /** 使用 AI 助手对话。 */
    public static final String AI_CHAT = "ai:chat";

    /** 助手侧系统管理**查询**能力开关（ADMIN / OPERATOR / ANALYST 持有，D-1）。 */
    public static final String AI_SYSTEM_QUERY = "ai:system:query";

    /** 助手侧系统管理**写**能力开关（仅 ADMIN 持有）。 */
    public static final String AI_SYSTEM_WRITE = "ai:system:write";

    // ---------------- 险种 ----------------

    public static final String INSURANCE_VIEW = "system:insurance:view";
    public static final String INSURANCE_CREATE = "system:insurance:create";
    public static final String INSURANCE_UPDATE = "system:insurance:update";
    public static final String INSURANCE_DISABLE = "system:insurance:disable";
    /** 险种逻辑删除 / 恢复（设计 §7.2）。 */
    public static final String INSURANCE_DELETE = "system:insurance:delete";

    // ---------------- 机构 ----------------

    public static final String ORG_VIEW = "system:org:view";
    public static final String ORG_CREATE = "system:org:create";
    public static final String ORG_UPDATE = "system:org:update";
    public static final String ORG_DISABLE = "system:org:disable";
    /** 机构逻辑删除 / 恢复 / 查看已删除（设计 §7.2）。 */
    public static final String ORG_DELETE = "system:org:delete";

    // ---------------- 部门 ----------------

    public static final String DEPT_VIEW = "system:dept:view";
    public static final String DEPT_CREATE = "system:dept:create";
    public static final String DEPT_UPDATE = "system:dept:update";
    public static final String DEPT_DISABLE = "system:dept:disable";
    /** 部门逻辑删除 / 恢复 / 查看已删除。 */
    public static final String DEPT_DELETE = "system:dept:delete";

    // ---------------- 用户（D-2：不含 create / reset-password） ----------------

    public static final String USER_VIEW = "system:user:view";
    public static final String USER_UPDATE = "system:user:update";
    public static final String USER_DISABLE = "system:user:disable";
    public static final String USER_ASSIGN_ROLE = "system:user:assign-role";
    /** 用户逻辑删除 / 恢复 / 查看已删除（仅 ADMIN，设计 §7.2）。 */
    public static final String USER_DELETE = "system:user:delete";

    // ---------------- 角色（权限主数据不允许改，R-04） ----------------

    public static final String ROLE_VIEW = "system:role:view";
    public static final String ROLE_CREATE = "system:role:create";
    public static final String ROLE_UPDATE = "system:role:update";
    public static final String ROLE_ASSIGN_PERMISSION = "system:role:assign-permission";
    /** 角色逻辑删除 / 恢复 / 查看已删除（仅 ADMIN，设计 §7.2）。 */
    public static final String ROLE_DELETE = "system:role:delete";

    // ---------------- 权限主数据 ----------------

    public static final String PERMISSION_VIEW = "system:permission:view";

    // ---------------- 操作审计（D-1a：仅 ADMIN） ----------------

    public static final String AUDIT_VIEW = "system:audit:view";

    // ---------------- 业务域（既有，供角色分配清单引用） ----------------

    public static final String DASHBOARD_VIEW = "dashboard:view";
    public static final String ORDER_TENDER_VIEW = "order:tender:view";
    public static final String ORDER_PERFORMANCE_VIEW = "order:performance:view";
    public static final String ANALYSIS_OVERVIEW_VIEW = "analysis:overview:view";
    public static final String PROJECT_VIEW = "project:view";
    public static final String ENTERPRISE_VIEW = "enterprise:view";

    private Permissions() {
    }
}
