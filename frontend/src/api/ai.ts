import { http } from './request'
import type {
  ConversationDetail,
  ConversationItem,
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

export { streamChat, ChatStreamError, API_BASE_URL } from '@/utils/chatStream'
export type { ChatStreamHandlers } from '@/utils/chatStream'
