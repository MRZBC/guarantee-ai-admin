<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  confirmProposal as confirmProposalApi,
  getConversation,
  listConversations,
  listProposals,
  listToolCalls,
  rejectProposal as rejectProposalApi,
  streamChat
} from '@/api/ai'
import ProposalCard from '@/components/ProposalCard.vue'
import type {
  ChatMessage,
  ChatRole,
  ConversationItem,
  ProposalPayload,
  SseDeltaPayload,
  SseDonePayload,
  SseErrorPayload,
  SseMetaPayload,
  SseProposalResultPayload,
  SseToolCallPayload,
  ToolCallItem
} from '@/types/ai'
import { formatDateTime, prettyJson } from '@/utils/format'
import { renderMarkdown } from '@/utils/markdown'

/** 界面上渲染的消息：id 为负数表示本地尚未落库的临时消息 */
interface DisplayMessage extends ChatMessage {
  streaming?: boolean
}

/** 工具调用按 messageId 关联到助手消息上 */
type ToolCallMap = Record<number, ToolCallItem[]>

const HISTORY_KEY = 'guarantee_admin_ai_conv_id'

const visible = ref(false)
const historyVisible = ref(false)
const streaming = ref(false)
const loadingHistory = ref(false)
const input = ref('')
const conversationId = ref<number | null>(null)
const conversationTitle = ref('新会话')
const messages = ref<DisplayMessage[]>([])
const toolCalls = ref<ToolCallMap>({})
const conversations = ref<ConversationItem[]>([])

/**
 * 变更提案卡片（二期）。
 *
 * 按 proposalId 去重：SSE 可能重复推送同一提案（同一会话内同目标同动作时后端会复用既有提案），
 * 刷新页面/切换会话时还会通过 GET /api/ai/proposals 再拉一次，必须避免渲染出两张卡。
 */
const proposals = ref<ProposalPayload[]>([])
/** 正在执行中的提案 id：用于"一张执行完成后才能操作下一张"的串行约束（SYS-C-13）。 */
const executingProposalId = ref<number | null>(null)

const listRef = ref<HTMLElement | null>(null)
let abortController: AbortController | null = null
let localIdSeed = -1

const canSend = computed(() => !streaming.value && input.value.trim().length > 0)
const hasMessages = computed(() => messages.value.length > 0)

/** 待确认的提案（用于会话内提示与角标）。 */
const pendingProposals = computed(() =>
  proposals.value.filter((item) => (item.status || 'PENDING').toUpperCase() === 'PENDING')
)

function nextLocalId(): number {
  const id = localIdSeed
  localIdSeed -= 1
  return id
}

function toolCallsOf(messageId: number): ToolCallItem[] {
  return toolCalls.value[messageId] ?? []
}

function hasToolCalls(messageId: number): boolean {
  return toolCallsOf(messageId).length > 0
}

/* ---------------- 提案状态维护 ---------------- */

/** 终态：不再需要用户确认，卡片必须从消息流里移除。 */
const TERMINAL_PROPOSAL_STATUSES = new Set(['EXECUTED', 'REJECTED', 'EXPIRED', 'INVALIDATED'])

function isTerminalProposal(status?: string | null): boolean {
  return TERMINAL_PROPOSAL_STATUSES.has((status || '').toUpperCase())
}

/**
 * 移除已终态的卡片。
 *
 * <p>为什么必须移除而不是"保留作记录"：确认卡挂在**消息流末尾**且会一直渲染，
 * 一段对话里连续做几次变更（例如先停用、再启用）就会堆成一列再也消不掉的卡片，
 * 把提问和回复挤出视野。执行结果由成功 Toast + 操作审计承载，
 * 不需要在对话里留一张不可操作的卡片。</p>
 */
function removeProposal(proposalId: number): void {
  proposals.value = proposals.value.filter((item) => item.proposalId !== proposalId)
}

function upsertProposal(payload: ProposalPayload): void {
  const index = proposals.value.findIndex((item) => item.proposalId === payload.proposalId)
  if (index >= 0) {
    proposals.value.splice(index, 1, { ...proposals.value[index], ...payload })
    proposals.value = [...proposals.value]
  } else {
    proposals.value = [...proposals.value, payload]
  }
}

