import { http } from './request'
import type {
  AiConfigChangeResult,
  AiConfigView,
  AiMetricsOverviewResponse,
  AiMetricsRange,
  AiMetricsToolsResponse,
  AiMetricsTrendResponse,
  ConversationDetail,
  ConversationItem,
  OperationAuditPage,
  OperationAuditQuery,
  PromptDetailView,
  PromptGateView,
  PromptHistoryView,
  PromptPublishResult,
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
 * 提案列表（用于待办角标、刷新页面后恢复确认卡）。
 *
 * 取值范围固定在**当前用户自己**的提案：确认接口会再次校验所有者（SYS-C-01）。
 *
 * <p>{@code conversationId} 用于把确认卡**限定在自己的会话**里。不传会拿到该用户
 * 跨会话的全部提案——真机故障：A 会话里挂着的"角色分配 user0005"卡片被 B 会话
 * 的"本轮结束兜底刷新"拉到，渲染在 B 会话消息流末尾，看起来像助手答非所问。</p>
 */
export function listProposals(status = 'PENDING', conversationId?: number | null) {
  return http.get<ProposalPayload[]>('/ai/proposals', {
    params: conversationId ? { status, conversationId } : { status }
  })
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

/* ---------------- AI 运行可视化（REQ-MCP-11 / T5-04） ---------------- */

/**
 * 概览卡 + 提案状态计数（同一时间窗口，给「AI 运行」页顶部）。
 *
 * <p>权限 `system:audit:view`（与操作审计页同一枚）。`range` 由后端归一：
 * 缺省/非法 → `24h`，因此页面不需要自己兜底非法值，但**显示**要用响应里的 `range`
 * （否则会出现"我点了 30 天、实际看的是 24 小时"的静默错配）。</p>
 */
export function getAiMetricsOverview(range: AiMetricsRange = '24h') {
  return http.get<AiMetricsOverviewResponse>('/ai/metrics/overview', { params: { range } })
}

/**
 * 按天趋势（只返回库中真实存在的日期，不补零）。
 *
 * @param days 1~90，缺省 7；后端归一，响应里的 `days` 才是实际取值
 */
export function getAiMetricsTrend(days = 7) {
  return http.get<AiMetricsTrendResponse>('/ai/metrics/trend', { params: { days } })
}

/**
 * Top 工具（调用次数 + 平均/最大/p95 耗时）。
 *
 * @param limit 1~20，缺省 10
 * @param range 24h / 7d / 30d，缺省 24h
 */
export function getAiMetricsTopTools(limit = 10, range: AiMetricsRange = '24h') {
  return http.get<AiMetricsToolsResponse>('/ai/metrics/tools/top', { params: { limit, range } })
}

/* ---------------- AI 配置（第四阶段 T4-04 / REQ-CFG-08） ---------------- */

/**
 * 全部配置项（含当前生效值 vs 目录默认值）。
 *
 * 权限 `ai:config:view`。密钥类只返回"引用名 + 是否已配置"，**密钥值永不经过本接口**。
 */
export function getAiConfig() {
  return http.get<AiConfigView>('/ai/config')
}

/**
 * 修改一个配置项（页面渠道：表单 + 二次确认 + 直接落库 + WEB 审计）。
 *
 * @param value 新值；传 `null` 表示"恢复默认值"（清空显式值）
 *
 * 边界由**服务端**校验（类型/范围/枚举），越界会返回可读错误；值未变化时后端返回
 * `changed=false`（不写入、不审计），页面据此如实提示而不是谎报"已保存"。
 */
export function changeAiConfig(key: string, value: string | null) {
  return http.post<AiConfigChangeResult>('/ai/config/change', { key, value })
}

/** 提示词版本历史 + 当前草稿 + 门禁结果（权限 `ai:config:view`）。 */
export function listPromptVersions() {
  return http.get<PromptHistoryView>('/ai/config/prompts')
}

/** 单版本正文 + 缺失的保护标记。 */
export function getPromptVersion(versionNo: number) {
  return http.get<PromptDetailView>(`/ai/config/prompts/${versionNo}`)
}

/**
 * 单独查门禁结果——**这个请求会真的执行门禁命令**（服务端跑嵌套 maven 的确定性黄金问题集）。
 *
 * <p>因此不能用全局 30s 超时：实测独立运行 ~14s、与其它构建并发时显著更久（e2e 实测曾 >180s）。
 * 单独放宽到 180s，并与页面上的"正在运行确定性评测（约 15 秒，最长 3 分钟）"提示配套，
 * 避免"前端已报失败、后端其实还在跑"的错位。</p>
 */
export function getPromptGate() {
  return http.get<PromptGateView>('/ai/config/prompts/gate', { timeout: 180_000 })
}

/** 保存草稿（已有草稿则原地更新；已发布版本不可改）。 */
export function savePromptDraft(content: string, note?: string | null) {
  return http.post<PromptDetailView>('/ai/config/prompts/draft', { content, note: note ?? null })
}

/**
 * 发布草稿（权限 `ai:config:update`）。
 *
 * 服务端**必须先过保护标记校验与确定性门禁**；门禁未跑/未全绿都会拒绝发布并返回可读原因。
 *
 * <p><b>为什么单独放宽超时到 180s</b>：发布会同步跑一次门禁（嵌套 maven）。
 * e2e 实测同一命令独立跑 13.9s；若沿用全局 30s 超时，页面会在后端仍可能成功时误报失败。
 * 这是过渡口径——门禁已在 T4-03 修成"评估在事务外"，进一步的"按内容哈希复用通过结果"
 * 登记为后续优化（见 REQ §17），本期不做。</p>
 */
export function publishPrompt(versionNo: number) {
  return http.post<PromptPublishResult>('/ai/config/prompts/publish', { versionNo }, { timeout: 180_000 })
}

/** 回滚到历史版本（应急路径，不重跑门禁；产生审计）。 */
export function rollbackPrompt(versionNo: number) {
  return http.post<PromptPublishResult>('/ai/config/prompts/rollback', { versionNo })
}

export { streamChat, ChatStreamError, API_BASE_URL } from '@/utils/chatStream'
export type { ChatStreamHandlers } from '@/utils/chatStream'
