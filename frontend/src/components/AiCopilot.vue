<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getConversation, listConversations, listToolCalls, streamChat } from '@/api/ai'
import type {
  ChatMessage,
  ConversationItem,
  SseDeltaPayload,
  SseDonePayload,
  SseErrorPayload,
  SseMetaPayload,
  SseToolCallPayload,
  ToolCallItem
} from '@/types/ai'
import { formatDateTime, prettyJson } from '@/utils/format'

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

const listRef = ref<HTMLElement | null>(null)
let abortController: AbortController | null = null
let localIdSeed = -1

const canSend = computed(() => !streaming.value && input.value.trim().length > 0)
const hasMessages = computed(() => messages.value.length > 0)

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
    messages.value = detail.messages.map((message) => ({ ...message }))
    historyVisible.value = false
    visible.value = true
    await loadToolCalls(id)
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
            placeholder.conversationId = meta.conversationId
            localStorage.setItem(HISTORY_KEY, String(meta.conversationId))
          }
          if (meta.title) conversationTitle.value = meta.title
          // meta 到达即代表会话已创建，刷新历史列表
          void refreshConversations()
        },
        onDelta: (payload) => {
          const delta = payload as unknown as SseDeltaPayload
          if (typeof delta.content === 'string') {
            placeholder.content += delta.content
            void scrollToBottom()
          }
        },
        onToolCall: (payload) => {
          appendToolCall(placeholder.id, payload as unknown as SseToolCallPayload)
          void scrollToBottom()
        },
        onDone: (payload) => {
          const done = payload as unknown as SseDonePayload
          if (typeof done.conversationId === 'number') {
            conversationId.value = done.conversationId
            localStorage.setItem(HISTORY_KEY, String(done.conversationId))
          }
          if (typeof done.messageId === 'number' && done.messageId > 0) {
            // 落库后用真实 messageId 替换临时 id，保证工具调用仍能对应
            const calls = toolCalls.value[placeholder.id]
            if (calls) {
              toolCalls.value = { ...toolCalls.value, [done.messageId]: calls }
              delete toolCalls.value[placeholder.id]
              toolCalls.value = { ...toolCalls.value }
            }
            placeholder.id = done.messageId
            replacedPlaceholder = true
          }
          placeholder.streaming = false
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
    placeholder.streaming = false

    if (streamError) {
      ElMessage.error(streamError)
      if (!placeholder.content.trim() && !hasToolCalls(placeholder.id)) {
        messages.value = messages.value.filter((message) => message !== placeholder)
      }
    } else if (!placeholder.content.trim() && !hasToolCalls(placeholder.id)) {
      // 流被中断且没有任何内容
      messages.value = messages.value.filter((message) => message !== placeholder)
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

    <el-drawer
      v-model="visible"
      :with-header="false"
      size="600px"
      direction="rtl"
      :modal="false"
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
                  <div class="ai-bubble ai-bubble--assistant">
                    <span class="ai-bubble__text">{{ message.content }}</span>
                    <span v-if="message.streaming" class="ai-cursor">▌</span>
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
  white-space: pre-wrap;
  /* 纯文本渲染，保留换行 */
}

.ai-bubble__text {
  white-space: pre-wrap;
}

.ai-cursor {
  animation: ai-blink 1s steps(2, start) infinite;
  margin-left: 2px;
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
</style>

<style>
/* 抽屉内容需要撑满，且不显示默认内边距 */
.ai-copilot__drawer .el-drawer__body {
  padding: 0;
  overflow: hidden;
}
</style>
