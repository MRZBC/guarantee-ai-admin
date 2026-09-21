export interface LoginParams {
  username: string
  password: string
}

export interface UserInfo {
  id: number
  username: string
  realName: string
  orgId: number | null
  orgName: string | null
  deptId: number | null
  deptName: string | null
  roles: string[]
  permissions: string[]
}

export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  user: UserInfo
}
