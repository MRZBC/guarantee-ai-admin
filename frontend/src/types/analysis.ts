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


export interface ComparisonMetric {
  current: number
  previous: number
  change: number
  changeRate?: number | null
  status: 'NEW' | 'UNCHANGED' | 'UP' | 'DOWN'
}

export interface OrderComparison {
  criteria: TrendQuery & { regionCode?: string; orgId?: number }
  previousStartDate: string
  previousEndDate: string
  dataStartDate?: string
  dataEndDate?: string
  comparisonComplete: boolean
  changeAlert: boolean
  orderCount: ComparisonMetric
  guaranteeAmount: ComparisonMetric
  premiumAmount: ComparisonMetric
}

export interface OrderReport {
  reportFingerprint: string
  generatedAt: string
  comparison: OrderComparison
  trend: OrderTrendItem[]
  regions: OrderRegionItem[]
  insurances: OrderInsuranceItem[]
  institutions: OrderInstitutionItem[]
}

export interface AnalysisEvidence {
  id: string
  kind: 'CHANGE' | 'PERIOD' | 'DISTRIBUTION'
  text: string
  drilldown: Record<string, string | number | boolean>
}

export interface AnalysisSummary {
  status: 'GENERATED' | 'FACTS_ONLY' | 'NO_DATA' | 'REPORT_CHANGED'
  report: OrderReport
  reportFingerprint: string
  generatedAt: string
  observations: { text: string; evidenceIds: string[] }[]
  evidence: AnalysisEvidence[]
  limitations: string[]
  message: string
  traceId: string | null
}