function applyProposalResult(payload: SseProposalResultPayload): void {
  // 后端在别处（例如另一次确认、到期任务）把提案推到终态时，同样要把它从列表移除，
  // 否则会留下一张永远点不动的卡片。
  if (isTerminalProposal(payload.status)) {
    removeProposal(payload.proposalId)
    return
  }
  const index = proposals.value.findIndex((item) => item.proposalId === payload.proposalId)
  if (index < 0) return
  proposals.value.splice(index, 1, {
    ...proposals.value[index],
    status: payload.status
  })
  proposals.value = [...proposals.value]
}

/**
 * 按**当前会话**刷新待确认卡片。
 *
 * <p>确认卡是会话内资产（后端 `ai_operation_proposal.conversation_id`）。此前这里
 * 拉的是"该用户全部待确认提案"，于是别的会话里挂着的卡片会被塞进当前会话的消息流
 * 末尾：用户问"建个新角色"，助手正常回了角色的命名与权限范围，紧接着却出现一张
 * 「角色分配 user0005」的危险操作卡——看起来像是助手答非所问，实际是泄漏。</p>
 *
 * <p>没有当前会话（新会话还没发出第一条消息）时不显示任何卡片：此时屏幕上没有任何
 * 对话上下文，卡片无从归位。</p>
 */
async function refreshProposals(scopeConversationId: number | null = conversationId.value): Promise<void> {
  if (!scopeConversationId) {
    proposals.value = []
    return
  }
  try {
    // 待确认列表是**唯一权威来源**：只保留 PENDING。
    // 之前这里把「本次会话内已终态的卡片」也合并保留，导致执行过的卡片永久堆积。
    const list = await listProposals('PENDING', scopeConversationId)
    proposals.value = [...(list ?? [])]
      // 双保险：后端已按会话过滤，这里再挡一次——
      // 只认"就是本会话"的卡片；conversationId 为空的提案不属于任何会话，故不渲染
      .filter((item) => item.conversationId === scopeConversationId)
      .sort((a, b) => a.proposalId - b.proposalId)
  } catch {
    // 错误提示已由响应拦截器统一处理
  }
}

async function handleConfirm(proposalId: number): Promise<void> {
  executingProposalId.value = proposalId
  try {
    const updated = await confirmProposalApi(proposalId)
    // 执行后卡片已终态 → 从消息流移除，避免在对话里堆积；结果由下面的 Toast 反馈
    if (isTerminalProposal(updated.status)) {
      removeProposal(proposalId)
    } else {
      upsertProposal(updated)
    }
    if ((updated.status || '').toUpperCase() === 'EXECUTED') {
      ElMessage.success('变更已执行')
    } else {
      ElMessage.warning(updated.summary || '变更未成功执行')
    }
  } catch {
    // 失败原因（已过期 / 权限已变更 / 已执行过）由拦截器提示；
    // 这里同步一次状态，避免界面停留在"待确认"
    await refreshProposals()
  } finally {
    executingProposalId.value = null
  }
}

async function handleReject(proposalId: number, reason: string): Promise<void> {
  executingProposalId.value = proposalId
  try {
    const updated = await rejectProposalApi(proposalId, reason || undefined)
    if (isTerminalProposal(updated.status)) {
      removeProposal(proposalId)
    } else {
      upsertProposal(updated)
    }
    ElMessage.info('已拒绝，系统未做任何变更')
  } catch {
    await refreshProposals()
  } finally {
    executingProposalId.value = null
  }
}

/**
 * 归一化消息角色。
 *
 * <p>后端与数据库存的是大写 {@code USER} / {@code ASSISTANT}，而模板按小写比较
 * （{@code message.role === 'user'}）。不归一化的话，历史会话里的用户提问会被渲染成
 * 助手气泡（带 AI 头像、左侧、多一个时间戳），只有刚发出、尚未重新加载的那一条才是对的。</p>
 */
