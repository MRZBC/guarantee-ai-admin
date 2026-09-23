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

    /**
     * 接收 AI 工具调用明细（工具名 / 入参 / 返回 JSON / 耗时 / 状态）。
     *
     * <p>这是**工程遥测**，不是业务能力：业务用户看不到任何有价值的信息，
     * 反而会暴露内部工具名与字段结构。因此默认只授予 ADMIN，
     * 需要在 `PermissionCatalog` 里按"谁是开发者"调整。</p>
     *
     * <p><b>它是服务端开关，不是前端显隐开关</b>：{@code AiChatService} 据此决定是否把
     * {@code tool_call} 事件并入 SSE 流。前端隐藏做不到"用户看不到"——
     * 事件里的完整入参与结果 JSON 仍在响应体中。</p>
     */
    public static final String AI_DEBUG_VIEW = "ai:debug:view";

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
    /**
     * 角色启停。
     *
     * <p>角色停用有真实语义：鉴权路径按 {@code r.status = 1} 过滤，停用即**立即收回**
     * 该角色带来的权限，因此停用后必须撤销持有者的 JWT。</p>
     */
    public static final String ROLE_DISABLE = "system:role:disable";
    /** 角色逻辑删除 / 恢复 / 查看已删除（仅 ADMIN，设计 §7.2）。 */
    public static final String ROLE_DELETE = "system:role:delete";

    // ---------------- 权限主数据 ----------------

    public static final String PERMISSION_VIEW = "system:permission:view";

    // ---------------- 操作审计（D-1a：仅 ADMIN） ----------------

    public static final String AUDIT_VIEW = "system:audit:view";

    // ---------------- 在线会话（AUTH-05：仅 ADMIN） ----------------

    /**
     * 查看在线会话。
     *
     * <p>列表包含全员登录 IP 与 User-Agent，属运维级信息，仅授予 ADMIN。</p>
     */
    public static final String SESSION_VIEW = "system:session:view";

    /**
     * 强制下线（踢出会话）。
     *
     * <p>"影响他人"的写操作，风险等级与删除相当，不下放给 OPERATOR（AUTH-05 §7）。</p>
     */
    public static final String SESSION_KICK = "system:session:kick";

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
