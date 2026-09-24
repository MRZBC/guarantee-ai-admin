/**
 * 确认框（ElMessageBox）正文的排版工具。
 *
 * 背景：危险动作的确认文案是「一句话 + 若干条影响」。早期写法把 `① …；② …；③ …。`
 * 直接拼成一段字符串——而 `ElMessageBox` 的正文只是一个 `<p>`，既没有换行也没有列表
 * 结构，在默认 420px 宽度下挤成一整块，信息密度过高、读不出层次。
 *
 * 渲染依赖：正文是**纯文本**，换行靠全局样式
 * `frontend/src/styles/main.css` 里的 `.el-message-box__message p { white-space: pre-line }`。
 * 若该样式被删，`\n` 会被折叠，本工具退化成一行。
 *
 * 为什么不用 `dangerouslyUseHTMLString`：这些文案普遍插值了来自数据库的数据
 * （用户名、角色名、部门名、机构名…）。走 HTML 渲染等于把这些数据当标记解析，
 * 只要有一处忘记转义就是一个存储型 XSS——为排版付这个代价不划算。
 *
 * @param lead  首行的一句话（例如「确认重置「张三」的密码为系统默认密码？」）
 * @param items 逐条影响，每项渲染为独占一行的「• xxx」；空项会被过滤
 */
export function confirmText(lead: string, items: string[]): string {
  const lines = items
    .filter((item) => item && item.trim() !== '')
    .map((item) => `• ${item}`)
  return lines.length === 0 ? lead : [lead, '', ...lines].join('\n')
}
