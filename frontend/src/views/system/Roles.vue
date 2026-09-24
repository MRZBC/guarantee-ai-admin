<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  assignRolePermissions,
  changeRoleStatus,
  createRole,
  deleteRole,
  listPermissions,
  pageRoles,
  updateRole
} from '@/api/system'
import PermissionTree from '@/components/PermissionTree.vue'
import { useUserStore } from '@/stores/user'
import { confirmText } from '@/utils/confirmText'
import { formatDateTime } from '@/utils/format'
import { isEnabled, statusLabel } from '@/utils/status'
import type {
  PermissionItem,
  RoleCreateParams,
  RoleItem,
  RoleQuery,
  RoleUpdateParams
} from '@/types/system'

const userStore = useUserStore()

/** 保留角色码：全权限、不可改、不可删、不可授权变更、不可停用（需求文档 §0 硬不变式）。 */
const ADMIN_ROLE_CODE = 'ADMIN'

/** 权限列折叠展示的预览条数，超出则显示「共 N 项」。 */
const PERMISSION_PREVIEW_COUNT = 3

const loading = ref(false)
const rows = ref<RoleItem[]>([])
const total = ref(0)

const query = reactive<RoleQuery>({
  pageNum: 1,
  pageSize: 10,
  roleCode: '',
  roleName: ''
})

/*
  逐按钮权限过滤（SYS-NF-04）：前端过滤仅为体验优化——避免"点了才 403"的死按钮，
  后端仍是唯一安全边界。系统角色写权限默认仅 ADMIN 持有。
*/
const canCreate = computed(() => userStore.permissions.includes('system:role:create'))
const canUpdate = computed(() => userStore.permissions.includes('system:role:update'))
const canAssign = computed(() => userStore.permissions.includes('system:role:assign-permission'))
/** 无 system:role:disable 时不渲染启停入口 */
const canDisable = computed(() => userStore.permissions.includes('system:role:disable'))
/** 无 system:role:delete 时不渲染删除入口 */
const canDelete = computed(() => userStore.permissions.includes('system:role:delete'))

/** 操作列是否渲染：四个动作任一可用即可。 */
const showActions = computed(
  () => canUpdate.value || canAssign.value || canDisable.value || canDelete.value
)

/* ---------------- 因业务规则而禁用的按钮（禁用 + tooltip，两者必须成对） ---------------- */

type RowAction = 'update' | 'assign' | 'disable' | 'delete'

const ADMIN_BLOCK_REASONS: Record<RowAction, string> = {
  update: '超级管理员（ADMIN）角色不允许修改',
  assign: '超级管理员（ADMIN）角色不允许变更权限',
  disable: '超级管理员（ADMIN）角色不允许停用',
  delete: '超级管理员（ADMIN）角色不允许删除'
}

/**
 * 某行的某动作因**业务规则**不可用时返回原因；可用时返回空串。
 *
 * 只有禁用而无说明，用户就分不清"我没权限""这是保留角色""系统坏了"三种可能，
 * 因此本函数是"禁用 + tooltip"约定的唯一文案来源（避免四个按钮各写一段导致漂移）。
 */
function disabledReason(row: RoleItem, action: RowAction): string {
  return row.roleCode === ADMIN_ROLE_CODE ? ADMIN_BLOCK_REASONS[action] : ''
}

/* ---------------- 权限列折叠展示 ---------------- */

function previewPermissions(row: RoleItem): string[] {
  return (row.permissionNames ?? []).slice(0, PERMISSION_PREVIEW_COUNT)
}

function permissionCountOf(row: RoleItem): number {
  return row.permissionCount ?? (row.permissionNames ?? []).length
}

