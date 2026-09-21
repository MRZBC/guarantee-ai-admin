import { http } from './request'
import type { ConversationDetail, ConversationItem, ToolCallItem } from '@/types/ai'

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

export { streamChat, ChatStreamError, API_BASE_URL } from '@/utils/chatStream'
export type { ChatStreamHandlers } from '@/utils/chatStream'
