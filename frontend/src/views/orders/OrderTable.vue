<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getPerformanceOrder, getTenderOrder, pagePerformanceOrders, pageTenderOrders } from '@/api/orders'
import { listInsuranceTypeOptions, listOrgOptions } from '@/api/system'
import { formatAmount, formatDate, formatPercent } from '@/utils/format'
import type { OrderItem, OrderQuery, PerformanceOrderItem } from '@/types/order'
import type { InsuranceTypeOption, OrgOption } from '@/types/system'

const props = defineProps<{
  /** TENDER = 投标订单，PERFORMANCE = 履约订单 */
  orderType: 'TENDER' | 'PERFORMANCE'
}>()

const isPerformance = computed(() => props.orderType === 'PERFORMANCE')
const pageTitle = computed(() => (isPerformance.value ? '履约订单' : '投标订单'))

const loading = ref(false)
const rows = ref<(OrderItem | PerformanceOrderItem)[]>([])
const total = ref(0)
const orgOptions = ref<OrgOption[]>([])
const insuranceOptions = ref<InsuranceTypeOption[]>([])

const query = reactive<OrderQuery>({
  pageNum: 1,
  pageSize: 10,
  orderNo: '',
  regionCode: '',
  orgId: null,
  insuranceTypeId: null,
  status: '',
  startDate: '',
  endDate: ''
})

/** 日期范围组件绑定值，拆分为 startDate / endDate 传后端 */
const dateRange = ref<[string, string] | null>(null)

/** 状态字典：后端返回 statusName 优先，这里作为兜底与筛选下拉 */
const statusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已审核' },
  { value: 'EFFECTIVE', label: '已生效' },
  { value: 'EXPIRED', label: '已到期' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'CANCELLED', label: '已取消' }
]

const statusMap: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待审核',
  PENDING_AUDIT: '待审核',
  APPROVED: '已审核',
  EFFECTIVE: '已生效',
  IN_EFFECT: '已生效',
  EXPIRED: '已到期',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
  CANCELED: '已取消'
}

function statusLabel(row: OrderItem): string {
  if (row.statusName) return row.statusName
  const code = (row.status ?? '').toUpperCase()
  return statusMap[code] ?? row.status ?? '--'
}

function statusTagType(row: OrderItem): 'success' | 'warning' | 'danger' | 'info' {
  const code = (row.status ?? '').toUpperCase()
  if (['EFFECTIVE', 'IN_EFFECT', 'APPROVED'].includes(code)) return 'success'
  if (['PENDING', 'PENDING_AUDIT', 'DRAFT'].includes(code)) return 'warning'
  if (['REJECTED', 'EXPIRED', 'CANCELLED', 'CANCELED'].includes(code)) return 'danger'
  return 'info'
}

function buildParams(): OrderQuery {
  return {
    pageNum: query.pageNum,
    pageSize: query.pageSize,
    orderNo: query.orderNo || undefined,
    regionCode: query.regionCode || undefined,
    orgId: query.orgId ?? undefined,
    insuranceTypeId: query.insuranceTypeId ?? undefined,
    status: query.status || undefined,
    startDate: dateRange.value?.[0] || undefined,
    endDate: dateRange.value?.[1] || undefined
  }
}

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const params = buildParams()
    const result = isPerformance.value
      ? await pagePerformanceOrders(params)
      : await pageTenderOrders(params)
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
  query.orderNo = ''
  query.regionCode = ''
  query.orgId = null
  query.insuranceTypeId = null
  query.status = ''
  dateRange.value = null
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

/* ---------------- 详情 ---------------- */

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<OrderItem | PerformanceOrderItem | null>(null)

async function openDetail(row: OrderItem): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = row
  try {
    detail.value = isPerformance.value
      ? await getPerformanceOrder(row.id)
      : await getTenderOrder(row.id)
  } catch {
    // 失败时保留列表行数据
  } finally {
    detailLoading.value = false
  }
}

async function loadOptions(): Promise<void> {
  try {
    const [orgs, insurances] = await Promise.all([listOrgOptions(), listInsuranceTypeOptions()])
    orgOptions.value = orgs ?? []
    insuranceOptions.value = insurances ?? []
  } catch {
    orgOptions.value = []
    insuranceOptions.value = []
  }
}

onMounted(() => {
  void loadOptions()
  void loadData()
})
</script>

