<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  assignUserRoles,
  changeUserStatus,
  createUser,
  deleteUser,
  kickSession,
  kickUserSessions,
  listDepartmentOptions,
  pageRoles,
  pageSessions,
  pageUsers,
  resetUserPassword,
  updateUser
} from '@/api/system'
import { useUserStore } from '@/stores/user'
import { confirmText } from '@/utils/confirmText'
import {
  BUILT_IN_DEFAULT_PASSWORD,
  DEFAULT_PASSWORD_CONFIG_KEY,
  DEFAULT_PASSWORD_HINT
} from '@/utils/defaultCredentials'
import { removeToken } from '@/utils/storage'
import { formatDateTime } from '@/utils/format'
import { isEnabled, statusLabel, statusParam, STATUS_OPTIONS } from '@/utils/status'
import type {
  DepartmentOption,
  RoleItem,
  SessionItem,
  UserAssignRolesParams,
  UserCreateParams,
  UserItem,
  UserQuery,
  UserUpdateParams
} from '@/types/system'

const userStore = useUserStore()

/** 保留角色码：后端禁止给自己增删 ADMIN（§4.2 / §6.4）。 */
const ADMIN_ROLE_CODE = 'ADMIN'

/** 下拉选项一次取足：系统管理域的角色/部门都是小数据集，翻页只会静默漏选项。 */
const OPTION_PAGE_SIZE = 200

/**
 * 修改资料对话框里手机号/邮箱的「当前值」掩码长度。
 * 展示的是**变更对比**（不是列表的"浏览"语义），掩码更贴合 D-4"审计只记是否变更"的精神（§6.3）。
 */
const MASK_KEEP_PREFIX = 3
const MASK_KEEP_SUFFIX = 4

const loading = ref(false)
const rows = ref<UserItem[]>([])
const total = ref(0)

const query = reactive<UserQuery>({
  pageNum: 1,
  pageSize: 10,
  username: '',
  realName: '',
  status: null
})

/*
  逐按钮权限过滤（SYS-NF-04）：前端过滤仅为体验优化——避免"点了才 403"的死按钮，
  后端仍是唯一安全边界。用户写权限默认仅 ADMIN 持有。
*/
/** 无 system:user:create 时不渲染「新增用户」入口 */
const canCreate = computed(() => userStore.permissions.includes('system:user:create'))
/** 无 system:user:update 时不渲染「改资料」入口 */
const canUpdate = computed(() => userStore.permissions.includes('system:user:update'))
/** 无 system:user:assign-role 时不渲染「角色」入口 */
const canAssign = computed(() => userStore.permissions.includes('system:user:assign-role'))
/** 无 system:user:reset-password 时不渲染「重置密码」入口 */
const canResetPassword = computed(() =>
  userStore.permissions.includes('system:user:reset-password')
)
/** 无 system:user:disable 时不渲染启停入口 */
const canDisable = computed(() => userStore.permissions.includes('system:user:disable'))
/** 无 system:user:delete 时不渲染删除入口 */
const canDelete = computed(() => userStore.permissions.includes('system:user:delete'))
/** 无 system:session:view 时不渲染「在线会话」入口 */
const canViewSessions = computed(() => userStore.permissions.includes('system:session:view'))
/** 无 system:session:kick 时不渲染踢出按钮 */
const canKickSession = computed(() => userStore.permissions.includes('system:session:kick'))

/**
 * 操作列是否渲染。
 *
 * <p><b>`canUpdate || canAssign || canResetPassword` 这三项不能漏</b>：只持有
 * 「改资料 / 角色 / 重置密码」而**没有**停用/删除/会话权限的账号，
 * 少了它们会导致整列不渲染（角色页踩过同一个坑）。</p>
 */
const showActions = computed(
  () =>
    canUpdate.value ||
    canAssign.value ||
    canResetPassword.value ||
    canDisable.value ||
    canViewSessions.value ||
    canDelete.value
)

/**
 * 该行是否就是当前登录用户。
 *
 * <p>页面上有四处规则都锚在这一点上（§6.5）：改自己的部门、停用自己、删除自己、
 * 重置自己的密码。后端同样会拒，前端置灰是为了消灭"点了才报错"。</p>
 */
function isSelf(row: UserItem): boolean {
  return userStore.user?.id !== undefined && userStore.user.id === row.id
}

/** 删除/停用/重置等提示里的用户称谓：优先姓名，退回用户名 */
function userLabel(row: UserItem): string {
  return row.realName || row.username
}

/* ---------------- 「禁用 + tooltip」的文案来源（§6.5） ---------------- */

type RowAction = 'update' | 'assign' | 'reset' | 'disable' | 'sessions' | 'delete'

/**
 * 因**业务规则**不可用时返回原因；可用时返回空串。
 *
 * <p>只有禁用而无说明，用户就分不清"我没权限""这是业务规则""系统坏了"三种可能，
 * 所以本函数是"禁用 + tooltip"约定的**唯一文案来源**（与 `Roles.vue` 的
 * `disabledReason()` 同款写法），避免六个按钮各写一段导致措辞漂移。</p>
 *
 * <p>注意 `update` 的禁用只针对**部门字段**（在对话框内对下拉框置灰），
 * 而不是禁用「改资料」按钮本身——"不允许修改自己的所属部门"不妨碍改自己的
 * 姓名/手机号/邮箱，把整个按钮禁掉会让用户以为自己不能改任何资料。</p>
 */
function disabledReason(row: UserItem, action: RowAction): string {
  if (isSelf(row)) {
    switch (action) {
      case 'reset':
        // 自己的密码走自助改密（要验旧密码），走管理侧重置等于绕过旧密码校验。
        // 这条守卫顺带覆盖"目标是最后一个启用 ADMIN"的场景——那时目标必然是操作者本人。
        return '请使用「修改密码」修改自己的密码'
      case 'disable':
        return '不允许停用自己的账号'
      case 'delete':
        return '不允许删除自己的账号'
      default:
        return ''
    }
  }
  return ''
}

