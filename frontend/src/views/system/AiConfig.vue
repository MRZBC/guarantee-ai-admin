<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeAiConfig,
  getAiConfig,
  getPromptGate,
  getPromptVersion,
  listPromptVersions,
  pageOperationAudits,
  publishPrompt,
  rollbackPrompt,
  savePromptDraft
} from '@/api/ai'
import { useUserStore } from '@/stores/user'
import type {
  AiConfigItemView,
  AiConfigView,
  OperationAuditItem,
  PromptDetailView,
  PromptGateView,
  PromptHistoryView,
  PromptVersionView
} from '@/types/ai'
import {
  auditActionLabel,
  auditResultLabel,
  auditResultTag,
  buildSnapshotDiff,
  noSnapshotHint,
  splitChangedFields
} from '@/utils/auditDict'

/**
 * 「系统配置 → AI 配置」页（第四阶段 REQ-CFG-08，T4-04）。
 *
 * <p><b>四页签</b>：模型 / 提示词 / 能力开关 / 变更历史。</p>
 *
 * <p><b>三条硬口径</b>：</p>
 * <ol>
 *   <li><b>写操作一律"先确认后提交"</b>：危险配置（改模型/温度/关工具/改提示词）
 *       先弹 `ElMessageBox` 二次确认；服务端还有 `ai:config:update` 兜底，前端隐藏按钮不算数。</li>
 *   <li><b>密钥只显示"已配置/未配置 + 引用名"</b>：接口不返回密钥值，
 *       页面也没有任何输入框能写入密钥值（只允许改引用名）。</li>
 *   <li><b>门禁三态如实显示</b>：PASSED / FAILED / NOT_RUN；`ran=false` 就是"未跑"，
 *       显示为"未跑"且**禁止发布**——绝不能把"没跑"显示成"通过"（AC-CFG-10）。</li>
 * </ol>
 *
 * <p><b>保护段落不在这份代码里重复定义</b>：受保护标记的真源在后端
 * `PromptVersionService.PROTECTED_MARKERS`，页面通过"保存草稿"返回的
 * `missingProtectedMarkers` 显示缺失项——前端再抄一份清单必然漂移。</p>
 *
 * <p><b>变更历史</b>复用操作审计接口（`targetType=AI_CONFIG`）：审计是同一张表、
 * 同一套字典（`utils/auditDict.ts`），不为配置单独造一套历史。</p>
 */
const userStore = useUserStore()

const canUpdate = computed(() => userStore.permissions.includes('ai:config:update'))

const activeTab = ref('model')

/* ---------------- 配置项 ---------------- */

const configLoading = ref(false)
const config = ref<AiConfigView | null>(null)
/** 编辑对话框 */
const editVisible = ref(false)
const editItem = ref<AiConfigItemView | null>(null)
const editValue = ref('')
const saving = ref(false)

const modelItems = computed(() =>
  (config.value?.items ?? []).filter((item) => item.category === 'MODEL' && !item.secretClass)
)
const secretItems = computed(() => (config.value?.items ?? []).filter((item) => item.secretClass))
const switchItems = computed(() =>
  (config.value?.items ?? []).filter((item) => item.category === 'SWITCH')
)
const budgetItems = computed(() =>
  (config.value?.items ?? []).filter((item) => item.category === 'BUDGET')
)
const promptPointer = computed(() =>
  (config.value?.items ?? []).find((item) => item.category === 'PROMPT') ?? null
)

/**
 * 未接线项由**服务端目录**下发（`AiConfigItemView.wired`）——页面不再维护硬编码清单。
 *
 * <p>历史：D3 之前 `NOT_WIRED_KEYS` 是前端手写清单，服务端仍接受写入；T6-02 接线
 * `model.max-tokens` / `model.timeout` / `model.max-retries` 后，目录里已全部 `wired=true`，
 * 机制保留给将来"仅展示"的配置项；服务端对 `wired=false` 同样拒写（不止前端置灰）。</p>
 */
