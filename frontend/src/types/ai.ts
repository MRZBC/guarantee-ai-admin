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

/* ---------------- 操作审计（P-07） ---------------- */

/**
 * 一条操作审计记录。
 *
 * <p><b>契约要点</b>：后端直接序列化 `AiOperationAudit` 实体，且 Jackson 配置为
 * `default-property-inclusion: non_null`——**null 字段会被省略**，
 * 因此除 `id` / `source` / `action` / `targetType` / `result` 外的字段一律标可选。</p>
 *
 * <p>`beforeValue` / `afterValue` 是 **JSON 字符串**（不是对象），需要 `JSON.parse`
 * 后渲染；解析失败必须降级为原文，不能抛异常（见 `utils/auditDict.ts`）。</p>
 */
export interface OperationAuditItem {
  id: number
  operatedAt?: string | null
  operatorUserId?: number | null
  operatorUsername?: string | null
  operatorRealName?: string | null
  /** AI（助手确认）/ WEB（页面直连） */
  source: string
  /** CREATE / UPDATE / ENABLE / DISABLE / DELETE / RESTORE / ASSIGN_ROLES / ASSIGN_PERMISSIONS / PROPOSAL_CREATED */
  action: string
  /** USER / ORG / DEPT / ROLE / PERMISSION / INSURANCE_TYPE */
  targetType: string
  targetId?: number | null
  targetName?: string | null
  beforeValue?: string | null
  afterValue?: string | null
  /** 英文逗号分隔 */
  changedFields?: string | null
  /** 1 = 快照超 8KB 未保留（SYS-A-16） */
  truncated?: number | null
  /** SUCCESS / FAILED / REJECTED / EXPIRED / PARTIAL */
  result: string
  errorMessage?: string | null
  /** 来源提案 id（注意不是 proposalNo） */
  proposalId?: number | null
  conversationId?: number | null
  traceId?: string | null
  /** 逻辑删除列（LD-01）：审计页不展示 */
  isDeleted?: number | null
  deletedAt?: string | null
  deletedBy?: string | null
}

/** 审计查询条件（与 `GET /api/ai/operation-audits` 的 Query String 一一对应）。 */
export interface OperationAuditQuery {
  /** yyyy-MM-dd，必填 */
  startDate: string
  /** yyyy-MM-dd，必填；与 startDate 跨度 ≤ 90 天（SYS-A-17） */
  endDate: string
  /** 按**账号**模糊匹配（不是姓名） */
  operatorUsername?: string
  targetType?: string
  action?: string
  result?: string
  source?: string
  /** 默认 50，后端收敛为最大 200；与 `all` 同时出现时以 `all` 为准 */
  limit?: number
  /**
   * 「全部」：不设条数上限（页面「条数上限 = 全部」时传 true）。
   *
   * 不能靠传一个很大的 `limit` 代替：后端 `clampLimit` 会把 >200 收敛回 200，
   * 那样界面写着"全部"却只拿到 200 条。唯一护栏仍是 ≤90 天的时间区间（SYS-A-17）。
   */
  all?: boolean
}

/** 审计分页结果：`total` 为命中总数，`items` 为本次返回的记录。 */
export interface OperationAuditPage {
  total: number
  items: OperationAuditItem[]
}
