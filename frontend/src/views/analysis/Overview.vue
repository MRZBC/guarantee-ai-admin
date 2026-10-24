<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { getOrderReport, exportOrderReport, getOverview } from '@/api/analysis'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { readAnalysisRoute, routeParams } from '@/utils/analysisNavigation'
import RegionSelect from '@/components/RegionSelect.vue'
import { listOrgOptions } from '@/api/system'
import type { OrgOption } from '@/types/system'
import type { OrderReport, ComparisonMetric } from '@/types/analysis'
import ChartPanel from '@/components/ChartPanel.vue'
import AnalysisInterpretation from '@/components/AnalysisInterpretation.vue'
import StatCards, { type StatCardItem } from '@/components/StatCards.vue'
import { formatAmount, formatAmountShort, formatDate } from '@/utils/format'
import type {
  AnalysisOverview,
  Granularity,
  OrderInstitutionItem,
  OrderInsuranceItem,
  OrderRegionItem,
  OrderTrendItem,
  OrderType
} from '@/types/analysis'
import type { EChartsOption } from '@/utils/echarts'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const report = ref<OrderReport | null>(null)
const exporting = ref(false)
const orgOptions = ref<OrgOption[]>([])
let requestId = 0

const PALETTE = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#9b59b6', '#00bcd4', '#ff9800']

const loading = ref(false)
const trendLoading = ref(false)
const overview = ref<AnalysisOverview | null>(null)
const trend = ref<OrderTrendItem[]>([])
const regions = ref<OrderRegionItem[]>([])
const insurances = ref<OrderInsuranceItem[]>([])
const institutions = ref<OrderInstitutionItem[]>([])

const filters = reactive<{
  orderType: OrderType
  regionCode: string
  orgId: number | null
  dateRange: [string, string] | null
}>({
  orderType: 'TENDER',
  regionCode: '',
  orgId: null,
  dateRange: null
})

const granularity = ref<Granularity>('MONTH')

const statItems = computed<StatCardItem[]>(() => {
  const data = overview.value
  return [
    { label: '订单总数', value: data?.totalOrderCount ?? 0, unit: '笔' },
    { label: '投标订单数', value: data?.tenderOrderCount ?? 0, unit: '笔', color: '#409eff' },
    { label: '履约订单数', value: data?.performanceOrderCount ?? 0, unit: '笔', color: '#67c23a' },
    {
      label: '担保总金额',
      value: formatAmount(data?.totalGuaranteeAmount ?? 0),
      unit: '元',
      color: '#e6a23c'
    },
    {
      label: '投标担保金额',
      value: formatAmount(data?.tenderGuaranteeAmount ?? 0),
      unit: '元'
    },
    {
      label: '履约担保金额',
      value: formatAmount(data?.performanceGuaranteeAmount ?? 0),
      unit: '元'
    },
    { label: '保费总额', value: formatAmount(data?.totalPremiumAmount ?? 0), unit: '元', color: '#f56c6c' },
    { label: '企业数量', value: data?.enterpriseCount ?? 0, unit: '家' },
    { label: '项目数量', value: data?.projectCount ?? 0, unit: '个' },
    { label: '机构数量', value: data?.orgCount ?? 0, unit: '个' },
    { label: '在保订单数', value: data?.effectiveOrderCount ?? 0, unit: '笔' },
    {
      label: '数据区间',
      value: `${formatDate(overview.value?.dataStartDate)} ~ ${formatDate(overview.value?.dataEndDate)}`
    }
  ]
})

const orderTypeLabel = computed(() => (report.value?.comparison.criteria.orderType !== 'PERFORMANCE' ? '投标' : '履约'))

const trendOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['订单数量', '担保金额', '保费金额'], top: 0 },
  grid: { left: 12, right: 20, bottom: 8, top: 46, containLabel: true },
  xAxis: { type: 'category', boundaryGap: false, data: trend.value.map((item) => item.period) },
  yAxis: [
    { type: 'value', name: '订单数', minInterval: 1 },
    {
      type: 'value',
      name: '金额',
      axisLabel: { formatter: (value: number) => formatAmountShort(value) }
    }
  ],
  series: [
    {
      name: '订单数量',
      type: 'line',
      smooth: true,
      yAxisIndex: 0,
      itemStyle: { color: '#409eff' },
      areaStyle: { opacity: 0.12 },
      data: trend.value.map((item) => item.orderCount)
    },
    {
      name: '担保金额',
      type: 'line',
      smooth: true,
      yAxisIndex: 1,
      itemStyle: { color: '#e6a23c' },
      data: trend.value.map((item) => item.guaranteeAmount)
    },
    {
      name: '保费金额',
      type: 'line',
      smooth: true,
      yAxisIndex: 1,
      itemStyle: { color: '#f56c6c' },
      data: trend.value.map((item) => item.premiumAmount)
    }
  ]
}))

const regionOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  grid: { left: 12, right: 20, bottom: 8, top: 30, containLabel: true },
  xAxis: {
    type: 'category',
    axisLabel: { interval: 0, rotate: regions.value.length > 6 ? 30 : 0 },
    data: regions.value.map((item) => item.regionName || item.regionCode)
  },
  yAxis: {
    type: 'value',
    name: '担保金额',
    axisLabel: { formatter: (value: number) => formatAmountShort(value) }
  },
  series: [
    {
      name: '担保金额',
      type: 'bar',
      barMaxWidth: 40,
      itemStyle: { color: '#409eff', borderRadius: [4, 4, 0, 0] },
      data: regions.value.map((item) => ({ value: item.guaranteeAmount, drilldown: { regionCode: item.regionCode, regionExact: true } }))
    },
    {
      name: '保费金额',
      type: 'bar',
      barMaxWidth: 40,
      itemStyle: { color: '#67c23a', borderRadius: [4, 4, 0, 0] },
      data: regions.value.map((item) => ({ value: item.premiumAmount, drilldown: { regionCode: item.regionCode, regionExact: true } }))
    }
  ]
}))

const insuranceOption = computed<EChartsOption>(() => ({
  tooltip: {
    trigger: 'item',
    formatter: (params: unknown) => {
      const item = params as { name: string; value: number; percent?: number }
      return `${item.name}<br/>担保金额：${formatAmount(item.value)} 元<br/>占比：${item.percent ?? 0}%`
    }
  },
  legend: { type: 'scroll', orient: 'vertical', right: 0, top: 'middle' },
  color: PALETTE,
  series: [
    {
      name: '担保金额',
      type: 'pie',
      radius: ['40%', '66%'],
      center: ['36%', '52%'],
      itemStyle: { borderColor: '#fff', borderWidth: 2 },
      label: { formatter: '{b}\n{d}%' },
      data: insurances.value.map((item) => ({ name: item.typeName, value: item.guaranteeAmount, drilldown: { insuranceTypeId: item.insuranceTypeId } }))
    }
  ]
}))

const institutionOption = computed<EChartsOption>(() => {
  // 横向柱状图数据需按数值升序，ECharts 自下而上绘制
  const sorted = [...institutions.value].sort((a, b) => a.guaranteeAmount - b.guaranteeAmount)
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown) => {
        const list = params as { name: string; value: number }[]
        const first = list[0]
        return first ? `${first.name}<br/>担保金额：${formatAmount(first.value)} 元` : ''
      }
    },
    grid: { left: 12, right: 70, bottom: 8, top: 20, containLabel: true },
    xAxis: {
      type: 'value',
      name: '担保金额',
      axisLabel: { formatter: (value: number) => formatAmountShort(value) }
    },
    yAxis: {
      type: 'category',
      data: sorted.map((item) => `${item.orgName} · ${item.regionName || item.regionCode}`)
    },
    series: [
      {
        name: '担保金额',
        type: 'bar',
        barMaxWidth: 22,
        itemStyle: { color: '#e6a23c', borderRadius: [0, 4, 4, 0] },
        label: {
          show: true,
          position: 'right',
          formatter: (params: unknown) => {
            const item = params as { value: number }
            return formatAmountShort(item.value)
          }
        },
        data: sorted.map((item) => ({ value: item.guaranteeAmount, drilldown: { orgId: item.orgId, regionCode: item.regionCode, regionExact: true } }))
      }
    ]
  }
})

const unappliedFilters = computed(() => {
  const c = report.value?.comparison.criteria
  return !c || filters.orderType !== c.orderType || (filters.regionCode || '') !== (c.regionCode || '')
    || (filters.orgId || null) !== (c.orgId || null) || filters.dateRange?.[0] !== c.startDate
    || filters.dateRange?.[1] !== c.endDate || granularity.value.toLowerCase() !== (c.granularity || 'month').toLowerCase()
})

function applyReport(result: OrderReport): void {
  report.value = result
  trend.value = result.trend
  regions.value = result.regions
  insurances.value = result.insurances
  institutions.value = result.institutions
  const c = result.comparison.criteria
  filters.dateRange = [c.startDate!, c.endDate!]
}

function clearReport(): void {
  report.value = null
  trend.value = []
  regions.value = []
  insurances.value = []
  institutions.value = []
}

