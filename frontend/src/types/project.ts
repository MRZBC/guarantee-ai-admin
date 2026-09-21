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
  projectName?: string
  regionCode?: string
  projectType?: string
  status?: string
  enterpriseId?: number | null
}
