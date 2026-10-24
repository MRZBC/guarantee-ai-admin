import type { LocationQuery } from 'vue-router'
import type { OrderQuery } from '@/types/order'
import type { TrendQuery } from '@/types/analysis'

function scalar(query: LocationQuery, key: string): string | undefined {
  const value = query[key]
  if (Array.isArray(value)) throw new Error('筛选参数不能重复')
  return value || undefined
}

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

export function readOrderRoute(query: LocationQuery): OrderQuery {
  const result: OrderQuery = {}
  for (const key of ['orgId', 'insuranceTypeId', 'enterpriseId', 'projectId', 'pageNum', 'pageSize'] as const) {
    const value = scalar(query, key)
    if (value !== undefined) {
      if (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value))) throw new Error('编号或分页参数无效')
      result[key] = Number(value)
    }
  }
  const region = scalar(query, 'regionCode')
  if (region && !/^\d{6}$/.test(region)) throw new Error('地区编码无效')
  result.regionCode = region
  const exact = scalar(query, 'regionExact')
  if (exact && !['true', 'false'].includes(exact)) throw new Error('地区匹配方式无效')
  result.regionExact = exact === 'true'
  if (result.regionExact && !region) throw new Error('精确地区筛选缺少地区编码')
  const start = scalar(query, 'startDate'), end = scalar(query, 'endDate')
  if (Boolean(start) !== Boolean(end) || (start && end && (!validDate(start) || !validDate(end) || start > end))) {
    throw new Error('申请日期区间无效')
  }
  result.startDate = start
  result.endDate = end
  result.orderNo = scalar(query, 'orderNo')
  result.status = scalar(query, 'status')
  return result
}

export function readAnalysisRoute(query: LocationQuery): TrendQuery & { regionCode?: string; orgId?: number } {
  const common = readOrderRoute(query)
  const orderType = scalar(query, 'orderType') || 'TENDER'
  const granularity = scalar(query, 'granularity') || 'MONTH'
  if (!['TENDER', 'PERFORMANCE'].includes(orderType) || !['DAY', 'MONTH', 'YEAR'].includes(granularity)) {
    throw new Error('分析类型或粒度无效')
  }
  return { orderType: orderType as TrendQuery['orderType'], granularity: granularity as TrendQuery['granularity'],
    startDate: common.startDate, endDate: common.endDate, regionCode: common.regionCode, orgId: common.orgId ?? undefined }
}

export function routeParams(params: object): Record<string, string> {
  return Object.fromEntries(Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => [key, String(value)]))
}
