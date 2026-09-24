import type { PageQuery } from './common'

/** 险种配置 */
export interface InsuranceTypeItem {
  id: number
  typeCode: string
  typeName: string
  category: string
  categoryName: string | null
  baseRate: number | null
  minAmount: number | null
  maxAmount: number | null
  /** 后端 TINYINT：1 启用 / 0 停用 */
  status: number | null
  description: string | null
  createdAt: string | null
  updatedAt: string | null
  /** 逻辑删除标记：后端 TINYINT，1 已删除 / 0 或 null 正常 */
  isDeleted: number | null
  /** 逻辑删除时间（yyyy-MM-dd HH:mm:ss）；未删除为 null。删除操作人不返回（设计文档 §10.3） */
  deletedAt: string | null
  /** 删除人标识：应用删除是 sys_user.id 的字符串；数据库直连删除为 "DB"（设计 §2.1a）。接口仍返回，页面当前不展示 */
  deletedBy: string | null
}

export interface InsuranceTypeOption {
  id: number
  typeCode: string
  typeName: string
  category: string
  categoryName: string | null
  /**
   * 后端 TINYINT：1 启用 / 0 停用。
   *
   * <p>筛选下拉里**会出现停用险种**（历史订单仍在列表里展示它），因此选项文案要标注状态，
   * 否则用户会以为它还能承保新业务。</p>
   */
  status?: number | null
  /** 逻辑删除标记：1 已删除 / 0 正常（原理由同上；仅直连删除会走到这个状态）。 */
  isDeleted?: number | null
}

export interface InsuranceTypeQuery extends PageQuery {
  typeName?: string
  category?: string
  status?: number | null
}

export interface InsuranceTypeCreateParams {
  typeCode: string
  typeName: string
  category: string
  baseRate: number | null
  minAmount: number | null
  maxAmount: number | null
  description?: string
}

export interface InsuranceTypeUpdateParams {
  typeName: string
  category: string
  baseRate: number | null
  minAmount: number | null
  maxAmount: number | null
  /** 后端 TINYINT：1 启用 / 0 停用 */
  status: number
  description?: string
}

/** 地区（行政区划）下拉项 —— 数据来自 `GET /api/system/regions/options` */
export interface RegionOption {
  /** 行政区划代码（6 位，例如 330000 / 330100 / 330102）：筛选参数就传它 */
  code: string
  /** 全称（例如 浙江省 / 杭州市 / 上城区） */
  name: string
  /** 简称，仅用于搜索（例如 浙江） */
  shortName: string | null
  /** 层级 1省 2市 3区县 */
  level: number
  /** 上级区划码（省级为空串）：前端据此组装省/市/区县树 */
  parentCode: string | null
}

/** 机构配置 */
export interface OrgItem {
  id: number
  orgCode: string
  orgName: string
  regionCode: string | null
  regionName: string | null
  orgLevel: number | null
  parentId: number | null
  parentName?: string | null
  /** 后端 TINYINT：1 启用 / 0 停用 */
  status: number | null
  sortNo: number | null
  createdAt: string | null
  /** 逻辑删除标记：后端 TINYINT，1 已删除 / 0 或 null 正常 */
  isDeleted: number | null
  /** 逻辑删除时间（yyyy-MM-dd HH:mm:ss）；未删除为 null。删除操作人不返回（设计文档 §10.3） */
  deletedAt: string | null
  /** 删除人标识：应用删除是 sys_user.id 的字符串；数据库直连删除为 "DB"（设计 §2.1a）。接口仍返回，页面当前不展示 */
  deletedBy: string | null
}

/** 机构树节点（在前端由扁平列表按 parentId 组装） */
export interface OrgTreeNode extends OrgItem {
  children: OrgTreeNode[]
}

export interface OrgTreeQuery {
  orgName?: string
  orgCode?: string
  regionCode?: string
  orgLevel?: number | null
  status?: number | null
}

export interface OrgOption {
  id: number
  orgName: string
  regionCode: string | null
  regionName: string | null
  /**
   * 后端 TINYINT：1 启用 / 0 停用。
   *
   * <p>筛选下拉里**会出现停用/已删除机构**（历史订单仍在列表里展示它们），
   * 因此选项文案要标注状态，否则用户会以为它还能用于新业务。</p>
   */
  status?: number | null
  /** 逻辑删除标记：1 已删除 / 0 正常（原理由同上）。 */
  isDeleted?: number | null
}

