const EMPTY = '--'

/** 金额格式化：千分位 + 两位小数 */
export function formatAmount(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return EMPTY
  const num = typeof value === 'number' ? value : Number(value)
  if (Number.isNaN(num)) return EMPTY
  return num.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })
}

/** 大额金额的紧凑展示（图表坐标轴用）：1.23亿 / 4,567.89万 */
export function formatAmountShort(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return EMPTY
  const abs = Math.abs(value)
  if (abs >= 100000000) return `${(value / 100000000).toFixed(2)}亿`
  if (abs >= 10000) return `${(value / 10000).toFixed(2)}万`
  return value.toFixed(2)
}

/**
 * 百分比格式化。
 * 后端 premiumRate / baseRate 均以「百分数值」返回（0.5 表示 0.5%），
 * 因此这里不再乘以 100，直接追加 % 符号。
 */
export function formatPercent(value: number | string | null | undefined, digits = 2): string {
  if (value === null || value === undefined || value === '') return EMPTY
  const num = typeof value === 'number' ? value : Number(value)
  if (Number.isNaN(num)) return EMPTY
  return `${num.toFixed(digits)}%`
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/** 兼容 '2024-05-01'、'2024-05-01 10:20:30'、ISO 串与时间戳 */
function toDate(value: string | number | Date | null | undefined): Date | null {
  if (value === null || value === undefined || value === '') return null
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value
  if (typeof value === 'number') {
    const d = new Date(value)
    return Number.isNaN(d.getTime()) ? null : d
  }
  const normalized = value.includes('T') ? value : value.replace(/-/g, '/')
  const d = new Date(normalized)
  return Number.isNaN(d.getTime()) ? null : d
}

/** 日期格式化 yyyy-MM-dd */
export function formatDate(value: string | number | Date | null | undefined): string {
  const d = toDate(value)
  if (!d) return EMPTY
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 日期时间格式化 yyyy-MM-dd HH:mm:ss */
export function formatDateTime(value: string | number | Date | null | undefined): string {
  const d = toDate(value)
  if (!d) return EMPTY
  return `${formatDate(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 纯文本安全截断 */
export function truncate(text: string | null | undefined, max = 40): string {
  if (!text) return EMPTY
  return text.length > max ? `${text.slice(0, max)}…` : text
}

/** JSON 美化，解析失败时原样返回 */
export function prettyJson(raw: string | null | undefined): string {
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}
