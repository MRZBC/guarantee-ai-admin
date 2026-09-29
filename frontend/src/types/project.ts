import type { PageQuery } from './common'

export interface ProjectItem {
  id: number
  projectCode: string
  projectName: string
  enterpriseId: number | null
  enterpriseName: string | null
  regionCode: string | null
  regionName: string | null
  projectAmount: number | null
  projectType: string | null
  status: string | null
  tenderDate: string | null
}

/** 项目详情额外字段 */
export interface ProjectDetail extends ProjectItem {
  tenderOrderCount: number
  performanceOrderCount: number
  totalGuaranteeAmount: number
  totalPremiumAmount: number
}

export interface ProjectQuery extends PageQuery {
  /** 名称模糊匹配：用于"搜索项目候选"，仍被订单页的项目下拉复用 */
  projectName?: string
  /** 主键精确匹配：用于"已选中某一条项目"，项目管理页的筛选走这个 */
  projectId?: number | null
  regionCode?: string
  projectType?: string
  status?: string
  enterpriseId?: number | null
}