async function reload(): Promise<void> {
  const id = ++requestId
  loading.value = true
  trendLoading.value = true
  clearReport()
  try {
    const params = readAnalysisRoute(route.query)
    filters.orderType = params.orderType || 'TENDER'
    filters.dateRange = params.startDate && params.endDate ? [params.startDate, params.endDate] : null
    filters.regionCode = params.regionCode || ''
    filters.orgId = params.orgId || null
    granularity.value = params.granularity || 'MONTH'
    const result = await getOrderReport(params)
    if (id !== requestId) return
    applyReport(result)
  } catch (error) {
    if (id === requestId) ElMessage.error(error instanceof Error ? error.message : '分析加载失败')
  } finally {
    if (id === requestId) { loading.value = false; trendLoading.value = false }
  }
}

async function handleSearch(): Promise<void> {
  const query = routeParams({ orderType: filters.orderType, startDate: filters.dateRange?.[0],
    endDate: filters.dateRange?.[1], regionCode: filters.regionCode, orgId: filters.orgId, granularity: granularity.value })
  if (JSON.stringify(query) === JSON.stringify(route.query)) await reload()
  else await router.replace({ query })
}

function handleReset(): void {
  filters.orderType = 'TENDER'
  filters.dateRange = null
  filters.regionCode = ''
  filters.orgId = null
  granularity.value = 'MONTH'
  void handleSearch()
}

function handleGranularityChange(): void { void handleSearch() }

function drilldown(data: unknown): void {
  if (loading.value || !report.value || !data || typeof data !== 'object' || !('drilldown' in data)) return
  const selected = data.drilldown as Record<string, unknown>
  if (Object.values(selected).some(value => value === null || value === undefined || value === '')) return
  const c = report.value.comparison.criteria
  if (!user.permissions.includes(c.orderType === 'TENDER' ? 'order:tender:view' : 'order:performance:view')) return
  // 将已应用的默认区间固化到分析页 URL，返回时仍是原来的时间口径。
  const analysisQuery = routeParams({ ...c, granularity: granularity.value.toUpperCase() })
  void router.replace({ query: analysisQuery }).then(() => router.push({
    name: c.orderType === 'TENDER' ? 'TenderOrders' : 'PerformanceOrders',
    query: routeParams({ startDate: c.startDate, endDate: c.endDate, regionCode: c.regionCode, orgId: c.orgId, ...selected })
  }))
}

async function download(): Promise<void> {
  if (!report.value || loading.value) return
  exporting.value = true
  try {
    const blob = await exportOrderReport(report.value.comparison.criteria)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = '运营分析摘要.csv'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出失败')
  } finally { exporting.value = false }
}

function changeText(metric: ComparisonMetric): string {
  if (metric.status === 'NEW') return '新增'
  if (metric.status === 'UNCHANGED') return '无变化'
  return `${metric.changeRate ?? '--'}%`
}

const metrics = computed(() => report.value ? [
  { name: '订单数（笔）', money: false, metric: report.value.comparison.orderCount },
  { name: '担保金额（元）', money: true, metric: report.value.comparison.guaranteeAmount },
  { name: '保费金额（元）', money: true, metric: report.value.comparison.premiumAmount }
] : [])

watch(() => route.fullPath, () => {
  if (route.name === 'AnalysisOverview') void reload()
  else ++requestId
}, { immediate: true })
onMounted(() => {
  void getOverview().then(data => { overview.value = data }).catch(() => {})
  void listOrgOptions().then(data => { orgOptions.value = data ?? [] }).catch(() => {})
})
</script>