export interface OrgQuery extends PageQuery {
  orgName?: string
  regionCode?: string
  status?: number | null
}

/** 部门配置 */
export interface DepartmentItem {
  id: number
  deptCode: string
  deptName: string
  parentId: number | null
  /** 后端 TINYINT：1 启用 / 0 停用 */
  status: number | null
  sortNo: number | null
  createdAt: string | null
  /** 逻辑删除标记：后端 TINYINT，1 已删除 / 0 或 null 正常 */
  isDeleted: number | null
  /** 逻辑删除时间（yyyy-MM-dd HH:mm:ss）；未删除为 null。删除操作人不返回（设计文档 §10.3） */
  deletedAt: string | null
  /** 删除人标识：应用删除是 sys_user.id 的字符串；数据库直连删除为 "DB"（设计 §2.1a）。接口仍返回，页面当前不展示 */
  deletedBy: string | null
}

export interface DepartmentOption {
  id: number
  deptName: string
}

export interface DepartmentQuery extends PageQuery {
  deptName?: string
  status?: number | null
}

/**
 * 部门树查询条件（`GET /system/departments/tree`）。
 *
 * <p>刻意**不含分页字段**：树必须基于全量数据组装，沿用分页会让树静默缺节点
 * （SYS-C-22 同构自机构树的 SYS-C-24）。过滤在服务端按同一 `queryWhere` 生效。</p>
 */
export interface DepartmentTreeQuery {
  deptName?: string
  deptCode?: string
  status?: number | null
}

/**
 * 部门树节点的**数据契约**（对应 `GET /system/departments/tree` 的返回行）。
 *
 * <p><b>这一棵树里只有部门。</b>机构（出函机构，`sys_org`）与部门（公司内部组织，
 * `sys_department`）是两个不同实体，且**用户与部门都不再有任何机构归属属性**：
 * `sys_department.org_id` 已随重构删除，部门树只按 `parent_id` 成树。</p>
 */
export interface DepartmentTreeNode {
  /** 恒为 `'DEPT'`：保留该字段是为了让"本树只含部门"在类型上可见 */
  nodeKind: 'DEPT'
  id: number
  deptCode: string | null
  deptName: string
  /** 上级部门 id；0 表示顶级部门 */
  parentId: number | null
  /** 后端 TINYINT：1 启用 / 0 停用 */
  status: number | null
  sortNo: number | null
  createdAt: string | null
  /** 逻辑删除标记：1 已删除 / 0 或 null 正常 */
  isDeleted: number | null
  /**
   * 说明：接口仍返回 `deletedAt` / `deletedBy`，但**页面不展示**（2026-09-22 评审裁定），
   * 因此这里刻意不声明——前端不再有任何消费方，声明了只会让人误以为有地方要用。
   * 后端字段本身保留（用于排障、审计与直连核对）。
   */
  children: DepartmentTreeNode[]
  /**
   * 树组装时按父子链推导的层级路径（如 `业务受理部 / 风险审查部`）。
   * 属于**视图派生字段**，接口不返回，因此可选。
   */
  path?: string
}

/** 用户配置 */
export interface UserItem {
  id: number
  username: string
  realName: string
  deptId: number | null
  deptName: string | null
  phone: string | null
  email: string | null
  /** 后端 TINYINT：1 启用 / 0 停用 */
  status: number | null
  lastLoginAt: string | null
  createdAt: string | null
  roleIds: number[] | null
  roleNames: string[] | null
  /**
   * 已分配角色的**编码**（不是 id）。
   *
   * <p>角色分配对话框的回显必须用它：分配接口 `PUT /system/users/{id}/roles` 收的是
   * `roleCodes`；且后端 `UserService` 的角色校验与回填同为 `is_deleted = 0` 口径，
   * 因此它是唯一"回显了就不会被判不存在"的来源——用 `roleIds` 或 `roleNames` 反查都会对不上
   * （与 `RoleItem.permissionCodes` 同款约定）。</p>
   */
  roleCodes: string[] | null
  /** 逻辑删除标记：后端 TINYINT，1 已删除 / 0 或 null 正常 */
  isDeleted: number | null
  /** 逻辑删除时间（yyyy-MM-dd HH:mm:ss）；未删除为 null。删除操作人不返回（设计文档 §10.3） */
  deletedAt: string | null
  /** 删除人标识：应用删除是 sys_user.id 的字符串；数据库直连删除为 "DB"（设计 §2.1a）。接口仍返回，页面当前不展示 */
  deletedBy: string | null
}

