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

  function setAuth(newToken: string, newUser: UserInfo | null): void {
    token.value = newToken
    setToken(newToken)
    user.value = newUser
    persistUser(newUser)
  }

  async function login(params: LoginParams): Promise<UserInfo> {
    const result = await loginApi(params)
    setAuth(result.token, result.user)
    return result.user
  }

  async function fetchMe(): Promise<UserInfo> {
    const info = await getMe()
    user.value = info
    persistUser(info)
    return info
  }

  function logout(): void {
    token.value = ''
    user.value = null
    removeToken()
    persistUser(null)
  }

  return {
    token,
    user,
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
