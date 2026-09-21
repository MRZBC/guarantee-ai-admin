import { http } from './request'
import type { PageResult } from '@/types/common'
import type { OrderQuery, PerformanceOrderItem, TenderOrderItem } from '@/types/order'

/** 投标订单分页列表 */
export function pageTenderOrders(params: OrderQuery) {
  return http.get<PageResult<TenderOrderItem>>('/orders/tender', { params })
}

/** 投标订单详情 */
export function getTenderOrder(id: number) {
  return http.get<TenderOrderItem>(`/orders/tender/${id}`)
}

/** 履约订单分页列表 */
export function pagePerformanceOrders(params: OrderQuery) {
  return http.get<PageResult<PerformanceOrderItem>>('/orders/performance', { params })
}

/** 履约订单详情 */
export function getPerformanceOrder(id: number) {
  return http.get<PerformanceOrderItem>(`/orders/performance/${id}`)
}
