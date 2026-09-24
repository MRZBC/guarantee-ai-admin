/**
 * 行政区划码工具（与后端 `com.guarantee.common.region.RegionCodePrefix` **同一条规则**）。
 *
 * <p>改这里就要同步改那边——后端那份有单测（`RegionCodePrefixTest`），前端这份没有测试运行器，
 * 因此两边必须靠注释互相指认。</p>
 */

/**
 * 层级前缀：把 6 位国标码截成"覆盖它全部下级"的前缀。
 *
 * <ul>
 *   <li>省 `330000` → `33`（选浙江 = 含其下所有市/区县）</li>
 *   <li>市 `330100` → `3301`</li>
 *   <li>区县 `330102` → `330102`（等价于精确匹配）</li>
 * </ul>
 */
export function regionPrefix(code: string | null | undefined): string {
  const trimmed = (code ?? '').trim()
  if (!/^\d{6}$/.test(trimmed)) return trimmed
  if (trimmed.endsWith('0000')) return trimmed.slice(0, 2)
  if (trimmed.endsWith('00')) return trimmed.slice(0, 4)
  return trimmed
}

/** 机构/订单等记录上的区划码，是否落在"所选地区"的范围内（含下级） */
export function regionMatches(rowCode: string | null | undefined, selectedCode: string): boolean {
  if (!selectedCode) return true
  return (rowCode ?? '').startsWith(regionPrefix(selectedCode))
}