export interface UserQuery extends PageQuery {
  username?: string
  realName?: string
  status?: number | null
}

/**
 * 新增用户参数（`POST /system/users`，权限 `system:user:create`）。
 *
 * <p><b>刻意没有任何密码字段</b>：密码由后端写入部署配置的**固定默认密码**，
 * 并同时置 `must_change_password = 1`（该用户首次登录必须改密）。管理员既不需要、
 * 也不应该指定初始密码——"明文密码永不经过 HTTP"是这条决策的附带收益。</p>
 *
 * <p>也没有 `status` 字段：新账号固定为启用（后端写死 `status = 1`）。</p>
 */
export interface UserCreateParams {
  /** 必填，4-64 位字母、数字、下划线、点或中划线 */
  username: string
  /** 必填，≤64 */
  realName: string
  /** 必填；部门必须存在且未删除（schema 里 `dept_id` 是 NOT NULL） */
  deptId: number
  /** 选填，≤20；11 位大陆手机号 */
  phone?: string
  /** 选填，≤128；邮箱格式 */
  email?: string
  /** 必填且至少 1 个（D2=A）；角色必须存在且启用 */
  roleCodes: string[]
}

/**
 * 修改用户资料参数（`PUT /system/users/{id}`，权限 `system:user:update`）。
 *
 * <p>三点是**契约本身**，不要"顺手"补字段：</p>
 * <ol>
 *   <li>`username` **不可改**（后端 `UpdateRequest` 里根本没有该字段）；</li>
 *   <li>`deptId` 省略/传 `null` 表示"**不改**"，不是"清空部门"——
 *       "清空部门"能力已整体移除（`sys_user.dept_id NOT NULL`）；</li>
 *   <li>**空请求会被拒**（「至少需要提供一个待修改字段」），因此只在字段确有变化时才上送。</li>
 * </ol>
 *
 * <p>同样没有 `status`：状态改动只能走 `PATCH /{id}/status`，只有那条路径带
 * 「停用前置检查 + 撤销持有者令牌」。</p>
 */
export interface UserUpdateParams {
  realName?: string
  phone?: string
  email?: string
  deptId?: number | null
}

/**
 * 用户角色分配参数（`PUT /system/users/{id}/roles`，权限 `system:user:assign-role`）。
 *
 * <p><b>全量替换语义</b>：提交的是"变更后的**完整**角色码集合"，漏传即等于移除。
 * 空数组会被后端拒绝（「角色列表不能为空」）。</p>
 *
 * <p><b>副作用</b>：后端会撤销该用户的**全部令牌**——立即强制下线，需重新登录后
 * 新权限才生效。影响必须在上屏文案里说明。</p>
 */
export interface UserAssignRolesParams {
  /** 变更后的完整角色码集合（`roleCode`，不是 id），不可为空 */
  roleCodes: string[]
}

/* ---------------- 在线会话（AUTH-05） ---------------- */

/**
 * 一条在线会话。
 *
 * <p>`idleExpiresAt` 与 `absoluteExpiresAt` 语义不同，**必须都展示**：
 * 前者是"再没有请求就到此为止"（有活动会顺延），后者是"自登录起算的硬上限"（不会延长）。
 * 只显示其中一个会让人误判会话还能用多久。</p>
 */
export interface SessionItem {
  /** 会话标识（令牌 jti）。不是凭据，展示与传递都安全 */
  jti: string
  userId: number | null
  username: string | null
  realName: string | null
  loginAt: string | null
  /** 空闲到期时间（有请求即顺延） */
  idleExpiresAt: string | null
  /** 绝对上限到期时间（自登录起算，不会延长） */
  absoluteExpiresAt: string | null
  loginIp: string | null
  userAgent: string | null
  /** 是否为当前请求所用的会话（用于"你正在踢出自己"的提示） */
  current: boolean
}

