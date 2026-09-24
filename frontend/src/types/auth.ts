export interface LoginParams {
  username: string
  password: string
}

/** 自助改密参数（`PUT /auth/password`，权限：已登录即可，无需权限码）。 */
export interface ChangePasswordParams {
  oldPassword: string
  /** D8=A：8~64 位；不得与原密码相同；不得等于系统默认密码 */
  newPassword: string
}

export interface UserInfo {
  id: number
  username: string
  realName: string
  deptId: number | null
  deptName: string | null
  roles: string[]
  permissions: string[]
  /**
   * 首次登录强制改密标记。
   *
   * <p>为 true 时，除 `PUT /auth/password`、`POST /auth/logout`、`GET /auth/me` 外的一切请求
   * 会被后端闸门拒为 **403 + code=1006**；前端据此把用户锁在「修改密码」页。</p>
   *
   * <p>可选是因为字段是后加的——旧响应里没有它，声明为必填会让类型与运行时不一致
   * （与 `LoginResult.idleTimeoutSeconds` 同一处理方式）。读取处一律按 falsy 处理。</p>
   */
  mustChangePassword?: boolean
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
  /**
   * 首次登录强制改密（D1=C）。true 时登录后必须先去 `/change-password`，
   * 改密完成前任何业务接口都会被服务端闸门拒绝。
   *
   * 可选同上：读取处按 falsy 处理。
   */
  mustChangePassword?: boolean
  user: UserInfo
}
