import type { PageQuery } from './common'

export interface EnterpriseItem {
  id: number
  entCode: string
  entName: string
  creditCode: string | null
  regionCode: string | null
  regionName: string | null
  industry: string | null
  entLevel: string | null
  contactName: string | null
  contactPhone: string | null
  /** 后端 TINYINT：1 正常 / 0 停用 */
  status: number | null
  orderCount: number
  totalGuaranteeAmount: number
}

/** 企业详情额外字段 */
export interface EnterpriseDetail extends EnterpriseItem {
  projectCount: number
  tenderOrderCount: number
  performanceOrderCount: number
  totalPremiumAmount: number
}

export interface EnterpriseQuery extends PageQuery {
  /** 名称模糊匹配：用于"搜索企业候选"，仍被订单页/项目页的企业下拉复用 */
  entName?: string
  /** 主键精确匹配：用于"已选中某一条企业"，企业管理页的筛选走这个 */
  entId?: number | null
  regionCode?: string
  industry?: string
  entLevel?: string
  /** 后端 TINYINT：1 正常 / 0 停用 */
  status?: number | null
}