function isNotWired(item: AiConfigItemView): boolean {
  return !item.wired
}

const notWiredItems = computed(() => modelItems.value.filter(isNotWired))

/* 真机集（--suite=live）：只标注、不阻断发布；没有证据就是"未跑"（AC-CFG-10 子句②）。 */
const liveGate = computed(() => gate.value?.live ?? null)

const liveLabel = computed(() => {
  const status = liveGate.value?.status
  if (status === 'PASSED') return '通过'
  if (status === 'FAILED') return '未通过'
  return '未跑'
})

const liveTag = computed<'success' | 'danger' | 'info'>(() => {
  const status = liveGate.value?.status
  if (status === 'PASSED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'info'
})

function displayValue(item: AiConfigItemView): string {
  if (item.secretClass) return item.value ?? '（未指定引用名）'
  if (item.value !== null && item.value !== undefined && item.value !== '') return item.value
  return '（未设置）'
}

function isTrue(item: AiConfigItemView): boolean {
  return String(item.value ?? '').toLowerCase() === 'true'
}

async function loadConfig(): Promise<void> {
  configLoading.value = true
  try {
    config.value = await getAiConfig()
  } finally {
    configLoading.value = false
  }
}

/** 危险配置的二次确认（沿用项目既有约定：确认文案说清"影响谁"）。 */
async function confirmDangerousIfNeeded(item: AiConfigItemView, action: string): Promise<boolean> {
  if (!item.dangerous) return true
  try {
    await ElMessageBox.confirm(
      `${action}属于危险配置，会影响**所有用户**的助手行为：\n${item.description}`,
      '危险配置确认',
      { type: 'warning', confirmButtonText: '确认修改', cancelButtonText: '取消' }
    )
    return true
  } catch {
    return false
  }
}

function openEdit(item: AiConfigItemView): void {
  editItem.value = item
  editValue.value = item.value ?? ''
  editVisible.value = true
}

async function submitEdit(): Promise<void> {
  const item = editItem.value
  if (!item) return
  if (!(await confirmDangerousIfNeeded(item, `把「${item.key}」改为 ${editValue.value || '（空）'}`))) {
    return
  }
  saving.value = true
  try {
    const result = await changeAiConfig(item.key, editValue.value)
    if (result.changed) {
      ElMessage.success(`${item.key} 已更新（下一个请求生效），并已写入操作审计`)
    } else {
      ElMessage.warning(result.message)
    }
    editVisible.value = false
    await loadConfig()
  } catch {
    // 请求拦截器已弹出可读错误（越界/类型错误/未知键都带原因），这里不重复提示
  } finally {
    saving.value = false
  }
}

/** 恢复默认值（清空显式值；版本号仍递增，保证"改配置不重启"的判据不失效）。 */
async function resetItem(item: AiConfigItemView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定把「${item.key}」恢复为默认值（${item.defaultValue ?? '未设置/沿用框架默认'}）吗？`,
      '恢复默认值',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const result = await changeAiConfig(item.key, null)
    ElMessage.success(result.changed ? '已恢复默认值，并已写入操作审计' : result.message)
    await loadConfig()
  } catch {
    /* 拦截器已提示 */
  }
}

/** 布尔开关：用 `:model-value` + `@change`（不用 v-model），失败时开关不会停在新状态。 */
async function changeBoolean(item: AiConfigItemView, value: string | number | boolean): Promise<void> {
  const next = value === true || value === 'true' || value === 1
  if (!(await confirmDangerousIfNeeded(item, `把「${item.key}」${next ? '打开' : '关闭'}`))) {
    return
  }
  try {
    const result = await changeAiConfig(item.key, next ? 'true' : 'false')
    ElMessage.success(result.changed ? '已更新（下一个请求生效），并已写入操作审计' : result.message)
    await loadConfig()
  } catch {
    /* 拦截器已提示 */
  }
}

/* ---------------- 提示词版本 ---------------- */

const promptLoading = ref(false)
const promptHistory = ref<PromptHistoryView | null>(null)
const gate = ref<PromptGateView | null>(null)
const published = ref<PromptDetailView | null>(null)
const draftDetail = ref<PromptDetailView | null>(null)
const draftContent = ref('')
const draftNote = ref('')
/** 编辑框内容是否还没保存（未保存时不允许发布，避免"发布了一个看不见的旧草稿"） */
const draftDirty = ref(false)

/**
 * 门禁结果的三种显示：**未检查**（本次进程还没跑过）/ **未跑**（跑了但没跑起来）/
 * 通过 / 未通过。三者都不能被显示成"通过"，且都不能允许发布（AC-CFG-10）。
 *
 * <p>列表接口不会顺手跑门禁（那会让页面每次打开卡分钟级），所以初始状态通常是"未检查"，
 * 由「刷新门禁」按钮显式触发；发布时服务端仍会**强制**再跑一次（权威判定点）。</p>
 */
const gateLabel = computed(() => {
  const value = gate.value
  if (!value) return '未检查'
  if (!value.ran) return value.summary.startsWith('尚未检查') ? '未检查' : '未跑'
  return value.passed ? '通过' : '未通过'
})

const gateTag = computed<'success' | 'danger' | 'info'>(() => {
  const value = gate.value
  if (!value || !value.ran) return 'info'
  return value.passed ? 'success' : 'danger'
})

const missingMarkers = computed(() => draftDetail.value?.missingProtectedMarkers ?? [])

/** 可以发布的三个前提：有草稿、草稿已保存、门禁通过。 */
const canPublish = computed(
  () => canUpdate.value && !!draftDetail.value && !draftDirty.value && missingMarkers.value.length === 0 && gate.value?.passed === true
)

const publishHint = computed(() => {
  if (!canUpdate.value) return '你没有「修改 AI 配置」权限（ai:config:update）'
  if (!draftDetail.value) return '还没有草稿：请先保存草稿'
  if (draftDirty.value) return '草稿有未保存的修改：请先保存草稿'
  if (missingMarkers.value.length > 0) return `草稿缺少受保护段落：${missingMarkers.value.join('；')}`
  if (!gate.value) return '尚未检查发布门禁：请先点击「刷新门禁」（确定性评测约 15 秒、最长 3 分钟）'
  if (!gate.value.ran) {
    return gate.value.summary.startsWith('尚未检查')
      ? '尚未检查发布门禁：请先点击「刷新门禁」（确定性评测约 15 秒、最长 3 分钟）'
      : `发布门禁未跑（${gate.value.summary}）：确定性黄金问题集必须先全绿`
  }
  if (!gate.value.passed) return `发布门禁未通过：${gate.value.summary}`
  return ''
})

/** 行级差异（只显示新增/删除的行）：给运营看"我到底改了什么"，不做逐字符 diff。 */
const lineDiff = computed(() => {
  const before = (published.value?.content ?? '').split('\n')
  const after = draftContent.value.split('\n')
  const beforeSet = new Map<string, number>()
  before.forEach((line) => beforeSet.set(line, (beforeSet.get(line) ?? 0) + 1))
  const added: string[] = []
  after.forEach((line) => {
    const left = beforeSet.get(line) ?? 0
    if (left > 0) beforeSet.set(line, left - 1)
    else added.push(line)
  })
  const afterSet = new Map<string, number>()
  after.forEach((line) => afterSet.set(line, (afterSet.get(line) ?? 0) + 1))
  const removed: string[] = []
  before.forEach((line) => {
    const left = afterSet.get(line) ?? 0
    if (left > 0) afterSet.set(line, left - 1)
    else removed.push(line)
  })
  return { added, removed }
})

async function loadPrompts(): Promise<void> {
  promptLoading.value = true
  try {
    promptHistory.value = await listPromptVersions()
    gate.value = promptHistory.value.gate

    const publishedSummary = promptHistory.value.versions.find((item) => item.status === 'PUBLISHED')
    published.value = publishedSummary ? await getPromptVersion(publishedSummary.versionNo) : null

    const draftSummary = promptHistory.value.draft
    if (draftSummary) {
      draftDetail.value = await getPromptVersion(draftSummary.versionNo)
      draftContent.value = draftDetail.value.content
      draftNote.value = draftSummary.note ?? ''
    } else {
      draftDetail.value = null
      // 没有草稿时以发布版为起点，方便"改一句就发一版"
      draftContent.value = published.value?.content ?? ''
      draftNote.value = ''
    }
    draftDirty.value = false
  } catch {
    /* 拦截器已提示 */
  } finally {
    promptLoading.value = false
  }
}

const gateLoading = ref(false)

async function refreshGate(): Promise<void> {
  gateLoading.value = true
  ElMessage.info('正在运行确定性评测（约 15 秒，最长 3 分钟）…')
  try {
    gate.value = await getPromptGate()
    ElMessage.info(`门禁：${gateLabel.value}（${gate.value.summary}）`)
    // 门禁结果同时反映在历史接口里，重新拉一次保持两处一致
    const history = await listPromptVersions()
    promptHistory.value = history
  } catch {
    /* 拦截器已提示 */
  } finally {
    gateLoading.value = false
  }
}

async function saveDraft(): Promise<void> {
  if (!draftContent.value.trim()) {
    ElMessage.warning('提示词正文不能为空')
    return
  }
  promptLoading.value = true
  try {
    draftDetail.value = await savePromptDraft(draftContent.value, draftNote.value || null)
    draftDirty.value = false
    if (draftDetail.value.missingProtectedMarkers.length > 0) {
      ElMessage.warning(
        `草稿已保存，但缺少受保护段落（发布会被服务端拒绝）：${draftDetail.value.missingProtectedMarkers.join('；')}`
      )
    } else {
      ElMessage.success('草稿已保存')
    }
    await loadPrompts()
  } catch {
    /* 拦截器已提示 */
  } finally {
    promptLoading.value = false
  }
}

async function publishDraft(): Promise<void> {
  const draft = draftDetail.value
  if (!draft) return
  try {
    await ElMessageBox.confirm(
      `确定发布 v${draft.version.versionNo} 吗？发布后**下一个请求**即对所有用户生效；已发布版本不可修改。`,
      '发布提示词',
      { type: 'warning', confirmButtonText: '确认发布' }
    )
  } catch {
    return
  }
  promptLoading.value = true
  try {
    const result = await publishPrompt(draft.version.versionNo)
    ElMessage.success(result.message)
    await loadPrompts()
  } catch {
    /* 门禁/保护标记被拒时，拦截器会弹出服务端的可读原因 */
  } finally {
    promptLoading.value = false
  }
}

async function rollbackTo(version: PromptVersionView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定回滚到 v${version.versionNo} 吗？回滚不重跑门禁（应急路径），会写审计；下一个请求生效。`,
      '回滚提示词',
      { type: 'warning', confirmButtonText: '确认回滚' }
    )
  } catch {
    return
  }
  promptLoading.value = true
  try {
    const result = await rollbackPrompt(version.versionNo)
    ElMessage.success(result.message)
    await loadPrompts()
  } catch {
    /* 拦截器已提示 */
  } finally {
    promptLoading.value = false
  }
}

