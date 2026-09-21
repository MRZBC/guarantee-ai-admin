/** 数据概览汇总 */
export interface AnalysisOverview {
  tenderOrderCount: number
  performanceOrderCount: number
  totalOrderCount: number
  tenderGuaranteeAmount: number
  performanceGuaranteeAmount: number
  totalGuaranteeAmount: number
  totalPremiumAmount: number
  enterpriseCount: number
  projectCount: number
  orgCount: number
  effectiveOrderCount: number
  dataStartDate: string | null
  dataEndDate: string | null
}

export type OrderType = 'TENDER' | 'PERFORMANCE'
export type Granularity = 'DAY' | 'MONTH' | 'YEAR'

/** 趋势查询参数 */
export interface TrendQuery {
  orderType?: OrderType
  startDate?: string
  endDate?: string
  granularity?: Granularity
}

export interface OrderTrendItem {
  period: string
  orderCount: number
  guaranteeAmount: number
  premiumAmount: number
}

export interface OrderRegionItem {
  regionCode: string
  regionName: string
  orderCount: number
  guaranteeAmount: number
  premiumAmount: number
  enterpriseCount: number
}

export interface OrderInsuranceItem {
  insuranceTypeId: number
  typeCode: string
  typeName: string
  category: string
  orderCount: number
  guaranteeAmount: number
  premiumAmount: number
}

export interface OrderInstitutionItem {
  orgId: number
  orgCode: string
  orgName: string
  regionCode: string
  regionName: string
  orderCount: number
  guaranteeAmount: number
  premiumAmount: number
  enterpriseCount: number
}

/** 分析类接口通用查询参数 */
export interface AnalysisQuery {
  orderType?: OrderType
  startDate?: string
  endDate?: string
  limit?: number
}
