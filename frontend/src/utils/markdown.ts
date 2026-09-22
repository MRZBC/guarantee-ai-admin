import MarkdownIt from 'markdown-it'

/**
 * 助手回答的 Markdown 渲染器。
 *
 * <p>安全说明（这里刻意不引入 DOMPurify 一类净化库，理由如下）：</p>
 * <ul>
 *   <li>{@code html: false} —— 不解析原生 HTML。模型输出里的 {@code <script>}、
 *       {@code <img onerror=...>} 等会被当作纯文本转义输出，这是防 XSS 的主要防线。</li>
 *   <li>markdown-it 默认的 {@code validateLink} 会拦截 {@code javascript:} /
 *       {@code vbscript:} / {@code file:} / {@code data:} 等危险协议，
 *       因此链接也不会成为注入点。</li>
 * </ul>
 * <p>只要这两点保持成立，就不需要额外的 HTML 净化步骤；若将来把 {@code html} 改为
 * {@code true}，则必须同时引入净化库。</p>
 */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  // 模型输出里单个换行通常就代表换行意图，开启后行为等价于原先的 white-space: pre-wrap
  breaks: true
})

// 外链一律新窗口打开，并阻断 opener 引用
md.renderer.rules.link_open = (tokens, idx, options, _env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return self.renderToken(tokens, idx, options)
}

function escapeHtml(source: string): string {
  return source
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 渲染缓存。
 *
 * <p>流式输出时每一帧都会重渲染整个消息列表，若每条消息都重新解析 Markdown，
 * 长回答下开销会明显放大。这里按「内容字符串」缓存结果：内容不变的旧消息直接命中，
 * 只有正在追加的那一条会真正重新解析。</p>
 */
const CACHE_LIMIT = 60
const cache = new Map<string, string>()

/** 把 Markdown 渲染成 HTML。渲染失败时退回转义后的纯文本，绝不抛错打断列表渲染。 */
export function renderMarkdown(source: string | null | undefined): string {
  if (!source) return ''

  const cached = cache.get(source)
  if (cached !== undefined) return cached

  let html: string
  try {
    html = md.render(source)
  } catch {
    html = `<p>${escapeHtml(source)}</p>`
  }

  // 先进先出淘汰：流式过程中 key 不断变化，避免缓存无限增长
  if (cache.size >= CACHE_LIMIT) {
    const oldest = cache.keys().next().value
    if (oldest !== undefined) cache.delete(oldest)
  }
  cache.set(source, html)
  return html
}
