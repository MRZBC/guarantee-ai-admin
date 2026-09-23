<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pageOperationAudits } from '@/api/ai'
import { useUserStore } from '@/stores/user'
import { formatDateTime, truncate } from '@/utils/format'
import {
  AUDIT_ACTION_OPTIONS,
  AUDIT_DEFAULT_DAYS,
  AUDIT_LIMIT_OPTIONS,
  AUDIT_MAX_RANGE_DAYS,
  AUDIT_RANGE_WARN_DAYS,
  AUDIT_RESULT_OPTIONS,
  AUDIT_SENSITIVE_PLACEHOLDER,
  AUDIT_SOURCE_OPTIONS,
  AUDIT_TARGET_TYPES_ADMIN,
  AUDIT_TARGET_TYPES_NON_ADMIN,
  auditActionLabel,
  auditResultLabel,
  auditResultTag,
  auditSourceLabel,
  auditSourceTag,
  auditTargetTypeLabel,
  buildSnapshotDiff,
  diffContainsSensitive,
  noSnapshotHint,
  splitChangedFields,
  type AuditDiffRow
} from '@/utils/auditDict'
import type { OperationAuditItem, OperationAuditQuery } from '@/types/ai'

/**
 * 操作审计页（P-07）。
 *
 * <p><b>为什么要有这个页面</b>：审计的写入链路是完整的（助手确认 `source=AI` +
 * 页面直连 `source=WEB` 写同一张表），但在本页之前**没有任何页面能看它**——
 * 唯一入口是助手的 `queryOperationAudit` 工具（仅 ADMIN）。
 * 等于"系统里最像证据的那张表，只能靠问 AI 才能看到"。</p>
 *
 * <p><b>三条来自接口契约的硬约束</b>：</p>
 * <ol>
 *   <li>时间区间必填且跨度 ≤ 90 天（SYS-A-17）——前端先挡，不让用户吃到后端报错；</li>
 *   <li><b>不支持翻页</b>（后端 offset 恒为 0），`limit` 是"最多取多少条"，最大 200
 *       → 页面必须如实告诉用户"最多显示最近 N 条"，而不是假装有下一页；</li>
 *   <li>非 ADMIN 只能看 `USER/ORG/DEPT` 三类（SYS-A-10），显式查 ROLE/PERMISSION 会 403
 *       → 目标类型下拉按 ADMIN / 非 ADMIN 给两套选项。</li>
 * </ol>
 *
 * <p><b>展示口径</b>：中英映射与 JSON 解析全部在前端完成（既有方案 §10.4 已决议
 * "审计查询接口不增删改"），因此本页不依赖任何后端改动。</p>
 */
const userStore = useUserStore()

/** 是否 ADMIN：决定"目标类型"能选哪些（后端白名单）。 */
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

const targetTypeOptions = computed(() =>
  isAdmin.value ? [...AUDIT_TARGET_TYPES_ADMIN] : [...AUDIT_TARGET_TYPES_NON_ADMIN]
)

const loading = ref(false)
const rows = ref<OperationAuditItem[]>([])
const total = ref(0)

const query = reactive({
  operatorUsername: '',
  targetType: '',
  action: '',
  result: '',
  source: '',
  /**
   * 条数上限：数字 = 最多取多少条；`'ALL'` = 全部（不设上限）。
   *
   * 二者是**不同语义**，不能只靠传一个大数字实现：后端 `clampLimit` 会把任何 >200 的值收敛回 200，
   * 那样页面写着"全部"却只拿到 200 条。所以「全部」走独立的 `all=true` 参数（后端已支持）。
   */
  limitMode: 50 as number | 'ALL'
})

// ------------------------------------------------------------------
// 时间区间：默认最近 7 天，跨度 ≤ 90 天
// ------------------------------------------------------------------

/** 与后端 `endDate.atTime(23,59,59)` 的口径一致：按自然日算跨度。 */
function startOfDay(date: Date): Date {
  const copied = new Date(date)
  copied.setHours(0, 0, 0, 0)
  return copied
}

function diffDays(start: Date, end: Date): number {
  return Math.round((startOfDay(end).getTime() - startOfDay(start).getTime()) / 86400000)
}

function defaultRange(): [Date, Date] {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - (AUDIT_DEFAULT_DAYS - 1))
  return [start, end]
}

