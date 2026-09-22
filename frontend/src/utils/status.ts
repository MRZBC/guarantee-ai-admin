/**
 * 状态 / 字典文案工具。
 *
 * <p>背景：系统类实体（机构、部门、用户、角色、险种、企业）的 {@code status}
 * 在库里是 {@code TINYINT}，后端 VO/DTO 均为 {@code Integer}，接口返回的是**数字**
 * （{@code 1} 启用 / {@code 0} 停用）；而业务类实体（项目、投标/履约订单）用的是
 * {@code VARCHAR(16)} 字符串枚举。前端早期给系统类页面也按字符串处理，
 * 于是 {@code (1).toUpperCase()} 抛 TypeError。</p>
 *
 * <p>更麻烦的是 Element Plus 在「单元格渲染函数抛异常」时会中断整个 tbody，
 * 表现为「只有『共 N 条』、一行数据都不显示」。因此这里所有取值一律先转字符串再处理，
 * 对未知类型保持容错：宁可单格显示异常值，也不要整张表被打空。</p>
 */

/** 后端 TINYINT 状态：1 启用 */
export const STATUS_ENABLED = 1
/** 后端 TINYINT 状态：0 停用 */
export const STATUS_DISABLED = 0

/** 状态筛选下拉选项。value 必须是数字，否则后端 Integer 绑定会 400。 */
export const STATUS_OPTIONS: { label: string; value: number }[] = [
  { label: '启用', value: STATUS_ENABLED },
  { label: '停用', value: STATUS_DISABLED }
]

/**
 * 通用字典取值。值可能是数字或字符串，统一转大写字符串再查表，
 * 查不到时原样回显而不是抛错。
 */
export function dictLabel(map: Record<string, string>, value: unknown): string {
  if (value === null || value === undefined || value === '') return '--'
  const key = String(value).toUpperCase()
  return map[key] ?? String(value)
}

/** 系统类实体状态文案：1 → 启用，0 → 停用。 */
export function statusLabel(status: unknown): string {
  if (status === null || status === undefined || status === '') return '--'
  if (typeof status === 'number') {
    if (status === STATUS_ENABLED) return '启用'
    if (status === STATUS_DISABLED) return '停用'
    return `未知(${status})`
  }
  return dictLabel(
    { ACTIVE: '启用', ENABLED: '启用', NORMAL: '启用', DISABLED: '停用', INACTIVE: '停用' },
    status
  )
}

/** 数据库直连删除的哨兵值（后端 deleted_by 的 DEFAULT 'DB'）。 */
export const DELETED_BY_DB = 'DB'

/**
 * 格式化「删除人标识」。
 *
 * <p>后端 `deleted_by` 是 `VARCHAR(64) NOT NULL DEFAULT 'DB'`，一个字段承载两种来源：
 * - 应用删除 → `sys_user.id` 的字符串（例如 `"1"`）
 * - 数据库直连删除 → `"DB"`（列默认值）
 *
 * <p><b>必须集中在这里判断，不允许各页面自己写</b>：该字段混存两种形态，
 * 直接拿去 `JOIN sys_user` 或当数字用会**静默**出错（设计文档 §2.1a / 风险 LD-R11）。
 *
 * <p>本函数只做"值 → 可读文案"的映射，不查用户表。若后续要显示账号名，
 * 应新增一个按 `target_type + target_id` 查审计的接口，而不是在这里发请求。
 *
 * <p><b>当前未被任何页面调用</b>（2026-09-22 起，5 个系统管理页面不再展示删除审计列）。
 * 保留它是因为：后端接口仍返回 `deletedBy`，页面一旦要展示删除人，必须走这里而不是各自解析
 * （混存字段自行判断会静默出错）。若长期无展示需求，可连同 `DELETED_BY_DB` 一起删除。
 */
export function formatDeletedBy(deletedBy: unknown): string {
  if (deletedBy === null || deletedBy === undefined || deletedBy === '') return '--'
  if (deletedBy === DELETED_BY_DB) return '数据库直连'
  // 数字字符串 = 应用删除，展示为 user#id；带出 id 便于人工回溯
  if (/^\d+$/.test(String(deletedBy))) return `用户#${deletedBy}`
  return String(deletedBy)
}

/** 是否启用。同样兼容数字与字符串两种表示。 */
export function isEnabled(status: unknown): boolean {
  if (typeof status === 'number') return status === STATUS_ENABLED
  const code = String(status ?? '').toUpperCase()
  return ['ACTIVE', 'ENABLED', 'NORMAL', '1'].includes(code)
}

/**
 * 把筛选状态规范化为查询参数。
 *
 * <p>只放行真正的数字：`el-select` 被清空时可能给出 `undefined` 或 `''`，
 * 而空串会让后端 Integer 绑定失败（`status=` → 400）。
 * 同时不能用 `query.status || undefined` —— `0`（停用）是合法取值却属于 falsy，
 * 那样会静默丢掉「筛选停用」这个条件。</p>
 */
export function statusParam(status: unknown): number | undefined {
  return typeof status === 'number' ? status : undefined
}
