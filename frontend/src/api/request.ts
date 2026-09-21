import axios, {
  AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig
} from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types/common'
import { getToken, removeToken } from '@/utils/storage'

/** 标记「已提示过错误」的请求，避免重复弹窗 */
interface RetriableConfig extends InternalAxiosRequestConfig {
  __notified?: boolean
}

const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

/** 请求拦截器：附加 Bearer token */
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error: unknown) => Promise.reject(error)
)

/** 清除登录态并跳转登录页 */
function redirectToLogin(): void {
  removeToken()
  const { pathname, hash } = window.location
  if (pathname === '/login' || hash.startsWith('#/login')) return
  // 使用 Hash 路由，跳转后刷新一次以清空内存中的登录态
  window.location.hash = '#/login'
  window.location.reload()
}

/** 响应拦截器：code === 0 解包 data，否则提示并 reject */
service.interceptors.response.use(
  (response: AxiosResponse<ApiResponse<unknown>>) => {
    const body = response.data
    // 非标准包装（例如二进制流）直接透传
    if (!body || typeof body !== 'object' || !('code' in body)) {
      return response.data as unknown as AxiosResponse
    }
    if (body.code === 0) {
      return body.data as unknown as AxiosResponse
    }
    const config = response.config as RetriableConfig
    if (!config.__notified) {
      config.__notified = true
      ElMessage.error(body.message || '请求失败')
    }
    return Promise.reject(new Error(body.message || '请求失败'))
  },
  (error: AxiosError<ApiResponse<unknown>>) => {
    const status = error.response?.status
    const config = (error.config ?? {}) as RetriableConfig
    let message = error.message || '网络异常，请稍后重试'

    if (status === 401) {
      if (!config.__notified) {
        config.__notified = true
        ElMessage.error('登录状态已失效，请重新登录')
      }
      redirectToLogin()
      return Promise.reject(error)
    }

    if (error.response?.data && typeof error.response.data.message === 'string') {
      message = error.response.data.message
    } else if (status === 403) {
      message = '没有权限访问该资源'
    } else if (status === 404) {
      message = '请求的资源不存在'
    } else if (status !== undefined && status >= 500) {
      message = '服务器异常，请稍后重试'
    } else if (error.code === 'ECONNABORTED') {
      message = '请求超时，请稍后重试'
    }

    if (!config.__notified) {
      config.__notified = true
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

export default service

/**
 * 类型化的请求封装。
 *
 * 响应拦截器已经在运行时把 `{ code, message, data }` 解包成了 `data`，
 * 但 axios 的类型定义仍然认为返回的是 `AxiosResponse<T>` —— 两者不一致。
 * 这里统一收口：声明请求返回 `Promise<T>`，与真实运行时行为保持一致。
 */
export interface TypedHttp {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T>
}

export const http: TypedHttp = {
  get: (url, config) => service.get(url, config) as unknown as Promise<unknown>,
  post: (url, data, config) => service.post(url, data, config) as unknown as Promise<unknown>,
  put: (url, data, config) => service.put(url, data, config) as unknown as Promise<unknown>,
  delete: (url, config) => service.delete(url, config) as unknown as Promise<unknown>
} as TypedHttp
