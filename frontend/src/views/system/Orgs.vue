<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { pageOrgs } from '@/api/system'
import { formatDateTime } from '@/utils/format'
import type { OrgItem, OrgQuery } from '@/types/system'

const loading = ref(false)
const rows = ref<OrgItem[]>([])
const total = ref(0)

const query = reactive<OrgQuery>({
  pageNum: 1,
  pageSize: 10,
  orgName: '',
  regionCode: '',
  status: ''
})

const orgLevelMap: Record<string, string> = {
  HEAD: '总部',
  HQ: '总部',
  PROVINCE: '省级',
  CITY: '市级',
  COUNTY: '区县级',
  DISTRICT: '区县级',
  BRANCH: '分支机构'
}

const statusMap: Record<string, string> = {
  ACTIVE: '启用',
  ENABLED: '启用',
  NORMAL: '启用',
  DISABLED: '停用',
  INACTIVE: '停用'
}

function labelOf(map: Record<string, string>, value: string | null | undefined): string {
  if (!value) return '--'
  return map[value.toUpperCase()] ?? value
}

function isEnabled(status: string | null | undefined): boolean {
  const code = (status ?? '').toUpperCase()
  return ['ACTIVE', 'ENABLED', 'NORMAL'].includes(code)
}

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const result = await pageOrgs({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      orgName: query.orgName || undefined,
      regionCode: query.regionCode || undefined,
      status: query.status || undefined
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
  query.orgName = ''
  query.regionCode = ''
  query.status = ''
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

onMounted(loadData)
</script>

<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <el-form :model="query" label-width="82px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="机构名称">
              <el-input
                v-model="query.orgName"
                placeholder="请输入机构名称"
                clearable
                @keyup.enter="handleSearch"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="区域编码">
              <el-input v-model="query.regionCode" placeholder="如 330100" clearable />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="状态">
              <el-select v-model="query.status" placeholder="全部状态" clearable>
                <el-option label="启用" value="ACTIVE" />
                <el-option label="停用" value="DISABLED" />
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
        <span class="table-toolbar__title">机构列表 · 共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe height="520" row-key="id">
        <el-table-column type="index" label="#" width="52" align="center" fixed />
        <el-table-column prop="orgCode" label="机构编码" width="150" fixed show-overflow-tooltip />
        <el-table-column prop="orgName" label="机构名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="regionName" label="区域" width="140" show-overflow-tooltip />
        <el-table-column prop="regionCode" label="区域编码" width="120" align="center">
          <template #default="{ row }">{{ row.regionCode || '--' }}</template>
        </el-table-column>
        <el-table-column prop="orgLevel" label="机构层级" width="110" align="center">
          <template #default="{ row }">{{ labelOf(orgLevelMap, row.orgLevel) }}</template>
        </el-table-column>
        <el-table-column prop="parentId" label="上级机构ID" width="120" align="center">
          <template #default="{ row }">{{ row.parentId ?? '--' }}</template>
        </el-table-column>
        <el-table-column prop="sortNo" label="排序号" width="90" align="center">
          <template #default="{ row }">{{ row.sortNo ?? '--' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="isEnabled(row.status) ? 'success' : 'info'" size="small">
              {{ labelOf(statusMap, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无机构数据" :image-size="80" />
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