/* ---------------- 列表 ---------------- */

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const result = await pageUsers({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      username: query.username || undefined,
      realName: query.realName || undefined,
      status: statusParam(query.status),
    })
    rows.value = result?.list ?? []
    total.value = result?.total ?? 0
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  query.pageNum = 1
  void loadData()
}

function handleReset(): void {
  query.username = ''
  query.realName = ''
  query.status = null
  query.pageNum = 1
  void loadData()
}


function handlePageChange(page: number): void {
  query.pageNum = page
  void loadData()
}

function handleSizeChange(size: number): void {
  query.pageSize = size
  query.pageNum = 1
  void loadData()
}

/* ---------------- 下拉选项：部门与角色 ---------------- */

const deptOptions = ref<DepartmentOption[]>([])
const deptLoading = ref(false)
const roleOptions = ref<RoleItem[]>([])
const roleLoading = ref(false)

/**
 * 部门下拉项。
 *
 * <p>后端已按"仅启用、未删除"过滤（`/system/departments/options`），
 * 响应体里也**没有** `isDeleted` 字段——因此这里不再二次过滤，也不要"顺手"加一个
 * `isDeleted !== 1` 判断：那会在类型上依赖一个契约没承诺的字段，且暗示后端可能返回
 * 已删除部门。若后端过滤口径变化，应改后端而不是在这里补丁。</p>
 */
async function loadDeptOptions(): Promise<void> {
  deptLoading.value = true
  try {
    const list = await listDepartmentOptions()
    deptOptions.value = list ?? []
  } catch {
    // 拦截器已提示；下拉为空时用户仍可重开弹窗重试
    deptOptions.value = []
  } finally {
    deptLoading.value = false
  }
}

/**
 * 角色下拉项：**只保留启用中的角色**。
 *
 * <p>后端 `validateAssignRoles` / `validateCreate` 要求角色必须存在且启用，
 * 因此停用角色不能出现在可选项里（否则提交必被拒）。</p>
 */
async function loadRoleOptions(): Promise<void> {
  roleLoading.value = true
  try {
    const result = await pageRoles({ pageNum: 1, pageSize: OPTION_PAGE_SIZE })
    roleOptions.value = (result?.list ?? []).filter((item) => isEnabled(item.status))
  } catch {
    roleOptions.value = []
  } finally {
    roleLoading.value = false
  }
}

/** 角色码 → 角色名（角色的可读名，仅用于展示）。 */
const roleNameByCode = computed(() => {
  const map = new Map<string, string>()
  for (const role of roleOptions.value) {
    map.set(role.roleCode, role.roleName || role.roleCode)
  }
  return map
})

function roleLabel(code: string): string {
  return roleNameByCode.value.get(code) ?? code
}

/* ---------------- 新增用户（权限：system:user:create） ---------------- */

const createVisible = ref(false)
const createSubmitting = ref(false)
const createFormRef = ref<FormInstance>()

const createForm = reactive<{
  username: string
  realName: string
  deptId: number | null
  phone: string
  email: string
  roleCodes: string[]
}>({
  username: '',
  realName: '',
  deptId: null,
  phone: '',
  email: '',
  roleCodes: []
})

