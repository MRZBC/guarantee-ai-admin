import { http } from './request'
import type { PageResult } from '@/types/common'
import type { EnterpriseDetail, EnterpriseItem, EnterpriseQuery } from '@/types/enterprise'

export function pageEnterprises(params: EnterpriseQuery) {
  return http.get<PageResult<EnterpriseItem>>('/enterprises', { params })
}

export function getEnterprise(id: number) {
  return http.get<EnterpriseDetail>(`/enterprises/${id}`)
}