function toDateParam(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

const dateRange = ref<[Date, Date] | null>(defaultRange())

/** 选区间时的锚点（第一个日期），用于把可选范围限制在锚点 ±90 天内。 */
const pickerAnchor = ref<Date | null>(null)

function handleCalendarChange(value: unknown): void {
  const list = Array.isArray(value) ? value : []
  const first = list[0]
  pickerAnchor.value = first instanceof Date ? startOfDay(first) : null
}

function disabledDate(date: Date): boolean {
  const today = new Date()
  today.setHours(23, 59, 59, 999)
  if (date.getTime() > today.getTime()) {
    return true
  }
  if (pickerAnchor.value && Math.abs(diffDays(pickerAnchor.value, date)) > AUDIT_MAX_RANGE_DAYS) {
    return true
  }
  return false
}

const selectedDays = computed(() => {
  const range = dateRange.value
  if (!range || !range[0] || !range[1]) return 0
  return diffDays(range[0], range[1]) + 1
})

const nearRangeLimit = computed(
  () => selectedDays.value > AUDIT_RANGE_WARN_DAYS && selectedDays.value <= AUDIT_MAX_RANGE_DAYS + 1
)

// ------------------------------------------------------------------
// 查询
// ------------------------------------------------------------------

async function handleSearch(): Promise<void> {
  const range = dateRange.value
  if (!range || !range[0] || !range[1]) {
    ElMessage.warning('请先选择时间区间（必填）')
    return
  }
  const days = diffDays(range[0], range[1])
  if (days < 0) {
    ElMessage.warning('起始日期不能晚于结束日期')
    return
  }
  if (days > AUDIT_MAX_RANGE_DAYS) {
    ElMessage.warning(
      `时间跨度最大 ${AUDIT_MAX_RANGE_DAYS} 天，当前为 ${days + 1} 天，请收窄区间后重试`
    )
    return
  }

  const params: OperationAuditQuery = {
    startDate: toDateParam(range[0]),
    endDate: toDateParam(range[1]),
    operatorUsername: query.operatorUsername.trim() || undefined,
    targetType: query.targetType || undefined,
    action: query.action || undefined,
    result: query.result || undefined,
    source: query.source || undefined,
    // 「全部」用独立开关表达；选了它就不传 limit（后端 all=true 时不设上限）
    ...(query.limitMode === 'ALL' ? { all: true } : { limit: query.limitMode })
  }

  loading.value = true
  try {
    const page = await pageOperationAudits(params)
    rows.value = page.items ?? []
    total.value = page.total ?? 0
  } catch {
    // 错误提示由 request.ts 统一处理（避免双弹窗）；这里保留上一次结果，
    // 让用户能对照"刚才看的是什么条件"而不是整屏变空。
  } finally {
    loading.value = false
  }
}

function handleReset(): void {
  dateRange.value = defaultRange()
  pickerAnchor.value = null
  query.operatorUsername = ''
  query.targetType = ''
  query.action = ''
  query.result = ''
  query.source = ''
  query.limitMode = 50
  void handleSearch()
}

onMounted(() => {
  void handleSearch()
})

// ------------------------------------------------------------------
// 详情抽屉
// ------------------------------------------------------------------

const detailVisible = ref(false)
const detail = ref<OperationAuditItem | null>(null)

const detailDiff = computed<AuditDiffRow[]>(() =>
  detail.value ? buildSnapshotDiff(detail.value.beforeValue, detail.value.afterValue) : []
)

const detailHasSensitive = computed(() => diffContainsSensitive(detailDiff.value))
const detailNoSnapshotHint = computed(() => (detail.value ? noSnapshotHint(detail.value) : ''))

function openDetail(row: OperationAuditItem): void {
  detail.value = row
  detailVisible.value = true
}

/** 该行是否有前后值可看（两个快照都为空时没有）。 */
function hasSnapshot(row: OperationAuditItem): boolean {
  return Boolean(row.beforeValue) || Boolean(row.afterValue)
}

/**
 * 没有快照时的单元格文案。
 *
 * <p>刻意只做字符串判断、**不在这里 JSON.parse**：Element Plus 的单元格渲染一旦抛异常
 * 会打断整个 tbody（表现为"只有『共 N 条』、一行都不显示"），
 * 因此渲染路径上只允许最廉价的判断。</p>
 */
function snapshotCellText(row: OperationAuditItem): string {
  const result = String(row.result ?? '').toUpperCase()
  if (result === 'EXPIRED' || result === 'REJECTED' || result === 'FAILED') {
    return '无（未执行）'
  }
  if (String(row.action ?? '').toUpperCase() === 'PROPOSAL_CREATED') {
    return '无（提案创建）'
  }
  return '无'
}

function operatorText(row: OperationAuditItem): string {
  const realName = row.operatorRealName?.trim()
  const username = row.operatorUsername?.trim()
  if (realName && username) return `${realName}（${username}）`
  return realName || username || '--'
}

function targetText(row: OperationAuditItem): string {
  const type = auditTargetTypeLabel(row.targetType)
  const name = row.targetName?.trim()
  const id = row.targetId ?? null
  if (name && id !== null) return `${type} · ${name}（${id}）`
  if (name) return `${type} · ${name}`
  if (id !== null) return `${type} · #${id}`
  return type
}

async function copyText(value: unknown, label: string): Promise<void> {
  const text = value === null || value === undefined ? '' : String(value)
  if (text === '') return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label} 已复制`)
  } catch {
    ElMessage.warning('当前浏览器不允许自动复制，请手动选中复制')
  }
}
</script>

<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <el-form :model="query" label-width="82px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="10" :lg="8">
            <el-form-item label="时间区间">
              <el-date-picker
                v-model="dateRange"
                type="daterange"
                unlink-panels
                style="width: 100%"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                :disabled-date="disabledDate"
                @calendar-change="handleCalendarChange"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="操作人账号">
              <el-input
                v-model="query.operatorUsername"
                placeholder="按账号模糊匹配（非姓名）"
                clearable
                @keyup.enter="handleSearch"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="目标类型">
              <el-select v-model="query.targetType" placeholder="全部类型" clearable>
                <el-option
                  v-for="code in targetTypeOptions"
                  :key="code"
                  :label="auditTargetTypeLabel(code)"
                  :value="code"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="动作">
              <el-select v-model="query.action" placeholder="全部动作" clearable>
                <el-option
                  v-for="code in AUDIT_ACTION_OPTIONS"
                  :key="code"
                  :label="auditActionLabel(code)"
                  :value="code"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="结果">
              <el-select v-model="query.result" placeholder="全部结果" clearable>
                <el-option
                  v-for="code in AUDIT_RESULT_OPTIONS"
                  :key="code"
                  :label="auditResultLabel(code)"
                  :value="code"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="渠道">
              <el-select v-model="query.source" placeholder="全部渠道" clearable>
                <el-option
                  v-for="code in AUDIT_SOURCE_OPTIONS"
                  :key="code"
                  :label="auditSourceLabel(code)"
                  :value="code"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8" :lg="6">
            <el-form-item label="条数上限">
              <el-select v-model="query.limitMode">
                <el-option v-for="n in AUDIT_LIMIT_OPTIONS" :key="n" :label="`${n} 条`" :value="n" />
                <el-option label="全部" value="ALL" />
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

      <el-alert
        v-if="nearRangeLimit"
        class="audit-range-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="`当前跨度 ${selectedDays} 天，已接近查询上限 ${AUDIT_MAX_RANGE_DAYS} 天`"
      />
    </el-card>

    <el-card class="table-card" shadow="never">
      <div class="table-toolbar">
        <span class="table-toolbar__title">
          操作审计 · 共 {{ total }} 条
          <!-- 只在"没显示完"时补一句：选了较小上限、或后端未支持「全部」时，用户能立刻看出差异，
               而不是以为自己看到了全部。数字全部展示时不加任何提示。 -->
          <span v-if="rows.length < total" class="text-muted">· 已显示 {{ rows.length }} 条</span>
        </span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe height="520" row-key="id">
        <el-table-column type="index" label="#" width="52" align="center" fixed />
        <el-table-column prop="operatedAt" label="操作时间" width="170" align="center" fixed>
          <template #default="{ row }">{{ formatDateTime(row.operatedAt) }}</template>
        </el-table-column>
        <el-table-column prop="operatorRealName" label="操作人" width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ operatorText(row) }}</template>
        </el-table-column>
        <el-table-column prop="source" label="渠道" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="auditSourceTag(row.source)" size="small">
              {{ auditSourceLabel(row.source) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="action" label="动作" width="120" align="center">
          <template #default="{ row }">{{ auditActionLabel(row.action) }}</template>
        </el-table-column>
        <el-table-column prop="targetName" label="目标" min-width="210" show-overflow-tooltip>
          <template #default="{ row }">{{ targetText(row) }}</template>
        </el-table-column>
        <el-table-column prop="changedFields" label="变更字段" min-width="170">
          <template #default="{ row }">
            <template v-if="splitChangedFields(row.changedFields).length > 0">
              <el-tag
                v-for="field in splitChangedFields(row.changedFields)"
                :key="field"
                class="audit-field-tag"
                size="small"
                type="info"
              >
                {{ field }}
              </el-tag>
            </template>
            <span v-else class="text-muted">--</span>
          </template>
        </el-table-column>
        <el-table-column prop="result" label="结果" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="auditResultTag(row.result)" size="small">
              {{ auditResultLabel(row.result) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="原因" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorMessage">{{ truncate(row.errorMessage, 40) }}</span>
            <span v-else class="text-muted">--</span>
          </template>
        </el-table-column>
        <el-table-column prop="beforeValue" label="前后值" width="130" align="center">
          <template #default="{ row }">
            <el-button v-if="hasSnapshot(row)" link type="primary" @click="openDetail(row)">查看</el-button>
            <span v-else class="text-muted">{{ snapshotCellText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="详情" width="80" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="该条件下没有审计记录，可放宽时间区间或清空筛选" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <el-drawer v-model="detailVisible" :title="`审计详情 #${detail?.id ?? ''}`" size="640px">
      <div v-if="detail" class="audit-detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="操作时间">
            {{ formatDateTime(detail.operatedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="操作人">
            {{ operatorText(detail) }}
            <span v-if="detail.operatorUserId" class="text-muted">（用户 id：{{ detail.operatorUserId }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="渠道">
            <el-tag :type="auditSourceTag(detail.source)" size="small">
              {{ auditSourceLabel(detail.source) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="动作">{{ auditActionLabel(detail.action) }}</el-descriptions-item>
          <el-descriptions-item label="目标">{{ targetText(detail) }}</el-descriptions-item>
          <el-descriptions-item label="结果">
            <el-tag :type="auditResultTag(detail.result)" size="small">
              {{ auditResultLabel(detail.result) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.errorMessage" label="原因">
            {{ detail.errorMessage }}
          </el-descriptions-item>
          <el-descriptions-item label="变更字段">
            <template v-if="splitChangedFields(detail.changedFields).length > 0">
              <el-tag
                v-for="field in splitChangedFields(detail.changedFields)"
                :key="field"
                class="audit-field-tag"
                size="small"
                type="info"
              >
                {{ field }}
              </el-tag>
            </template>
            <span v-else class="text-muted">--</span>
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="audit-detail__title">字段级变更</h4>
        <el-table v-if="detailDiff.length > 0" :data="detailDiff" size="small" border>
          <el-table-column prop="field" label="字段" width="150">
            <template #default="{ row }">
              <span class="mono">{{ row.field }}</span>
              <el-tag v-if="row.changed" class="audit-field-tag" size="small" type="warning">已变更</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="before" label="原值" min-width="150" show-overflow-tooltip />
          <el-table-column prop="after" label="新值" min-width="150" show-overflow-tooltip />
        </el-table>
        <el-alert
          v-else
          class="audit-range-alert"
          type="info"
          :closable="false"
          show-icon
          :title="detailNoSnapshotHint"
        />

        <el-alert
          v-if="detailHasSensitive"
          class="audit-range-alert"
          type="warning"
          :closable="false"
          show-icon
          title="该字段为敏感字段，审计只记录是否变更，不记录具体值（D-4）"
          :description="`出现 ${AUDIT_SENSITIVE_PLACEHOLDER} 的字段属于手机号/邮箱等敏感信息，这是系统设计而非数据缺失。`"
        />

        <h4 class="audit-detail__title">可回溯信息</h4>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="TraceId">
            <span class="mono">{{ detail.traceId || '--' }}</span>
            <el-button
              v-if="detail.traceId"
              class="audit-copy"
              link
              type="primary"
              icon="CopyDocument"
              @click="copyText(detail.traceId, 'TraceId')"
            />
          </el-descriptions-item>
          <el-descriptions-item label="提案 id">
            <span class="mono">{{ detail.proposalId ?? '--' }}</span>
            <el-button
              v-if="detail.proposalId"
              class="audit-copy"
              link
              type="primary"
              icon="CopyDocument"
              @click="copyText(detail.proposalId, '提案 id')"
            />
            <span class="text-muted">（助手渠道的变更才有）</span>
          </el-descriptions-item>
          <el-descriptions-item label="会话 id">
            <span class="mono">{{ detail.conversationId ?? '--' }}</span>
            <el-button
              v-if="detail.conversationId"
              class="audit-copy"
              link
              type="primary"
              icon="CopyDocument"
              @click="copyText(detail.conversationId, '会话 id')"
            />
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.audit-range-alert {
  margin-top: 12px;
}

.audit-field-tag {
  margin: 0 4px 2px 0;
}

.audit-detail__title {
  margin: 18px 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.audit-copy {
  margin-left: 6px;
}
</style>
