<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getProject, pageProjects } from '@/api/project'
import RegionSelect from '@/components/RegionSelect.vue'
import { formatAmount, formatDate } from '@/utils/format'
import type { ProjectDetail, ProjectItem, ProjectQuery } from '@/types/project'

const loading = ref(false)
const rows = ref<ProjectItem[]>([])
const total = ref(0)

const query = reactive<ProjectQuery>({
  pageNum: 1,
  pageSize: 10,
  projectName: '',
  regionCode: '',
  projectType: '',
  status: '',
  enterpriseId: null
})

const projectTypeMap: Record<string, string> = {
  TENDER: '招标项目',
  CONSTRUCTION: '建设工程',
  GOVERNMENT: '政府采购',
  SERVICE: '服务类',
  GOODS: '货物类',
  OTHER: '其他'
}

const statusMap: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待审核',
  IN_PROGRESS: '进行中',
  ONGOING: '进行中',
  COMPLETED: '已完成',
  FINISHED: '已完成',
  CANCELLED: '已取消',
  CANCELED: '已取消',
  CLOSED: '已关闭'
}

function labelOf(map: Record<string, string>, value: string | null | undefined): string {
  if (!value) return '--'
  return map[value.toUpperCase()] ?? value
}

function statusTagType(status: string | null | undefined): 'success' | 'warning' | 'danger' | 'info' {
  const code = (status ?? '').toUpperCase()
  if (['COMPLETED', 'FINISHED'].includes(code)) return 'success'
  if (['IN_PROGRESS', 'ONGOING', 'PENDING', 'DRAFT'].includes(code)) return 'warning'
  if (['CANCELLED', 'CANCELED', 'CLOSED'].includes(code)) return 'danger'
  return 'info'
}

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const result = await pageProjects({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      projectName: query.projectName || undefined,
      regionCode: query.regionCode || undefined,
      projectType: query.projectType || undefined,
      status: query.status || undefined,
      enterpriseId: query.enterpriseId ?? undefined
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
  query.projectName = ''
  query.regionCode = ''
  query.projectType = ''
  query.status = ''
  query.enterpriseId = null
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

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ProjectDetail | ProjectItem | null>(null)

async function openDetail(row: ProjectItem): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = row
  try {
    detail.value = await getProject(row.id)
  } catch {
    // 保留列表数据
  } finally {
    detailLoading.value = false
  }
}

function asDetail(value: ProjectDetail | ProjectItem | null): ProjectDetail | null {
  return value && 'totalGuaranteeAmount' in value ? (value as ProjectDetail) : null
}

/** 详情接口返回后才有统计字段 */
const detailStats = computed(() => asDetail(detail.value))

onMounted(loadData)
</script>

<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <el-form :model="query" label-width="82px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="项目名称">
              <el-input
                v-model="query.projectName"
                placeholder="请输入项目名称"
                clearable
                @keyup.enter="handleSearch"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="地区">
              <!-- 地区下拉（行政区划字典）：替代原先手填"区域编码" -->
              <RegionSelect v-model="query.regionCode" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="项目类型">
              <el-select v-model="query.projectType" placeholder="全部类型" clearable>
                <el-option
                  v-for="(label, value) in projectTypeMap"
                  :key="value"
                  :label="label"
                  :value="value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="状态">
              <el-select v-model="query.status" placeholder="全部状态" clearable>
                <el-option
                  v-for="(label, value) in statusMap"
                  :key="value"
                  :label="label"
                  :value="value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="企业ID">
              <el-input-number
                v-model="query.enterpriseId"
                :min="1"
                :controls="false"
                placeholder="企业ID"
                style="width: 100%"
              />
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
        <span class="table-toolbar__title">项目列表 · 共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe height="520" row-key="id">
        <el-table-column type="index" label="#" width="52" align="center" fixed />
        <el-table-column prop="projectCode" label="项目编号" width="160" fixed show-overflow-tooltip />
        <el-table-column prop="projectName" label="项目名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="enterpriseName" label="所属企业" min-width="180" show-overflow-tooltip />
        <el-table-column prop="regionName" label="区域" width="130" show-overflow-tooltip />
        <el-table-column prop="projectAmount" label="项目金额(元)" width="150" align="right">
          <template #default="{ row }">{{ formatAmount(row.projectAmount) }}</template>
        </el-table-column>
        <el-table-column prop="projectType" label="项目类型" width="120" align="center">
          <template #default="{ row }">{{ labelOf(projectTypeMap, row.projectType) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ labelOf(statusMap, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="tenderDate" label="招标日期" width="120" align="center">
          <template #default="{ row }">{{ formatDate(row.tenderDate) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无项目数据" :image-size="80" />
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

    <el-dialog v-model="detailVisible" title="项目详情" width="820px">
      <div v-loading="detailLoading">
        <el-descriptions v-if="detail" :column="2" border>
          <el-descriptions-item label="项目编号">{{ detail.projectCode || '--' }}</el-descriptions-item>
          <el-descriptions-item label="项目名称">{{ detail.projectName || '--' }}</el-descriptions-item>
          <el-descriptions-item label="所属企业">
            {{ detail.enterpriseName || '--' }}
          </el-descriptions-item>
          <el-descriptions-item label="区域">
            {{ detail.regionName || '--' }}
            <span v-if="detail.regionCode" class="text-muted">({{ detail.regionCode }})</span>
          </el-descriptions-item>
          <el-descriptions-item label="项目金额">
            {{ formatAmount(detail.projectAmount) }} 元
          </el-descriptions-item>
          <el-descriptions-item label="项目类型">
            {{ labelOf(projectTypeMap, detail.projectType) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.status)" size="small">
              {{ labelOf(statusMap, detail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="招标日期">
            {{ formatDate(detail.tenderDate) }}
          </el-descriptions-item>
          <template v-if="detailStats">
            <el-descriptions-item label="投标订单数">
              {{ detailStats.tenderOrderCount }} 笔
            </el-descriptions-item>
            <el-descriptions-item label="履约订单数">
              {{ detailStats.performanceOrderCount }} 笔
            </el-descriptions-item>
            <el-descriptions-item label="担保总金额">
              {{ formatAmount(detailStats.totalGuaranteeAmount) }} 元
            </el-descriptions-item>
            <el-descriptions-item label="保费总额">
              {{ formatAmount(detailStats.totalPremiumAmount) }} 元
            </el-descriptions-item>
          </template>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-toolbar__title {
  font-weight: 600;
  color: #303133;
}
</style>
