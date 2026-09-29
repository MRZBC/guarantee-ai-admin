<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAiMetricsOverview, getAiMetricsTopTools, getAiMetricsTrend } from '@/api/ai'
import ChartPanel from '@/components/ChartPanel.vue'
import StatCards, { type StatCardItem } from '@/components/StatCards.vue'
import type {
  AiMetricsRange,
  AiToolCallStat,
  AiTurnMetricOverview,
  AiTurnMetricTrendPoint,
  ProposalStatusStat
} from '@/types/ai'
import type { EChartsOption } from '@/utils/echarts'

/**
 * 「AI 运行」只读页（REQ-MCP-11 / AC-MCP-09）。
 *
 * <p><b>为什么要有这个页面</b>：单轮指标 {@code ai_turn_metric} 从 T5-01 起就在落库
 * （轮次/调用数/token/耗时/触顶/失败率），但此前**没有任何页面能看它** ——
 * 等于"回答'上周平均轮次是多少''换模型后成本涨了多少'时只能靠猜"。
 * 本页把同一批聚合以只读方式呈现给持有 {@code system:audit:view} 的管理员。</p>
 *
 * <p><b>三条来自接口契约的硬约束</b>：</p>
 * <ol>
 *   <li><b>窗口以响应为准</b>：`range` 由后端归一（未知回落 24h），页面把响应里的 `range`
 *       显示出来，而不是把用户点的那个值当真——否则会出现"点了 30 天、看的是 24 小时"的静默错配；</li>
 *   <li><b>比率不在前端算</b>：`errorRate`/`cappedRate` 直接用后端返回值（两处算比率必然对不上账）；</li>
 *   <li><b>不补零</b>：趋势只画库中真实存在的日期，"那天没跑过"与"那天跑了 0 次"是两件事
 *       （REQ-MCP-09 的口径）——因此图表空态就是真空态。</li>
 * </ol>
 *
 * <p><b>token 口径</b>：这里的 input/output tokens 是<strong>模型真实 usage</strong>，
 * 与 {@code ai_message.token_count}（字数估算）**不是一回事**，页面不把两者混着显示。
 * 库里没有 usage 时它显示 0，而不是拿字数估算顶上。</p>
 *
 * <p><b>趋势为什么还配一张明细表</b>：图表好看但不能核对。管理员（和验收）需要"哪天、多少轮、
 * 多少 token"这些**能对上账的数字**，所以同一批点在图表下方以表格呈现，两者同源同窗口。</p>
 */
