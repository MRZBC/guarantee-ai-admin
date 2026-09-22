import { http } from './request'
import type { PageResult } from '@/types/common'
import type {
  DepartmentItem,
  DepartmentOption,
  DepartmentQuery,
  DepartmentTreeQuery,
  InsuranceTypeCreateParams,
  InsuranceTypeItem,
  InsuranceTypeOption,
  InsuranceTypeQuery,
  InsuranceTypeUpdateParams,
  OrgItem,
  OrgOption,
  OrgQuery,
  OrgTreeQuery,
  PermissionItem,
  RoleItem,
  RoleQuery,
  UserItem,
  UserQuery
} from '@/types/system'

/* ---------------- 险种配置 ---------------- */

export function pageInsuranceTypes(params: InsuranceTypeQuery) {
  return http.get<PageResult<InsuranceTypeItem>>('/system/insurance-types', { params })
}

export function listInsuranceTypeOptions() {
  return http.get<InsuranceTypeOption[]>('/system/insurance-types/options')
}

export function createInsuranceType(data: InsuranceTypeCreateParams) {
  return http.post<InsuranceTypeItem>('/system/insurance-types', data)
}

export function updateInsuranceType(id: number, data: InsuranceTypeUpdateParams) {
  return http.put<InsuranceTypeItem>(`/system/insurance-types/${id}`, data)
}

/** 逻辑删除（权限：system:insurance:delete）；被引用时后端返回 code != 0 + 中文 message */
export function deleteInsuranceType(id: number) {
  return http.delete<InsuranceTypeItem>(`/system/insurance-types/${id}`)
}


/* ---------------- 机构配置 ---------------- */

export function pageOrgs(params: OrgQuery) {
  return http.get<PageResult<OrgItem>>('/system/orgs', { params })
}

/**
 * 机构树形数据源（SYS-C-21 / SYS-C-24）。
 *
 * 树必须基于**全量**数据组装：沿用分页接口会让机构数超过一页时树静默缺节点，
 * 属于必须避免的错误展示，因此这里单独走 `/system/orgs/tree`（仅必要字段，条数上限 2000）。
 */
export function listOrgTree(params: OrgTreeQuery) {
  return http.get<OrgItem[]>('/system/orgs/tree', { params })
}

export function listOrgOptions() {
  return http.get<OrgOption[]>('/system/orgs/options')
}

/** 机构写操作（权限：system:org:create / :update / :disable） */
export function createOrg(data: Record<string, unknown>) {
  return http.post<OrgItem>('/system/orgs', data)
}

export function updateOrg(id: number, data: Record<string, unknown>) {
  return http.put<OrgItem>(`/system/orgs/${id}`, data)
}

export function changeOrgStatus(id: number, status: number) {
  return http.patch<OrgItem>(`/system/orgs/${id}/status`, { status })
}

/** 逻辑删除（权限：system:org:delete）；存在启用中的下级机构时会被拒绝 */
export function deleteOrg(id: number) {
  return http.delete<OrgItem>(`/system/orgs/${id}`)
}


/* ---------------- 部门配置 ---------------- */

export function pageDepartments(params: DepartmentQuery) {
  return http.get<PageResult<DepartmentItem>>('/system/departments', { params })
}

/** 部门下拉项（重构后不再接受 `orgId`：用户与部门都没有机构归属属性） */
export function listDepartmentOptions() {
  return http.get<DepartmentOption[]>('/system/departments/options')
}

/**
 * 部门树数据源（全量、扁平；嵌套结构与机构分组由页面组装）。
 *
 * <p>**不要用 `pageDepartments` 代替它**：分页接口只返回一页（默认 10 条），
 * 超出的部门会从树上静默消失且界面看不出异常（SYS-C-24 同款陷阱）。</p>
 */
export function listDepartmentTree(params: DepartmentTreeQuery) {
  return http.get<DepartmentItem[]>('/system/departments/tree', { params })
}

/** 新增部门（权限：system:dept:create）。parentId 传 0 或省略表示顶级 */
export function createDepartment(data: Record<string, unknown>) {
  return http.post<DepartmentItem>('/system/departments', data)
}

/** 修改部门（权限：system:dept:update）。deptCode 不可改（SYS-W-03） */
export function updateDepartment(id: number, data: Record<string, unknown>) {
  return http.put<DepartmentItem>(`/system/departments/${id}`, data)
}

/** 部门启停（权限：system:dept:disable）；停用时部门下不能有启用用户 */
export function changeDepartmentStatus(id: number, status: number) {
  return http.patch<DepartmentItem>(`/system/departments/${id}/status`, { status })
}

/** 逻辑删除（权限：system:dept:delete）；存在下级部门或启用用户时会被拒绝 */
export function deleteDepartment(id: number) {
  return http.delete<DepartmentItem>(`/system/departments/${id}`)
}


/* ---------------- 用户配置 ---------------- */

export function pageUsers(params: UserQuery) {
  return http.get<PageResult<UserItem>>('/system/users', { params })
}

/** 逻辑删除（权限：system:user:delete） */
export function deleteUser(id: number) {
  return http.delete<UserItem>(`/system/users/${id}`)
}


/* ---------------- 角色配置 ---------------- */

export function pageRoles(params: RoleQuery) {
  return http.get<PageResult<RoleItem>>('/system/roles', { params })
}

/** 逻辑删除（权限：system:role:delete） */
export function deleteRole(id: number) {
  return http.delete<RoleItem>(`/system/roles/${id}`)
}


/* ---------------- 权限 ---------------- */

export function listPermissions() {
  return http.get<PermissionItem[]>('/system/permissions')
}
