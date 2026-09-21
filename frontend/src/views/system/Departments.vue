<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listOrgOptions, pageDepartments } from '@/api/system'
import { formatDateTime } from '@/utils/format'
import { isEnabled, statusLabel, statusParam, STATUS_OPTIONS } from '@/utils/status'
import type { DepartmentItem, DepartmentQuery, OrgOption } from '@/types/system'

const loading = ref(false)
const rows = ref<DepartmentItem[]>([])
const total = ref(0)
const orgOptions = ref<OrgOption[]>([])

const query = reactive<DepartmentQuery>({
  pageNum: 1,
  pageSize: 10,
  orgId: null,
  deptName: '',
  status: null
})

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const result = await pageDepartments({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      orgId: query.orgId ?? undefined,
      deptName: query.deptName || undefined,
      status: statusParam(query.status)
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
  query.orgId = null
  query.deptName = ''
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
            <el-form-item label="部门名称">
              <el-input
                v-model="query.deptName"
                placeholder="请输入部门名称"
                clearable
                @keyup.enter="handleSearch"
              />
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
        <span class="table-toolbar__title">部门列表 · 共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe height="520" row-key="id">
        <el-table-column type="index" label="#" width="52" align="center" fixed />
        <el-table-column prop="deptCode" label="部门编码" width="150" fixed show-overflow-tooltip />
        <el-table-column prop="deptName" label="部门名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="orgName" label="所属机构" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orgName || '--' }}</template>
        </el-table-column>
        <el-table-column prop="orgId" label="机构ID" width="100" align="center">
          <template #default="{ row }">{{ row.orgId ?? '--' }}</template>
        </el-table-column>
        <el-table-column prop="parentId" label="上级部门ID" width="120" align="center">
          <template #default="{ row }">{{ row.parentId ?? '--' }}</template>
        </el-table-column>
        <el-table-column prop="sortNo" label="排序号" width="90" align="center">
          <template #default="{ row }">{{ row.sortNo ?? '--' }}</template>
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
        <template #empty>
          <el-empty description="暂无部门数据" :image-size="80" />
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
</style>
