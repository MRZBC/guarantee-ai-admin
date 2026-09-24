import { dictLabel } from './status'

/**
 * 操作审计的字典与展示映射（P-07）。
 *
 * <p><b>为什么集中在这里</b>：审计页与（将来的）其它页面都要把 `action` / `targetType` /
 * `result` / `source` 四个英文码翻成中文。分散在各页面写映射，迟早会出现
 * "同一个码在 A 页叫『停用』、在 B 页叫『禁用』"——审计是"谁改了什么"的事实记录，
 * 措辞不一致会让人怀疑记录本身不一致。</p>
 *
 * <p><b>取值范围来自实测</b>（2026-09-23 开发库 925 行）：`PROPOSAL_CREATED` 占 32%，
 * `DELETE` / `RESTORE` 也真实存在，`result` 有 `EXPIRED` / `REJECTED`。
 * 少一个码就会让整列显示英文，因此这四组映射必须覆盖全量取值。</p>
 *
 * <p>本文件只做"值 → 文案"的映射，**不发任何请求**（沿用 `status.ts` 的约定）。</p>
 */

/** 与 `SensitiveFieldMasker.CHANGED_PLACEHOLDER` 保持一致。 */
export const AUDIT_SENSITIVE_PLACEHOLDER = '<changed>'

/** 与 `SensitiveFieldMasker.SNAPSHOT_MAX_BYTES`（8KB）对应的展示口径。 */
export const AUDIT_SNAPSHOT_LIMIT_LABEL = '8KB'

/** 查询跨度上限（SYS-A-17，后端 `OperationAuditService.MAX_RANGE_DAYS`）。 */
export const AUDIT_MAX_RANGE_DAYS = 90

/** 接近上限时的提示阈值（与 §5.5 一致）。 */
export const AUDIT_RANGE_WARN_DAYS = 80

/** 条数上限可选值（后端 `clampLimit` 最大 200）。 */
export const AUDIT_LIMIT_OPTIONS = [50, 100, 200]

/** 默认查询天数。 */
export const AUDIT_DEFAULT_DAYS = 7

/**
 * 动作码 → 中文。
 *
 * `PROPOSAL_CREATED` 是提案生命周期事件（不是一次字段变更，无前后值）；
 * `DELETE` / `RESTORE` 来自逻辑删除。三者都必须在，否则会显示英文码。
 *
 * `CHANGE_PASSWORD`（自助改密）与 `RESET_PASSWORD`（管理员重置他人密码）随
 * 「固定默认密码 + 强制首次改密」一起新增：两者都不在 `AUDIT_ACTION_LABELS` 的既有取值里，
 * 不加就会在审计页整列显示英文码——而这两条恰恰是安全追溯的关键记录（RK-U-14）。
 */
export const AUDIT_ACTION_LABELS: Record<string, string> = {
  CREATE: '新增',
  UPDATE: '修改',
  ENABLE: '启用',
  DISABLE: '停用',
  DELETE: '删除',
  RESTORE: '恢复',
  ASSIGN_ROLES: '角色分配',
  ASSIGN_PERMISSIONS: '权限授权',
  PROPOSAL_CREATED: '提案创建',
  CHANGE_PASSWORD: '修改密码',
  RESET_PASSWORD: '重置密码'
}

/** 目标类型码 → 中文。 */
export const AUDIT_TARGET_TYPE_LABELS: Record<string, string> = {
  USER: '用户',
  ORG: '机构',
  DEPT: '部门',
  ROLE: '角色',
  PERMISSION: '权限',
  INSURANCE_TYPE: '险种'
}

/** 结果码 → 中文。 */
export const AUDIT_RESULT_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  FAILED: '失败',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
  PARTIAL: '部分成功'
}

/** 渠道码 → 中文。 */
export const AUDIT_SOURCE_LABELS: Record<string, string> = {
  AI: '助手确认',
  WEB: '页面直连'
}

/** 非 ADMIN 可查的目标类型（后端 SYS-A-10 白名单，显式查 ROLE/PERMISSION 会 403）。 */
export const AUDIT_TARGET_TYPES_ADMIN = [
  'USER',
  'ORG',
  'DEPT',
  'ROLE',
  'PERMISSION',
  'INSURANCE_TYPE'
] as const

export const AUDIT_TARGET_TYPES_NON_ADMIN = ['USER', 'ORG', 'DEPT'] as const

export const AUDIT_ACTION_OPTIONS = Object.keys(AUDIT_ACTION_LABELS)
export const AUDIT_RESULT_OPTIONS = Object.keys(AUDIT_RESULT_LABELS)
export const AUDIT_SOURCE_OPTIONS = Object.keys(AUDIT_SOURCE_LABELS)

/** 元素 Plus 的 tag 类型。 */
export type AuditTagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

/** 字典取值，未命中时原样回显（宁可单格显示英文码，也不要整张表被打空）。 */
export function auditActionLabel(value: unknown): string {
  return dictLabel(AUDIT_ACTION_LABELS, value)
}

export function auditTargetTypeLabel(value: unknown): string {
  return dictLabel(AUDIT_TARGET_TYPE_LABELS, value)
}

