/**
 * SSE (text/event-stream) 解析工具。
 *
 * 为什么不用 EventSource：EventSource 只支持 GET，而 /api/ai/chat 是 POST
 * 且需要 Authorization 头，所以必须用 fetch + ReadableStream 手工解析。
 *
 * 解析规则（依据 W3C Event Stream 规范的相关子集）：
 *  - 按行切分，`event:` 设置事件名，`data:` 可出现多行（用 \n 连接）
 *  - 空行代表一个事件结束，此时派发上个事件
 *  - 忽略以 `:` 开头的注释行与 `id:` / `retry:` 字段
 *  - 同时兼容 \n 与 \r\n，并处理 \r\n 被拆到两个 chunk 的情况
 *  - 流结束时（无结尾空行）把残留事件也派发出去
 */

export interface SseMessage {
  /** 事件名，缺省为 'message' */
  event: string
  /** 拼接后的原始 data 文本 */
  rawData: string
  /** JSON.parse 之后的对象；解析失败时为 null */
  data: unknown
}

export type SseHandler = (message: SseMessage) => void

/** 用于保护 data 内部换行符，避免被当作事件分隔 */
const NEWLINE_PLACEHOLDER = '\u0000__SSE_NL__\u0000'

interface ParsedLine {
  field: string
  value: string
}

function parseLine(line: string): ParsedLine | null {
  if (line === '') return null
  if (line.startsWith(':')) return null
  const colon = line.indexOf(':')
  if (colon === -1) return { field: line, value: '' }
  const field = line.slice(0, colon)
  let value = line.slice(colon + 1)
  if (value.startsWith(' ')) value = value.slice(1)
  return { field, value }
}

/**
 * 创建一个增量式 SSE 解析器。
 * 每次收到网络分片就调用 push()，最后一个分片之后必须调用 flush()。
 */
export function createSseParser(onMessage: SseHandler) {
  let buffer = ''
  let eventName = ''
  let dataLines: string[] = []

  function dispatch(): void {
    if (dataLines.length === 0) {
      // data 为空的事件（例如仅有 event: 行）在规范中不派发
      eventName = ''
      return
    }
    const rawData = dataLines.join('\n').split(NEWLINE_PLACEHOLDER).join('\n')
    const name = eventName || 'message'
    eventName = ''
    dataLines = []

    let parsed: unknown = null
    const trimmed = rawData.trim()
    if (trimmed !== '') {
      try {
        parsed = JSON.parse(trimmed)
      } catch {
        parsed = null
      }
    }
    onMessage({ event: name, rawData, data: parsed })
  }

  function handleLine(line: string): void {
    if (line === '') {
      dispatch()
      return
    }
    const parsed = parseLine(line)
    if (!parsed) return
    if (parsed.field === 'event') {
      eventName = parsed.value
    } else if (parsed.field === 'data') {
      dataLines.push(parsed.value.split('\n').join(NEWLINE_PLACEHOLDER))
    }
    // id / retry 字段对本场景无意义，忽略
  }

  return {
    /** 推送一段刚解码出来的文本 */
    push(chunk: string): void {
      buffer += chunk
      let index = buffer.indexOf('\n')
      while (index !== -1) {
        let line = buffer.slice(0, index)
        if (line.endsWith('\r')) line = line.slice(0, -1)
        buffer = buffer.slice(index + 1)
        handleLine(line)
        index = buffer.indexOf('\n')
      }
      // 处于 \r\n 之间的孤立 \r：等待下一个分片再判断
      if (buffer === '\r') return
    },
    /** 流结束时调用：处理残留的无换行尾巴，并派发未闭合的事件 */
    flush(): void {
      if (buffer !== '') {
        let line = buffer
        if (line.endsWith('\r')) line = line.slice(0, -1)
        buffer = ''
        if (line !== '') handleLine(line)
      }
      dispatch()
    }
  }
}

/**
 * 消费一个 fetch 响应体流并派发 SSE 事件。
 * 无论正常结束还是中途异常，都会在 finally 中释放 reader。
 */
export async function parseSseStream(
  body: ReadableStream<Uint8Array>,
  onMessage: SseHandler,
  signal?: AbortSignal
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  const parser = createSseParser(onMessage)

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      if (value) parser.push(decoder.decode(value, { stream: true }))
      if (signal?.aborted) break
    }
    parser.push(decoder.decode())
    parser.flush()
  } finally {
    try {
      await reader.cancel()
    } catch {
      // 流已结束或已取消，忽略
    }
    reader.releaseLock()
  }
}
