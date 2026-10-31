<script setup lang="ts">
import { computed, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { generateAnalysisSummary } from '@/api/ai'
import { useUserStore } from '@/stores/user'
import { routeParams } from '@/utils/analysisNavigation'
import type { AnalysisEvidence, AnalysisSummary, OrderReport } from '@/types/analysis'

const props = defineProps<{ report: OrderReport | null; disabled: boolean }>()
const emit = defineEmits<{ reportUpdated: [report: OrderReport] }>()
const user = useUserStore()
const route = useRoute()
const router = useRouter()
const busy = ref(false)
const result = ref<AnalysisSummary | null>(null)
const notice = ref('')
let sequence = 0
let controller: AbortController | undefined
const allowed = computed(() => user.permissions.includes('ai:chat') && user.permissions.includes('analysis:overview:view'))
const canDrill = computed(() => user.permissions.includes(props.report?.comparison.criteria.orderType === 'PERFORMANCE'
  ? 'order:performance:view' : 'order:tender:view'))
const visibleEvidence = computed(() => {
  const summary = result.value
  if (!summary) return []
  const ids = new Set(['orders', 'guarantee', 'premium', 'previous', ...summary.observations.flatMap(item => item.evidenceIds)])
  return summary.evidence.filter(item => ids.has(item.id))
})
const generatedTime = computed(() => {
  const raw = result.value?.generatedAt
  if (!raw) return ''
  const date = new Date(raw)
  if (!Number.isFinite(date.getTime())) return raw
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit',
    day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(date)
})
function evidenceLabels(ids: string[]): string {
  return ids.map(id => String(visibleEvidence.value.findIndex(item => item.id === id) + 1)).join('、')
}
const scope = computed(() => {
  const c = result.value?.report.comparison
  if (!c) return ''
  return `${c.criteria.orderType === 'PERFORMANCE' ? '履约' : '投标'}订单 · ${c.criteria.startDate} 至 ${c.criteria.endDate}`
    + ` · 地区：${c.criteria.regionCode || '全部'} · 机构：${c.criteria.orgId || '全部'}`
    + ` · 比较期：${c.previousStartDate} 至 ${c.previousEndDate}`
})

function cancel(): void {
  ++sequence
  controller?.abort()
  controller = undefined
  busy.value = false
}

function clear(): void { cancel(); result.value = null; notice.value = '' }

async function generate(): Promise<void> {
  const report = props.report
  if (!report || props.disabled || busy.value || !allowed.value) return
  cancel()
  const id = sequence
  controller = new AbortController()
  busy.value = true
  result.value = null
  notice.value = ''
  try {
    const response = await generateAnalysisSummary(report.comparison.criteria, report.reportFingerprint, controller.signal)
    if (id !== sequence) return
    if (response.status === 'REPORT_CHANGED') {
      emit('reportUpdated', response.report)
      // report变化的watch会清理旧状态，刷新完成后再给出重试提示。
      await Promise.resolve()
      notice.value = '数据已更新，请重新解读'
    } else result.value = response
  } catch (error) {
    if (id === sequence) notice.value = error instanceof Error ? error.message : '解读失败，请重试'
  } finally {
    if (id === sequence) { busy.value = false; controller = undefined }
  }
}

function drill(evidence: AnalysisEvidence): void {
  const report = result.value?.report
  if (!report || !canDrill.value || !Object.keys(evidence.drilldown).length) return
  const c = report.comparison.criteria
  void router.replace({ query: routeParams(c) }).then(() => router.push({
    name: c.orderType === 'PERFORMANCE' ? 'PerformanceOrders' : 'TenderOrders',
    query: routeParams(evidence.drilldown)
  }))
}

watch(() => props.report, clear)
watch(() => props.disabled, disabled => { if (disabled) clear() })
watch(allowed, value => { if (!value) clear() })
watch(() => route.fullPath, clear)
onDeactivated(clear)
onBeforeUnmount(cancel)
</script>

<template>
  <section v-if="allowed" class="interpretation" data-testid="analysis-interpretation">
    <div class="interpretation-actions">
      <el-button type="primary" plain :loading="busy" :disabled="disabled || !report?.reportFingerprint" @click="generate">
        {{ result ? '重新生成解读' : '让助手解读' }}
      </el-button>
      <el-button v-if="busy" @click="cancel">取消等待</el-button>
      <span v-if="disabled" class="text-muted">请先完成查询，再解读已应用的筛选结果</span>
    </div>
    <p v-if="busy" role="status">正在根据本次报告整理关注点…</p>
    <el-alert v-if="notice" :title="notice" type="warning" :closable="false" />
    <div v-if="result" data-testid="analysis-interpretation-result">
      <h3>{{ result.status === 'GENERATED' ? 'AI 分析解读' : '统计事实（未生成 AI 解读）' }}</h3>
      <p>{{ scope }}</p>
      <p class="text-muted">统计生成时间：{{ generatedTime }}（北京时间） · {{ result.message }}</p>
      <ol v-if="result.observations.length" aria-label="解读关注点">
        <li v-for="(item, index) in result.observations" :key="index">
          {{ item.text }} <span class="text-muted">依据：{{ evidenceLabels(item.evidenceIds) }}</span>
        </li>
      </ol>
      <el-alert v-for="item in result.limitations" :key="item" :title="item" type="info" :closable="false" />
      <h4>数据依据与订单核查</h4>
      <ul class="evidence-list">
        <li v-for="(item, index) in visibleEvidence" :key="item.id">
          <span>依据 {{ index + 1 }} · {{ item.text }}</span>
          <el-button v-if="canDrill && Object.keys(item.drilldown).length" link type="primary" @click="drill(item)">
            {{ item.id === 'previous' ? '查看比较期订单' : '查看对应订单' }}
          </el-button>
        </li>
      </ul>
      <p v-if="result.traceId" class="text-muted">请求编号：{{ result.traceId }}</p>
    </div>
  </section>
</template>

<style scoped>
.interpretation { margin-top: 16px; }
.interpretation-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.el-alert { margin-top: 8px; }
li { margin: 8px 0; line-height: 1.7; overflow-wrap: anywhere; }
.evidence-list { padding-left: 20px; }
</style>
