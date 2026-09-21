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
  orgLevel: string | null
  parentId: number | null
  /** 后端 TINYINT：1 启用 / 0 停用 */
  status: number | null
  sortNo: number | null
  createdAt: string | null
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
