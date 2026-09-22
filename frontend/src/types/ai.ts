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

/* ---------- 变更提案（二期，SYS-C-11 要求确认卡只用结构化载荷） ---------- */

export type ProposalAction =
  | 'CREATE'
  | 'UPDATE'
  | 'ENABLE'
  | 'DISABLE'
  | 'ASSIGN_ROLES'
  | 'ASSIGN_PERMISSIONS'
  | string

export type ProposalStatus =
  | 'PENDING'
  | 'EXECUTING'
  | 'EXECUTED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'INVALIDATED'
  | 'FAILED'
  | string

/** 单个字段的变更明细；新增动作只有 after，停用/清空只有 before。 */
export interface ProposalChange {
  field: string
  label: string
  before: string | null
  after: string | null
}

/**
 * 提案载荷。
 *
 * 由后端 SSE `proposal` 事件或 `GET /api/ai/proposals/{id}` 返回，
 * 前端**不得**自行拼装参数（SYS-C-11）。
 */
export interface ProposalPayload {
  proposalId: number
  proposalNo: string
  /** 来源会话：用于把确认卡归位到对应会话（SYS-C-14） */
  conversationId?: number | null
  toolName: string
  action: ProposalAction
  actionName: string
  targetType: string
  targetTypeName: string
  targetId: number | null
  targetName: string | null
  summary: string
  changes: ProposalChange[]
  impact: string[]
  warnings: string[]
  /** 危险动作：需要二次确认弹窗 */
  dangerous: boolean
  /** 用户原话，用于核对模型有没有理解错（SYS-C-15） */
  userText: string | null
  expiresAt: string
  status: ProposalStatus
}

/** SSE `proposal` 事件载荷（与 ProposalPayload 同构，无 status） */
export type SseProposalPayload = Omit<ProposalPayload, 'status'>

/** SSE `proposal_result` 事件载荷 */
export interface SseProposalResultPayload {
  proposalId: number
  status: ProposalStatus
  message: string
  auditId: number | null
  executedAt: string | null
}