/* ---------------- 变更历史（复用操作审计） ---------------- */

const historyLoading = ref(false)
const historyRows = ref<OperationAuditItem[]>([])
const historyTotal = ref(0)
const historyLoaded = ref(false)
const historyError = ref('')

function isoDate(offsetDays: number): string {
  const date = new Date()
  date.setDate(date.getDate() + offsetDays)
  return date.toISOString().slice(0, 10)
}

const historyRange = ref<[string, string]>([isoDate(-6), isoDate(0)])

async function loadHistory(): Promise<void> {
  historyLoading.value = true
  historyError.value = ''
  try {
    const page = await pageOperationAudits({
      startDate: historyRange.value[0],
      endDate: historyRange.value[1],
      targetType: 'AI_CONFIG',
      all: true
    })
    historyRows.value = page.items
    historyTotal.value = page.total
    historyLoaded.value = true
  } catch {
    // 审计接口要 `system:audit:view`（ADMIN 默认拥有）；权限不足时给可操作提示而不是空表
    historyError.value = '读取变更历史失败：该接口需要「操作审计」权限（system:audit:view），请联系管理员'
  } finally {
    historyLoading.value = false
  }
}

function historyDiff(row: OperationAuditItem) {
  return buildSnapshotDiff(row.beforeValue, row.afterValue)
}

