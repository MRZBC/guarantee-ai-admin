<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getOrderRegion, getOrderTrend, getOverview } from '@/api/analysis'
import ChartPanel from '@/components/ChartPanel.vue'
import StatCards, { type StatCardItem } from '@/components/StatCards.vue'
import { formatAmount, formatAmountShort, formatDate } from '@/utils/format'
import type { AnalysisOverview, OrderRegionItem, OrderTrendItem } from '@/types/analysis'
import type { EChartsOption } from '@/utils/echarts'

const loading = ref(false)
const overview = ref<AnalysisOverview | null>(null)
const trend = ref<OrderTrendItem[]>([])
const regions = ref<OrderRegionItem[]>([])

const statItems = computed<StatCardItem[]>(() => {
  const data = overview.value
  return [
    { label: '订单总数', value: data?.totalOrderCount ?? 0, unit: '笔' },
    { label: '投标订单', value: data?.tenderOrderCount ?? 0, unit: '笔' },
    { label: '履约订单', value: data?.performanceOrderCount ?? 0, unit: '笔' },
    {
      label: '担保总金额',
      value: formatAmount(data?.totalGuaranteeAmount ?? 0),
      unit: '元'
    },
    { label: '保费总额', value: formatAmount(data?.totalPremiumAmount ?? 0), unit: '元' },
    { label: '企业数量', value: data?.enterpriseCount ?? 0, unit: '家' },
    { label: '项目数量', value: data?.projectCount ?? 0, unit: '个' },
    { label: '在保订单', value: data?.effectiveOrderCount ?? 0, unit: '笔' }
  ]
})

const dataRangeText = computed(() => {
  const data = overview.value
  if (!data?.dataStartDate && !data?.dataEndDate) return '暂无数据区间'
  return `${formatDate(data.dataStartDate)} ~ ${formatDate(data.dataEndDate)}`
})

const trendOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['订单数量', '担保金额'], top: 0 },
  grid: { left: 16, right: 24, bottom: 8, top: 44, containLabel: true },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map((item) => item.period)
  },
  yAxis: [
    { type: 'value', name: '订单数', minInterval: 1 },
    {
      type: 'value',
      name: '担保金额',
      axisLabel: { formatter: (value: number) => formatAmountShort(value) }
    }
  ],
  series: [
    {
      name: '订单数量',
      type: 'line',
      smooth: true,
      yAxisIndex: 0,
      itemStyle: { color: '#2765b0' },
      areaStyle: { opacity: 0.12 },
      data: trend.value.map((item) => item.orderCount)
    },
    {
      name: '担保金额',
      type: 'line',
      smooth: true,
      yAxisIndex: 1,
      itemStyle: { color: '#8db6c6' },
      data: trend.value.map((item) => item.guaranteeAmount)
    }
  ]
}))

const regionOption = computed<EChartsOption>(() => {
  const top = regions.value.slice(0, 8)
  return {
    tooltip: {
      trigger: 'item',
      formatter: (params: unknown) => {
        const item = params as { name: string; value: number; percent?: number }
        return `${item.name}<br/>担保金额：${formatAmount(item.value)} 元<br/>占比：${item.percent ?? 0}%`
      }
    },
    legend: { type: 'scroll', orient: 'vertical', right: 0, top: 'middle' },
    series: [
      {
        name: '担保金额',
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['38%', '52%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { formatter: '{b}\n{d}%' },
        data: top.map((item) => ({ name: item.regionName || item.regionCode, value: item.guaranteeAmount }))
      }
    ]
  }
})

const barOption = computed<EChartsOption>(() => {
  const top = regions.value.slice(0, 8)
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 16, right: 24, bottom: 8, top: 30, containLabel: true },
    xAxis: { type: 'category', data: top.map((item) => item.regionName || item.regionCode) },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: (value: number) => formatAmountShort(value) }
    },
    series: [
      {
        name: '担保金额',
        type: 'bar',
        barMaxWidth: 38,
        itemStyle: { color: '#2765b0', borderRadius: [4, 4, 0, 0] },
        data: top.map((item) => item.guaranteeAmount)
      }
    ]
  }
})

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    const [overviewData, trendData, regionData] = await Promise.all([
      getOverview(),
      getOrderTrend({ granularity: 'MONTH' }),
      getOrderRegion({ limit: 10 })
    ])
    overview.value = overviewData
    trend.value = trendData ?? []
    regions.value = regionData ?? []
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<template>
  <div class="page-container">
    <div class="dashboard-heading">
      <div>
        <h1>业务总览</h1>
        <p>查看保函业务的整体情况</p>
      </div>
      <span>数据区间：{{ dataRangeText }}</span>
    </div>
    <StatCards :items="statItems" :span="6" />

    <el-row :gutter="12">
      <el-col :xs="24" :lg="16">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>订单与担保金额趋势</span>
              <span class="text-muted">数据区间：{{ dataRangeText }}</span>
            </div>
          </template>
          <ChartPanel
            :option="trendOption"
            :loading="loading"
            :empty="!trend.length"
            :height="330"
          />
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="8">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>区域担保金额占比</span>
              <span class="text-muted">TOP 8</span>
            </div>
          </template>
          <ChartPanel
            :option="regionOption"
            :loading="loading"
            :empty="!regions.length"
            :height="330"
          />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header>
        <div class="card-header"><span>区域担保金额对比</span></div>
      </template>
      <ChartPanel :option="barOption" :loading="loading" :empty="!regions.length" :height="300" />
    </el-card>
  </div>
</template>

<style scoped>
.dashboard-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.dashboard-heading h1 { margin: 0 0 6px; font-size: 22px; color: #24374c; }
.dashboard-heading p, .dashboard-heading > span { margin: 0; font-size: 13px; color: #8190a0; }

@media (max-width: 600px) {
  .dashboard-heading { align-items: flex-start; flex-direction: column; }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  gap: 12px;
  flex-wrap: wrap;
}
</style>