export interface SessionQuery extends PageQuery {
  userId?: number
  username?: string
  keyword?: string
}

/** 强制下线的结果 */
export interface SessionKickResult {
  /** 实际终止的会话数 */
  kicked: number
  /** 被终止的会话里是否包含发起者自己当前所用的会话 */
  selfKicked: boolean
}

/** 角色配置 */
export interface RoleItem {
  id: number
  roleCode: string
  roleName: string
  description: string | null
  /** 后端 TINYINT：1 启用 / 0 停用 */
  status: number | null
  createdAt: string | null
  permissionIds: number[] | null
  permissionNames: string[] | null
  /**
   * 已授权权限**编码**（不是 id）。
   *
   * 授权对话框回显必须用它：授权接口 `PUT /system/roles/{roleCode}/permissions` 收的是
   * `permCodes`；且后端 `selectPermissionRefsByRoleIds`（回填本字段）与授权校验
   * `selectPermissionEntitiesByCodes` 同为 `is_deleted = 0` 口径，因此它是唯一
   * "回显了就不会被判不存在"的来源——用 `permissionNames` 反查或 `permissionIds` 都会对不上。
   */
  permissionCodes: string[] | null
  /** 已授权权限数（权限列折叠展示「共 N 项」用） */
  permissionCount: number | null
  /** 持有该角色的用户数（授权影响提示用：变更后这些人会被强制下线） */
  userCount: number | null
  /** 逻辑删除标记：后端 TINYINT，1 已删除 / 0 或 null 正常 */
  isDeleted: number | null
  /** 逻辑删除时间（yyyy-MM-dd HH:mm:ss）；未删除为 null。删除操作人不返回（设计文档 §10.3） */
  deletedAt: string | null
  /** 删除人标识：应用删除是 sys_user.id 的字符串；数据库直连删除为 "DB"（设计 §2.1a）。接口仍返回，页面当前不展示 */
  deletedBy: string | null
}

export interface RoleQuery extends PageQuery {
  roleCode?: string
  roleName?: string
}

/** 角色新增参数（`POST /system/roles`，权限 `system:role:create`） */
export interface RoleCreateParams {
  /** 必填，2-32 位；保留码 `ADMIN` 前端先拦、后端兜底 */
  roleCode: string
  /** 必填，≤64 */
  roleName: string
  /** 选填，≤255 */
  description?: string
}

/**
 * 角色修改参数（`PUT /system/roles/{id}`，权限 `system:role:update`）。
 *
 * 两个字段是**刻意不提供**的，不要"顺手"补上：
 * - `roleCode`：后端 `RoleDto.UpdateRequest` 无此字段，角色编码创建后不可改；
 * - `status`：状态改动一律走 `PATCH /{id}/status`——**只有那条路径**带
 *   「停用前置检查（有启用用户则拒绝）+ 撤销持有者令牌」，走 PUT 会静默绕过这两道保护。
 */
export interface RoleUpdateParams {
  roleName: string
  description?: string
}

/**
 * 角色授权参数（`PUT /system/roles/{roleCode}/permissions`，权限 `system:role:assign-permission`）。
 *
 * **全量替换语义**：后端先 `softDeleteRolePermissionsNotIn` 再 `upsertRolePermissions`，
 * 提交的是"变更后的**完整**权限集"，漏传即等于删除。空集会被后端拒绝。
 */
export interface RoleAssignPermissionsParams {
  /** 变更后的完整权限码集合（`permCode`，不是 id），不可为空 */
  permCodes: string[]
}

/** 权限（平铺结构，前端按 parentId 组树） */
export interface PermissionItem {
  id: number
  permCode: string
  permName: string
  permType: string | null
  parentId: number | null
  path: string | null
  component: string | null
  icon: string | null
  sortNo: number | null
  /**
   * 逻辑删除标记。后端**会返回**该字段，但当前无需前端过滤：
   * `SysPermissionMapper.selectAllOrdered` 已加 `is_deleted = 0`（与授权校验同口径）。
   */
  isDeleted: number | null
  /** 逻辑删除时间；未删除为 null。同上，后端已过滤 */
  deletedAt: string | null
}
