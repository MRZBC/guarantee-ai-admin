<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { pageEnterprises } from '@/api/enterprise'
import { getPerformanceOrder, getTenderOrder, pagePerformanceOrders, pageTenderOrders } from '@/api/orders'
import { pageProjects } from '@/api/project'
import { listInsuranceTypeOptions, listOrgOptions } from '@/api/system'
import RegionSelect from '@/components/RegionSelect.vue'
import { MIN_KEYWORD_LEN, useRemoteSearch } from '@/composables/useRemoteSearch'
import { formatAmount, formatDate, formatPercent } from '@/utils/format'
import type { EnterpriseItem } from '@/types/enterprise'
import type { OrderItem, OrderQuery, PerformanceOrderItem } from '@/types/order'
import type { ProjectItem } from '@/types/project'
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
  projectId: null,
  enterpriseId: null,
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
    projectId: query.projectId ?? undefined,
    enterpriseId: query.enterpriseId ?? undefined,
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
  query.projectId = null
  query.enterpriseId = null
  query.status = ''
  dateRange.value = null
  // 搜索型筛选项要把"已选 + 关键词 + 候选"一起复位，否则会留下上一位项目的标签
  projectSearch.reset()
  enterpriseSearch.reset()
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
    /*
      险种按下单页面的**订单类别**过滤：接口返回的是两类险种的全集
      （启用中的 + 已停用但仍有历史订单的），
      投标订单页混进「履约保函」只会让用户选中后得到一张空列表——
      本页真正要保证的是"下拉里出现的每一项都能筛出数据"。
    */
    insuranceOptions.value = (insurances ?? []).filter((item) => item.category === props.orderType)
  } catch {
    orgOptions.value = []
    insuranceOptions.value = []
  }
}

/**
 * 下拉选项文案的状态后缀。
 *
 * 停用/已删除的机构与险种**仍然可选**——它们的名字就出现在列表的历史订单里，
 * 筛不了等于"看得到、筛不到"。标注只是避免用户以为它们还能用于新业务。
 */
function optionLabel(name: string, item: { status?: number | null; isDeleted?: number | null }): string {
  if (item.isDeleted === 1) return `${name}（已删除）`
  if (item.status === 0) return `${name}（已停用）`
  return name
}

const insuranceLabel = (item: InsuranceTypeOption): string => optionLabel(item.typeName, item)
const orgLabel = (item: OrgOption): string => optionLabel(item.orgName, item)

/* ---------------- 项目 / 企业：模糊搜索（不用全量下拉） ----------------
 *
 * 项目与企业各有数千条：全量下拉既渲染不动、也没法用（要在一屏里翻三千项）。
 * 规则（至少 2 个字、300ms 防抖、最多 20 条、选中项留在候选里）统一收敛在
 * {@link useRemoteSearch}，项目页复用同一实现，避免两页行为漂移。
 */

const projectSearch = useRemoteSearch<ProjectItem>({
  fetch: async (keyword, pageSize) => {
    const page = await pageProjects({ pageNum: 1, pageSize, projectName: keyword })
    return page?.list ?? []
  },
  labelOf: (item) => `${item.projectName}（${item.projectCode}）`,
  minLengthText: `请输入至少 ${MIN_KEYWORD_LEN} 个字`,
  emptyText: '无匹配项目'
})

const enterpriseSearch = useRemoteSearch<EnterpriseItem>({
  fetch: async (keyword, pageSize) => {
    const page = await pageEnterprises({ pageNum: 1, pageSize, entName: keyword })
    return page?.list ?? []
  },
  labelOf: (item) => `${item.entName}（${item.entCode}）`,
  minLengthText: `请输入至少 ${MIN_KEYWORD_LEN} 个字`,
  emptyText: '无匹配企业'
})

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
            <el-form-item label="地区">
              <!-- 地区下拉（行政区划字典）：替代原先手填"区域编码"，只列当前有数据的地区 -->
              <RegionSelect v-model="query.regionCode" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="机构">
              <el-select v-model="query.orgId" placeholder="全部机构" clearable filterable>
                <el-option
                  v-for="org in orgOptions"
                  :key="org.id"
                  :label="orgLabel(org)"
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
                  :label="insuranceLabel(item)"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="项目">
              <!--
                项目有数千条，不做全量下拉：远程模糊搜索，≥2 个字才查，300ms 防抖，最多 20 条。
                候选项带项目编码，便于区分"浙江省水利工程项目0681"这类重名。
              -->
              <el-select
                v-model="query.projectId"
                placeholder="项目名称（至少 2 个字）"
                clearable
                filterable
                :filter-method="projectSearch.filterMethod"
                :loading="projectSearch.searching"
                :no-data-text="projectSearch.noDataText"
                @change="projectSearch.handleChange"
              >
                <el-option
                  v-for="item in projectSearch.options.value"
                  :key="item.id"
                  :label="projectSearch.labelOf(item)"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="企业">
              <el-select
                v-model="query.enterpriseId"
                placeholder="企业名称（至少 2 个字）"
                clearable
                filterable
                :filter-method="enterpriseSearch.filterMethod"
                :loading="enterpriseSearch.searching"
                :no-data-text="enterpriseSearch.noDataText"
                @change="enterpriseSearch.handleChange"
              >
                <el-option
                  v-for="item in enterpriseSearch.options.value"
                  :key="item.id"
                  :label="enterpriseSearch.labelOf(item)"
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