/* ---------------- 列表 ---------------- */

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const result = await pageRoles({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      roleCode: query.roleCode || undefined,
      roleName: query.roleName || undefined
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
  query.roleCode = ''
  query.roleName = ''
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

/* ---------------- 新增 / 修改（权限：system:role:create / :update） ---------------- */

const form = reactive<{
  id: number | null
  roleCode: string
  roleName: string
  description: string
}>({
  id: null,
  roleCode: '',
  roleName: '',
  description: ''
})

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const isEdit = computed(() => form.id !== null)
const dialogTitle = computed(() => (isEdit.value ? '修改角色' : '新增角色'))

const rules = computed<FormRules>(() => ({
  roleCode: [
    { required: true, message: '请输入角色编码', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_-]{2,32}$/,
      message: '角色编码由 2-32 位字母、数字、下划线或中划线组成',
      trigger: 'blur'
    },
    {
      // 保留码前端先拦、后端兜底（后端仍会返回「不允许使用保留角色编码 ADMIN」）
      validator: (_rule, value, callback) => {
        if (String(value ?? '').trim().toUpperCase() === ADMIN_ROLE_CODE) {
          callback(new Error('不允许使用保留角色编码 ADMIN'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  roleName: [
    { required: true, message: '请输入角色名称', trigger: 'blur' },
    { max: 64, message: '角色名称长度不能超过 64', trigger: 'blur' }
  ],
  description: [{ max: 255, message: '描述长度不能超过 255', trigger: 'blur' }]
}))

function resetForm(): void {
  form.id = null
  form.roleCode = ''
  form.roleName = ''
  form.description = ''
  formRef.value?.clearValidate()
}

function openCreate(): void {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: RoleItem): void {
  resetForm()
  form.id = row.id
  form.roleCode = row.roleCode
  form.roleName = row.roleName
  form.description = row.description ?? ''
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (isEdit.value && form.id !== null) {
      // 刻意不含 roleCode（不可改）与 status：状态只能走 PATCH /{id}/status，
      // 只有那条路径带「停用前置检查 + 撤销持有者令牌」，走 PUT 会静默绕过保护。
      const payload: RoleUpdateParams = {
        roleName: form.roleName.trim(),
        description: form.description
      }
      await updateRole(form.id, payload)
      ElMessage.success('角色修改成功')
    } else {
      const payload: RoleCreateParams = {
        roleCode: form.roleCode.trim(),
        roleName: form.roleName.trim(),
        description: form.description
      }
      await createRole(payload)
      ElMessage.success('角色新增成功')
    }
    dialogVisible.value = false
    await loadData()
  } catch {
    // 失败原因（保留码 / 角色编码已存在）已由响应拦截器统一提示；
    // 刻意不关弹窗，便于就地修改后重试。
  } finally {
    submitting.value = false
  }
}

/* ---------------- 授权（权限：system:role:assign-permission） ---------------- */

const assignVisible = ref(false)
const assignSubmitting = ref(false)
const assignTarget = ref<RoleItem | null>(null)
/** 对话框内正在编辑的完整权限码集合（v-model 绑定权限树） */
const assignCodes = ref<string[]>([])

const permissions = ref<PermissionItem[]>([])
const permLoading = ref(false)
/**
 * 权限清单加载失败。最可能的原因是当前账号缺少 `system:permission:view`——
 * 它与授权权限 `system:role:assign-permission` 之间**没有蕴含关系**（需求文档 D1）。
 */
const permError = ref(false)

const originalCodes = computed<string[]>(() => assignTarget.value?.permissionCodes ?? [])

const addedCodes = computed(() =>
  assignCodes.value.filter((code) => !originalCodes.value.includes(code))
)
const removedCodes = computed(() =>
  originalCodes.value.filter((code) => !assignCodes.value.includes(code))
)
const assignDirty = computed(() => addedCodes.value.length > 0 || removedCodes.value.length > 0)

/** 非空即表示保存不可用，同时作为禁用按钮的 tooltip 文案。 */
const assignBlockReason = computed(() => {
  if (permLoading.value) return '权限清单加载中…'
  if (permError.value) return '权限清单加载失败，无法授权'
  if (assignCodes.value.length === 0) return '请至少勾选一项权限'
  if (!assignDirty.value) return '权限未发生变化'
  return ''
})

const permNameByCode = computed(() => {
  const map = new Map<string, string>()
  for (const permission of permissions.value) {
    map.set(permission.permCode, permission.permName || permission.permCode)
  }
  return map
})

function permLabel(code: string): string {
  return permNameByCode.value.get(code) ?? code
}

async function loadPermissions(): Promise<void> {
  permLoading.value = true
  permError.value = false
  try {
    const list = await listPermissions()
    permissions.value = list ?? []
  } catch {
    // 拦截器只会说"没有权限访问该资源"，这里补一个可行动的说明（对话框内以 el-alert 呈现）
    permError.value = true
    permissions.value = []
  } finally {
    permLoading.value = false
  }
}

async function openAssign(row: RoleItem): Promise<void> {
  assignTarget.value = row
  assignCodes.value = [...(row.permissionCodes ?? [])]
  assignVisible.value = true
  if (permissions.value.length === 0 && !permLoading.value) {
    await loadPermissions()
  }
}

async function handleAssignSubmit(): Promise<void> {
  const target = assignTarget.value
  if (!target || assignBlockReason.value) return

  try {
    await ElMessageBox.confirm(
      confirmText('确认提交权限变更？', [
        `保存后，持有「${target.roleName}」的 ${target.userCount ?? 0} 个用户会被立即强制下线，需要重新登录后新权限才生效`
      ]),
      '角色授权',
      { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  assignSubmitting.value = true
  try {
    // 全量替换语义：提交的是变更后的完整集合
    await assignRolePermissions(target.roleCode, assignCodes.value)
    ElMessage.success(`已更新「${target.roleName}」的权限`)
    assignVisible.value = false
    await loadData()
  } catch {
    // 拦截器已提示
  } finally {
    assignSubmitting.value = false
  }
}

/* ---------------- 启停（权限：system:role:disable） ---------------- */

/**
 * 启停角色。
 *
 * <p>鉴权链路按 {@code r.status = 1} 过滤角色，因此停用是**立即生效**的权限收回：
 * 持有该角色的用户立刻失去它带来的权限，且后端会撤销这些用户的令牌，他们需要重新登录。
 * ADMIN 角色不被允许停用。人数前端拿不到准确值，文案里不写数字。</p>
 */
async function toggleStatus(row: RoleItem): Promise<void> {
  const next = isEnabled(row.status) ? 0 : 1
  const word = next === 0 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      next === 0
        ? confirmText(`确认停用「${row.roleName}」？`, [
            '仍有启用中的用户持有该角色时会被拒绝，请先解除绑定',
            '超级管理员（ADMIN）角色不允许停用，该操作会被系统拒绝',
            '停用不是删除，角色记录仍然保留，可随时重新启用'
          ])
        : `确认启用「${row.roleName}」？`,
      `${word}角色`,
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await changeRoleStatus(row.id, next)
    ElMessage.success(`已${word}「${row.roleName}」`)
    await loadData()
  } catch {
    // 失败原因（如「超级管理员（ADMIN）角色不允许停用」）已由响应拦截器统一提示
  }
}

/* ---------------- 逻辑删除（权限：system:role:delete） ---------------- */

async function handleDelete(row: RoleItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      confirmText(`确认删除角色「${row.roleName}」？`, [
        '删除后该角色不再出现在默认列表中，且页面不提供恢复入口——如只是暂停业务，请改用「停用」',
        '仍有未删除的用户持有该角色时，删除会被拒绝并给出数量',
        '删除后该角色带来的权限不再签发，其持有者会被强制下线'
      ]),
      '删除角色',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteRole(row.id)
    ElMessage.success(`已删除角色「${row.roleName}」`)
    await loadData()
  } catch {
    // 失败原因（如「该角色不能删除：已分配给 5 个用户」）已由响应拦截器统一提示
  }
}

onMounted(() => {
  void loadData()
  void loadPermissions()
})
</script>

<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <el-form :model="query" label-width="82px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="角色编码">
              <el-input
                v-model="query.roleCode"
                placeholder="请输入角色编码"
                clearable
                @keyup.enter="handleSearch"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="角色名称">
              <el-input v-model="query.roleName" placeholder="请输入角色名称" clearable />
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
        <span class="table-toolbar__title">角色列表 · 共 {{ total }} 条</span>
        <el-button v-if="canCreate" type="primary" icon="Plus" @click="openCreate">
          新增角色
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
        <el-table-column prop="roleCode" label="角色编码" width="160" fixed show-overflow-tooltip />
        <el-table-column prop="roleName" label="角色名称" width="160" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '--' }}</template>
        </el-table-column>
        <el-table-column prop="permissionNames" label="权限" min-width="300">
          <template #default="{ row }">
            <template v-if="row.permissionNames && row.permissionNames.length">
              <el-tag
                v-for="perm in previewPermissions(row)"
                :key="perm"
                size="small"
                type="success"
                effect="plain"
                class="tag-gap"
              >
                {{ perm }}
              </el-tag>
              <!-- 超长权限集折叠：全量标签会把表格撑破（最长可达 40 项） -->
              <span v-if="permissionCountOf(row) > PERMISSION_PREVIEW_COUNT" class="text-muted">
                共 {{ permissionCountOf(row) }} 项
              </span>
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
        <el-table-column prop="createdAt" label="创建时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column
          v-if="showActions"
          label="操作"
          width="200"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <!-- 无权限不渲染按钮；因业务规则不可用的按钮「禁用 + tooltip」成对出现。
                 el-tooltip 对 disabled 的 el-button 不生效（禁用元素不触发鼠标事件），
                 因此必须用 <span> 包裹——Element Plus 的已知行为。 -->
            <el-tooltip
              v-if="canUpdate"
              :disabled="!disabledReason(row, 'update')"
              :content="disabledReason(row, 'update')"
            >
              <span>
                <el-button
                  link
                  type="primary"
                  :disabled="!!disabledReason(row, 'update')"
                  @click="openEdit(row)"
                >
                  修改
                </el-button>
              </span>
            </el-tooltip>

            <el-tooltip
              v-if="canAssign"
              :disabled="!disabledReason(row, 'assign')"
              :content="disabledReason(row, 'assign')"
            >
              <span>
                <el-button
                  link
                  type="primary"
                  :disabled="!!disabledReason(row, 'assign')"
                  @click="openAssign(row)"
                >
                  授权
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
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无角色数据" :image-size="80" />
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

    <!-- 新增 / 修改角色 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="角色编码" prop="roleCode">
          <el-input
            v-model="form.roleCode"
            placeholder="如 REGION_OPS"
            :disabled="isEdit"
            clearable
          />
          <div v-if="isEdit" class="form-hint text-muted">角色编码创建后不可修改</div>
        </el-form-item>
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" placeholder="如 区域运营专员" clearable />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="选填，角色职责说明"
          />
        </el-form-item>
        <!-- 刻意不提供「状态」字段：状态改动走列表的停用/启用按钮。
             PUT /{id} 不带停用前置检查与令牌撤销，从这里改状态会绕过那两道保护。 -->
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 角色授权 -->
    <el-dialog
      v-model="assignVisible"
      :title="`授权角色「${assignTarget?.roleName ?? ''}」`"
      width="720px"
      destroy-on-close
    >
      <el-alert
        v-if="permError"
        type="error"
        :closable="false"
        show-icon
        title="无法加载权限清单"
        class="assign-alert"
      >
        <div>
          当前账号缺少「权限配置」查看权限（<code>system:permission:view</code>），
          因此无法列出可授予的权限码。请联系管理员为该账号补上该权限后重试。
        </div>
      </el-alert>

      <div v-else v-loading="permLoading" class="assign-body">
        <!-- key 绑定目标角色：切换角色时强制重建，使 default-checked-keys 重新生效 -->
        <PermissionTree
          v-if="permissions.length > 0"
          :key="assignTarget?.roleCode ?? ''"
          v-model="assignCodes"
          :permissions="permissions"
        />
        <el-empty v-else-if="!permLoading" description="没有可授予的权限" :image-size="70" />
      </div>

      <div v-if="!permError" class="assign-summary">
        <div class="assign-summary__row">
          <span class="assign-summary__label">新增权限</span>
          <template v-if="addedCodes.length">
            <el-tag
              v-for="code in addedCodes"
              :key="`add-${code}`"
              size="small"
              type="success"
              class="tag-gap"
            >
              {{ permLabel(code) }}
            </el-tag>
          </template>
          <span v-else class="text-muted">无</span>
        </div>
        <div class="assign-summary__row">
          <span class="assign-summary__label">移除权限</span>
          <template v-if="removedCodes.length">
            <el-tag
              v-for="code in removedCodes"
              :key="`del-${code}`"
              size="small"
              type="danger"
              class="tag-gap"
            >
              {{ permLabel(code) }}
            </el-tag>
          </template>
          <span v-else class="text-muted">无</span>
        </div>
        <div class="assign-summary__row">
          <span class="assign-summary__label">变更后共</span>
          <span>{{ assignCodes.length }} 项</span>
        </div>
      </div>

      <el-alert
        v-if="!permError"
        type="warning"
        :closable="false"
        show-icon
        class="assign-alert"
        :title="`保存后，持有「${assignTarget?.roleName ?? ''}」的 ${assignTarget?.userCount ?? 0} 个用户会被立即强制下线，需重新登录`"
      />

      <template #footer>
        <!-- 必须用 flex + gap 控制间距，不能依赖 Element Plus 的
             `.el-button + .el-button { margin-left: 12px }`：保存按钮被 <span> 包住
             （为了给禁用态挂 tooltip），相邻兄弟选择器不再匹配，两个按钮会贴在一起。 -->
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
                保存授权
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-toolbar__title {
  font-weight: 600;
  color: #303133;
}

/*
  弹窗按钮间距只由这一处控制。
  不能依赖 Element Plus 的 `.el-button + .el-button { margin-left: 12px }`：
  授权弹窗的保存按钮被 <span> 包住（给禁用态挂 tooltip 用），相邻兄弟选择器不匹配，
  两个按钮会贴在一起。用 flex gap 统一，并抹掉相邻兄弟 margin 以免叠加成 24px。
*/
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.dialog-footer :deep(.el-button + .el-button) {
  margin-left: 0;
}

.tag-gap {
  margin: 2px 4px 2px 0;
}

.form-hint {
  font-size: 12px;
  line-height: 1.6;
}

.assign-body {
  min-height: 120px;
}

.assign-alert {
  margin-top: 12px;
}

.assign-summary {
  margin-top: 12px;
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
</style>
