export interface LoginParams {
  username: string
  password: string
}

export interface UserInfo {
  id: number
  username: string
  realName: string
  deptId: number | null
  deptName: string | null
  roles: string[]
  permissions: string[]
}

export interface LoginResult {
  token: string
  tokenType: string
  /** 距**绝对上限**（自登录起算）的剩余秒数，语义与引入空闲超时之前一致 */
  expiresIn: number
  /**
   * 空闲超时秒数（AUTH-04）：连续无请求超过该时长即失效，有请求会自动顺延。
   *
   * 可选是因为字段是后加的——旧响应里没有它，声明为必填会让类型与运行时不一致。
   */
  idleTimeoutSeconds?: number
  user: UserInfo
}
