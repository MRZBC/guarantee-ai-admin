import { http } from './request'
import type {
  ConversationDetail,
  ConversationItem,
  OperationAuditPage,
  OperationAuditQuery,
  ProposalPayload,
  ToolCallItem
} from '@/types/ai'

/** 历史会话列表 */
export function listConversations() {
  return http.get<ConversationItem[]>('/ai/conversations')
}

/** 会话详情（含消息） */
export function getConversation(id: number) {
  return http.get<ConversationDetail>(`/ai/conversations/${id}`)
}

/** 某会话下的全部工具调用记录 */
export function listToolCalls(conversationId: number) {
  return http.get<ToolCallItem[]>(`/ai/tool-calls/${conversationId}`)
}

/* ---------------- 变更提案（5.3.2） ---------------- */

/**
 * 待确认提案列表（用于待办角标）。
 *
 * 取值范围固定在**当前用户自己**的提案：确认接口会再次校验所有者（SYS-C-01）。
 */
export function listProposals(status = 'PENDING') {
  return http.get<ProposalPayload[]>('/ai/proposals', { params: { status } })
}

/** 提案详情：刷新页面或切回历史会话时恢复确认卡（SYS-C-14） */
export function getProposal(id: number) {
  return http.get<ProposalPayload>(`/ai/proposals/${id}`)
}

/** 确认并执行提案（一次性消费，后端条件更新防重复执行） */
export function confirmProposal(id: number) {
  return http.post<ProposalPayload>(`/ai/proposals/${id}/confirm`)
}

/** 拒绝提案（拒绝同样落审计，不允许无痕拒绝） */
export function rejectProposal(id: number, reason?: string) {
  return http.post<ProposalPayload>(`/ai/proposals/${id}/reject`, { reason })
}

/* ---------------- 操作审计（P-07） ---------------- */

/**
 * 操作审计列表（页面等价于助手的 `queryOperationAudit` 工具）。
 *
 * <p>时间区间**必填且跨度 ≤ 90 天**（SYS-A-17），后端会拒绝无区间或超跨度的请求。</p>
 *
 * <p>注意：后端当前**不支持翻页**（offset 恒为 0），`limit` 的语义是"最多取多少条"，
 * 默认 50、最大 200；响应里的 `total` 是命中总数，不是"总页数"。</p>
 */
export function pageOperationAudits(params: OperationAuditQuery) {
  return http.get<OperationAuditPage>('/ai/operation-audits', { params })
}

export { streamChat, ChatStreamError, API_BASE_URL } from '@/utils/chatStream'
export type { ChatStreamHandlers } from '@/utils/chatStream'
