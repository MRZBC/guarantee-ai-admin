<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  getOrderInstitution,
  getOrderInsurance,
  getOrderRegion,
  getOrderTrend,
  getOverview
} from '@/api/analysis'
import ChartPanel from '@/components/ChartPanel.vue'
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
  dateRange: [string, string] | null
}>({
  orderType: 'TENDER',
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

const orderTypeLabel = computed(() => (filters.orderType === 'TENDER' ? '投标' : '履约'))

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
      data: regions.value.map((item) => item.guaranteeAmount)
    },
    {
      name: '保费金额',
      type: 'bar',
      barMaxWidth: 40,
      itemStyle: { color: '#67c23a', borderRadius: [4, 4, 0, 0] },
      data: regions.value.map((item) => item.premiumAmount)
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
      data: insurances.value.map((item) => ({ name: item.typeName, value: item.guaranteeAmount }))
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
      data: sorted.map((item) => item.orgName)
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
        data: sorted.map((item) => item.guaranteeAmount)
      }
    ]
  }
})

function baseParams() {
  return {
    orderType: filters.orderType,
    startDate: filters.dateRange?.[0] || undefined,
    endDate: filters.dateRange?.[1] || undefined
  }
}

async function loadOverview(): Promise<void> {
  try {
    overview.value = await getOverview()
  } catch {
    // 拦截器已提示
  }
}

async function loadTrend(): Promise<void> {
  trendLoading.value = true
  try {
    trend.value = (await getOrderTrend({ ...baseParams(), granularity: granularity.value })) ?? []
  } catch {
    trend.value = []
  } finally {
    trendLoading.value = false
  }
}

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    const params = baseParams()
    const [regionData, insuranceData, institutionData] = await Promise.all([
      getOrderRegion({ ...params, limit: 10 }),
      getOrderInsurance(params),
      getOrderInstitution({ ...params, limit: 10 })
    ])
    regions.value = regionData ?? []
    insurances.value = insuranceData ?? []
    institutions.value = institutionData ?? []
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function reload(): Promise<void> {
  await Promise.all([loadOverview(), loadTrend(), loadAll()])
}

function handleSearch(): void {
  void reload()
}

function handleReset(): void {
  filters.orderType = 'TENDER'
  filters.dateRange = null
  granularity.value = 'MONTH'
  void reload()
}

function handleGranularityChange(): void {
  void loadTrend()
}

onMounted(() => {
  void reload()
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
          <el-col :xs="24" :sm="12" :md="5" class="filter-actions">
            <el-form-item label-width="0">
              <el-button type="primary" icon="Search" @click="handleSearch">查询</el-button>
              <el-button icon="Refresh" @click="handleReset">重置</el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <StatCards :items="statItems" :span="6" />

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
          <ChartPanel :option="regionOption" :loading="loading" :empty="!regions.length" :height="340" />
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-header"><span>险种分布</span></div>
          </template>
          <ChartPanel
            :option="insuranceOption"
            :loading="loading"
            :empty="!insurances.length"
            :height="340"
          />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header>
        <div class="card-header"><span>机构排行（TOP 10）</span></div>
      </template>
      <ChartPanel
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
