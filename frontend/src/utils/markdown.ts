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
 * 修补「中文里加粗闭不上」的问题。
 *
 * <p><b>问题</b>：CommonMark 对强调定界符有 flanking 规则 —— 闭合 {@code **} 若
 * **前一个字符是标点**，则还必须**后接空白或标点**才算合法闭合。而中文没有词间空格，
 * 于是下面这种极常见的写法会整对失效、把星号原样显示出来：</p>
 * <pre>
 *   以上为**投标保函（TENDER）**口径，即全量。
 *                          ↑ 前是「）」标点，后是「口」汉字 → 闭不上
 * </pre>
 * <p>实测对照（markdown-it 14）：</p>
 * <table>
 *   <tr><td>{@code **投标保函（TENDER）**口径}</td><td>❌ 原样</td></tr>
 *   <tr><td>{@code **投标保函(TENDER)**口径}（半角括号）</td><td>❌ 原样</td></tr>
 *   <tr><td>{@code **投标保函（TENDER）** 口径}（后接空格）</td><td>✅</td></tr>
 *   <tr><td>{@code **投标保函（TENDER）**，即全量}（后接标点）</td><td>✅</td></tr>
 *   <tr><td>{@code **投标保函**口径}（内容不以标点结尾）</td><td>✅</td></tr>
 * </table>
 *
 * <p><b>为什么不在源码里补空格 / 零宽字符</b>：补普通空格在中文里很显眼；零宽空格
 * （U+200B）与 ZWNBSP（U+FEFF）markdown-it 并不认作空白，补了也没用；发丝空格
 * （U+200A）虽然有效，但等于往正文里塞了不可见字符，复制时会一起带走。
 * 因此改为**在渲染结果上修补**：markdown-it 已经消费掉所有**语法合法**的强调，
 * 剩下的字面量 {@code **} 必定是它放弃处理的那些，补成 {@code <strong>} 恰好是作者本意，
 * 且**一个字符都不改动正文**。</p>
 *
 * <p><b>为什么要跳过 code / pre</b>：代码块里的 {@code **} 必须保持字面量，
 * 否则会把示例代码改坏。</p>
 *
 * <p><b>安全性</b>：只处理两个标签之间的文本节点，不碰标签与属性；且入参是
 * {@code html:false} 下 markdown-it 已转义过的 HTML（模型输出的 {@code <script>}
 * 早已变成 {@code &lt;script&gt;}），这里只额外插入自己构造的 {@code <strong>}，
 * 不引入新的注入面。</p>
 */
function repairUnclosedEmphasis(html: string): string {
  // 用捕获组切分：偶数下标是文本节点，奇数下标是标签
  const parts = html.split(/(<[^>]*>)/)
  let codeDepth = 0

  for (let i = 0; i < parts.length; i += 1) {
    const part = parts[i]
    if (part.startsWith('<')) {
      if (/^<(code|pre)(\s|>)/i.test(part)) {
        codeDepth += 1
      } else if (/^<\/(code|pre)>/i.test(part)) {
        codeDepth = Math.max(0, codeDepth - 1)
      }
      continue
    }
    if (codeDepth === 0) {
      // [^*\n]+ 保证不跨 * 与换行匹配，避免把两处无关的 ** 配成一对
      parts[i] = part.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    }
  }
  return parts.join('')
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
    html = repairUnclosedEmphasis(md.render(source))
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
