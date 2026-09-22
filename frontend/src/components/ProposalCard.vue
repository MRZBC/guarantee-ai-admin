<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { ProposalPayload, ProposalStatus } from '@/types/ai'

/**
 * 变更确认卡（SYS-C-11 ~ SYS-C-15 / 5.3.3）。
 *
 * 设计要点：
 * - **载荷完全来自后端**：卡片只渲染 props，不拼装任何参数（SYS-C-11），
 *   也不支持在卡片上编辑参数（SYS-C-12：改参数=重新说一遍）。
 * - **倒计时**：15 分钟有效期，倒计时结束自动禁用按钮并提示过期（AC-17）。
 * - **二次确认**：危险动作（停用、角色变更）在执行前再弹一次确认（AC-19 的体验侧）。
 * - **串行处理**：执行中禁止再次点击；父组件负责多卡串行的约束（SYS-C-13）。
 */
const props = defineProps<{
  proposal: ProposalPayload
  /** 是否正在执行中（父组件维护，用于禁用其它卡片） */
  busy?: boolean
  /** 是否已被其它卡片占用（串行处理，SYS-C-13） */
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm', proposalId: number): void
  (e: 'reject', proposalId: number, reason: string): void
}>()

/** 本地状态：执行结果由父组件通过 proposal.status 回流，这里只维护交互态。 */
const executing = ref(false)
const rejected = ref(false)
const rejectReason = ref('')
const now = ref(Date.now())
let timer: number | null = null

const DANGEROUS_ACTIONS = ['DISABLE', 'ASSIGN_ROLES', 'ASSIGN_PERMISSIONS']

const isDangerous = computed(
  () => props.proposal.dangerous || DANGEROUS_ACTIONS.includes(props.proposal.action)
)

const expiry = computed(() => {
  const raw = props.proposal.expiresAt
  if (!raw) return null
  // 后端返回的是 "yyyy-MM-dd HH:mm:ss"（jackson date-format），
  // Safari 不接受空格分隔，统一替换为 T 再解析。
  const normalized = raw.includes('T') ? raw : raw.replace(' ', 'T')
  const time = new Date(normalized).getTime()
  return Number.isNaN(time) ? null : time
})

const remainingMs = computed(() => {
  if (!expiry.value) return 0
  return Math.max(0, expiry.value - now.value)
})

const expired = computed(() => remainingMs.value <= 0)

const remainingText = computed(() => {
  if (!expiry.value) return '有效期未知'
  if (expired.value) return '已过期'
  const total = Math.floor(remainingMs.value / 1000)
  const mm = String(Math.floor(total / 60)).padStart(2, '0')
  const ss = String(total % 60).padStart(2, '0')
  return `${mm}:${ss}`
})

/** 已终态：展示结果，不再提供操作（AC-15 / AC-16 / AC-17）。 */
const terminalStatus = computed<ProposalStatus | null>(() => {
  const status = (props.proposal.status || '').toUpperCase()
  if (status === 'PENDING' || status === 'EXECUTING' || status === '') return null
  return status
})

const statusType = computed<'success' | 'danger' | 'info' | 'warning'>(() => {
  switch (terminalStatus.value) {
    case 'EXECUTED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'INVALIDATED':
      return 'warning'
    default:
      return 'info'
  }
})

const statusText = computed(() => {
  switch (terminalStatus.value) {
    case 'EXECUTED':
      return '执行成功'
    case 'FAILED':
      return '执行失败'
    case 'REJECTED':
      return '已拒绝'
    case 'EXPIRED':
      return '已过期'
    case 'INVALIDATED':
      return '已失效（权限已变更）'
    case 'EXECUTING':
      return '执行中…'
    default:
      return ''
  }
})

const canOperate = computed(
  () => terminalStatus.value === null && !expired.value && !executing.value && !rejected.value
)

const buttonsDisabled = computed(
  () => !canOperate.value || props.busy === true || props.disabled === true
)

function startTimer(): void {
  stopTimer()
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
}

function stopTimer(): void {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
}

function handleConfirm(): void {
  if (buttonsDisabled.value) return
  if (isDangerous.value) {
    // 危险动作二次确认（SYS-C-12 / 5.3.3 风险提示）
    const ok = window.confirm(
      `这是危险操作：${props.proposal.actionName} ${props.proposal.targetTypeName}` +
        `${props.proposal.targetName ? `「${props.proposal.targetName}」` : ''}。\n` +
        `影响面：${props.proposal.impact.join('；') || '未提供'}\n\n确认执行吗？`
    )
    if (!ok) return
  }
  executing.value = true
  emit('confirm', props.proposal.proposalId)
}

function handleReject(): void {
  if (buttonsDisabled.value) return
  emit('reject', props.proposal.proposalId, rejectReason.value)
  rejected.value = true
}

/** 父组件执行结束后需要把 executing 复位（通过 busy 变化识别）。 */
watch(
  () => props.busy,
  (busy) => {
    if (!busy) executing.value = false
  }
)

watch(
  () => props.proposal.proposalId,
  () => {
    executing.value = false
    rejected.value = false
    rejectReason.value = ''
    now.value = Date.now()
    startTimer()
  },
  { immediate: true }
)