function normalizeRole(role: string | null | undefined): ChatRole {
  const value = (role ?? '').toLowerCase()
  if (value === 'user' || value === 'system') return value
  return 'assistant'
}

function appendToolCall(messageId: number, payload: SseToolCallPayload): void {
  if (!messageId) return
  const existing = toolCalls.value[messageId] ?? []
  const item: ToolCallItem = {
    id: payload.id,
    conversationId: conversationId.value ?? 0,
    messageId,
    toolName: payload.toolName,
    toolType: payload.toolType,
    arguments: payload.arguments ?? null,
    result: payload.result ?? null,
    status: payload.status,
    durationMs: payload.durationMs ?? null,
    errorMessage: null,
    createdAt: new Date().toISOString()
  }
  const index = existing.findIndex((call) => call.id === item.id)
  if (index >= 0) {
    existing.splice(index, 1, { ...existing[index], ...item })
    toolCalls.value = { ...toolCalls.value, [messageId]: [...existing] }
  } else {
    toolCalls.value = { ...toolCalls.value, [messageId]: [...existing, item] }
  }
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

const showEmpty = computed(() => !hasMessages.value && !streaming.value)

/* ---------------- 抽屉开关 ---------------- */

function open(): void {
  visible.value = true
  void scrollToBottom()
}

function close(): void {
  visible.value = false
}

function toggle(): void {
  if (visible.value) close()
  else open()
}

/* ---------------- 会话管理 ---------------- */

async function refreshConversations(): Promise<void> {
  try {
    conversations.value = await listConversations()
  } catch {
    // 错误提示已由响应拦截器统一处理
  }
}

async function openHistory(): Promise<void> {
  historyVisible.value = true
  loadingHistory.value = true
  try {
    await refreshConversations()
  } finally {
    loadingHistory.value = false
  }
}

async function loadToolCalls(id: number): Promise<void> {
  try {
    const calls = await listToolCalls(id)
    const grouped: ToolCallMap = {}
    for (const call of calls) {
      const key = call.messageId ?? 0
      if (!grouped[key]) grouped[key] = []
      grouped[key].push(call)
    }
    toolCalls.value = grouped
  } catch {
    toolCalls.value = {}
  }
}

async function loadConversation(id: number): Promise<void> {
  if (streaming.value) {
    ElMessage.warning('请先停止当前回答')
    return
  }
  loadingHistory.value = true
  try {
    const detail = await getConversation(id)
    conversationId.value = detail.conversation.id
    conversationTitle.value = detail.conversation.title || '历史会话'
    messages.value = detail.messages.map((message) => ({
      ...message,
      role: normalizeRole(message.role)
    }))
    historyVisible.value = false
    visible.value = true
    await loadToolCalls(id)
    // 刷新页面/切回历史会话时恢复 PENDING 确认卡（SYS-C-14）：
    // refreshProposals 本身按会话过滤，只恢复当前会话的卡片
    await refreshProposals(id)
    await scrollToBottom()
  } catch {
    // 拦截器已提示
  } finally {
    loadingHistory.value = false
  }
}

function startNewConversation(): void {
  if (streaming.value) {
    ElMessage.warning('请先停止当前回答')
    return
  }
  conversationId.value = null
  conversationTitle.value = '新会话'
  messages.value = []
  toolCalls.value = {}
  proposals.value = []
  input.value = ''
  historyVisible.value = false
}

/* ---------------- 发送 / 流式接收 ---------------- */

function stop(): void {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  streaming.value = false
  const last = messages.value[messages.value.length - 1]
  if (last && last.role === 'assistant') {
    last.streaming = false
    if (!last.content.trim() && !hasToolCalls(last.id)) {
      messages.value = messages.value.filter((message) => message !== last)
    }
  }
}

async function send(): Promise<void> {
  const text = input.value.trim()
  if (!text || streaming.value) return

  input.value = ''
  messages.value.push({
    id: nextLocalId(),
    conversationId: conversationId.value ?? 0,
    role: 'user',
    content: text,
    tokenCount: null,
    createdAt: new Date().toISOString()
  })

  const placeholder: DisplayMessage = {
    id: nextLocalId(),
    conversationId: conversationId.value ?? 0,
    role: 'assistant',
    content: '',
    tokenCount: null,
    createdAt: new Date().toISOString(),
    streaming: true
  }
  messages.value.push(placeholder)
  /*
    必须取回「数组里的响应式代理」再修改。
    push 进 reactive 数组的是原始对象，而局部变量 placeholder 始终指向那个原始对象；
    直接改 placeholder.content 会绕过 Proxy 的 set 拦截、不触发依赖更新，
    表现就是回答完全不流式、直到结束时才一次性出现
    （只有 streaming.value 等其它响应式变化才顺带触发一次渲染）。
  */
  const streamingMessage = messages.value[messages.value.length - 1]
  await scrollToBottom()

  const controller = new AbortController()
  abortController = controller
  streaming.value = true
  let streamError: string | null = null
  let replacedPlaceholder = false

  try {
    await streamChat(
      { conversationId: conversationId.value, message: text },
      {
        onMeta: (payload) => {
          const meta = payload as unknown as SseMetaPayload
          if (typeof meta.conversationId === 'number') {
            conversationId.value = meta.conversationId
            streamingMessage.conversationId = meta.conversationId
            localStorage.setItem(HISTORY_KEY, String(meta.conversationId))
          }
          if (meta.title) conversationTitle.value = meta.title
          // meta 到达即代表会话已创建，刷新历史列表
          void refreshConversations()
        },
        onDelta: (payload) => {
          const delta = payload as unknown as SseDeltaPayload
          if (typeof delta.content === 'string') {
            streamingMessage.content += delta.content
            void scrollToBottom()
          }
        },
        onToolCall: (payload) => {
          appendToolCall(streamingMessage.id, payload as unknown as SseToolCallPayload)
          void scrollToBottom()
        },
        onReset: () => {
          // 本轮正文是模型调用工具前的前言（如 "I'll query ..."），
          // 已实时显示过，但不属于最终回答，这里清掉，由下一轮重新流式输出正文
          streamingMessage.content = ''
        },
        /*
          提案事件：后端的写工具只产出提案，这里立即渲染确认卡。
          卡片数据完全来自后端载荷，前端不拼装任何参数（SYS-C-11）。
        */
        onProposal: (payload) => {
          const proposal = payload as unknown as ProposalPayload
          if (typeof proposal?.proposalId !== 'number') return
          upsertProposal({
            ...proposal,
            // SSE 载荷没有 status（它只会在生成时推送），补成 PENDING 以便卡片可操作
            status: 'PENDING'
          })
          void scrollToBottom()
        },
        onProposalResult: (payload) => {
          const result = payload as unknown as SseProposalResultPayload
          if (typeof result?.proposalId !== 'number') return
          applyProposalResult(result)
          if (result.message) {
            ElMessage.info(result.message)
          }
        },
        onDone: (payload) => {
          const done = payload as unknown as SseDonePayload
          if (typeof done.conversationId === 'number') {
            conversationId.value = done.conversationId
            localStorage.setItem(HISTORY_KEY, String(done.conversationId))
          }
          if (typeof done.messageId === 'number' && done.messageId > 0) {
            // 落库后用真实 messageId 替换临时 id，保证工具调用仍能对应
            const calls = toolCalls.value[streamingMessage.id]
            if (calls) {
              toolCalls.value = { ...toolCalls.value, [done.messageId]: calls }
              delete toolCalls.value[streamingMessage.id]
              toolCalls.value = { ...toolCalls.value }
            }
            streamingMessage.id = done.messageId
            replacedPlaceholder = true
          }
          streamingMessage.streaming = false
        },
        onError: (payload) => {
          const err = payload as unknown as SseErrorPayload
          streamError = err.message || 'AI 服务异常'
        }
      },
      controller.signal
    )
  } catch (error) {
    const aborted = error instanceof DOMException && error.name === 'AbortError'
    if (!aborted) {
      streamError = error instanceof Error ? error.message : 'AI 服务异常'
    }
  } finally {
    abortController = null
    streaming.value = false
    streamingMessage.streaming = false

    /*
      兜底：本轮结束后以服务端为准刷新一次待确认提案（**只刷新当前会话**）。

      提案事件是**尽力推送**：ProposalEventPublisher 在"会话通道未注册 / 已关闭"时
      只落库不推送，推送失败也只记 debug 日志。一旦漏推，模型正文里照样会写
      "请在确认卡上点击「确认执行」"，用户就会看到"说生成了提案、却没有卡片"。
      这里用 GET 待确认列表兜住——进入会话时本来也有同样的刷新（SYS-C-14）。

      必须带 conversationId：不带就会把**别的会话**里挂着的待确认卡拉进来，
      渲染在本轮回答后面，表现为"助手答非所问地弹了一张危险操作卡"（已修）。
    */
    void refreshProposals(conversationId.value)

    if (streamError) {
      ElMessage.error(streamError)
      if (!streamingMessage.content.trim() && !hasToolCalls(streamingMessage.id)) {
        /*
          兜底：原来这里是把气泡**直接删掉**，用户看到的就只是"问了没反应"。
          现场反馈原话：「就算有bug或者做不了，也应该兜底一下吧」——
          留着气泡并写明失败原因，比让界面回到"什么都没发生"更有用。
          （后端正常路径已保证非空正文，这里兜的是流被中断这类客户端侧情况。）
        */
        streamingMessage.content = `回答失败：${streamError}`
      }
    } else if (!streamingMessage.content.trim() && !hasToolCalls(streamingMessage.id)) {
      // 流被中断且没有任何内容：同样留一条可读说明，不静默删气泡
      streamingMessage.content =
        '这一轮没有收到任何内容（回答可能被中断）。请重试一次；如果仍然如此，建议把问题拆小一点再问。'
    } else if (!replacedPlaceholder && conversationId.value) {
      // 补充拉取一次工具调用，避免流式事件丢失
      void loadToolCalls(conversationId.value)
    }
    await scrollToBottom()
  }
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  if (canSend.value) void send()
}

function toolTypeTagType(toolType: string): 'primary' | 'warning' | 'info' {
  return toolType?.toUpperCase() === 'WRITE' ? 'warning' : 'primary'
}

function statusTagType(status: string): 'success' | 'danger' | 'info' {
  const value = (status || '').toUpperCase()
  if (value === 'SUCCESS') return 'success'
  if (value === 'FAILED' || value === 'ERROR') return 'danger'
  return 'info'
}

watch(messages, () => void scrollToBottom(), { deep: true })

/*
  挂载时**刻意不拉**待确认提案：此时既没有当前会话，也没有任何消息，
  拉到的只能是"该用户跨会话的全部提案"，而卡片必须归位到自己的会话
  （Q-8 的口径就是"仅会话内提示，不做全局角标"）。
  卡片在进入/切回某个会话时由 loadConversation 按会话恢复（SYS-C-14）。
*/

onBeforeUnmount(() => {
  abortController?.abort()
  abortController = null
})
</script>

<template>
  <div class="ai-copilot">
    <!-- 悬浮入口按钮 -->
    <el-tooltip content="业务分析助手" placement="left">
      <el-badge :is-dot="streaming" class="ai-copilot__badge">
        <el-button
          class="ai-copilot__fab"
          type="primary"
          circle
          size="large"
          @click="toggle"
        >
          <el-icon :size="22"><MagicStick /></el-icon>
        </el-button>
      </el-badge>
    </el-tooltip>

    <!--
      说明（两个属性缺一不可）：
      :modal="false"     —— 不加深色遮罩，打开助手时页面内容依然可见。
      modal-penetrable   —— 关键。Element Plus 在 mask=false 时**并非不渲染遮罩**，
                            而是改为渲染一个 position:fixed; inset:0 的全屏容器
                            （见其 ElOverlay 实现），该容器默认 pointer-events: auto，
                            会把抽屉以外所有区域的点击全部吃掉，表现为「页面点不动」。
                            modal-penetrable 会为其加上 .is-penetrable，
                            使其 pointer-events:none、而内部 .el-drawer 仍为 auto。
    -->
    <el-drawer
      v-model="visible"
      :with-header="false"
      size="600px"
      direction="rtl"
      :modal="false"
      modal-penetrable
      class="ai-copilot__drawer"
    >
      <div class="ai-panel">
        <header class="ai-panel__header">
          <div class="ai-panel__title">
            <el-icon><MagicStick /></el-icon>
            <span>业务分析助手</span>
            <el-tag v-if="conversationTitle" size="small" type="info" effect="plain">
              {{ conversationTitle }}
            </el-tag>
            <!-- 会话内待办提示（RK-05：避免提案被遗忘在 PENDING） -->
            <el-tag v-if="pendingProposals.length" size="small" type="warning" effect="dark">
              {{ pendingProposals.length }} 个待确认变更
            </el-tag>
          </div>
          <div class="ai-panel__actions">
            <el-button size="small" :disabled="streaming" @click="startNewConversation">
              <el-icon><Plus /></el-icon>
              新建会话
            </el-button>
            <el-button size="small" @click="openHistory">
              <el-icon><Clock /></el-icon>
              历史会话
            </el-button>
            <el-button size="small" text @click="close">
              <el-icon><Close /></el-icon>
            </el-button>
          </div>
        </header>

        <div ref="listRef" class="ai-panel__body">
          <el-empty
            v-if="showEmpty"
            description="向我提问，例如：今年投标订单的保费和担保金额趋势如何？"
            :image-size="80"
          />

          <div v-for="message in messages" :key="message.id" class="ai-message">
            <template v-if="message.role === 'user'">
              <div class="ai-message__row ai-message__row--user">
                <div class="ai-bubble ai-bubble--user">{{ message.content }}</div>
                <el-avatar :size="30" class="ai-avatar ai-avatar--user">我</el-avatar>
              </div>
            </template>

            <template v-else>
              <div class="ai-message__row ai-message__row--assistant">
                <el-avatar :size="30" class="ai-avatar ai-avatar--ai">
                  <el-icon><MagicStick /></el-icon>
                </el-avatar>
                <div class="ai-message__main">
                  <div
                    class="ai-bubble ai-bubble--assistant"
                    :class="{ 'is-streaming': message.streaming }"
                  >
                    <!--
                      助手回答是 Markdown，交给 renderMarkdown 渲染成 HTML。
                      安全性由 markdown-it 的 html:false（转义原生 HTML）+ 默认的
                      validateLink（拦截 javascript: 等协议）保证，故此处可用 v-html。
                    -->
                    <div class="markdown-body" v-html="renderMarkdown(message.content)"></div>
                    <span v-if="message.streaming && !message.content" class="text-muted">
                      正在思考…
                    </span>
                  </div>

                  <!-- 工具调用过程 -->
                  <div v-if="hasToolCalls(message.id)" class="ai-tools">
                    <el-collapse class="ai-tools__collapse">
                      <el-collapse-item
                        v-for="call in toolCallsOf(message.id)"
                        :key="call.id"
                      >
                        <template #title>
                          <div class="ai-tool__title">
                            <el-tag
                              size="small"
                              :type="toolTypeTagType(call.toolType)"
                              effect="dark"
                            >
                              {{ call.toolType }}
                            </el-tag>
                            <span class="ai-tool__name mono">{{ call.toolName }}</span>
                            <el-tag size="small" :type="statusTagType(call.status)" effect="plain">
                              {{ call.status }}
                            </el-tag>
                            <span class="text-muted ai-tool__duration">
                              {{ call.durationMs ?? 0 }} ms
                            </span>
                          </div>
                        </template>
                        <div class="ai-tool__section">
                          <div class="ai-tool__label">参数 arguments</div>
                          <pre class="json-block">{{ prettyJson(call.arguments) || '（无）' }}</pre>
                        </div>
                        <div class="ai-tool__section">
                          <div class="ai-tool__label">结果 result</div>
                          <pre class="json-block">{{ prettyJson(call.result) || '（无）' }}</pre>
                        </div>
                        <div v-if="call.errorMessage" class="ai-tool__section">
                          <div class="ai-tool__label">错误</div>
                          <pre class="json-block">{{ call.errorMessage }}</pre>
                        </div>
                      </el-collapse-item>
                    </el-collapse>
                  </div>

                  <div class="ai-message__meta">{{ formatDateTime(message.createdAt) }}</div>
                </div>
              </div>
            </template>
          </div>

          <!--
            变更确认卡（二期）：挂在消息流末尾而不是某条消息内部。
            原因：提案与消息没有强绑定关系，而且刷新页面后消息是重新拉取的、
            提案是另一条接口恢复的（SYS-C-14），绑到消息上会导致恢复不到。
          -->
          <div v-if="proposals.length" class="ai-proposals">
            <ProposalCard
              v-for="item in proposals"
              :key="item.proposalId"
              :proposal="item"
              :busy="executingProposalId !== null"
              :disabled="executingProposalId !== null && executingProposalId !== item.proposalId"
              @confirm="handleConfirm"
              @reject="handleReject"
            />
          </div>
        </div>

        <footer class="ai-panel__footer">
          <el-input
            v-model="input"
            type="textarea"
            :rows="3"
            resize="none"
            maxlength="2000"
            placeholder="输入问题，Enter 发送，Shift+Enter 换行"
            @keydown="handleKeydown"
          />
          <div class="ai-panel__footer-actions">
            <span class="text-muted ai-panel__hint">
              示例：今年投标订单的保费和担保金额趋势如何？
            </span>
            <div>
              <el-button v-if="streaming" size="small" type="danger" plain @click="stop">
                <el-icon><VideoPause /></el-icon>
                停止
              </el-button>
              <el-button
                size="small"
                type="primary"
                :disabled="!canSend"
                :loading="streaming"
                @click="send"
              >
                <el-icon><Promotion /></el-icon>
                发送
              </el-button>
            </div>
          </div>
        </footer>
      </div>
    </el-drawer>

    <!-- 历史会话 -->
    <el-dialog v-model="historyVisible" title="历史会话" width="520px" append-to-body>
      <div v-loading="loadingHistory" class="ai-history">
        <el-empty v-if="!conversations.length && !loadingHistory" description="暂无历史会话" />
        <div
          v-for="item in conversations"
          :key="item.id"
          class="ai-history__item"
          :class="{ 'is-active': item.id === conversationId }"
          @click="loadConversation(item.id)"
        >
          <div class="ai-history__title">{{ item.title || '未命名会话' }}</div>
          <div class="ai-history__meta">
            <span>{{ item.messageCount }} 条消息</span>
            <span>·</span>
            <span>{{ formatDateTime(item.updatedAt || item.createdAt) }}</span>
            <span>·</span>
            <span class="mono">{{ item.model }}</span>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.ai-copilot__fab {
  width: 52px;
  height: 52px;
  box-shadow: 0 4px 14px rgba(64, 158, 255, 0.45);
}

.ai-copilot {
  position: fixed;
  right: 26px;
  bottom: 32px;
  z-index: 2000;
}

.ai-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.ai-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid #e4e7ed;
  flex-wrap: wrap;
}

