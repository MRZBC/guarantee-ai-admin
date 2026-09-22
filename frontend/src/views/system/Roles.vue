<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteRole, pageRoles } from '@/api/system'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import { isEnabled, statusLabel } from '@/utils/status'
import type { RoleItem, RoleQuery } from '@/types/system'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref<RoleItem[]>([])
const total = ref(0)

const query = reactive<RoleQuery>({
  pageNum: 1,
  pageSize: 10,
  roleCode: '',
  roleName: ''
})

/** 无 system:role:delete 时不渲染删除入口（后端仍是安全边界，SYS-NF-04） */
const canDelete = computed(() => userStore.permissions.includes('system:role:delete'))

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const result = await pageRoles({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      roleCode: query.roleCode || undefined,
      roleName: query.roleName || undefined,
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

/* ---------------- 逻辑删除 / 恢复（权限：system:role:delete） ---------------- */

async function handleDelete(row: RoleItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除角色「${row.roleName}」？`
        + '① 删除后该角色不再出现在默认列表中；'
        + '② 删除后该记录不再出现在列表中，且页面不提供恢复入口——如只是暂停业务，请改用「停用」；'
        + '③ 若该角色已被其它数据引用，删除会被拒绝并给出引用数量。',
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


onMounted(loadData)
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
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '--' }}</template>
        </el-table-column>
        <el-table-column prop="permissionNames" label="权限" min-width="320">
          <template #default="{ row }">
            <template v-if="row.permissionNames && row.permissionNames.length">
              <el-tag
                v-for="perm in row.permissionNames"
                :key="perm"
                size="small"
                type="success"
                effect="plain"
                class="tag-gap"
              >
                {{ perm }}
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
        <el-table-column prop="createdAt" label="创建时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column v-if="canDelete" label="操作" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 已删除角色不进列表，因此只有「删除」；恢复入口已随「显示已删除」开关撤除（2026-09-22） -->
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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
