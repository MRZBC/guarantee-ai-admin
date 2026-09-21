import { getToken } from '@/utils/storage'
import { parseSseStream, type SseMessage } from '@/utils/sse'
import type { ChatRequest } from '@/types/ai'

/** 后端接口基础地址（开发环境经 vite proxy 转发到 :8080） */
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

/** 允许运行时覆盖，便于在非浏览器环境（如自动化验证）中指向本地服务 */
function resolveBaseUrl(): string {
  const override =
    typeof process !== 'undefined' && process.env ? process.env.VITE_API_BASE_URL : undefined
  return (override || API_BASE_URL).replace(/\/$/, '')
}

export interface ChatStreamHandlers {
  onMeta?: (payload: Record<string, unknown>) => void
  onDelta?: (payload: Record<string, unknown>) => void
  onToolCall?: (payload: Record<string, unknown>) => void
  onDone?: (payload: Record<string, unknown>) => void
  onError?: (payload: Record<string, unknown>) => void
  /** 任何无法归类的事件（含缺少事件名的裸 data） */
  onUnknown?: (message: SseMessage) => void
}

/** 名称归一化：兼容 kebab-case / 空格 / 大小写差异 */
function normalizeEventName(name: string): string {
  return name.trim().toLowerCase().replace(/[-\s]+/g, '_')
}

function asRecord(data: unknown): Record<string, unknown> {
  return data && typeof data === 'object' && !Array.isArray(data) ? (data as Record<string, unknown>) : {}
}

/** 流式对话失败时抛出的错误，携带后端给出的提示 */
export class ChatStreamError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ChatStreamError'
  }
}

/**
 * POST /api/ai/chat 流式对话。
 *
 * 注意：这里刻意不使用 axios —— 需要拿到未缓冲的 response.body 才能逐块解析 SSE。
 * 通过 AbortSignal 支持「停止」按钮。
 */
export async function streamChat(
  payload: ChatRequest,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  const token = getToken()
  const response = await fetch(`${resolveBaseUrl()}/ai/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(payload),
    signal
  })

  if (!response.ok) {
    if (response.status === 401) {
      throw new ChatStreamError('登录状态已失效，请重新登录')
    }
    const text = await response.text().catch(() => '')
    let message = `请求失败（HTTP ${response.status}）`
    if (text) {
      try {
        const parsed = JSON.parse(text) as { message?: string }
        if (parsed.message) message = parsed.message
      } catch {
        message = text.slice(0, 200)
      }
    }
    throw new ChatStreamError(message)
  }

  // 后端异常时可能返回 JSON 包装体而不是事件流，这里做兜底识别
  const contentType = response.headers.get('content-type') ?? ''
  if (!response.body) {
    throw new ChatStreamError('当前浏览器不支持流式响应')
  }
  if (contentType.includes('application/json') && !contentType.includes('event-stream')) {
    const text = await response.text()
    try {
      const wrapper = JSON.parse(text) as { code?: number; message?: string }
      if (wrapper.code !== undefined && wrapper.code !== 0) {
        throw new ChatStreamError(wrapper.message || '对话失败')
      }
    } catch (error) {
      if (error instanceof ChatStreamError) throw error
    }
    return
  }

  await parseSseStream(
    response.body,
    (message) => {
      const name = normalizeEventName(message.event)
      const data = asRecord(message.data)
      switch (name) {
        case 'meta':
          handlers.onMeta?.(data)
          break
        case 'delta':
          handlers.onDelta?.(data)
          break
        case 'tool_call':
        case 'toolcall':
          handlers.onToolCall?.(data)
          break
        case 'done':
          handlers.onDone?.(data)
          break
        case 'error':
          handlers.onError?.(data)
          break
        default:
          handlers.onUnknown?.(message)
          break
      }
    },
    signal
  )
}