export function auditResultLabel(value: unknown): string {
  return dictLabel(AUDIT_RESULT_LABELS, value)
}

export function auditSourceLabel(value: unknown): string {
  return dictLabel(AUDIT_SOURCE_LABELS, value)
}

/** 渠道 tag：AI=warning（助手）/ WEB=primary（页面直连），一眼可区分（AC-22）。 */
export function auditSourceTag(value: unknown): AuditTagType {
  return String(value ?? '').toUpperCase() === 'AI' ? 'warning' : 'primary'
}

/** 结果 tag。 */
export function auditResultTag(value: unknown): AuditTagType {
  switch (String(value ?? '').toUpperCase()) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'PARTIAL':
      return 'warning'
    case 'REJECTED':
    case 'EXPIRED':
      return 'info'
    default:
      return 'info'
  }
}

/** `changedFields` 是英文逗号分隔的字符串。 */
export function splitChangedFields(raw: unknown): string[] {
  if (raw === null || raw === undefined) return []
  return String(raw)
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item !== '')
}

/** 快照值 → 单行可读文本（对象/数组折叠成一行 JSON，避免撑爆抽屉）。 */
export function formatSnapshotValue(value: unknown): string {
  if (value === null || value === undefined) return '（空）'
  if (typeof value === 'string') return value === '' ? '（空字符串）' : value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

/** 单行 diff。 */
export interface AuditDiffRow {
  field: string
  before: string
  after: string
  changed: boolean
}

/**
 * 解析快照 JSON。
 *
 * <p>解析失败返回 `{ ok: false, raw }`，由调用方降级为原样文本展示——
 * 绝不在渲染路径上抛异常（Element Plus 的单元格渲染异常会打断整个 tbody）。</p>
 */
export function parseSnapshot(raw: unknown): { ok: true; value: Record<string, unknown> | null } | { ok: false; raw: string } {
  if (raw === null || raw === undefined || String(raw).trim() === '') {
    return { ok: true, value: null }
  }
  const text = String(raw)
  try {
    const parsed: unknown = JSON.parse(text)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { ok: false, raw: text }
    }
    return { ok: true, value: parsed as Record<string, unknown> }
  } catch {
    return { ok: false, raw: text }
  }
}

/**
 * 字段级 diff：取 before/after 的字段并集，**变更过的排前面**。
 *
 * 不只看 after 的键：停用/删除类变更的 after 可能是空对象或缺少字段，
 * 只按 after 遍历会漏掉"原本有值、现在被清空"的字段。
 */
export function buildSnapshotDiff(beforeValue: unknown, afterValue: unknown): AuditDiffRow[] {
  const before = parseSnapshot(beforeValue)
  const after = parseSnapshot(afterValue)
  const beforeMap = before.ok && before.value ? before.value : {}
  const afterMap = after.ok && after.value ? after.value : {}
  const fields = new Set<string>([...Object.keys(beforeMap), ...Object.keys(afterMap)])
  const rows: AuditDiffRow[] = []
  for (const field of fields) {
    const b = beforeMap[field]
    const a = afterMap[field]
    rows.push({
      field,
      before: formatSnapshotValue(b),
      after: formatSnapshotValue(a),
      changed: JSON.stringify(b ?? null) !== JSON.stringify(a ?? null)
    })
  }
  return rows.sort((x, y) => Number(y.changed) - Number(x.changed) || x.field.localeCompare(y.field))
}

/** diff 里是否出现脱敏占位符（出现时抽屉必须给出 D-4 说明）。 */
export function diffContainsSensitive(rows: AuditDiffRow[]): boolean {
  return rows.some(
    (row) =>
      row.before.includes(AUDIT_SENSITIVE_PLACEHOLDER) || row.after.includes(AUDIT_SENSITIVE_PLACEHOLDER)
  )
}

/**
 * "为什么没有前后值"的说明文案。
 *
 * <p>实测约 46% 的行没有快照（提案创建 / 被拒绝 / 过期未执行），
 * 不解释原因用户会以为"数据丢了"。</p>
 */
export function noSnapshotHint(row: {
  truncated?: number | null
  action?: string | null
  result?: string | null
}): string {
  if (Number(row.truncated) === 1) {
    return `本次变更的快照超过 ${AUDIT_SNAPSHOT_LIMIT_LABEL}，未保留前后值（SYS-A-16）；`
      + '只能依据「变更字段」判断哪些字段发生了变化。'
  }
  const action = String(row.action ?? '').toUpperCase()
  const result = String(row.result ?? '').toUpperCase()
  if (action === 'PROPOSAL_CREATED') {
    return '这是「提案创建」事件：只记录"生成了待确认的提案"本身，不涉及任何字段变更，因此没有前后值。'
  }
  if (result === 'EXPIRED') {
    return '该提案已过期、未执行任何变更，因此没有前后值。'
  }
  if (result === 'REJECTED') {
    return '该变更被拒绝、未执行，因此没有前后值。'
  }
  if (result === 'FAILED') {
    return '该变更执行失败、未改动任何数据，因此没有前后值。'
  }
  return '该记录未保存前后快照（事件类审计只记录发生了什么，不记录字段值）。'
}