<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <el-form :model="query" label-width="82px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="订单号">
              <el-input
                v-model="query.orderNo"
                placeholder="请输入订单号"
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
            <el-form-item label="机构">
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
            <el-form-item label="险种">
              <el-select
                v-model="query.insuranceTypeId"
                placeholder="全部险种"
                clearable
                filterable
              >
                <el-option
                  v-for="item in insuranceOptions"
                  :key="item.id"
                  :label="item.typeName"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="状态">
              <el-select v-model="query.status" placeholder="全部状态" clearable>
                <el-option
                  v-for="item in statusOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="8">
            <el-form-item label="申请日期">
              <el-date-picker
                v-model="dateRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                range-separator="至"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                unlink-panels
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
        <span class="table-toolbar__title">{{ pageTitle }}列表 · 共 {{ total }} 条</span>
      </div>

      <el-table
        v-loading="loading"
        :data="rows"
        border
        stripe
        height="520"
        size="default"
        row-key="id"
      >
        <el-table-column type="index" label="#" width="52" align="center" fixed />
        <el-table-column prop="orderNo" label="订单号" width="180" fixed show-overflow-tooltip />
        <el-table-column
          v-if="isPerformance"
          prop="contractNo"
          label="合同编号"
          width="170"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{ row.contractNo || '--' }}</template>
        </el-table-column>
        <el-table-column prop="projectName" label="项目名称" min-width="200" show-overflow-tooltip />
        <el-table-column
          prop="enterpriseName"
          label="企业名称"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column
          prop="insuranceTypeName"
          label="险种"
          width="140"
          show-overflow-tooltip
        />
        <el-table-column prop="orgName" label="机构" width="160" show-overflow-tooltip />
        <el-table-column prop="regionName" label="区域" width="120" show-overflow-tooltip />
        <el-table-column prop="guaranteeAmount" label="担保金额(元)" width="150" align="right">
          <template #default="{ row }">{{ formatAmount(row.guaranteeAmount) }}</template>
        </el-table-column>
        <el-table-column prop="premiumAmount" label="保费(元)" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.premiumAmount) }}</template>
        </el-table-column>
        <el-table-column prop="premiumRate" label="费率" width="100" align="right">
          <template #default="{ row }">{{ formatPercent(row.premiumRate) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row)" size="small">{{ statusLabel(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="applyDate" label="申请日期" width="120" align="center">
          <template #default="{ row }">{{ formatDate(row.applyDate) }}</template>
        </el-table-column>
        <el-table-column prop="effectiveDate" label="生效日期" width="120" align="center">
          <template #default="{ row }">{{ formatDate(row.effectiveDate) }}</template>
        </el-table-column>
        <el-table-column prop="expireDate" label="到期日期" width="120" align="center">
          <template #default="{ row }">{{ formatDate(row.expireDate) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无订单数据" :image-size="80" />
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

    <el-dialog v-model="detailVisible" :title="`${pageTitle}详情`" width="860px">
      <div v-loading="detailLoading">
        <el-descriptions v-if="detail" :column="2" border>
          <el-descriptions-item label="订单号">{{ detail.orderNo || '--' }}</el-descriptions-item>
          <el-descriptions-item v-if="isPerformance" label="合同编号">
            {{ (detail as PerformanceOrderItem).contractNo || '--' }}
          </el-descriptions-item>
          <el-descriptions-item label="项目名称">
            {{ detail.projectName || '--' }}
          </el-descriptions-item>
          <el-descriptions-item label="企业名称">
            {{ detail.enterpriseName || '--' }}
          </el-descriptions-item>
          <el-descriptions-item label="险种名称">
            {{ detail.insuranceTypeName || '--' }}
          </el-descriptions-item>
          <el-descriptions-item label="险种类别">
            {{ detail.insuranceTypeCategory || '--' }}
          </el-descriptions-item>
          <el-descriptions-item label="所属机构">{{ detail.orgName || '--' }}</el-descriptions-item>
          <el-descriptions-item label="区域">
            {{ detail.regionName || '--' }}
            <span v-if="detail.regionCode" class="text-muted">({{ detail.regionCode }})</span>
          </el-descriptions-item>
          <el-descriptions-item label="担保金额">
            {{ formatAmount(detail.guaranteeAmount) }} 元
          </el-descriptions-item>
          <el-descriptions-item label="保费金额">
            {{ formatAmount(detail.premiumAmount) }} 元
          </el-descriptions-item>
          <el-descriptions-item label="费率">
            {{ formatPercent(detail.premiumRate) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail)" size="small">{{ statusLabel(detail) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="申请日期">
            {{ formatDate(detail.applyDate) }}
          </el-descriptions-item>
          <el-descriptions-item label="生效日期">
            {{ formatDate(detail.effectiveDate) }}
          </el-descriptions-item>
          <el-descriptions-item label="到期日期">
            {{ formatDate(detail.expireDate) }}
          </el-descriptions-item>
          <el-descriptions-item label="项目ID">{{ detail.projectId ?? '--' }}</el-descriptions-item>
          <el-descriptions-item label="企业ID">
            {{ detail.enterpriseId ?? '--' }}
          </el-descriptions-item>
          <el-descriptions-item label="机构ID">{{ detail.orgId ?? '--' }}</el-descriptions-item>
          <el-descriptions-item label="险种ID">
            {{ detail.insuranceTypeId ?? '--' }}
          </el-descriptions-item>
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
