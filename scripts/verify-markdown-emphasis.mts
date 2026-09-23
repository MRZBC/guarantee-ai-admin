/**
 * 校验助手回答的 Markdown 渲染：中文里「加粗闭不上」是否已被修补。
 *
 * <p>用法：在仓库根目录执行 {@code node scripts/verify-markdown-emphasis.mts}（需 Node 22+，
 * 依赖其内置的 TypeScript 类型擦除，因此直接 import 前端的 .ts 源码而无需构建）。</p>
 *
 * <p>为什么是独立脚本而不是单元测试：本项目前端未引入测试运行器（见 frontend/package.json），
 * 与 {@code scripts/verify-dept-tree-shape.mjs} 等既有校验脚本保持一致。</p>
 */
import { renderMarkdown } from '../frontend/src/utils/markdown.ts'

/** [说明, 输入, 期望包含, 期望不包含] */
const cases: Array<[string, string, string[], string[]]> = [
  [
    '截图里的元凶：标点结尾 + 闭合 ** 紧跟汉字',
    '- 以上为**投标保函（TENDER）**口径，未按行政区划或承保机构过滤，即全量。',
    ['<strong>投标保函（TENDER）</strong>口径'],
    []
  ],
  ['半角括号同样失效 → 同样修好', '- 以上为**投标保函(TENDER)**口径', ['<strong>投标保函(TENDER)</strong>口径'], []],
  [
    '内容以汉字结尾（markdown-it 本来就能渲染，不能被改坏）',
    '- 上述数字是 **年初至今的累计值**，不是完整年度终值。',
    ['<strong>年初至今的累计值</strong>'],
    []
  ],
  ['一行里多个加粗', '**A（一）**甲 **B（二）**乙 **C（三）**丙', ['<strong>A（一）</strong>甲', '<strong>B（二）</strong>乙', '<strong>C（三）</strong>丙'], []],
  ['行内代码里的 ** 必须原样', '示例：`a**b**c` 不是加粗', ['<code>a**b**c</code>'], ['<code><strong>']],
  ['围栏代码块里的 ** 必须原样', '```\nx**y**z\n```', ['<pre><code>x**y**z'], ['<strong>']],
  [
    '链接 href 属性里的 ** 不能被碰（只有链接文字该加粗）',
    '[**投标保函（TENDER）**口径](https://example.com/a?x=**1**)',
    ['href="https://example.com/a?x=**1**"', '<strong>投标保函（TENDER）</strong>口径'],
    ['<strong>1</strong>']
  ],
  ['奇数个 ** 不误配', '这里只有一个 ** 符号', ['** 符号'], ['<strong>']],
  ['无 ** 的普通文本不受影响', '2026 年第三季度投标订单共 1,234 笔。', ['1,234 笔'], ['<strong>']],
  [
    '表格单元格里的加粗',
    '| 指标 | 数值 |\n| --- | --- |\n| **投标保函（TENDER）**订单量 | 43,380 笔 |',
    ['<td><strong>投标保函（TENDER）</strong>订单量</td>'],
    []
  ],
  [
    'XSS：模型输出的原生 HTML 仍被转义，只多包一层我们自己构造的 strong',
    '**<script>alert(1)</script>**口径',
    ['<strong>&lt;script&gt;alert(1)&lt;/script&gt;</strong>口径'],
    ['<script>']
  ]
]

let failed = 0
for (const [label, src, mustHave, mustNotHave] of cases) {
  const html = renderMarkdown(src)
  const missing = mustHave.filter((s) => !html.includes(s))
  const leaked = mustNotHave.filter((s) => html.includes(s))
  const ok = missing.length === 0 && leaked.length === 0
  if (!ok) failed += 1
  console.log((ok ? 'PASS' : 'FAIL') + ' | ' + label)
  if (!ok) {
    if (missing.length) console.log('       缺少: ' + JSON.stringify(missing))
    if (leaked.length) console.log('       不该出现: ' + JSON.stringify(leaked))
    console.log('       html => ' + JSON.stringify(html))
  }
}
console.log('\n合计 ' + cases.length + ' 项，失败 ' + failed + ' 项')
