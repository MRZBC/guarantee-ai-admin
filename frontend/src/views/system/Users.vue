<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeUserStatus,
  deleteUser,
  kickSession,
  kickUserSessions,
  pageSessions,
  pageUsers
} from '@/api/system'
import { useUserStore } from '@/stores/user'
import { removeToken } from '@/utils/storage'
import { formatDateTime } from '@/utils/format'
import { isEnabled, statusLabel, statusParam, STATUS_OPTIONS } from '@/utils/status'
import type { SessionItem, UserItem, UserQuery } from '@/types/system'

const userStore = useUserStore()

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

/** 无 system:user:disable 时不渲染启停入口（后端仍是安全边界，SYS-NF-04） */
const canDisable = computed(() => userStore.permissions.includes('system:user:disable'))
/** 无 system:user:delete 时不渲染删除入口（后端仍是安全边界，SYS-NF-04） */
const canDelete = computed(() => userStore.permissions.includes('system:user:delete'))
/** 无 system:session:view 时不渲染「在线会话」入口（后端仍是安全边界，SYS-NF-04） */
const canViewSessions = computed(() => userStore.permissions.includes('system:session:view'))
/** 无 system:session:kick 时不渲染踢出按钮（后端仍是安全边界，SYS-NF-04） */
const canKickSession = computed(() => userStore.permissions.includes('system:session:kick'))

/** 删除提示中的用户称谓：优先姓名，退回用户名 */
function userLabel(row: UserItem): string {
  return row.realName || row.username
}

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
        ? `确认停用「${label}」？`
          + '① 该用户会被立即强制下线，需要重新登录；'
          + '② 不能停用自己，也不能停用最后一个启用状态的超级管理员，这两种情况会被系统拒绝；'
          + '③ 停用不是删除，用户记录仍然保留，可随时重新启用。'
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

/* ---------------- 逻辑删除 / 恢复（权限：system:user:delete） ---------------- */

async function handleDelete(row: UserItem): Promise<void> {
  const label = userLabel(row)
  try {
    await ElMessageBox.confirm(
      `确认删除用户「${label}」？`
        + '① 删除后该用户不再出现在默认列表中；'
        + '② 删除后该记录不再出现在列表中，且页面不提供恢复入口——如只是暂停业务，请改用「停用」；'
        + '③ 若该用户已被其它数据引用，删除会被拒绝并给出引用数量。',
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
      `确认踢出${label}？`
        + '① 对方会被立即登出，需要重新登录；'
        + '② 踢出对**下一个请求**生效，不会中断对方正在进行的流式对话；'
        + '③ 这只是踢出这一条会话——若对方还有其它浏览器/设备的会话，请用「全部踢出」。',
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
      `确认踢出「${label}」的全部会话？`
        + '① 该用户在所有浏览器/设备上的登录都会失效，需要重新登录；'
        + '② 若其中包含你自己当前的会话，你也会被登出；'
        + '③ 这与「停用用户」不同：账号本身不受影响，随时可以重新登录。',
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
        <el-table-column prop="realName" label="姓名" width="120" show-overflow-tooltip />
        <el-table-column prop="deptName" label="所属部门" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.deptName || '--' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="140">
          <template #default="{ row }">{{ row.phone || '--' }}</template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.email || '--' }}</template>
        </el-table-column>
        <el-table-column prop="roleNames" label="角色" min-width="180">
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
        <el-table-column
          v-if="canDisable || canDelete || canViewSessions"
          label="操作"
          width="200"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <!-- 无权限不渲染按钮（前端过滤仅为体验优化，后端仍是安全边界，SYS-NF-04）。
                 已删除用户不进列表，因此只有启停与删除；恢复入口已随「显示已删除」开关撤除（2026-09-22） -->
            <el-button v-if="canDisable" link type="primary" @click="toggleStatus(row)">
              {{ isEnabled(row.status) ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="canViewSessions" link type="primary" @click="openSessions(row)">
              在线会话
            </el-button>
            <el-button v-if="canDelete" link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
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
