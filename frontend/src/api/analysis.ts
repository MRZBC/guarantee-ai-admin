import { http } from './request'
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
