/** 后端统一响应包装：{ code, message, data, traceId } */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId?: string
}

/** 分页数据结构 */
export interface PageResult<T> {
  pageNum: number
  pageSize: number
  total: number
  list: T[]
}

/** 分页查询基础参数 */
export interface PageQuery {
  pageNum?: number
  pageSize?: number
}
