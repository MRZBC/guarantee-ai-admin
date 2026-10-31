import service, { http } from './request'
import type {
  AnalysisQuery,
  AnalysisOverview,
  OrderInstitutionItem,
  OrderInsuranceItem,
  OrderRegionItem,
  OrderTrendItem,
  TrendQuery
} from '@/types/analysis'

/** 数据总览指标 */
export function getOverview() {
  return http.get<AnalysisOverview>('/analysis/overview')
}

/** 订单趋势 */
export function getOrderTrend(params: TrendQuery) {
  return http.get<OrderTrendItem[]>('/analysis/order-trend', { params })
}

/** 区域分布 */
export function getOrderRegion(params: AnalysisQuery) {
  return http.get<OrderRegionItem[]>('/analysis/order-region', { params })
}

/** 险种分布 */
export function getOrderInsurance(params: AnalysisQuery) {
  return http.get<OrderInsuranceItem[]>('/analysis/order-insurance', { params })
}

/** 机构排行 */
export function getOrderInstitution(params: AnalysisQuery) {
  return http.get<OrderInstitutionItem[]>('/analysis/order-institution', { params })
}


/** 同一数据库快照中的比较与图表结果。 */
export function getOrderReport(params: TrendQuery & { regionCode?: string; orgId?: number }) {
  return http.get<import('@/types/analysis').OrderReport>('/analysis/order-report', { params })
}

export async function exportOrderReport(params: TrendQuery & { regionCode?: string; orgId?: number }): Promise<Blob> {
  const blob = await service.get('/analysis/order-report/export', { params, responseType: 'blob' }) as unknown as Blob
  // 业务拒绝可能是 HTTP 200 的 JSON，不能将其当 CSV 下载。
  if (blob.type.includes('json')) {
    const error = JSON.parse(await blob.text()) as { message?: string }
    throw new Error(error.message || '导出失败')
  }
  return blob
}
