<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteUser, listOrgOptions, pageUsers } from '@/api/system'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import { isEnabled, statusLabel, statusParam, STATUS_OPTIONS } from '@/utils/status'
import type { OrgOption, UserItem, UserQuery } from '@/types/system'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref<UserItem[]>([])
const total = ref(0)
const orgOptions = ref<OrgOption[]>([])

const query = reactive<UserQuery>({
  pageNum: 1,
  pageSize: 10,
  username: '',
  realName: '',
  orgId: null,
  status: null
})

/** 无 system:user:delete 时不渲染删除入口（后端仍是安全边界，SYS-NF-04） */
const canDelete = computed(() => userStore.permissions.includes('system:user:delete'))

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
      orgId: query.orgId ?? undefined,
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

async function loadOrgOptions(): Promise<void> {
  try {
    orgOptions.value = (await listOrgOptions()) ?? []
  } catch {
    orgOptions.value = []
  }
}

function handleSearch(): void {
  query.pageNum = 1
  void loadData()
}

function handleReset(): void {
  query.username = ''
  query.realName = ''
  query.orgId = null
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


onMounted(() => {
  void loadOrgOptions()
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
            <el-form-item label="所属机构">
              <el-select v-model="query.orgId" placeholder="全部机构" clearable filterable>
                <el-option
                  v-for="org in orgOptions"
                  :key="org.id"
                  :label="org.orgName"
                  :value="org.id"
                />
              </el-select>
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
        <el-table-column prop="orgName" label="所属机构" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orgName || '--' }}</template>
        </el-table-column>
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
        <el-table-column v-if="canDelete" label="操作" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 已删除用户不进列表，因此只有「删除」；恢复入口已随「显示已删除」开关撤除（2026-09-22） -->
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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



</style>
