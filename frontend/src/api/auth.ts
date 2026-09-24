import { http } from './request'
import type { ChangePasswordParams, LoginParams, LoginResult, UserInfo } from '@/types/auth'

export function login(data: LoginParams) {
  return http.post<LoginResult>('/auth/login', data)
}

export function getMe() {
  return http.get<UserInfo>('/auth/me')
}

/**
 * 自助修改密码（`PUT /auth/password`）。
 *
 * <p><b>不需要权限码</b>——登录即可调用，且它是"首次登录强制改密"闸门的**白名单接口之一**
 * （另外两个是 `POST /auth/logout` 与 `GET /auth/me`）。没有它，被标记 `mustChangePassword`
 * 的账号会被永久关在闸门后面却没有任何改密入口，账号直接变砖。</p>
 *
 * <p>改的是**当前登录用户自己**的密码：请求体里刻意没有"改谁的密码"这类参数——
 * 一旦接受，越权就只是传错一个参数。旧密码必须验证通过。</p>
 *
 * <p><b>成功后调用方必须主动清 token 并回登录页</b>：后端会撤销该用户全部令牌
 * （否则旧令牌里的强制改密标记恒为 true，用户改完仍被闸门拦住），停留无意义。</p>
 */
export function changePassword(data: ChangePasswordParams) {
  return http.put<void>('/auth/password', data)
}
