/** 会话摘要 */
export interface ConversationItem {
  id: number
  conversationNo: string
  title: string
  model: string
  status: string
  messageCount: number
  createdAt: string
  updatedAt: string
}

export type ChatRole = 'user' | 'assistant' | 'system'

export interface ChatMessage {
  id: number
  conversationId: number
  role: ChatRole
  content: string
  tokenCount: number | null
  createdAt: string
}

export interface ConversationDetail {
  conversation: ConversationItem
  messages: ChatMessage[]
}

export type ToolType = 'READ' | 'WRITE' | string
export type ToolCallStatus = 'SUCCESS' | 'FAILED' | 'RUNNING' | string

/** 工具调用记录 */
export interface ToolCallItem {
  id: number
  conversationId: number
  messageId: number | null
  toolName: string
  toolType: ToolType
  arguments: string | null
  result: string | null
  status: ToolCallStatus
  durationMs: number | null
  errorMessage: string | null
  createdAt: string
}

/** POST /api/ai/chat 请求体 */
export interface ChatRequest {
  conversationId: number | null
  message: string
}

/* ---------- SSE 事件负载 ---------- */

export interface SseMetaPayload {
  conversationId: number
  conversationNo: string
  title: string
}

export interface SseDeltaPayload {
  content: string
}

export interface SseToolCallPayload {
  id: number
  toolName: string
  toolType: ToolType
  arguments: string | null
  result: string | null
  status: ToolCallStatus
  durationMs: number | null
}

export interface SseDonePayload {
  conversationId: number
  messageId: number
}

export interface SseErrorPayload {
  message: string
}