.ai-panel__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.ai-panel__actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.ai-panel__body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f7f8fa;
}

.ai-message {
  margin-bottom: 14px;
}

.ai-message__row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.ai-message__row--user {
  justify-content: flex-end;
}

.ai-message__main {
  max-width: 84%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ai-avatar--user {
  background: #67c23a;
  color: #fff;
  flex-shrink: 0;
}

.ai-avatar--ai {
  background: #409eff;
  color: #fff;
  flex-shrink: 0;
}

.ai-bubble {
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

.ai-bubble--user {
  background: #409eff;
  color: #fff;
  max-width: 84%;
  white-space: pre-wrap;
}

.ai-bubble--assistant {
  background: #fff;
  border: 1px solid #e4e7ed;
  color: #303133;
  /*
    内容已交给 Markdown 渲染，换行由 markdown-it 的 breaks 选项负责。
    这里不能再用 pre-wrap：生成的 HTML 中标签之间存在缩进与换行，
    pre-wrap 会把它们渲染成多余空行。
  */
  white-space: normal;
}

/* ---------------- Markdown 内容样式 ---------------- */
/* v-html 插入的节点不带 scoped 属性，必须用 :deep 穿透 */

.markdown-body {
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

.markdown-body :deep(> :first-child) {
  margin-top: 0;
}

.markdown-body :deep(> :last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 14px 0 8px;
  font-weight: 600;
  line-height: 1.4;
  color: #303133;
}

.markdown-body :deep(h1) {
  font-size: 19px;
}

.markdown-body :deep(h2) {
  font-size: 17px;
}

.markdown-body :deep(h3) {
  font-size: 15px;
}

.markdown-body :deep(h4) {
  font-size: 14px;
}

.markdown-body :deep(p) {
  margin: 8px 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 8px 0;
  padding-left: 22px;
}

.markdown-body :deep(li) {
  margin: 3px 0;
}

.markdown-body :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 13px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #e4e7ed;
  padding: 6px 9px;
  text-align: left;
  vertical-align: top;
}

.markdown-body :deep(th) {
  background: #f5f7fa;
  font-weight: 600;
  white-space: nowrap;
}

.markdown-body :deep(code) {
  padding: 1px 5px;
  border-radius: 3px;
  background: #f5f7fa;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12.5px;
}

.markdown-body :deep(pre) {
  margin: 10px 0;
  padding: 10px 12px;
  border-radius: 4px;
  background: #f5f7fa;
  overflow-x: auto;
}

.markdown-body :deep(pre code) {
  padding: 0;
  background: transparent;
}

.markdown-body :deep(blockquote) {
  margin: 10px 0;
  padding: 4px 12px;
  border-left: 3px solid #dcdfe6;
  color: #606266;
  background: #fafafa;
}

.markdown-body :deep(a) {
  color: #409eff;
  text-decoration: none;
}

.markdown-body :deep(a:hover) {
  text-decoration: underline;
}

.markdown-body :deep(hr) {
  margin: 14px 0;
  border: none;
  border-top: 1px solid #e4e7ed;
}

/* 流式输出时，把光标追加到最后一个块级元素内部文字的末尾 */
.ai-bubble--assistant.is-streaming .markdown-body > :last-child::after {
  content: '▌';
  margin-left: 2px;
  animation: ai-blink 1s steps(2, start) infinite;
}

@keyframes ai-blink {
  to {
    visibility: hidden;
  }
}

.ai-message__meta {
  font-size: 12px;
  color: #c0c4cc;
}

.ai-tools {
  border: 1px dashed #dcdfe6;
  border-radius: 6px;
  background: #fff;
  padding: 0 10px;
}

.ai-tools__collapse {
  border-top: none;
}

.ai-tool__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  flex-wrap: wrap;
}

.ai-tool__name {
  color: #303133;
  font-weight: 600;
}

.ai-tool__duration {
  font-size: 12px;
}

.ai-tool__section {
  margin-bottom: 8px;
}

.ai-tool__label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.ai-panel__footer {
  border-top: 1px solid #e4e7ed;
  padding: 12px 16px;
  background: #fff;
}

.ai-panel__footer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  gap: 8px;
}

.ai-panel__hint {
  font-size: 12px;
}

.ai-history__item {
  padding: 10px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.ai-history__item:hover,
.ai-history__item.is-active {
  border-color: #409eff;
  background: #ecf5ff;
}

.ai-history__title {
  font-size: 14px;
  color: #303133;
  margin-bottom: 4px;
}

.ai-history__meta {
  font-size: 12px;
  color: #909399;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.ai-proposals {
  margin-top: 10px;
}
</style>

<style>
/* 抽屉内容需要撑满，且不显示默认内边距 */
.ai-copilot__drawer .el-drawer__body {
  padding: 0;
  overflow: hidden;
}
</style>