/* ---------------- 生命周期 ---------------- */

onMounted(async () => {
  await loadConfig()
  await loadPrompts()
})
</script>

<template>
  <div class="ai-config">
    <el-card shadow="never">
      <template #header>
        <div class="ai-config__header">
          <div>
            <span class="ai-config__title">AI 配置</span>
            <el-tag v-if="config" type="info" size="small" class="ai-config__version">
              配置版本 v{{ config.version }}
            </el-tag>
          </div>
          <div class="ai-config__actions">
            <el-tooltip content="改配置不需要重启：快照按版本号在请求前比对并重载" placement="top">
              <el-button :loading="configLoading" @click="loadConfig">刷新配置</el-button>
            </el-tooltip>
          </div>
        </div>
      </template>

      <el-alert
        v-if="!canUpdate"
        type="info"
        show-icon
        :closable="false"
        title="当前账号只有查看权限"
        description="修改配置需要权限码 ai:config:update（危险权限）。页面隐藏按钮不算安全边界，服务端同样会拒绝。"
      />

      <el-tabs v-model="activeTab" class="ai-config__tabs">
        <!-- ============ 模型 ============ -->
        <el-tab-pane label="模型" name="model">
          <el-alert
            v-if="notWiredItems.length > 0"
            type="warning"
            show-icon
            :closable="false"
            class="mb-8"
            title="以下配置项本期未接线：仅展示，改了不生效（预留）"
            :description="notWiredItems.map((item) => item.key).join('、') + '——运行期仍沿用框架默认/改造前行为；接线后此处标注会移除。'"
          />
          <el-table :data="modelItems" v-loading="configLoading" stripe>
            <el-table-column prop="key" label="配置项" min-width="200">
              <template #default="{ row }">
                <span>{{ row.key }}</span>
                <el-tooltip
                  v-if="isNotWired(row)"
                  content="本期未接线：运行期不读取该键，改了不生效（预留）。"
                  placement="top"
                >
                  <el-tag size="small" type="warning" class="ml-4">本期未接线</el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="当前生效值" min-width="180">
              <template #default="{ row }">
                <span>{{ displayValue(row) }}</span>
                <el-tag v-if="row.overridden" size="small" type="success" class="ml-4">已自定义</el-tag>
                <el-tag v-else size="small" type="info" class="ml-4">默认</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="默认值" min-width="140">
              <template #default="{ row }">{{ row.defaultValue ?? '（沿用框架默认）' }}</template>
            </el-table-column>
            <el-table-column label="范围" min-width="120">
              <template #default="{ row }">
                {{ row.minValue !== null && row.maxValue !== null ? `${row.minValue} ~ ${row.maxValue}` : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="影响面" min-width="280">
              <template #default="{ row }">
                <span class="ai-config__desc">{{ row.description }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <template v-if="isNotWired(row)">
                  <el-tooltip content="本期未接线：运行期不读取该键，故不允许编辑（预留）。" placement="top">
                    <span class="ai-config__desc">未接线</span>
                  </el-tooltip>
                </template>
                <template v-else>
                  <el-button v-if="canUpdate" link type="primary" @click="openEdit(row)">修改</el-button>
                  <el-button v-if="canUpdate && row.overridden" link type="warning" @click="resetItem(row)">
                    恢复默认
                  </el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>

          <el-divider content-position="left">密钥引用</el-divider>
          <el-descriptions :column="1" border>
            <el-descriptions-item
              v-for="item in secretItems"
              :key="item.key"
              :label="item.key"
            >
              <el-tag :type="item.configured ? 'success' : 'danger'" size="small">
                {{ item.configured ? '已配置' : '未配置' }}
              </el-tag>
              <span class="ml-8">引用名：{{ displayValue(item) }}</span>
              <el-button v-if="canUpdate" link type="primary" class="ml-8" @click="openEdit(item)">
                修改引用名
              </el-button>
              <div class="ai-config__desc">{{ item.description }}</div>
            </el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>

        <!-- ============ 提示词 ============ -->
        <el-tab-pane label="提示词" name="prompt">
          <div class="prompt-head">
            <div>
              <span>当前发布版：</span>
              <el-tag v-if="published" type="success">v{{ published.version.versionNo }}</el-tag>
              <el-tag v-else type="info">无（使用 jar 内置提示词）</el-tag>
              <span v-if="published?.version.publishedAt" class="ml-8">
                {{ published.version.publishedAt }} · {{ published.version.publishedBy }}
              </span>
            </div>
            <div>
              <span>发布门禁：</span>
              <el-tag :type="gateTag">{{ gateLabel }}</el-tag>
              <el-tooltip :content="gate?.summary ?? ''" placement="top">
                <span class="ai-config__desc ml-8">{{ gate?.summary }}</span>
              </el-tooltip>
              <el-button link type="primary" class="ml-8" :loading="gateLoading" @click="refreshGate">
                刷新门禁
              </el-button>
            </div>
            <!--
              真机集（--suite=live）：AC-CFG-10 子句②要求"缺失时页面明确标注未跑"。
              它**只做标注、不阻断发布**——发布门禁以确定性集为准（REQ-CFG-11 的两级门禁口径）。
            -->
            <div>
              <span>真机集（--suite=live）：</span>
              <el-tag :type="liveTag">{{ liveLabel }}</el-tag>
              <el-tooltip :content="liveGate?.reason ?? '未配置 DEEPSEEK_API_KEY 或未跑（--suite=live）'" placement="top">
                <span class="ai-config__desc ml-8">
                  {{ liveGate?.reason ?? '未配置 DEEPSEEK_API_KEY 或未跑（--suite=live）' }}
                </span>
              </el-tooltip>
            </div>
          </div>

          <el-alert
            v-if="gate && !gate.ran"
            type="warning"
            show-icon
            :closable="false"
            title="发布门禁未跑 / 尚未检查（这不是「通过」）"
            :description="`${gate.summary}。确定性黄金问题集必须先全绿才允许发布。`"
          />
          <el-alert
            v-else-if="gate && !gate.passed"
            type="error"
            show-icon
            :closable="false"
            title="发布门禁未通过"
            :description="gate.summary"
          />
          <el-alert
            v-if="missingMarkers.length > 0"
            type="error"
            show-icon
            :closable="false"
            class="mt-8"
            title="草稿缺少受保护段落（发布会被服务端拒绝）"
            :description="missingMarkers.join('；')"
          />

          <div class="prompt-editor">
            <el-input
              v-model="draftContent"
              type="textarea"
              :rows="16"
              spellcheck="false"
              placeholder="在这里编辑提示词草稿"
              @input="draftDirty = true"
            />
            <el-input
              v-model="draftNote"
              class="mt-8"
              maxlength="200"
              show-word-limit
              placeholder="版本说明（谁、为什么改）"
              @input="draftDirty = true"
            />
            <div class="prompt-editor__actions">
              <el-button v-if="canUpdate" type="primary" :loading="promptLoading" @click="saveDraft">
                保存草稿
              </el-button>
              <el-tooltip :content="publishHint" placement="top" :disabled="!publishHint">
                <span>
                  <el-button
                    v-if="canUpdate"
                    type="danger"
                    :disabled="!canPublish"
                    :loading="promptLoading"
                    @click="publishDraft"
                  >
                    发布
                  </el-button>
                </span>
              </el-tooltip>
              <span class="ai-config__desc">
                {{ draftDirty ? '有未保存的修改' : '草稿已保存' }}
                <template v-if="draftDetail"> · 草稿版本 v{{ draftDetail.version.versionNo }}</template>
              </span>
            </div>
          </div>

          <el-divider content-position="left">与发布版的行级差异</el-divider>
          <el-empty
            v-if="!published || (lineDiff.added.length === 0 && lineDiff.removed.length === 0)"
            description="与发布版没有差异"
          />
          <div v-else class="prompt-diff">
            <div v-for="(line, index) in lineDiff.removed" :key="`r-${index}`" class="prompt-diff__line prompt-diff__line--removed">
              - {{ line }}
            </div>
            <div v-for="(line, index) in lineDiff.added" :key="`a-${index}`" class="prompt-diff__line prompt-diff__line--added">
              + {{ line }}
            </div>
          </div>

          <el-divider content-position="left">版本历史</el-divider>
          <el-table :data="promptHistory?.versions ?? []" v-loading="promptLoading" stripe>
            <el-table-column prop="versionNo" label="版本" width="80">
              <template #default="{ row }">v{{ row.versionNo }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="row.status === 'PUBLISHED' ? 'success' : row.status === 'DRAFT' ? 'warning' : 'info'"
                >
                  {{ row.status === 'PUBLISHED' ? '已发布' : row.status === 'DRAFT' ? '草稿' : '已归档' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="note" label="说明" min-width="160" />
            <el-table-column prop="publishedAt" label="发布时间" min-width="160" />
            <el-table-column prop="publishedBy" label="发布人" width="100" />
            <el-table-column label="内容哈希" min-width="160">
              <template #default="{ row }">
                <span class="ai-config__hash">{{ (row.contentHash ?? '').slice(0, 12) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="canUpdate && row.status === 'ARCHIVED'"
                  link
                  type="warning"
                  @click="rollbackTo(row)"
                >
                  回滚到此版
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ============ 能力开关 ============ -->
        <el-tab-pane label="能力开关" name="switches">
          <el-table :data="switchItems" v-loading="configLoading" stripe>
            <el-table-column prop="key" label="开关" min-width="200" />
            <el-table-column label="当前" width="120">
              <template #default="{ row }">
                <el-switch
                  :model-value="isTrue(row)"
                  :disabled="!canUpdate"
                  @change="(value: string | number | boolean) => changeBoolean(row, value)"
                />
              </template>
            </el-table-column>
            <el-table-column label="影响面（关闭后将发生什么）" min-width="360">
              <template #default="{ row }">
                <span class="ai-config__desc">{{ row.description }}</span>
              </template>
            </el-table-column>
          </el-table>

          <el-divider content-position="left">预算参数</el-divider>
          <el-table :data="budgetItems" v-loading="configLoading" stripe>
            <el-table-column prop="key" label="参数" min-width="200" />
            <el-table-column label="当前生效值" width="140">
              <template #default="{ row }">{{ displayValue(row) }}</template>
            </el-table-column>
            <el-table-column label="默认值" width="120">
              <template #default="{ row }">{{ row.defaultValue ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="范围" width="140">
              <template #default="{ row }">
                {{ row.minValue !== null && row.maxValue !== null ? `${row.minValue} ~ ${row.maxValue}` : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="影响面" min-width="280">
              <template #default="{ row }">
                <span class="ai-config__desc">{{ row.description }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canUpdate" link type="primary" @click="openEdit(row)">修改</el-button>
                <el-button v-if="canUpdate && row.overridden" link type="warning" @click="resetItem(row)">
                  恢复默认
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-descriptions v-if="promptPointer" :column="1" border class="mt-16">
            <el-descriptions-item :label="promptPointer.key">
              <span>{{ displayValue(promptPointer) }}</span>
              <div class="ai-config__desc">{{ promptPointer.description }}</div>
            </el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>

        <!-- ============ 变更历史 ============ -->
        <el-tab-pane label="变更历史" name="history">
          <div class="history-toolbar">
            <el-date-picker
              v-model="historyRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
            />
            <el-button type="primary" :loading="historyLoading" @click="loadHistory">查询</el-button>
            <span class="ai-config__desc">只查 target_type = AI_CONFIG 的记录（谁、何时、哪一项、before → after）</span>
          </div>

          <el-alert
            v-if="historyError"
            type="warning"
            show-icon
            :closable="false"
            :title="historyError"
          />

          <template v-else>
            <el-table v-if="historyLoaded" :data="historyRows" v-loading="historyLoading" stripe>
              <el-table-column type="expand">
                <template #default="{ row }">
                  <el-table :data="historyDiff(row)" size="small" border>
                    <el-table-column prop="field" label="字段" min-width="200" />
                    <el-table-column prop="before" label="变更前" min-width="220" />
                    <el-table-column prop="after" label="变更后" min-width="220" />
                  </el-table>
                  <div v-if="historyDiff(row).length === 0" class="ai-config__desc">
                    {{ noSnapshotHint(row) }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="operatedAt" label="时间" min-width="170" />
              <el-table-column label="动作" width="110">
                <template #default="{ row }">{{ auditActionLabel(row.action) }}</template>
              </el-table-column>
              <el-table-column label="目标" min-width="200">
                <template #default="{ row }">
                  <span>{{ row.targetName ?? '—' }}</span>
                  <el-tag size="small" type="info" class="ml-4">{{ row.targetType }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作人" min-width="140">
                <template #default="{ row }">{{ row.operatorRealName || row.operatorUsername || '—' }}</template>
              </el-table-column>
              <el-table-column label="渠道" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.source === 'AI' ? 'warning' : 'primary'">
                    {{ row.source === 'AI' ? '助手确认' : '页面直连' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="结果" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="auditResultTag(row.result)">
                    {{ auditResultLabel(row.result) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="变更字段" min-width="180">
                <template #default="{ row }">
                  {{ splitChangedFields(row.changedFields).join('、') || '—' }}
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else description="选择时间区间后点击查询" />
            <div v-if="historyLoaded" class="ai-config__desc">共 {{ historyTotal }} 条</div>
          </template>
        </el-tab-pane>
      </el-tabs>

      <!-- 编辑配置项 -->
      <el-dialog v-model="editVisible" :title="editItem ? `修改 ${editItem.key}` : '修改配置'" width="520px">
        <template v-if="editItem">
          <el-input v-model="editValue" :placeholder="editItem.defaultValue ?? '新值'" />
          <div class="ai-config__desc mt-8">{{ editItem.description }}</div>
          <div class="ai-config__desc">
            类型 {{ editItem.valueType }}
            <template v-if="editItem.minValue !== null && editItem.maxValue !== null">
              · 允许范围 {{ editItem.minValue }} ~ {{ editItem.maxValue }}
            </template>
            · 留空并保存 = 恢复默认值
          </div>
        </template>
        <template #footer>
          <el-button @click="editVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<style scoped>
.ai-config__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ai-config__title {
  font-size: 16px;
  font-weight: 600;
}

.ai-config__version {
  margin-left: 8px;
}

.ai-config__desc {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.ai-config__hash {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}

.ai-config__tabs {
  margin-top: 8px;
}

.prompt-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.prompt-editor {
  margin-top: 8px;
}

.prompt-editor__actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.prompt-diff {
  max-height: 260px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 8px;
}

.prompt-diff__line {
  white-space: pre-wrap;
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  line-height: 1.5;
}

.prompt-diff__line--added {
  color: var(--el-color-success);
}

.prompt-diff__line--removed {
  color: var(--el-color-danger);
}

.history-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.ml-4 {
  margin-left: 4px;
}

.ml-8 {
  margin-left: 8px;
}

.mt-8 {
  margin-top: 8px;
}

.mt-16 {
  margin-top: 16px;
}
</style>
