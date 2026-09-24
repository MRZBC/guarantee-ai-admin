import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getMe, login as loginApi } from '@/api/auth'
import type { LoginParams, UserInfo } from '@/types/auth'
import { getToken, removeToken, setToken } from '@/utils/storage'

const USER_KEY = 'guarantee_admin_user'

function readCachedUser(): UserInfo | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as UserInfo
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', () => {
  /** token 与用户信息持久化在 localStorage，刷新页面后可恢复登录态 */
  const token = ref<string>(getToken())
  const user = ref<UserInfo | null>(readCachedUser())

  /**
   * 首次登录强制改密标记（D1=C）。
   *
   * <p><b>为什么同时持久化到 localStorage 并在初始化时读回</b>：刷新页面时内存状态全部丢失，
   * 若初始值恒为 `false`，路由守卫会在 `fetchMe()` 补齐之前先放行一个业务路由——
   * 后端随即以 403/1006 拒绝，用户白等一轮请求。读回缓存让守卫**在首次导航时**就能把
   * 用户直接引到改密页；`fetchMe()` 仍会刷新它（服务端是唯一事实来源）。</p>
   */
  const mustChangePassword = ref<boolean>(readCachedUser()?.mustChangePassword === true)

  const isLoggedIn = computed(() => token.value !== '')
  const realName = computed(() => user.value?.realName || user.value?.username || '未登录')
  const roles = computed<string[]>(() => user.value?.roles ?? [])
  const permissions = computed<string[]>(() => user.value?.permissions ?? [])

  function persistUser(value: UserInfo | null): void {
    if (value) {
      localStorage.setItem(USER_KEY, JSON.stringify(value))
    } else {
      localStorage.removeItem(USER_KEY)
    }
  }

  function setAuth(newToken: string, newUser: UserInfo | null, mustChange: boolean): void {
    token.value = newToken
    setToken(newToken)
    user.value = newUser ? { ...newUser, mustChangePassword: mustChange } : null
    persistUser(user.value)
    mustChangePassword.value = mustChange
  }

  /**
   * 登录。返回值里的 `mustChangePassword` 由 `LoginResponse` 提供（服务端同时把它写进 JWT claim），
   * 调用方据此决定落地页：为 true 时必须先去 `/change-password`。
   */
  async function login(params: LoginParams): Promise<UserInfo> {
    const result = await loginApi(params)
    const mustChange = result.mustChangePassword === true
    setAuth(result.token, result.user, mustChange)
    return user.value as UserInfo
  }

  /**
   * 拉取当前用户并刷新 `mustChangePassword`。
   *
   * <p>`GET /auth/me` 在闸门白名单内，所以在"被强制改密"状态下也能正常调用——
   * 这正是刷新页面后守卫仍能拿到该标记的原因。</p>
   */
  async function fetchMe(): Promise<UserInfo> {
    const info = await getMe()
    const mustChange = info.mustChangePassword === true
    user.value = { ...info, mustChangePassword: mustChange }
    persistUser(user.value)
    mustChangePassword.value = mustChange
    return user.value
  }

  /**
   * 退出登录：清本地登录态。
   *
   * <p>沿用既有口径——只清本地，**不发** `POST /auth/logout`（本仓库前端既有的登出路径
   * 就是这样，`request.ts` 的 401 分支同样只做本地清理）。因此登出在任何状态下都必然可用，
   * 包括被强制改密闸门拦住时：改密页的「退出登录」入口正是靠这一点才能成为逃生通道。</p>
   */
  function logout(): void {
    token.value = ''
    user.value = null
    mustChangePassword.value = false
    removeToken()
    persistUser(null)
  }

  return {
    token,
    user,
    mustChangePassword,
    isLoggedIn,
    realName,
    roles,
    permissions,
    setAuth,
    login,
    fetchMe,
    logout
  }
})
