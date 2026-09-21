import { http } from './request'
import type { PageResult } from '@/types/common'
import type { ProjectDetail, ProjectItem, ProjectQuery } from '@/types/project'

export function pageProjects(params: ProjectQuery) {
  return http.get<PageResult<ProjectItem>>('/projects', { params })
}

export function getProject(id: number) {
  return http.get<ProjectDetail>(`/projects/${id}`)
}