/** 11 位大陆手机号：与后端 `validateCreate` 的口径一致（前端只做先拦）。 */
const PHONE_PATTERN = /^1[3-9]\d{9}$/
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const createRules = computed<FormRules>(() => ({
  username: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_.-]{4,64}$/,
      message: '登录账号由 4-64 位字母、数字、下划线、点或中划线组成',
      trigger: 'blur'
    }
  ],
  realName: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { max: 64, message: '姓名长度不能超过 64', trigger: 'blur' }
  ],
  deptId: [{ required: true, message: '请选择所属部门', trigger: 'change' }],
  phone: [
    {
      validator: (_rule, value, callback) => {
        const text = String(value ?? '').trim()
        if (text !== '' && !PHONE_PATTERN.test(text)) {
          callback(new Error('手机号格式不正确，应为 11 位大陆手机号'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  email: [
    {
      validator: (_rule, value, callback) => {
        const text = String(value ?? '').trim()
        if (text !== '' && !EMAIL_PATTERN.test(text)) {
          callback(new Error('邮箱格式不正确'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  roleCodes: [
    {
      // D2=A：至少 1 个角色。避免"能登录但整站空白、每个接口都 403"的账号
      validator: (_rule, value, callback) => {
        if (!Array.isArray(value) || value.length === 0) {
          callback(new Error('请至少选择一个角色'))
          return
        }
        callback()
      },
      trigger: 'change'
    }
  ]
}))

function resetCreateForm(): void {
  createForm.username = ''
  createForm.realName = ''
  createForm.deptId = null
  createForm.phone = ''
  createForm.email = ''
  createForm.roleCodes = []
  createFormRef.value?.clearValidate()
}

function openCreate(): void {
  resetCreateForm()
  createVisible.value = true
  // 选项可能因为上一次加载失败而为空，打开时补一次（已有数据时不重复请求）
  if (deptOptions.value.length === 0 && !deptLoading.value) void loadDeptOptions()
  if (roleOptions.value.length === 0 && !roleLoading.value) void loadRoleOptions()
}

/**
 * 提交新增。
 *
 * <p><b>成功后不弹密码框</b>：密码由后端写入部署配置的固定默认密码，
 * 前端既没提供也无从获知——只要提示"初始密码为系统默认密码，首次登录必须修改"。</p>
 */
async function handleCreateSubmit(): Promise<void> {
  if (!createFormRef.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return

  createSubmitting.value = true
  try {
    const payload: UserCreateParams = {
      username: createForm.username.trim(),
      realName: createForm.realName.trim(),
      // 表单校验已保证非空，这里的兜底只为类型收窄
      deptId: createForm.deptId as number,
      phone: createForm.phone.trim() || undefined,
      email: createForm.email.trim() || undefined,
      roleCodes: [...createForm.roleCodes]
    }
    const created = await createUser(payload)
    const label = created?.realName || created?.username || payload.realName
    createVisible.value = false
    await loadData()
    ElMessage.success(
      {
        message: `用户「${label}」已创建。${DEFAULT_PASSWORD_HINT}。`
          + '该用户首次登录时必须修改后才能使用，请线下转达并督促其尽快登录。',
        duration: 8000
      }
    )
  } catch {
    // 失败原因（如「登录账号已存在：xxx」）已由响应拦截器统一提示；
    // 刻意不关弹窗，便于就地改名后重试。
  } finally {
    createSubmitting.value = false
  }
}

/* ---------------- 修改资料（权限：system:user:update） ---------------- */

const editVisible = ref(false)
const editSubmitting = ref(false)
const editFormRef = ref<FormInstance>()
/** 被编辑行的原始值快照：用于"空改动判断"与"只提交真正变了的字段"。 */
const editOrigin = ref<UserItem | null>(null)

const editForm = reactive<{
  id: number | null
  username: string
  realName: string
  phone: string
  email: string
  deptId: number | null
}>({
  id: null,
  username: '',
  realName: '',
  phone: '',
  email: '',
  deptId: null
})

const editTargetIsSelf = computed(() => editForm.id !== null && userStore.user?.id === editForm.id)

const editRules = computed<FormRules>(() => ({
  realName: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { max: 64, message: '姓名长度不能超过 64', trigger: 'blur' }
  ],
  phone: [
    {
      validator: (_rule, value, callback) => {
        const text = String(value ?? '').trim()
        if (text !== '' && !PHONE_PATTERN.test(text)) {
          callback(new Error('手机号格式不正确，应为 11 位大陆手机号'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  email: [
    {
      validator: (_rule, value, callback) => {
        const text = String(value ?? '').trim()
        if (text !== '' && !EMAIL_PATTERN.test(text)) {
          callback(new Error('邮箱格式不正确'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}))

function resetEditForm(): void {
  editForm.id = null
  editForm.username = ''
  editForm.realName = ''
  editForm.phone = ''
  editForm.email = ''
  editForm.deptId = null
  editOrigin.value = null
  editFormRef.value?.clearValidate()
}

function openEdit(row: UserItem): void {
  resetEditForm()
  editForm.id = row.id
  editForm.username = row.username
  editForm.realName = row.realName ?? ''
  editForm.phone = row.phone ?? ''
  editForm.email = row.email ?? ''
  editForm.deptId = row.deptId ?? null
  editOrigin.value = row
  editVisible.value = true
  if (deptOptions.value.length === 0 && !deptLoading.value) void loadDeptOptions()
}

/**
 * 提交前的**空改动判断**（§6.3 / §4.1）。
 *
 * <p>四个字段与当前值完全一致时保存按钮禁用并提示「未做任何修改」——
 * 因为后端 `UpdateRequest.isEmpty()` 为 true 时会返回
 * 「至少需要提供一个待修改字段（realName / phone / email / deptId）」，
 * 前端不应该把这个可预测的失败推给用户。</p>
 *
 * <p>`deptId` 只在**真的换了部门**时才算改动：按契约 `null` / 省略表示"不改"，
 * 传原值等于制造一次无意义的写操作。</p>
 */
const editChanged = computed<boolean>(() => {
  const origin = editOrigin.value
  if (!origin) return false
  if ((editForm.realName ?? '').trim() !== (origin.realName ?? '')) return true
  if ((editForm.phone ?? '').trim() !== (origin.phone ?? '')) return true
  if ((editForm.email ?? '').trim() !== (origin.email ?? '')) return true
  if (editForm.deptId !== null && editForm.deptId !== origin.deptId) return true
  return false
})

/** 非空即表示保存不可用，同时作为禁用按钮的 tooltip 文案（与 `Roles.vue` 的 `assignBlockReason` 同款）。 */
const editBlockReason = computed(() => {
  if (editForm.id === null) return ''
  if (!editChanged.value) return '未做任何修改'
  return ''
})

/**
 * 提交修改资料。
 *
 * <p>只上送**真正发生变化**的字段：后端对 `realName / phone / email / deptId` 都是可选的，
 * 且 `deptId` 省略即表示"不改"，因此整包回传并不会"更安全"，反而会：
 * ① 让审计的 before/after 记下一次没有意义的"变更"；② 在并发编辑时用陈旧值覆盖他人的改动。</p>
 */
async function handleEditSubmit(): Promise<void> {
  if (!editFormRef.value) return
  const origin = editOrigin.value
  if (!origin || editForm.id === null) return
  const valid = await editFormRef.value.validate().catch(() => false)
  if (!valid) return
  if (!editChanged.value) {
    ElMessage.info('未做任何修改')
    return
  }

  editSubmitting.value = true
  try {
    const payload: UserUpdateParams = {}
    const realName = editForm.realName.trim()
    const phone = editForm.phone.trim()
    const email = editForm.email.trim()
    if (realName !== (origin.realName ?? '')) payload.realName = realName
    if (phone !== (origin.phone ?? '')) payload.phone = phone
    if (email !== (origin.email ?? '')) payload.email = email
    if (editForm.deptId !== null && editForm.deptId !== origin.deptId) {
      payload.deptId = editForm.deptId
    }

    await updateUser(editForm.id, payload)
    editVisible.value = false
    ElMessage.success(`已更新用户「${userLabel(origin)}」的资料`)
    await loadData()
  } catch {
    // 失败原因（如「不允许修改自己的所属部门」「部门不存在或已删除: 9」）已由拦截器统一提示
  } finally {
    editSubmitting.value = false
  }
}

/* ---------------- 角色分配（权限：system:user:assign-role） ---------------- */

const assignVisible = ref(false)
const assignSubmitting = ref(false)
const assignTarget = ref<UserItem | null>(null)
/** 对话框内正在编辑的完整角色码集合（全量替换语义，提交的就是它） */
const assignCodes = ref<string[]>([])

/**
 * 目标用户当前持有的角色码。
 *
 * <p>按冻结契约，后端返回 `roleCodes`——它是**唯一**"回显了就不会被判不存在"的来源
 * （`roleCodes` 与分配接口的入参、后端的角色校验同为 `is_deleted = 0` 口径）。
 * 若该字段缺失（接口尚未升级或列表未回填），退回 `roleNames`：`el-select` 的选项
 * 以编码为 value，未匹配的编码会显示为裸值；这是**降级**而不是正确行为，
 * 因此只在前者不可用时使用。</p>
 */
function currentRoleCodes(row: UserItem): string[] {
  const codes = row.roleCodes
  if (codes && codes.length > 0) return codes
  return row.roleNames ?? []
}

const assignOriginalCodes = computed<string[]>(() =>
  assignTarget.value ? currentRoleCodes(assignTarget.value) : []
)

function openAssign(row: UserItem): void {
  assignTarget.value = row
  assignCodes.value = [...currentRoleCodes(row)]
  assignVisible.value = true
  if (roleOptions.value.length === 0 && !roleLoading.value) void loadRoleOptions()
}

const assignAddedCodes = computed(() =>
  assignCodes.value.filter((code) => !assignOriginalCodes.value.includes(code))
)
const assignRemovedCodes = computed(() =>
  assignOriginalCodes.value.filter((code) => !assignCodes.value.includes(code))
)
const assignDirty = computed(
  () => assignAddedCodes.value.length > 0 || assignRemovedCodes.value.length > 0
)

/** 目标是自己时，`ADMIN` 选项置灰（后端禁止给自己增删 ADMIN）。 */
const assignTargetIsSelf = computed(
  () => assignTarget.value !== null && isSelf(assignTarget.value)
)

/** 非空即表示保存不可用，同时作为禁用按钮的 tooltip 文案。 */
const assignBlockReason = computed(() => {
  if (roleLoading.value) return '角色清单加载中…'
  if (assignCodes.value.length === 0) return '请至少选择一个角色'
  if (!assignDirty.value) return '角色未发生变化'
  return ''
})

async function handleAssignSubmit(): Promise<void> {
  const target = assignTarget.value
  if (!target || assignBlockReason.value) return

  const label = userLabel(target)
  try {
    await ElMessageBox.confirm(
      confirmText(`确认将「${label}」的角色调整为 ${assignCodes.value.length} 个？`, [
        '保存后该用户的全部登录会话会被立即强制下线（含 AI 助手的长连接），需要重新登录后新权限才生效',
        '不允许给自己增加或移除 ADMIN 角色',
        '若该用户是最后一个启用状态的超级管理员，移除其 ADMIN 角色会被系统拒绝'
      ]),
      '角色分配',
      { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  assignSubmitting.value = true
  try {
    // 全量替换语义：提交的是变更后的完整集合
    const payload: UserAssignRolesParams = { roleCodes: [...assignCodes.value] }
    await assignUserRoles(target.id, payload)
    assignVisible.value = false
    ElMessage.success(`已更新「${label}」的角色`)
    await loadData()
  } catch {
    // 失败原因（如「不允许给自己增加或移除 ADMIN 角色」）已由响应拦截器统一提示；
    // 刻意不关弹窗，便于就地调整后重试。
  } finally {
    assignSubmitting.value = false
  }
}

/* ---------------- 重置密码（权限：system:user:reset-password，D3=B） ---------------- */

/**
 * 管理员重置他人密码。
 *
 * <p><b>用 `ElMessageBox.confirm` 而不是对话框表单</b>：本操作**无任何入参**，
 * 不存在"填什么"的问题，只是一个危险动作确认——与「停用」「删除」的既有形态一致。
 * 让管理员指定一个新密码是刻意排除的：那会让明文密码经手管理员并进入请求体，
 * 正是"固定默认密码"方案要避免的。</p>
 *
 * <p>确认文案必须写清三件事（§6.4a）：重置后必须改密才能用、当前会话全部强制下线、
 * 目标是最后一个启用超管时会被拒。</p>
 */
async function handleResetPassword(row: UserItem): Promise<void> {
  const label = userLabel(row)
  try {
    await ElMessageBox.confirm(
      confirmText(`确认将「${label}」的密码重置为系统默认密码？`, [
        `重置后该账号的密码为 ${BUILT_IN_DEFAULT_PASSWORD}（若部署已通过 ${DEFAULT_PASSWORD_CONFIG_KEY} 覆盖，以运维配置为准）`,
        '重置后该用户下次登录必须修改密码才能使用系统',
        '该用户当前所有登录会话会被立即强制下线（含 AI 助手的长连接）',
        '该用户是最后一个启用状态的超级管理员时会被系统拒绝'
      ]),
      '重置密码',
      { type: 'warning', confirmButtonText: '确认重置', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    // 无请求体：密码由后端写死为部署配置的默认密码
    await resetUserPassword(row.id)
    ElMessage.success(
      {
        message: `已重置「${label}」的密码。${DEFAULT_PASSWORD_HINT}。`
          + '该用户下次登录必须修改密码，请线下转达。',
        duration: 8000
      }
    )
    await loadData()
  } catch {
    // 失败原因（如「请使用「修改密码」修改自己的密码」）已由响应拦截器统一提示
  }
}

/* ---------------- 启停（权限：system:user:disable） ---------------- */

/**
 * 启停用户。
 *
 * <p>停用是**安全动作**：后端会立即撤销该用户已签发的全部令牌（JWT），其当前会话随即失效，
 * 必须重新登录。后端还会拒绝"停用自己"与"停用最后一个启用状态的超级管理员"，
 * 拒绝原因由响应拦截器统一提示，这里不重复加工。</p>
 */
async function toggleStatus(row: UserItem): Promise<void> {
  const label = userLabel(row)
  const next = isEnabled(row.status) ? 0 : 1
  const word = next === 0 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      next === 0
        ? confirmText(`确认停用「${label}」？`, [
            '该用户会被立即强制下线，需要重新登录',
            '不能停用自己，也不能停用最后一个启用状态的超级管理员，这两种情况会被系统拒绝',
            '停用不是删除，用户记录仍然保留，可随时重新启用'
          ])
        : `确认启用「${label}」？`,
      `${word}用户`,
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await changeUserStatus(row.id, next)
    ElMessage.success(`已${word}「${label}」`)
    await loadData()
  } catch {
    // 失败原因（如「不允许停用自己的账号」）已由响应拦截器统一提示
  }
}

/* ---------------- 逻辑删除（权限：system:user:delete） ---------------- */

async function handleDelete(row: UserItem): Promise<void> {
  const label = userLabel(row)
  try {
    await ElMessageBox.confirm(
      confirmText(`确认删除用户「${label}」？`, [
        '删除后该用户不再出现在默认列表中，且页面不提供恢复入口——如只是暂停业务，请改用「停用」',
        '删除后该账号无法登录，若其当前处于登录状态会被立即强制下线',
        '若该用户已被其它数据引用，删除会被拒绝并给出引用数量'
      ]),
      '删除用户',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteUser(row.id)
    ElMessage.success(`已删除用户「${label}」`)
    await loadData()
  } catch {
    // 失败原因（如「该用户不能删除：存在 2 条在办业务」）已由响应拦截器统一提示
  }
}


/* ---------------- 在线会话与强制下线（权限：system:session:view / :kick） ---------------- */

const sessionDrawerVisible = ref(false)
const sessionLoading = ref(false)
const sessionRows = ref<SessionItem[]>([])
const sessionUser = ref<UserItem | null>(null)

/** 打开某用户的在线会话抽屉。入口只对有 view 权限的账号渲染，后端仍是安全边界 */
async function openSessions(row: UserItem): Promise<void> {
  sessionUser.value = row
  sessionDrawerVisible.value = true
  await loadSessions()
}

async function loadSessions(): Promise<void> {
  const user = sessionUser.value
  if (!user) {
    return
  }
  sessionLoading.value = true
  try {
    // 在线会话量级很小（管理员后台），一次取足即可；分页在服务端已支持，这里不做翻页交互
    const result = await pageSessions({ pageNum: 1, pageSize: 100, userId: user.id })
    sessionRows.value = result?.list ?? []
  } catch {
    sessionRows.value = []
  } finally {
    sessionLoading.value = false
  }
}

/**
 * 自己被踢出后清理本地登录态。
 *
 * 不复用 `request.ts` 的跳转逻辑（它未导出）：这里的行为必须与它一致——
 * 清 token → 跳登录页 → 刷新以清空内存中的登录态。
 */
function redirectToLoginAfterSelfKick(): void {
  ElMessage.warning('已踢出你自己的当前会话，请重新登录')
  removeToken()
  window.location.hash = '#/login'
  window.location.reload()
}

async function kickOne(session: SessionItem): Promise<void> {
  const label = session.current ? '你自己的当前会话' : `会话 ${session.loginIp || ''}`.trim()
  try {
    await ElMessageBox.confirm(
      confirmText(`确认踢出${label}？`, [
        '对方会被立即登出，需要重新登录',
        '踢出对下一个请求生效，不会中断对方正在进行的流式对话',
        '这只是踢出这一条会话——若对方还有其它浏览器/设备的会话，请用「全部踢出」'
      ]),
      '强制下线',
      { type: 'warning', confirmButtonText: '确认踢出', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const result = await kickSession(session.jti)
    if (result?.selfKicked) {
      redirectToLoginAfterSelfKick()
      return
    }
    ElMessage.success(result?.kicked ? '已踢出该会话' : '该会话已失效，无需踢出')
    await loadSessions()
  } catch {
    // 失败原因已由响应拦截器统一提示
  }
}

async function kickAllSessions(): Promise<void> {
  const user = sessionUser.value
  if (!user) {
    return
  }
  const label = user.realName || user.username
  try {
    await ElMessageBox.confirm(
      confirmText(`确认踢出「${label}」的全部会话？`, [
        '该用户在所有浏览器/设备上的登录都会失效，需要重新登录',
        '若其中包含你自己当前的会话，你也会被登出',
        '这与「停用用户」不同：账号本身不受影响，随时可以重新登录'
      ]),
      '全部踢出',
      { type: 'warning', confirmButtonText: '确认全部踢出', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const result = await kickUserSessions(user.id)
    if (result?.selfKicked) {
      redirectToLoginAfterSelfKick()
      return
    }
    ElMessage.success(`已踢出 ${result?.kicked ?? 0} 个会话`)
    await loadSessions()
  } catch {
    // 失败原因已由响应拦截器统一提示
  }
}


/* ---------------- 掩码（D-4：变更对比用掩码，列表浏览仍维持明文） ---------------- */

/**
 * 手机号掩码：`138****5678`（保留前 3 位与后 4 位）。
 *
 * <p>只用于**改资料对话框的"当前值"回显**：那里展示的是"变更对比"，
 * 与列表的"浏览"语义不同（§6.3）。列表列展示维持明文（D6=Q-15），不要顺手改。</p>
 */
function maskPhone(value: string | null): string {
  const text = (value ?? '').trim()
  if (text === '') return '（未填写）'
  if (text.length <= MASK_KEEP_PREFIX + MASK_KEEP_SUFFIX) {
    return '*'.repeat(text.length)
  }
  return (
    text.slice(0, MASK_KEEP_PREFIX)
    + '*'.repeat(text.length - MASK_KEEP_PREFIX - MASK_KEEP_SUFFIX)
    + text.slice(-MASK_KEEP_SUFFIX)
  )
}

/**
 * 邮箱掩码：`z******n@example.com`（保留本地部分首尾字符，域名保留）。
 *
 * <p>与手机号同一用途、同一理由。域名不掩码是因为它本身不是个人信息，
 * 掩掉反而让人无法确认"当前值到底是哪个邮箱"——那就失去对比的意义了。</p>
 */
function maskEmail(value: string | null): string {
  const text = (value ?? '').trim()
  if (text === '') return '（未填写）'
  const at = text.lastIndexOf('@')
  if (at <= 0) return maskPhone(text)
  const local = text.slice(0, at)
  const domain = text.slice(at)
  if (local.length <= 2) return `${local.slice(0, 1)}***${domain}`
  return `${local.slice(0, 1)}${'*'.repeat(local.length - 2)}${local.slice(-1)}${domain}`
}

onMounted(() => {
  void loadData()
})
</script>

<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <el-form :model="query" label-width="82px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="用户名">
              <el-input
                v-model="query.username"
                placeholder="请输入用户名"
                clearable
                @keyup.enter="handleSearch"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="姓名">
              <el-input v-model="query.realName" placeholder="请输入姓名" clearable />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="状态">
              <el-select v-model="query.status" placeholder="全部状态" clearable>
                <el-option
                  v-for="opt in STATUS_OPTIONS"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6" class="filter-actions">
            <el-form-item label-width="0">
              <el-button type="primary" icon="Search" @click="handleSearch">查询</el-button>
              <el-button icon="Refresh" @click="handleReset">重置</el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <div class="table-toolbar">
        <span class="table-toolbar__title">用户列表 · 共 {{ total }} 条</span>
        <el-button v-if="canCreate" type="primary" icon="Plus" @click="openCreate">
          新增用户
        </el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="rows"
        border
        stripe
        height="520"
        row-key="id"
      >
        <el-table-column type="index" label="#" width="52" align="center" fixed />
        <el-table-column prop="username" label="用户名" width="140" fixed show-overflow-tooltip />
        <el-table-column prop="realName" label="姓名" width="110" show-overflow-tooltip />
        <el-table-column prop="deptName" label="所属部门" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.deptName || '--' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="130">
          <!-- D6=Q-15：列表列展示**维持明文**，掩码只用于改资料对话框的"当前值"回显 -->
          <template #default="{ row }">{{ row.phone || '--' }}</template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.email || '--' }}</template>
        </el-table-column>
        <el-table-column prop="roleNames" label="角色" min-width="170">
          <template #default="{ row }">
            <template v-if="row.roleNames && row.roleNames.length">
              <el-tag
                v-for="role in row.roleNames"
                :key="role"
                size="small"
                type="primary"
                effect="plain"
                class="tag-gap"
              >
                {{ role }}
              </el-tag>
            </template>
            <span v-else>--</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="isEnabled(row.status) ? 'success' : 'info'" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最后登录" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>

        <!--
          操作列（§6.1）：六个按钮，宽度 340。
          `v-if` 必须包含 canUpdate / canAssign / canResetPassword —— 现状只有
          canDisable || canDelete || canViewSessions，那样"只持有改资料/角色/重置权限"的账号
          会整列不渲染（角色页已踩过同一个坑）。
        -->
        <el-table-column v-if="showActions" label="操作" width="340" align="center" fixed="right">
          <template #default="{ row }">
            <!--
              无权限不渲染按钮；因业务规则不可用的按钮一律「禁用 + tooltip」成对出现。
              el-tooltip 对 disabled 的 el-button 不生效（禁用元素不触发鼠标事件），
              因此必须用 <span> 包裹 —— Element Plus 的已知行为（§6.5）。
              按钮间距由 .row-actions 的 flex + gap 控制，不依赖相邻兄弟选择器。
            -->
            <div class="row-actions">
              <el-tooltip
                v-if="canUpdate"
                :disabled="!disabledReason(row, 'update')"
                :content="disabledReason(row, 'update')"
              >
                <span>
                  <el-button link type="primary" @click="openEdit(row)">改资料</el-button>
                </span>
              </el-tooltip>

              <el-tooltip
                v-if="canAssign"
                :disabled="!disabledReason(row, 'assign')"
                :content="disabledReason(row, 'assign')"
              >
                <span>
                  <el-button link type="primary" @click="openAssign(row)">角色</el-button>
                </span>
              </el-tooltip>

              <el-tooltip
                v-if="canResetPassword"
                :disabled="!disabledReason(row, 'reset')"
                :content="disabledReason(row, 'reset')"
              >
                <span>
                  <el-button
                    link
                    type="primary"
                    :disabled="!!disabledReason(row, 'reset')"
                    @click="handleResetPassword(row)"
                  >
                    重置密码
                  </el-button>
                </span>
              </el-tooltip>

              <el-tooltip
                v-if="canDisable"
                :disabled="!disabledReason(row, 'disable')"
                :content="disabledReason(row, 'disable')"
              >
                <span>
                  <el-button
                    link
                    type="primary"
                    :disabled="!!disabledReason(row, 'disable')"
                    @click="toggleStatus(row)"
                  >
                    {{ isEnabled(row.status) ? '停用' : '启用' }}
                  </el-button>
                </span>
              </el-tooltip>

              <el-tooltip
                v-if="canViewSessions"
                :disabled="!disabledReason(row, 'sessions')"
                :content="disabledReason(row, 'sessions')"
              >
                <span>
                  <el-button link type="primary" @click="openSessions(row)">在线会话</el-button>
                </span>
              </el-tooltip>

              <el-tooltip
                v-if="canDelete"
                :disabled="!disabledReason(row, 'delete')"
                :content="disabledReason(row, 'delete')"
              >
                <span>
                  <el-button
                    link
                    type="danger"
                    :disabled="!!disabledReason(row, 'delete')"
                    @click="handleDelete(row)"
                  >
                    删除
                  </el-button>
                </span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无用户数据" :image-size="80" />
        </template>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          :current-page="query.pageNum"
          :page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- ============ 新增用户（权限：system:user:create，§6.2） ============
         刻意**没有密码字段**：密码由后端写入部署配置的固定默认密码并置强制改密标记。
         也没有「状态」字段：新账号固定启用。 -->
    <el-dialog v-model="createVisible" title="新增用户" width="620px" @closed="resetCreateForm">
      <el-alert type="info" :closable="false" show-icon class="dialog-alert">
        <template #title>初始密码为系统默认密码（{{ BUILT_IN_DEFAULT_PASSWORD }}）</template>
        <div class="dialog-alert__body">
          创建成功后该用户首次登录时<strong>必须修改初始密码</strong>才能使用系统。
          请线下转达并督促其尽快登录。
          <br />
          若本次部署已通过 <code>{{ DEFAULT_PASSWORD_CONFIG_KEY }}</code> 覆盖默认密码，请以运维配置为准。
        </div>
      </el-alert>

      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="100px">
        <el-form-item label="登录账号" prop="username">
          <el-input v-model="createForm.username" placeholder="如 zhangsan" clearable />
          <div class="form-hint text-muted">
            4-64 位字母、数字、下划线、点或中划线；创建后不可修改
          </div>
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="createForm.realName" placeholder="如 张三" clearable />
        </el-form-item>
        <el-form-item label="所属部门" prop="deptId">
          <el-select
            v-model="createForm.deptId"
            placeholder="请选择所属部门"
            filterable
            :loading="deptLoading"
            style="width: 100%"
          >
            <el-option
              v-for="dept in deptOptions"
              :key="dept.id"
              :label="dept.deptName"
              :value="dept.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="createForm.phone" placeholder="选填，11 位手机号" clearable />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="createForm.email" placeholder="选填，如 zhangsan@example.com" clearable />
        </el-form-item>
        <el-form-item label="角色" prop="roleCodes">
          <el-select
            v-model="createForm.roleCodes"
            multiple
            placeholder="请至少选择一个角色"
            :loading="roleLoading"
            style="width: 100%"
          >
            <el-option
              v-for="role in roleOptions"
              :key="role.roleCode"
              :label="role.roleName || role.roleCode"
              :value="role.roleCode"
            />
          </el-select>
          <div class="form-hint text-muted">
            角色必填（至少 1 个）：没有角色的账号能登录，但每个接口都会 403
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <!-- flex + gap：多个按钮被 <span> 包住后，相邻兄弟选择器不再匹配（§6.5 连带注意） -->
        <div class="dialog-footer">
          <el-button @click="createVisible = false">取消</el-button>
          <el-button type="primary" :loading="createSubmitting" @click="handleCreateSubmit">
            创建
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- ============ 修改资料（权限：system:user:update，§6.3） ============ -->
    <el-dialog v-model="editVisible" title="修改用户资料" width="640px" @closed="resetEditForm">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="100px">
        <el-form-item label="登录账号">
          <!-- username 不可改：后端 UpdateRequest 里根本没有该字段 -->
          <el-input :model-value="editForm.username" disabled />
          <div class="form-hint text-muted">登录账号创建后不可修改</div>
        </el-form-item>

        <el-form-item label="姓名" prop="realName">
          <el-input v-model="editForm.realName" placeholder="请输入姓名" clearable />
          <div class="form-hint text-muted">
            当前：{{ editOrigin?.realName || '（未填写）' }} → 新值：{{ editForm.realName || '（未填写）' }}
          </div>
        </el-form-item>

        <el-form-item label="手机号" prop="phone">
          <el-input v-model="editForm.phone" placeholder="11 位手机号" clearable />
          <!-- 当前值以**掩码**展示（§6.3 / P04-T9）：这里展示的是变更对比，不是浏览 -->
          <div class="form-hint text-muted">
            当前：{{ maskPhone(editOrigin?.phone ?? null) }} → 新值：{{ editForm.phone || '（未填写）' }}
          </div>
        </el-form-item>

        <el-form-item label="邮箱" prop="email">
          <el-input v-model="editForm.email" placeholder="如 zhangsan@example.com" clearable />
          <div class="form-hint text-muted">
            当前：{{ maskEmail(editOrigin?.email ?? null) }} → 新值：{{ editForm.email || '（未填写）' }}
          </div>
        </el-form-item>

        <el-form-item label="所属部门">
          <!-- 目标是自己 → 禁用 + tooltip（后端亦拒）。tooltip 必须用 <span> 包裹禁用按钮 -->
          <el-tooltip
            :disabled="!editTargetIsSelf"
            content="不允许修改自己的所属部门"
            placement="top"
          >
            <span class="block-span">
              <el-select
                v-model="editForm.deptId"
                placeholder="请选择所属部门"
                filterable
                :loading="deptLoading"
                :disabled="editTargetIsSelf"
                style="width: 100%"
              >
                <el-option
                  v-for="dept in deptOptions"
                  :key="dept.id"
                  :label="dept.deptName"
                  :value="dept.id"
                />
              </el-select>
            </span>
          </el-tooltip>
          <div v-if="editTargetIsSelf" class="form-hint text-muted">
            不允许修改自己的所属部门
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="editVisible = false">取消</el-button>
          <el-tooltip :disabled="!editBlockReason" :content="editBlockReason">
            <span>
              <el-button
                type="primary"
                :disabled="!!editBlockReason"
                :loading="editSubmitting"
                @click="handleEditSubmit"
              >
                保存
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </template>
    </el-dialog>

    <!-- ============ 角色分配（权限：system:user:assign-role，§6.4） ============ -->
    <el-dialog
      v-model="assignVisible"
      :title="`分配角色 —— ${assignTarget ? userLabel(assignTarget) : ''}`"
      width="620px"
      destroy-on-close
    >
      <el-form label-width="100px">
        <el-form-item label="当前角色">
          <template v-if="assignOriginalCodes.length">
            <el-tag
              v-for="code in assignOriginalCodes"
              :key="`cur-${code}`"
              size="small"
              type="info"
              effect="plain"
              class="tag-gap"
            >
              {{ roleLabel(code) }}
            </el-tag>
          </template>
          <span v-else class="text-muted">未分配角色</span>
        </el-form-item>

        <el-form-item label="变更后角色">
          <el-select
            v-model="assignCodes"
            multiple
            placeholder="请至少选择一个角色"
            :loading="roleLoading"
            style="width: 100%"
          >
            <el-option
              v-for="role in roleOptions"
              :key="role.roleCode"
              :label="role.roleName || role.roleCode"
              :value="role.roleCode"
              :disabled="assignTargetIsSelf && role.roleCode === ADMIN_ROLE_CODE"
            />
          </el-select>
          <div v-if="assignTargetIsSelf" class="form-hint text-muted">
            不允许给自己增加或移除 ADMIN 角色
          </div>
        </el-form-item>
      </el-form>

      <!-- 变更对比：提交前展示「当前 → 变更后」的新增/移除两组标签（§6.4） -->
      <div class="assign-summary">
        <div class="assign-summary__row">
          <span class="assign-summary__label">新增角色</span>
          <template v-if="assignAddedCodes.length">
            <el-tag
              v-for="code in assignAddedCodes"
              :key="`add-${code}`"
              size="small"
              type="success"
              class="tag-gap"
            >
              {{ roleLabel(code) }}
            </el-tag>
          </template>
          <span v-else class="text-muted">无</span>
        </div>
        <div class="assign-summary__row">
          <span class="assign-summary__label">移除角色</span>
          <template v-if="assignRemovedCodes.length">
            <el-tag
              v-for="code in assignRemovedCodes"
              :key="`del-${code}`"
              size="small"
              type="danger"
              class="tag-gap"
            >
              {{ roleLabel(code) }}
            </el-tag>
          </template>
          <span v-else class="text-muted">无</span>
        </div>
        <div class="assign-summary__row">
          <span class="assign-summary__label">变更后共</span>
          <span>{{ assignCodes.length }} 个角色</span>
        </div>
      </div>

      <!-- 影响提示：该用户全部令牌被撤销 -->
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="dialog-alert"
        title="保存后该用户的全部登录会话会被立即强制下线，需重新登录后新权限才生效"
      />

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="assignVisible = false">取消</el-button>
          <el-tooltip :disabled="!assignBlockReason" :content="assignBlockReason">
            <span>
              <el-button
                type="primary"
                :disabled="!!assignBlockReason"
                :loading="assignSubmitting"
                @click="handleAssignSubmit"
              >
                保存
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </template>
    </el-dialog>

    <!-- 在线会话抽屉（AUTH-05）。挂在用户配置页而不新建菜单/路由：会话是"某个人"的属性，
         从这里进入最贴合使用场景，也省掉一整套菜单 + 路由守卫 + 权限联动的改造（D6） -->
    <el-drawer
      v-model="sessionDrawerVisible"
      :title="`在线会话 —— ${sessionUser?.realName || sessionUser?.username || ''}`"
      size="720px"
      destroy-on-close
    >
      <div v-loading="sessionLoading">
        <div class="session-toolbar">
          <span class="session-toolbar__hint">
            共 {{ sessionRows.length }} 条。空闲到期时间有请求即顺延；绝对上限自登录起算，不会延长。
          </span>
          <el-button
            v-if="canKickSession && sessionRows.length > 0"
            type="danger"
            plain
            size="small"
            @click="kickAllSessions"
          >
            全部踢出
          </el-button>
        </div>

        <el-table :data="sessionRows" size="small" border>
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.current" type="success" size="small">当前</el-tag>
              <span v-else>--</span>
            </template>
          </el-table-column>
          <el-table-column prop="loginIp" label="登录 IP" width="130">
            <template #default="{ row }">{{ row.loginIp || '--' }}</template>
          </el-table-column>
          <el-table-column label="登录时间" width="160" align="center">
            <template #default="{ row }">{{ formatDateTime(row.loginAt) }}</template>
          </el-table-column>
          <el-table-column label="空闲到期" width="160" align="center">
            <template #default="{ row }">{{ formatDateTime(row.idleExpiresAt) }}</template>
          </el-table-column>
          <el-table-column label="绝对上限" width="160" align="center">
            <template #default="{ row }">{{ formatDateTime(row.absoluteExpiresAt) }}</template>
          </el-table-column>
          <el-table-column label="客户端" min-width="150">
            <template #default="{ row }">
              <el-tooltip
                v-if="row.userAgent"
                :content="row.userAgent"
                placement="top"
                :show-after="300"
              >
                <span class="session-ua">{{ row.userAgent }}</span>
              </el-tooltip>
              <span v-else>--</span>
            </template>
          </el-table-column>
          <el-table-column v-if="canKickSession" label="操作" width="80" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" @click="kickOne(row)">踢出</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="该用户当前没有在线会话" :image-size="80" />
          </template>
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.table-toolbar__title {
  font-weight: 600;
  color: #303133;
}

.tag-gap {
  margin: 2px 4px 2px 0;
}

/*
  操作列按钮间距只由这一处控制。
  不能依赖 Element Plus 的 `.el-button + .el-button { margin-left: 12px }`：
  每个按钮都被 <span> 包住（为了给禁用态挂 tooltip），相邻兄弟选择器不再匹配，
  六个按钮会贴成一坨。用 flex + gap 统一，并抹掉相邻兄弟 margin 以免叠加。
*/
.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px;
}

.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* 弹窗 footer 的间距规则与操作列同源（Roles.vue 的 .dialog-footer） */
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.dialog-footer :deep(.el-button + .el-button) {
  margin-left: 0;
}

.form-hint {
  font-size: 12px;
  line-height: 1.6;
}

/* el-tooltip 对 disabled 的 el-select 同样不生效，需要 <span> 包裹；
   但 <span> 是 inline 元素，内部 100% 宽的下拉框会算错宽度，故显式块化。 */
.block-span {
  display: block;
  width: 100%;
}

.dialog-alert {
  margin-bottom: 16px;
}

.dialog-alert__body {
  line-height: 1.7;
  font-size: 13px;
}

.assign-summary {
  margin-top: 4px;
  padding: 10px 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
}

.assign-summary__row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 22px;
}

.assign-summary__row + .assign-summary__row {
  margin-top: 6px;
}

.assign-summary__label {
  flex: 0 0 68px;
  color: var(--el-text-color-secondary);
}

.session-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.session-toolbar__hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
}

.session-ua {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
</style>
