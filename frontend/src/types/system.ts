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

/** 权限（平铺结构） */
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
}
