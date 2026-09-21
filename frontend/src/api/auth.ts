import { http } from './request'
import type { LoginParams, LoginResult, UserInfo } from '@/types/auth'

export function login(data: LoginParams) {
  return http.post<LoginResult>('/auth/login', data)
}

export function getMe() {
  return http.get<UserInfo>('/auth/me')
}