<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <el-form :model="filters" label-width="82px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="6">
            <el-form-item label="订单类型">
              <!--
                用下拉而不是分段单选：筛选区其它控件（地区、统计区间）都是"点开再选"，
                夹一组平铺按钮会让这一项在视觉上突出成另一种东西。
                选项只有两个，代价是多两次点击，换来与整行控件同构。
              -->
              <el-select v-model="filters.orderType" @change="handleSearch">
                <el-option label="投标订单" value="TENDER" />
                <el-option label="履约订单" value="PERFORMANCE" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="统计区间">
              <el-date-picker
                v-model="filters.dateRange"
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
          <el-col :xs="24" :md="6"><el-form-item label="地区"><RegionSelect v-model="filters.regionCode" /></el-form-item></el-col>
          <el-col :xs="24" :md="6"><el-form-item label="承保机构"><el-select v-model="filters.orgId" clearable filterable><el-option v-for="org in orgOptions" :key="org.id" :label="org.orgName" :value="org.id" /></el-select></el-form-item></el-col>
          <el-col :xs="24" :sm="12" :md="5" class="filter-actions">
            <el-form-item label-width="0">
              <el-button type="primary" icon="Search" @click="handleSearch">查询</el-button>
              <el-button icon="Refresh" @click="handleReset">重置</el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <p>全库概览（不随下方分析筛选变化）</p>
    <StatCards v-if="overview" :items="statItems" :span="6" />
    <el-skeleton v-else :rows="3" animated />

    <el-card v-if="report" shadow="never" data-testid="analysis-summary">
      <template #header>
        <div class="card-header">
          <span>当前筛选 · {{ orderTypeLabel }}订单分析</span>
          <el-button v-if="user.permissions.includes('analysis:export')" :loading="exporting" :disabled="loading" @click="download">导出分析摘要 CSV</el-button>
        </div>
      </template>
      <p>本期：{{ report.comparison.criteria.startDate }} 至 {{ report.comparison.criteria.endDate }}（申请日期，含首尾）</p>
      <p>上期：{{ report.comparison.previousStartDate }} 至 {{ report.comparison.previousEndDate }}</p>
      <el-alert v-if="!report.comparison.comparisonComplete" title="对比数据可能不完整，暂不生成变化提示" type="info" :closable="false" />
      <el-alert v-else-if="report.comparison.changeAlert" :title="`订单数变化提示：${changeText(report.comparison.orderCount)}，请结合明细核查`" type="warning" :closable="false" />
      <el-table :data="metrics">
        <el-table-column prop="name" label="指标" />
        <el-table-column label="本期"><template #default="{ row }">{{ row.money ? formatAmount(row.metric.current) : row.metric.current.toLocaleString() }}</template></el-table-column>
        <el-table-column label="上期"><template #default="{ row }">{{ row.money ? formatAmount(row.metric.previous) : row.metric.previous.toLocaleString() }}</template></el-table-column>
        <el-table-column label="绝对变化"><template #default="{ row }">{{ row.money ? formatAmount(row.metric.change) : row.metric.change.toLocaleString() }}</template></el-table-column>
        <el-table-column label="变化率"><template #default="{ row }">{{ changeText(row.metric) }}</template></el-table-column>
      </el-table>
      <p>点击图表或“查看订单”可核查明细；Top 10 只展示部分分类。导出为导出时重新查询的结果。</p>
      <AnalysisInterpretation :report="report" :disabled="loading || unappliedFilters" @report-updated="applyReport" />
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>{{ orderTypeLabel }}订单趋势</span>
          <el-radio-group v-model="granularity" size="small" @change="handleGranularityChange">
            <el-radio-button value="DAY">按日</el-radio-button>
            <el-radio-button value="MONTH">按月</el-radio-button>
            <el-radio-button value="YEAR">按年</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <ChartPanel
        :option="trendOption"
        :loading="trendLoading"
        :empty="!trend.length"
        :height="340"
      />
    </el-card>

    <el-row :gutter="12">
      <el-col :xs="24" :lg="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-header"><span>区域分布（TOP 10）</span></div>
          </template>
          <ChartPanel @select="drilldown" :option="regionOption" :loading="loading" :empty="!regions.length" :height="340" />
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-header"><span>险种分布</span></div>
          </template>
          <ChartPanel
            @select="drilldown"
            :option="insuranceOption"
            :loading="loading"
            :empty="!insurances.length"
            :height="340"
          />
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="report" shadow="never">
      <template #header>分布明细与下钻</template>
      <el-tabs>
        <el-tab-pane label="地区 Top 10">
          <el-table :data="regions"><el-table-column prop="regionName" label="地区" /><el-table-column prop="orderCount" label="订单数" />
            <el-table-column label="操作"><template #default="{ row }"><el-button :disabled="!row.regionCode" link @click="drilldown({ drilldown: { regionCode: row.regionCode, regionExact: true } })">查看订单</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="险种">
          <el-table :data="insurances"><el-table-column prop="typeName" label="险种" /><el-table-column prop="orderCount" label="订单数" />
            <el-table-column label="操作"><template #default="{ row }"><el-button :disabled="!row.insuranceTypeId" link @click="drilldown({ drilldown: { insuranceTypeId: row.insuranceTypeId } })">查看订单</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="机构与地区 Top 10">
          <el-table :data="institutions"><el-table-column prop="orgName" label="机构" /><el-table-column prop="regionName" label="地区" /><el-table-column prop="orderCount" label="订单数" />
            <el-table-column label="操作"><template #default="{ row }"><el-button :disabled="!row.orgId || !row.regionCode" link @click="drilldown({ drilldown: { orgId: row.orgId, regionCode: row.regionCode, regionExact: true } })">查看订单</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-header"><span>机构与地区排行（TOP 10）</span></div>
      </template>
      <ChartPanel
        @select="drilldown"
        :option="institutionOption"
        :loading="loading"
        :empty="!institutions.length"
        :height="360"
      />
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
</style>
