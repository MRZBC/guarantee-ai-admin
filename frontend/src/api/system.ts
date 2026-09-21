import { http } from './request'
import type { PageResult } from '@/types/common'
import type {
  DepartmentItem,
  DepartmentOption,
  DepartmentQuery,
  InsuranceTypeCreateParams,
  InsuranceTypeItem,
  InsuranceTypeOption,
  InsuranceTypeQuery,
  InsuranceTypeUpdateParams,
  OrgItem,
  OrgOption,
  OrgQuery,
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

/* ---------------- 机构配置 ---------------- */

export function pageOrgs(params: OrgQuery) {
  return http.get<PageResult<OrgItem>>('/system/orgs', { params })
}

export function listOrgOptions() {
  return http.get<OrgOption[]>('/system/orgs/options')
}

/* ---------------- 部门配置 ---------------- */

export function pageDepartments(params: DepartmentQuery) {
  return http.get<PageResult<DepartmentItem>>('/system/departments', { params })
}

export function listDepartmentOptions(orgId?: number) {
  return http.get<DepartmentOption[]>('/system/departments/options', {
    params: orgId ? { orgId } : {}
  })
}

/* ---------------- 用户配置 ---------------- */

export function pageUsers(params: UserQuery) {
  return http.get<PageResult<UserItem>>('/system/users', { params })
}

/* ---------------- 角色配置 ---------------- */

export function pageRoles(params: RoleQuery) {
  return http.get<PageResult<RoleItem>>('/system/roles', { params })
}

/* ---------------- 权限 ---------------- */

export function listPermissions() {
  return http.get<PermissionItem[]>('/system/permissions')
}