onBeforeUnmount(stopTimer)
</script>

<template>
  <div class="proposal-card" :class="{ 'is-dangerous': isDangerous, 'is-terminal': terminalStatus }">
    <div class="proposal-card__header">
      <div class="proposal-card__title">
        <el-tag :type="isDangerous ? 'danger' : 'primary'" size="small" effect="dark">
          {{ isDangerous ? '危险操作' : '待确认变更' }}
        </el-tag>
        <span class="proposal-card__action">{{ proposal.actionName }}{{ proposal.targetTypeName }}</span>
        <span v-if="proposal.targetName" class="proposal-card__target">
          — {{ proposal.targetName }}
        </span>
      </div>
      <div class="proposal-card__timer" :class="{ 'is-expired': expired }">
        <template v-if="terminalStatus"> {{ statusText }} </template>
        <template v-else> 15 分钟内有效（剩余 {{ remainingText }}） </template>
      </div>
    </div>

    <div class="proposal-card__summary">{{ proposal.summary }}</div>

    <!-- 变更明细：字段 / 中文标签 / 原值 / 新值；新增动作只展示新值 -->
    <el-table
      v-if="proposal.changes && proposal.changes.length"
      :data="proposal.changes"
      size="small"
      border
      class="proposal-card__table"
    >
      <el-table-column prop="label" label="字段" width="120" />
      <el-table-column label="原值" min-width="120">
        <template #default="{ row }">
          <span :class="{ 'text-muted': row.before === null }">{{ row.before ?? '（新增）' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="新值" min-width="140">
        <template #default="{ row }">
          <span :class="{ 'text-muted': row.after === null }">{{ row.after ?? '（清空）' }}</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 影响面：高亮文案 -->
    <div v-if="proposal.impact && proposal.impact.length" class="proposal-card__impact">
      <div v-for="(item, index) in proposal.impact" :key="index">• {{ item }}</div>
    </div>

    <!-- 风险提示 -->
    <el-alert
      v-for="(warning, index) in proposal.warnings"
      :key="index"
      :title="warning"
      type="warning"
      show-icon
      :closable="false"
      class="proposal-card__warning"
    />

    <!-- 用户原话与解析参数同时可见（SYS-C-15） -->
    <div v-if="proposal.userText" class="proposal-card__origin">
      <span class="proposal-card__origin-label">你的原话：</span>
      <span>{{ proposal.userText }}</span>
    </div>

    <div v-if="terminalStatus" class="proposal-card__result">
      <el-tag :type="statusType" size="small">{{ statusText }}</el-tag>
      <span class="text-muted">提案号 {{ proposal.proposalNo }}</span>
    </div>

    <div v-else class="proposal-card__footer">
      <el-input
        v-model="rejectReason"
        size="small"
        placeholder="拒绝原因（可选）"
        :disabled="buttonsDisabled"
        class="proposal-card__reason"
      />
      <div class="proposal-card__buttons">
        <el-button
          size="small"
          type="danger"
          plain
          :disabled="buttonsDisabled"
          @click="handleReject"
        >
          拒绝
        </el-button>
        <el-button
          size="small"
          :type="isDangerous ? 'danger' : 'primary'"
          :loading="executing || proposal.status === 'EXECUTING'"
          :disabled="buttonsDisabled"
          @click="handleConfirm"
        >
          确认执行
        </el-button>
      </div>
      <div v-if="expired && !terminalStatus" class="proposal-card__expired">
        提案已过期，请重新发起变更请求
      </div>
    </div>
  </div>
</template>

<style scoped>
.proposal-card {
  margin: 8px 0;
  padding: 12px;
  border: 1px solid #a0cfff;
  border-left: 4px solid #409eff;
  border-radius: 6px;
  background: #fff;
}

.proposal-card.is-dangerous {
  border-color: #fbc4c4;
  border-left-color: #f56c6c;
}

.proposal-card.is-terminal {
  opacity: 0.92;
}

.proposal-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.proposal-card__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.proposal-card__target {
  color: #606266;
  font-weight: 400;
}

.proposal-card__timer {
  font-size: 12px;
  color: #e6a23c;
}

.proposal-card__timer.is-expired {
  color: #f56c6c;
}

.proposal-card__summary {
  margin: 8px 0;
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
}

.proposal-card__table {
  margin: 6px 0;
}

.proposal-card__impact {
  margin: 6px 0;
  padding: 8px 10px;
  border-radius: 4px;
  background: #fdf6ec;
  color: #b88230;
  font-size: 12.5px;
  line-height: 1.7;
}

.proposal-card__warning {
  margin: 6px 0;
}

.proposal-card__origin {
  margin: 6px 0;
  font-size: 12.5px;
  color: #909399;
  line-height: 1.6;
}

.proposal-card__origin-label {
  color: #c0c4cc;
}

.proposal-card__footer {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.proposal-card__reason {
  flex: 1;
  min-width: 160px;
}

.proposal-card__buttons {
  display: flex;
  gap: 6px;
}

.proposal-card__expired {
  width: 100%;
  font-size: 12px;
  color: #f56c6c;
}

.proposal-card__result {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
}

.text-muted {
  color: #c0c4cc;
}
</style>
