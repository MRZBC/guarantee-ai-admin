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
  deptCount?: number | null
  userCount?: number | null
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
  orgId: number | null
  orgName: string | null
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
  orgId: number | null
}

export interface DepartmentQuery extends PageQuery {
  orgId?: number | null
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
  orgId?: number | null
  deptName?: string
  deptCode?: string
  status?: number | null
}

/**
 * 部门树节点的**数据契约**（对应 `GET /system/departments/tree` 的返回行）。
 *
 * <p><b>这一棵树里只有部门。</b>机构（出函机构，`sys_org`）与部门（公司内部组织，`sys_department`）
 * 是两个不同实体：机构有自己的三级层级（`org_level`），部门靠 `parent_id` 成树，
 * `org_id` 只是部门的**归属属性**。因此机构**不是**本树的层级，只以两种形式出现：
 * 筛选条件（`orgId`）与 `orgName` 字段（页面用「所属机构」列与根节点上的机构标签呈现）。</p>
 *
 * <p>曾经出现过的 `ORG` 伪节点方案已废弃——它把机构塞进部门层级，既让机构在树上"位置是虚的"，
 * 又把部门真实的父子层级压成平级。</p>
 */
export interface DepartmentTreeNode {
  /** 恒为 `'DEPT'`：保留该字段是为了让"本树只含部门"在类型上可见 */
  nodeKind: 'DEPT'
  id: number
  deptCode: string | null
  deptName: string
  /** 归属机构 id（`sys_department.org_id`） */
  orgId: number | null
  orgName: string | null
  /** 上级部门 id；0 表示该机构内的顶级部门 */
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
  orgId: number | null
  orgName: string | null
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
  orgId?: number | null
  status?: number | null
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
