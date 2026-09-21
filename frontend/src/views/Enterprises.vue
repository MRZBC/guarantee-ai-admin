<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getEnterprise, pageEnterprises } from '@/api/enterprise'
import { formatAmount } from '@/utils/format'
import { dictLabel, isEnabled, statusLabel, statusParam, STATUS_OPTIONS } from '@/utils/status'
import type { EnterpriseDetail, EnterpriseItem, EnterpriseQuery } from '@/types/enterprise'

const loading = ref(false)
const rows = ref<EnterpriseItem[]>([])
const total = ref(0)

const query = reactive<EnterpriseQuery>({
  pageNum: 1,
  pageSize: 10,
  entName: '',
  regionCode: '',
  industry: '',
  entLevel: '',
  status: null
})

const industryMap: Record<string, string> = {
  CONSTRUCTION: '建筑业',
  MANUFACTURING: '制造业',
  IT: '信息技术',
  TRADE: '批发零售',
  TRANSPORT: '交通运输',
  AGRICULTURE: '农林牧渔',
  SERVICE: '服务业',
  FINANCE: '金融业',
  OTHER: '其他'
}

const entLevelMap: Record<string, string> = {
  AAA: 'AAA',
  AA: 'AA',
  A: 'A',
  BBB: 'BBB',
  BB: 'BB',
  B: 'B',
  C: 'C'
}

function statusTagType(status: unknown): 'success' | 'warning' | 'danger' | 'info' {
  if (status === null || status === undefined || status === '') return 'info'
  // 企业状态是 TINYINT：1 正常 / 0 停用
  return isEnabled(status) ? 'success' : 'danger'
}

function levelTagType(level: string | null | undefined): 'success' | 'warning' | 'info' {
  const code = String(level ?? '').toUpperCase()
  if (code.startsWith('AAA')) return 'success'
  if (code.startsWith('AA')) return 'warning'
  return 'info'
}

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const result = await pageEnterprises({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      entName: query.entName || undefined,
      regionCode: query.regionCode || undefined,
      industry: query.industry || undefined,
      entLevel: query.entLevel || undefined,
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

function handleSearch(): void {
  query.pageNum = 1
  void loadData()
}

function handleReset(): void {
  query.entName = ''
  query.regionCode = ''
  query.industry = ''
  query.entLevel = ''
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

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<EnterpriseDetail | EnterpriseItem | null>(null)

const detailStats = computed<EnterpriseDetail | null>(() =>
  detail.value && 'totalPremiumAmount' in detail.value ? (detail.value as EnterpriseDetail) : null
)

async function openDetail(row: EnterpriseItem): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = row
  try {
    detail.value = await getEnterprise(row.id)
  } catch {
    // 保留列表数据
  } finally {
    detailLoading.value = false
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
            <el-form-item label="企业名称">
              <el-input
                v-model="query.entName"
                placeholder="请输入企业名称"
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
            <el-form-item label="所属行业">
              <el-select v-model="query.industry" placeholder="全部行业" clearable>
                <el-option
                  v-for="(label, value) in industryMap"
                  :key="value"
                  :label="label"
                  :value="value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="企业等级">
              <el-select v-model="query.entLevel" placeholder="全部等级" clearable>
                <el-option
                  v-for="(label, value) in entLevelMap"
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
        <span class="table-toolbar__title">企业列表 · 共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe height="520" row-key="id">
        <el-table-column type="index" label="#" width="52" align="center" fixed />
        <el-table-column prop="entCode" label="企业编码" width="150" fixed show-overflow-tooltip />
        <el-table-column prop="entName" label="企业名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="creditCode" label="统一社会信用代码" width="190" show-overflow-tooltip />
        <el-table-column prop="regionName" label="区域" width="130" show-overflow-tooltip />
        <el-table-column prop="industry" label="所属行业" width="120" align="center">
          <template #default="{ row }">{{ dictLabel(industryMap, row.industry) }}</template>
        </el-table-column>
        <el-table-column prop="entLevel" label="企业等级" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.entLevel" :type="levelTagType(row.entLevel)" size="small">
              {{ dictLabel(entLevelMap, row.entLevel) }}
            </el-tag>
            <span v-else>--</span>
          </template>
        </el-table-column>
        <el-table-column prop="contactName" label="联系人" width="100" show-overflow-tooltip />
        <el-table-column prop="contactPhone" label="联系电话" width="140" show-overflow-tooltip />
        <el-table-column prop="orderCount" label="订单数" width="90" align="right" />
        <el-table-column prop="totalGuaranteeAmount" label="担保总额(元)" width="150" align="right">
          <template #default="{ row }">{{ formatAmount(row.totalGuaranteeAmount) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无企业数据" :image-size="80" />
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

    <el-dialog v-model="detailVisible" title="企业详情" width="820px">
      <div v-loading="detailLoading">
        <el-descriptions v-if="detail" :column="2" border>
          <el-descriptions-item label="企业编码">{{ detail.entCode || '--' }}</el-descriptions-item>
          <el-descriptions-item label="企业名称">{{ detail.entName || '--' }}</el-descriptions-item>
          <el-descriptions-item label="统一社会信用代码">
            {{ detail.creditCode || '--' }}
          </el-descriptions-item>
          <el-descriptions-item label="区域">
            {{ detail.regionName || '--' }}
            <span v-if="detail.regionCode" class="text-muted">({{ detail.regionCode }})</span>
          </el-descriptions-item>
          <el-descriptions-item label="所属行业">
            {{ dictLabel(industryMap, detail.industry) }}
          </el-descriptions-item>
          <el-descriptions-item label="企业等级">
            {{ dictLabel(entLevelMap, detail.entLevel) }}
          </el-descriptions-item>
          <el-descriptions-item label="联系人">{{ detail.contactName || '--' }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">
            {{ detail.contactPhone || '--' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.status)" size="small">
              {{ statusLabel(detail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="订单数">{{ detail.orderCount ?? 0 }} 笔</el-descriptions-item>
          <template v-if="detailStats">
            <el-descriptions-item label="项目数量">
              {{ detailStats.projectCount }} 个
            </el-descriptions-item>
            <el-descriptions-item label="投标订单数">
              {{ detailStats.tenderOrderCount }} 笔
            </el-descriptions-item>
            <el-descriptions-item label="履约订单数">
              {{ detailStats.performanceOrderCount }} 笔
            </el-descriptions-item>
            <el-descriptions-item label="担保总额">
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
