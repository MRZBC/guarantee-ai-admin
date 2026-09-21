import type { PageQuery } from './common'

/** 订单通用字段（投标 / 履约共有） */
export interface OrderItem {
  id: number
  orderNo: string
  projectId: number | null
  projectName: string | null
  enterpriseId: number | null
  enterpriseName: string | null
  insuranceTypeId: number | null
  insuranceTypeName: string | null
  insuranceTypeCategory: string | null
  orgId: number | null
  orgName: string | null
  regionCode: string | null
  regionName: string | null
  guaranteeAmount: number | null
  premiumAmount: number | null
  premiumRate: number | null
  status: string | null
  statusName: string | null
  applyDate: string | null
  effectiveDate: string | null
  expireDate: string | null
}

/** 履约订单额外包含合同编号 */
export interface PerformanceOrderItem extends OrderItem {
  contractNo?: string | null
}

export type TenderOrderItem = OrderItem

/** 订单列表查询参数 */
export interface OrderQuery extends PageQuery {
  orderNo?: string
  regionCode?: string
  orgId?: number | null
  insuranceTypeId?: number | null
  status?: string
  startDate?: string
  endDate?: string
}