const RANGE_OPTIONS: { value: AiMetricsRange; label: string }[] = [
  { value: '24h', label: '近 24 小时' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' }
]

/** 趋势天数候选：后端收敛到 1~90，这里只给常用档位（避免手输非法值）。 */
const TREND_DAYS_OPTIONS = [7, 14, 30]

/** 提案状态中文映射（后端返回原始枚举，展示层映射只在前端做）。 */
const PROPOSAL_STATUS_LABELS: Record<string, string> = {
  PENDING: '待确认',
  CONFIRMED: '已确认',
  EXECUTED: '已执行',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
  FAILED: '执行失败',
  CANCELLED: '已取消'
}

/** 提案状态标签配色（与后端枚举同值域）。 */
const PROPOSAL_STATUS_TAGS: Record<string, 'primary' | 'success' | 'info' | 'warning' | 'danger'> = {
  PENDING: 'warning',
  CONFIRMED: 'primary',
  EXECUTED: 'success',
  REJECTED: 'info',
  EXPIRED: 'info',
  FAILED: 'danger',
  CANCELLED: 'info'
}

const loading = ref(false)
const trendLoading = ref(false)
const toolsLoading = ref(false)
const range = ref<AiMetricsRange>('24h')
const trendDays = ref(7)
/** 后端归一后的实际窗口标签（页面显示这个，而不是 `range`）。 */
const effectiveRange = ref<AiMetricsRange>('24h')
const overview = ref<AiTurnMetricOverview | null>(null)
const proposals = ref<ProposalStatusStat[]>([])
const trend = ref<AiTurnMetricTrendPoint[]>([])
const tools = ref<AiToolCallStat[]>([])

function rangeLabel(value: AiMetricsRange): string {
  return RANGE_OPTIONS.find((item) => item.value === value)?.label ?? value
}

function formatRate(value: number | undefined): string {
  return `${((value ?? 0) * 100).toFixed(1)}%`
}

function formatMs(value: number | undefined): string {
  const ms = value ?? 0
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms.toFixed(0)}ms`
}

function formatNumber(value: number | undefined): string {
  return (value ?? 0).toLocaleString('zh-CN')
}

const proposalTotal = computed(() => proposals.value.reduce((sum, item) => sum + item.statusCount, 0))

const statItems = computed<StatCardItem[]>(() => {
  const data = overview.value
  return [
    { label: '问答数', value: formatNumber(data?.turns), unit: '次', color: '#409eff' },
    { label: '失败数', value: formatNumber(data?.errorTurns), unit: '次', color: '#f56c6c' },
    { label: '失败率', value: formatRate(data?.errorRate), color: '#f56c6c' },
    { label: '触顶数', value: formatNumber(data?.cappedTurns), unit: '次', color: '#e6a23c' },
    { label: '触顶率', value: formatRate(data?.cappedRate), color: '#e6a23c' },
    { label: '平均轮次', value: (data?.avgRounds ?? 0).toFixed(2) },
    { label: '平均耗时', value: formatMs(data?.avgTotalCostMs) },
    { label: '输入 token', value: formatNumber(data?.inputTokens) },
    { label: '输出 token', value: formatNumber(data?.outputTokens) },
    {
      label: 'token 合计',
      value: formatNumber((data?.inputTokens ?? 0) + (data?.outputTokens ?? 0)),
      color: '#67c23a'
    },
    { label: '提案总数', value: formatNumber(proposalTotal.value), unit: '件' },
    { label: '统计窗口', value: rangeLabel(effectiveRange.value) }
  ]
})

const trendOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['问答数', '失败数', '平均轮次', '平均耗时', 'token 合计'], top: 0, type: 'scroll' },
  grid: { left: 12, right: 20, bottom: 8, top: 46, containLabel: true },
  xAxis: { type: 'category', boundaryGap: false, data: trend.value.map((item) => item.statDate) },
  yAxis: [
    { type: 'value', name: '次数 / 轮次', minInterval: 0 },
    {
      type: 'value',
      name: '耗时 / token',
      axisLabel: { formatter: (value: number) => (value >= 1000 ? `${value / 1000}k` : `${value}`) }
    }
  ],
  series: [
    {
      name: '问答数',
      type: 'line',
      smooth: true,
      yAxisIndex: 0,
      itemStyle: { color: '#409eff' },
      areaStyle: { opacity: 0.12 },
      data: trend.value.map((item) => item.turns)
    },
    {
      name: '失败数',
      type: 'line',
      smooth: true,
      yAxisIndex: 0,
      itemStyle: { color: '#f56c6c' },
      data: trend.value.map((item) => item.errorTurns)
    },
    {
      name: '平均轮次',
      type: 'line',
      smooth: true,
      yAxisIndex: 0,
      itemStyle: { color: '#e6a23c' },
      data: trend.value.map((item) => Number(item.avgRounds.toFixed(2)))
    },
    {
      name: '平均耗时',
      type: 'line',
      smooth: true,
      yAxisIndex: 1,
      itemStyle: { color: '#909399' },
      data: trend.value.map((item) => Math.round(item.avgTotalCostMs))
    },
    {
      name: 'token 合计',
      type: 'line',
      smooth: true,
      yAxisIndex: 1,
      itemStyle: { color: '#67c23a' },
      data: trend.value.map((item) => item.inputTokens + item.outputTokens)
    }
  ]
}))

async function loadOverview(): Promise<void> {
  loading.value = true
  try {
    const response = await getAiMetricsOverview(range.value)
    overview.value = response.overview
    proposals.value = response.proposals ?? []
    effectiveRange.value = response.range
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function loadTrend(): Promise<void> {
  trendLoading.value = true
  try {
    const response = await getAiMetricsTrend(trendDays.value)
    trend.value = response.points ?? []
  } catch {
    // 拦截器已提示
  } finally {
    trendLoading.value = false
  }
}

async function loadTools(): Promise<void> {
  toolsLoading.value = true
  try {
    const response = await getAiMetricsTopTools(10, range.value)
    tools.value = response.tools ?? []
  } catch {
    // 拦截器已提示
  } finally {
    toolsLoading.value = false
  }
}

async function reload(): Promise<void> {
  await Promise.all([loadOverview(), loadTrend(), loadTools()])
}

onMounted(() => {
  void reload()
})
</script>

<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <el-form label-width="82px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="统计窗口">
              <el-radio-group v-model="range" :disabled="loading" @change="reload">
                <el-radio-button v-for="item in RANGE_OPTIONS" :key="item.value" :value="item.value">
                  {{ item.label }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="趋势天数">
              <el-select v-model="trendDays" :disabled="trendLoading" @change="loadTrend">
                <el-option v-for="day in TREND_DAYS_OPTIONS" :key="day" :label="`近 ${day} 天`" :value="day" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" class="filter-actions">
            <el-form-item label-width="0">
              <el-button type="primary" icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <!--
        口径提示放在筛选卡里，而不是 tooltip 里：看到 0 token 的人第一反应是"是不是坏了"，
        必须当场告诉他这是"模型未返回 usage"，而不是页面没取到数。
      -->
      <div class="range-hint">
        口径：数据来自 <code>ai_turn_metric</code>（一次问答一行）。token 为<strong>模型真实 usage</strong>，
        与消息字数估算不同源；趋势只显示库中真实存在的日期，不补零。当前窗口：
        <el-tag size="small" type="info">{{ rangeLabel(effectiveRange) }}</el-tag>
      </div>
    </el-card>

    <StatCards :items="statItems" :span="6" />

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>按天趋势（近 {{ trendDays }} 天）</span>
          <el-tag v-if="trend.length === 0 && !trendLoading" size="small" type="info">暂无数据</el-tag>
        </div>
      </template>
      <ChartPanel :option="trendOption" :loading="trendLoading" :empty="trend.length === 0" :height="320" />
      <el-table :data="trend" size="small" class="detail-table" :show-header="trend.length > 0">
        <el-table-column prop="statDate" label="日期" width="120" />
        <el-table-column prop="turns" label="问答数" width="90" />
        <el-table-column prop="errorTurns" label="失败数" width="90" />
        <el-table-column prop="cappedTurns" label="触顶数" width="90" />
        <el-table-column label="平均轮次" width="100">
          <template #default="{ row }">{{ Number(row.avgRounds).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="平均耗时" width="110">
          <template #default="{ row }">{{ formatMs(row.avgTotalCostMs) }}</template>
        </el-table-column>
        <el-table-column prop="inputTokens" label="输入 token" width="120" />
        <el-table-column prop="outputTokens" label="输出 token" width="120" />
      </el-table>
    </el-card>

    <el-row :gutter="12">
      <el-col :xs="24" :md="16">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>Top 工具（{{ rangeLabel(effectiveRange) }}，按调用次数）</span>
              <el-tag v-if="tools.length === 0 && !toolsLoading" size="small" type="info">暂无数据</el-tag>
            </div>
          </template>
          <el-table v-loading="toolsLoading" :data="tools" size="small">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="toolName" label="工具" min-width="180" />
            <el-table-column prop="calls" label="调用次数" width="100" sortable />
            <el-table-column label="平均耗时" width="110">
              <template #default="{ row }">{{ formatMs(row.avgDurationMs) }}</template>
            </el-table-column>
            <el-table-column label="最大耗时" width="110">
              <template #default="{ row }">{{ formatMs(row.maxDurationMs) }}</template>
            </el-table-column>
            <el-table-column label="p95 耗时" width="110">
              <template #default="{ row }">{{ formatMs(row.p95DurationMs) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>提案状态（{{ rangeLabel(effectiveRange) }}）</span>
              <el-tag v-if="proposals.length === 0 && !loading" size="small" type="info">暂无数据</el-tag>
            </div>
          </template>
          <el-table v-loading="loading" :data="proposals" size="small">
            <el-table-column label="状态" min-width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="PROPOSAL_STATUS_TAGS[row.status] ?? 'info'">
                  {{ PROPOSAL_STATUS_LABELS[row.status] ?? row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="statusCount" label="数量" width="90" />
            <el-table-column label="占比" width="90">
              <template #default="{ row }">
                {{ proposalTotal === 0 ? '—' : `${((row.statusCount / proposalTotal) * 100).toFixed(1)}%` }}
              </template>
            </el-table-column>
          </el-table>
          <div class="proposal-total">合计：{{ formatNumber(proposalTotal) }} 件</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 12px;
}

.filter-actions :deep(.el-form-item) {
  margin-bottom: 0;
}

.range-hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.7;
  margin-top: 4px;
}

.range-hint code {
  background: #f5f7fa;
  padding: 0 4px;
  border-radius: 3px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.el-card {
  margin-bottom: 12px;
}

.detail-table {
  margin-top: 8px;
}

.proposal-total {
  margin-top: 8px;
  font-size: 13px;
  color: #606266;
  text-align: right;
}
</style>
