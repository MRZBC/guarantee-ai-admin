package com.guarantee.common.security;

/**
 * 权限码常量（与 {@code sys_permission.perm_code} 一一对应）。
 *
 * <p>集中定义的原因：权限码同时被后端校验点（{@code @PreAuthorize}）、AI 工具注册裁剪
 * （SYS-P-12a）、数据初始化脚本（SYS-P-13）引用，字符串散落各处极易漂移。
 * 权限码一旦下发即视为契约，只能新增、不可改名。</p>
 *
 * <p>除权限码外，末尾还有**两条组合授权表达式**（{@link #ORG_OPTIONS_READ} /
 * {@link #INSURANCE_OPTIONS_READ}）：它们被两个授权域共用的只读字典接口使用，
 * 不是 {@code sys_permission} 里的权限码，因此不参与角色分配清单。</p>
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

    /**
     * 查看 AI 配置（模型参数 / 提示词 / 能力开关 / 预算参数）与配置变更历史（REQ-CFG-10）。
     *
     * <p>ADMIN 默认拥有，其余角色默认无。</p>
     */
    public static final String AI_CONFIG_VIEW = "ai:config:view";

    /**
     * 修改 AI 配置（REQ-CFG-10）。
     *
     * <p><b>危险权限</b>（与 {@link #AI_SYSTEM_WRITE} 同档）：它能改变**全站**助手的行为面——
     * 换模型/调温度会改变所有人的回答，改 `tools.proposal.enabled` 会一次性关掉所有账号
     * （含 ADMIN）的写能力，改 `model.api-key-ref` 会让助手整体不可用。因此默认只授予 ADMIN，
     * 且写接口一律 `@PreAuthorize`，不能只靠前端隐藏。</p>
     */
    public static final String AI_CONFIG_UPDATE = "ai:config:update";

    /**
     * 业务 MCP 只读能力（REQ-MCP-02 / AC-MCP-03）。
     *
     * <p><b>危险权限</b>：拿到它就等于拿到平台受控取数面的入口。只授予**服务账号**
     * （{@code sys_user.account_type=SERVICE}），不参与登录、不计入人类用户统计；
     * 数据范围仍由 {@code DataScopeService} 按服务账号判定，与页面/助手同源。
     * 无该权限时 MCP 工具在注册期即被裁掉（fail-closed）。</p>
     *
     * <p>命名警告：本仓库里裸 {@code mcp} 是 JWT"首登强制改密"claim，不得复用；
     * 一切与业务 MCP 相关的标识符统一 {@code ai:mcp:*} / {@code AI_MCP_*}。</p>
     */
    public static final String AI_MCP_READ = "ai:mcp:read";

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

    // ---------------- 用户 ----------------
    //
    // D-2 曾把"新建账号"与"密码重置"整体移出本期；P-10（docs/REQ-用户管理新增与修改.md）
    // 已把这两项作为独立立项交付，因此 create / reset-password 两个权限码在本期新增。
    // 仍**不含**"用户自助找回密码"（无邮件/短信通道）。

    public static final String USER_VIEW = "system:user:view";
    public static final String USER_UPDATE = "system:user:update";
    public static final String USER_DISABLE = "system:user:disable";
    public static final String USER_ASSIGN_ROLE = "system:user:assign-role";
    /** 用户逻辑删除 / 恢复 / 查看已删除（仅 ADMIN，设计 §7.2）。 */
    public static final String USER_DELETE = "system:user:delete";
    /**
     * 用户新增（P-10）。
     *
     * <p>新建账号写入**固定默认密码**并置 {@code must_change_password = 1}，
     * 该用户首次登录必须改密后才能使用系统。</p>
     */
    public static final String USER_CREATE = "system:user:create";
    /**
     * 管理员重置他人密码（P-10 / D3=B）。
     *
     * <p>重置为固定默认密码并要求下次登录改密；**不得重置自己**
     * （自己的密码走 {@code PUT /api/auth/password} 自助改密，要验旧密码）。</p>
     */
    public static final String USER_RESET_PASSWORD = "system:user:reset-password";

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

    // ---------------- 跨域只读字典的组合授权表达式 ----------------
    //
    // 下面两条**不是权限码**（不在 sys_permission 里、不参与角色分配），而是"被多个授权域
    // 共用"的只读下拉接口的可见性表达式。收口在权限码旁边，是为了让两个 Controller 引用
    // 同一份定义——否则"改了机构忘了险种"就会重演（与 PermissionCatalog 收口角色矩阵同一理由）。

    /**
     * 机构下拉（{@code GET /api/system/orgs/options}）的可见性：
     * 「机构配置」只读 **或** 能看到订单（投标 / 履约）。
     *
     * <p><b>为什么不能只挂 {@link #ORG_VIEW}</b>：订单页的「机构」筛选下拉复用了这个接口，
     * 而"能看订单"与"能进机构配置页"是两个互相独立的授权域。只挂配置权限的后果是——
     * 一个"有订单权限、没有系统配置权限"的角色（例如自定义的「业务运营（无系统配置）」）
     * 一进订单页就吃 403：列表数据正常返回，筛选下拉却空着并弹「没有该操作的权限」。
     * 下拉是订单筛选的一部分，它的授权就该跟"能不能看订单"对齐，而不是跟"能不能进配置页"对齐。</p>
     *
     * <p><b>放行不扩大暴露面</b>：返回体只有机构 id / 名称 / 区域（{@code OrgOptionVO}，
     * 仅启用机构），与订单行已经展示的 {@code orgName} / {@code regionName} 同源同敏感度；
     * 机构配置页本身的读接口（分页列表 / 树 / 明细）仍由 {@link #ORG_VIEW} 把守。</p>
     */
    public static final String ORG_OPTIONS_READ = "hasAnyAuthority('" + ORG_VIEW + "', '"
            + ORDER_TENDER_VIEW + "', '" + ORDER_PERFORMANCE_VIEW + "')";

    /**
     * 险种下拉（{@code GET /api/system/insurance-types/options}）的可见性：口径同
     * {@link #ORG_OPTIONS_READ}（「险种配置」只读 或 能看到订单）。
     */
    public static final String INSURANCE_OPTIONS_READ = "hasAnyAuthority('" + INSURANCE_VIEW + "', '"
            + ORDER_TENDER_VIEW + "', '" + ORDER_PERFORMANCE_VIEW + "')";

    private Permissions() {
    }
}
