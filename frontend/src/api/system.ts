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
  RegionOption,
  RoleAssignPermissionsParams,
  RoleCreateParams,
  RoleItem,
  RoleQuery,
  RoleUpdateParams,
  SessionItem,
  SessionKickResult,
  SessionQuery,
  UserAssignRolesParams,
  UserCreateParams,
  UserItem,
  UserQuery,
  UserUpdateParams
} from '@/types/system'

/* ---------------- 地区（行政区划字典） ---------------- */

/**
 * 地区下拉选项（`GET /api/system/regions/options`）。
 *
 * <p>**登录即可访问**，不需要任何权限码：地区是国标公开数据，被订单 / 项目 / 企业 / 机构配置
 * 四个页面共用；筛选字典的授权必须对齐"能不能看业务数据"，否则"能看订单但没有系统配置权限"
 * 的角色会一进页面就吃 403（见 `docs/DEC-业务筛选下拉的授权口径.md`）。</p>
 *
 * @param level 层级：1 省 / 2 市 / 3 区县；不传 = 不限层级
 * @param parentCode 上级区划码（做省 → 市级联时用）
 * @param onlyWithData 默认 true：只返回"当前有业务数据"的地区（否则下拉里会出现选了必然空结果的项）
 */
export function listRegionOptions(params?: {
  level?: number
  parentCode?: string
  onlyWithData?: boolean
}) {
  return http.get<RegionOption[]>('/system/regions/options', { params })
}

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

/**
 * 险种启停（权限：system:insurance:disable）。status：1 启用 / 0 停用。
 *
 * <p>停用**不校验订单引用**：后端会正常改状态，历史订单因此不受影响；
 * 只有逻辑删除才会因"已被 N 条订单引用"被拒绝。页面文案必须与此口径一致。</p>
 */
export function changeInsuranceTypeStatus(id: number, status: number) {
  return http.patch<InsuranceTypeItem>(`/system/insurance-types/${id}/status`, { status })
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

/**
 * 用户启停（权限：system:user:disable）。status：1 启用 / 0 停用。
 *
 * <p>停用是一次**安全动作**：后端会立即撤销该用户已签发的全部令牌（JWT），
 * 因此其当前会话直接失效、必须重新登录。后端还会拒绝两类操作并在 message 里给出中文原因：
 * 停用自己、停用最后一个启用状态的超级管理员。</p>
 */
export function changeUserStatus(id: number, status: number) {
  return http.patch<UserItem>(`/system/users/${id}/status`, { status })
}

/** 逻辑删除（权限：system:user:delete） */
export function deleteUser(id: number) {
  return http.delete<UserItem>(`/system/users/${id}`)
}

/**
 * 新增用户（权限：system:user:create）。
 *
 * <p>后端写入**部署配置的默认密码**并置 `must_change_password = 1`：该用户首次登录
 * 必须先改密才能使用系统（服务端强制闸门）。返回体是新建的用户，供页面提示用。</p>
 */
export function createUser(data: UserCreateParams) {
  return http.post<UserItem>('/system/users', data)
}

/**
 * 修改用户资料（权限：system:user:update）。
 *
 * <p>只提交**真正变化的字段**：后端按"字段是否出现"判定变更，未出现的键会被忽略，
 * 因此页面里对未改动的字段不要放进 payload。</p>
 */
export function updateUser(id: number, data: UserUpdateParams) {
  return http.put<UserItem>(`/system/users/${id}`, data)
}

/**
 * 重置他人密码（权限：system:user:reset-password）。
 *
 * <p>无请求体：密码由后端写死为部署配置的默认密码，并置 `must_change_password = 1`。
 * **不能重置自己**（自己的密码走自助改密 `PUT /api/auth/password`，要验旧密码）。</p>
 */
export function resetUserPassword(id: number) {
  return http.post<UserItem>(`/system/users/${id}/reset-password`)
}

/**
 * 分配用户角色（权限：system:user:assign-role）。
 *
 * <p>**全量替换**语义：提交的是变更后的完整角色集合，不是增量。
 * 变更后后端会撤销该用户已签发的令牌，其当前会话立即失效。</p>
 */
export function assignUserRoles(id: number, data: UserAssignRolesParams) {
  return http.put<UserItem>(`/system/users/${id}/roles`, data)
}


/* ---------------- 角色配置 ---------------- */

export function pageRoles(params: RoleQuery) {
  return http.get<PageResult<RoleItem>>('/system/roles', { params })
}

/** 新增角色（权限：system:role:create）；roleCode 创建后不可修改 */
export function createRole(data: RoleCreateParams) {
  return http.post<RoleItem>('/system/roles', data)
}

/**
 * 修改角色（权限：system:role:update）。
 *
 * <p>刻意**不含 roleCode 与 status**：编码不可改；状态只能走
 * `PATCH /{id}/status`（那条路径才带"停用前置检查 + 撤销持有者令牌"），
 * 从 PUT 改状态会静默绕过保护。</p>
 */
export function updateRole(id: number, data: RoleUpdateParams) {
  return http.put<RoleItem>(`/system/roles/${id}`, data)
}

/**
 * 角色授权（权限：system:role:assign-permission）。
 *
 * <p>**全量替换**语义：提交的是变更后的完整权限码集合（`permCode`，不是 id）。
 * 变更后后端会撤销持有该角色用户的令牌，权限立即生效。</p>
 */
export function assignRolePermissions(roleCode: string, permCodes: string[]) {
  return http.put<RoleItem>(`/system/roles/${roleCode}/permissions`,
    { permCodes } as RoleAssignPermissionsParams)
}

/**
 * 角色启停（权限：system:role:disable）。status：1 启用 / 0 停用。
 *
 * <p>鉴权链路按 {@code r.status = 1} 过滤角色，所以停用是**立即生效**的权限收回：
 * 持有该角色的用户会立刻失去它带来的权限，且后端会撤销这些用户已签发的 JWT，
 * 他们必须重新登录。ADMIN（超级管理员）角色不允许停用。</p>
 */
export function changeRoleStatus(id: number, status: number) {
  return http.patch<RoleItem>(`/system/roles/${id}/status`, { status })
}

/** 逻辑删除（权限：system:role:delete） */
export function deleteRole(id: number) {
  return http.delete<RoleItem>(`/system/roles/${id}`)
}


/* ---------------- 权限 ---------------- */

export function listPermissions() {
  return http.get<PermissionItem[]>('/system/permissions')
}


/* ---------------- 在线会话（AUTH-05，权限：system:session:view / :kick） ---------------- */

/** 在线会话列表 */
export function pageSessions(params: SessionQuery) {
  return http.get<PageResult<SessionItem>>('/system/sessions', { params })
}

/**
 * 踢出单个会话。
 *
 * <p>踢出对**下一个请求**生效：不会中断对方正在进行的流式对话（该请求已通过认证）。
 * 返回的 `selfKicked` 为 true 时说明踢的是自己当前的会话，调用方应提示并跳登录页。</p>
 */
export function kickSession(jti: string) {
  return http.delete<SessionKickResult>(`/system/sessions/${encodeURIComponent(jti)}`)
}

/** 踢出某用户的全部会话（对方若有多个浏览器/设备，单踢一个不够） */
export function kickUserSessions(userId: number) {
  return http.delete<SessionKickResult>('/system/sessions', { params: { userId } })
}
