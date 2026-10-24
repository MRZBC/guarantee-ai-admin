import type { OperationAuditItem } from '@/types/ai'
import { buildSnapshotDiff, parseSnapshot, splitChangedFields, AUDIT_SENSITIVE_PLACEHOLDER } from './auditDict'

const labels: Record<string, string> = {
  'model.base-url': '模型服务地址', 'model.name': '模型名称', 'model.temperature': '模型温度',
  'model.max-tokens': '最大回复长度', 'model.timeout': '模型超时时间', 'model.max-retries': '模型重试次数',
  'model.api-key-ref': '模型密钥引用', 'tools.order.enabled': '订单查询工具',
  'tools.analysis.enabled': '分析工具', 'tools.system.enabled': '系统查询工具',
  'tools.audit.enabled': '审计查询工具', 'tools.proposal.enabled': '变更提案工具', 'knowledge.enabled': '业务知识检索',
  'budget.history-limit': '历史消息上限', 'budget.max-rounds': '工具轮次上限',
  'budget.max-calls-per-round': '每轮调用上限', 'budget.soft-timeout-ms': '问答时间预算',
  'budget.tool-result-bytes': '工具结果长度上限', 'budget.tool-timeout-ms': '工具超时时间',
  'prompt.active-version': '生效提示词版本', versionNo: '生效版本', status: '状态', source: '正文来源',
  contentHash: '正文哈希', baseVersion: '基础规则版本', baseHash: '基础规则哈希',
  evaluationId: '评测编号', migrationSourceVersion: '迁移来源版本', migrationSourceHash: '迁移来源哈希',
  promptAction: '提示词操作'
}

export const configFieldLabel = (field: string): string => labels[field] ?? field
export const isPromptAudit = (row: OperationAuditItem): boolean => /^prompt\.v\d+$/.test(row.targetName ?? '')
export const configTargetLabel = (row: OperationAuditItem): string => isPromptAudit(row)
  ? `提示词 v${row.targetName!.slice('prompt.v'.length)}` : configFieldLabel(row.targetName ?? '未知配置项')

export function configSnapshot(raw: unknown): Record<string, unknown> {
  const parsed = parseSnapshot(raw)
  return parsed.ok && parsed.value ? parsed.value : {}
}

function displayValue(field: string, value: string): string {
  if (value === AUDIT_SENSITIVE_PLACEHOLDER) return '已变更（敏感内容不展示）'
  if (field === 'status') return ({ BUILTIN: '内置模板', DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' } as Record<string, string>)[value] ?? value
  if (field === 'source') return ({ BUILTIN: '内置模板', PUBLISHED: '发布版本' } as Record<string, string>)[value] ?? value
  if (field === 'promptAction') return ({ PUBLISH: '发布', ROLLBACK: '回滚' } as Record<string, string>)[value] ?? value
  if (field === 'versionNo') return value === '0' ? '内置模板' : value === '（空）' ? value : `v${value}`
  if (value === 'true' || value === 'false') return value === 'true' ? '开启' : '关闭'
  return value
}

export function configAuditDiff(row: OperationAuditItem) {
  return buildSnapshotDiff(row.beforeValue, row.afterValue).map(item => ({
    ...item, field: configFieldLabel(item.field),
    before: displayValue(item.field, item.before), after: displayValue(item.field, item.after)
  }))
}

export function configChangeSummary(row: OperationAuditItem): string {
  if (isPromptAudit(row)) {
    const before = configSnapshot(row.beforeValue), after = configSnapshot(row.afterValue)
    if (typeof before.versionNo === 'number' && typeof after.versionNo === 'number') {
      const action = after.promptAction === 'ROLLBACK' ? '回滚提示词' : '发布提示词'
      return `${action}：${before.versionNo === 0 ? '内置模板' : `v${before.versionNo}`} → v${after.versionNo}`
    }
    return `${configTargetLabel(row)} 状态变更（旧记录未保存切换前版本）`
  }
  const fields = new Set(splitChangedFields(row.changedFields))
  const changes = buildSnapshotDiff(row.beforeValue, row.afterValue)
    .filter(item => item.changed || fields.has(item.field))
  if (!changes.length) return '未保存变更前后值，请展开查看记录说明'
  return changes.map(item => item.before === AUDIT_SENSITIVE_PLACEHOLDER || item.after === AUDIT_SENSITIVE_PLACEHOLDER
    ? `${configFieldLabel(item.field)}：已变更（敏感内容不展示）`
    : `${configFieldLabel(item.field)}：${displayValue(item.field, item.before)} → ${displayValue(item.field, item.after)}`
  ).join('；')
}

/** 默认查询日按北京时间计算，避免凌晨用UTC日期把今天排除在外。 */
export function configHistoryDate(offsetDays: number, now = new Date()): string {
  const day = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
  const date = new Date(`${day}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + offsetDays)
  return date.toISOString().slice(0, 10)
}

/** 保留相同前后缀，展示真实修改片段；中间多处修改合并为一段，保留行号。 */
export function promptChangeFragment(before: string, after: string) {
  const left = before.split('\n'), right = after.split('\n')
  let start = 0, endLeft = left.length, endRight = right.length
  while (start < endLeft && start < endRight && left[start] === right[start]) start++
  while (endLeft > start && endRight > start && left[endLeft - 1] === right[endRight - 1]) { endLeft--; endRight-- }
  return { removed: left.slice(start, endLeft).map((text, index) => ({ line: start + index + 1, text })),
    added: right.slice(start, endRight).map((text, index) => ({ line: start + index + 1, text })) }
}
