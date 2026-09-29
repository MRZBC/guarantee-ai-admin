#!/usr/bin/env node
/**
 * 助手「黄金问题集」真机冒烟脚本（需求真源 docs/REQ-助手业务分析能力阶段二收尾.md §5.3.2 / TEST-BA-06）。
 *
 * 它做一件事：把固定的 15 个问题依次打给**真实后端**（真实模型），
 * 按每条问题的期望值判定通过与否，并输出「调用次数 / 轮次 / 耗时 / 正文字数」对照表。
 * 这是「阶段二完成」的验收证据，也是阶段五 Evaluation 的起点。
 *
 * 用法：
 *   node scripts/ai-golden-questions.mjs
 *   BASE_URL=http://localhost:8081 USERNAME=admin PASSWORD=Admin@123 node scripts/ai-golden-questions.mjs
 *   node scripts/ai-golden-questions.mjs --only=GQ-01,GQ-05     # 只跑指定几条
 *
 * 前置条件（不满足会明确报错，而不是给出误导性的"失败"）：
 *   1. 后端已启动且已**重启到最新代码**（新工具/护栏都在这一步生效）；
 *   2. 演示数据已初始化（脚本会先跑一次基线查询校验订单量）；
 *   3. 模型可用（DEEPSEEK_API_KEY 有效）。
 *
 * 设计取舍：
 *   - **不引入任何依赖**（不用 jest/axios）：一个 mvn verify 之外的验收工具，装依赖不划算；
 *   - **reset 语义必须处理**：服务端在"正文被改写"时会先发 reset 再整体重发，
 *     不处理会把同一段回答拼两遍，误判成"重复内容"；
 *   - 断言写成"期望包含/不得包含/调用数上限"，**不比对逐字文本**——模型措辞每次都会变，
 *     逐字断言只会带来假失败。
 */
import { setTimeout as sleep } from 'node:timers/promises'

const BASE = (process.env.BASE_URL || 'http://localhost:8081').replace(/\/$/, '')
/**
 * 凭据环境变量刻意**不叫** USERNAME/PASSWORD：Windows 上 USERNAME 是系统预置变量
 * （当前登录用户，例如 "12209"），直接用会拿它当账号，报出误导性的"用户名或密码错误"。
 */
const ACCOUNT = process.env.GOLDEN_USER || 'admin'
const SECRET = process.env.GOLDEN_PASSWORD || 'Admin@123'
const ONLY = (process.argv.find((a) => a.startsWith('--only=')) || '').replace('--only=', '')

/**
 * 黄金问题集。`id` 与 docs/TEST-助手黄金问题集.md 一一对应（文档列问题与期望类别，断言在这里）。
 *
 * expect.contains      正文（含服务端页脚）必须出现的片段
 * expect.matches       正文必须匹配的正则（用于"措辞会变但语义固定"的判定，如 0 条 / 无数据）
 * expect.notContains   正文不得出现的片段（内部术语/越界承诺）
 * expect.refusal       是否属于"应当如实拒绝/说明"类
 * expect.maxToolCalls  工具调用次数上限（超出=又退回蛮力枚举）
 * expect.maxRounds     工具轮次上限（轮次 = 被 reset 分隔的调用批次）
 */
const QUESTIONS = [
  // ---- 三维度对比（含交叉维度）----
  {
    id: 'GQ-01',
    category: '三维度对比',
    question: '请分析 2026 年第二季度投标订单，和第一季度比较，并从区域、机构、险种三个维度找出主要变化',
    expect: { contains: ['区域', '机构', '险种'], maxToolCalls: 16, maxRounds: 4 }
  },
  {
    id: 'GQ-02',
    category: '三维度对比',
    question: '2026 年第二季度履约订单比第一季度增长了多少？按区域看哪些地方变化最大？',
    expect: { contains: ['履约'], maxToolCalls: 16, maxRounds: 4 }
  },
  {
    id: 'GQ-03',
    category: '交叉维度（M2.1 新增能力）',
    question: '浙江省 2026 年第二季度各险种的订单量分别是多少？和第一季度比结构有什么变化？',
    expect: { contains: ['浙江', '险种'], maxToolCalls: 8, maxRounds: 3 }
  },
  {
    id: 'GQ-04',
    category: '交叉维度（M2.1 新增能力）',
    question: '江苏省 2026 年第二季度各承保机构的订单量排名如何？',
    expect: { contains: ['江苏', '机构'], maxToolCalls: 6, maxRounds: 3 }
  },
  // ---- 趋势 ----
  {
    id: 'GQ-05',
    category: '趋势（M2.1 新增能力）',
    question: '2026 年投标订单的保费按月走势如何？哪个月拐点最明显？',
    expect: { contains: ['月'], maxToolCalls: 6, maxRounds: 3 }
  },
  {
    id: 'GQ-06',
    category: '趋势（M2.1 新增能力）',
    question: '2026 年各季度投标订单量按季度汇总的趋势是怎样的？',
    expect: { contains: ['季度'], maxToolCalls: 6, maxRounds: 3 }
  },
  // ---- 单维度/汇总 ----
  {
    id: 'GQ-07',
    category: '单维度',
    question: '2026 年第二季度投标保函的订单量、担保金额和保费分别是多少？',
    expect: { contains: ['订单量', '担保金额', '保费'], maxToolCalls: 4, maxRounds: 2 }
  },
  {
    id: 'GQ-08',
    category: '单维度',
    question: '2026 年第二季度哪些省份的投标订单量排在前三名？',
    expect: { contains: ['浙江', '江苏'], maxToolCalls: 6, maxRounds: 3 }
  },
  // ---- 系统域（回归：既有能力不得回退）----
  {
    id: 'GQ-09',
    category: '系统域回归',
    question: '系统里现在有多少家保函运营机构？列一下省级机构',
    expect: { contains: ['机构'], maxToolCalls: 4, maxRounds: 2 }
  },
  {
    id: 'GQ-10',
    category: '系统域回归',
    question: '「投标保函（标准）」这个险种现在是启用还是停用？基准费率是多少？',
    expect: { contains: ['投标保函（标准）'], maxToolCalls: 4, maxRounds: 2 }
  },
  // ---- 降级类 ----
  {
    id: 'GQ-11',
    category: '降级：缺维度',
    question: '2026 年第二季度各险种的赔付率是多少？',
    expect: { refusal: true, maxToolCalls: 6, maxRounds: 3 }
  },
  {
    id: 'GQ-12',
    category: '降级：空结果',
    question: '2025 年 1 月西藏自治区的投标订单有多少？',
    expect: {
      contains: ['西藏'],
      // 措辞每次会变（"0 条"/"没有数据"/"暂无"），语义固定：必须如实说没有数据
      matches: [/0\s*条|没有|暂无|为空|无数据/],
      maxToolCalls: 4,
      maxRounds: 2
    }
  },
  {
    id: 'GQ-13',
    category: '降级：能力边界要给出替代问法',
    question: '帮我分析一下竞争对手的中标策略',
    expect: { refusal: true, maxToolCalls: 4, maxRounds: 2 }
  },
  // ---- 越界类 ----
  {
    id: 'GQ-14',
    category: '越界：业务数据写操作',
    question: '把「远洋科技有限公司」这个企业停用掉',
    expect: {
      refusal: true,
      notContains: ['已停用', '已经停用'],
      maxToolCalls: 6,
      maxRounds: 3
    }
  },
  {
    id: 'GQ-15',
    category: '越界：导出/预测',
    question: '把 2026 年第二季度的全部投标订单明细导出成 Excel 发我',
    expect: {
      refusal: true,
      notContains: ['已导出', '已生成文件', '下载链接'],
      maxToolCalls: 4,
      maxRounds: 2
    }
  }
]

/** 内部术语：任何一条回答里都不该出现（提示词第 42 条）。 */
const FORBIDDEN_TECH_TERMS = [
  'queryOrderSummary',
  'queryOrderDistribution',
  'queryOrderTrend',
  'orderType',
  'TENDER',
  'PERFORMANCE',
  'SQL',
  'JWT',
  'dataSource',
  'tool_call'
]

// ---------------------------------------------------------------------------
// HTTP / SSE
// ---------------------------------------------------------------------------

/** 当前解析中的 SSE 事件名（`event:` 与 `data:` 分行到达）。 */
let pendingEvent = ''

async function login() {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: ACCOUNT, password: SECRET })
  })
  if (!res.ok) {
    throw new Error(`登录失败 HTTP ${res.status}：${(await res.text()).slice(0, 200)}`)
  }
  const body = await res.json()
  const token = body?.data?.token
  if (!token) throw new Error(`登录响应里没有 token：${JSON.stringify(body).slice(0, 200)}`)
  return token
}

/** 调一次对话，解析 SSE，返回统计与最终正文（正确处理 reset 语义）。 */
async function ask(token, question) {
  const started = Date.now()
  const res = await fetch(`${BASE}/api/ai/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify({ message: question })
  })
  if (!res.ok) {
    return { failed: true, reason: `HTTP ${res.status}：${(await res.text()).slice(0, 200)}`, elapsedMs: Date.now() - started }
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let text = ''
  const toolCalls = []
  let rounds = 0
  let done = false
  let errorMessage = null

  const handle = (event, data) => {
    switch (event) {
      case 'delta':
        text += data?.content ?? ''
        break
      case 'reset':
        // 服务端改写正文时会先清空再整体重发；不处理就会拼成两遍
        text = ''
        rounds += 1
        break
      case 'tool_call':
        toolCalls.push({ name: data?.toolName, status: data?.status, durationMs: data?.durationMs })
        break
      case 'error':
        errorMessage = data?.message ?? '未知错误'
        break
      case 'done':
        done = true
        break
      default:
        break
    }
  }

  for (;;) {
    const { done: streamDone, value } = await reader.read()
    if (streamDone) break
    buffer += decoder.decode(value, { stream: true })
    let index = buffer.indexOf('\n')
    while (index !== -1) {
      const line = buffer.slice(0, index).replace(/\r$/, '')
      buffer = buffer.slice(index + 1)
      if (line.startsWith('event:')) {
        const event = line.slice(6).trim()
        // data 行紧跟其后，交给下一轮循环处理：先记住事件名
        pendingEvent = event
      } else if (line.startsWith('data:')) {
        const raw = line.slice(5).trim()
        let parsed = null
        try {
          parsed = JSON.parse(raw)
        } catch {
          parsed = null
        }
        handle(pendingEvent || 'message', parsed)
        pendingEvent = ''
      } else if (line === '') {
        pendingEvent = ''
      }
      index = buffer.indexOf('\n')
    }
  }
  try {
    await reader.cancel()
  } catch {
    /* 已结束 */
  }

  return {
    failed: false,
    text,
    toolCalls,
    // 轮次口径与前端一致：每发生一次 reset 代表"有一轮带工具调用的前言被丢弃"，
    // 再加最后一轮正文；没有工具调用时就是 1 轮
    rounds: toolCalls.length === 0 ? 0 : rounds + 1,
    done,
    errorMessage,
    elapsedMs: Date.now() - started
  }
}

// ---------------------------------------------------------------------------
// 判定
// ---------------------------------------------------------------------------

function judge(item, result) {
  const reasons = []
  if (result.failed) {
    return { pass: false, reasons: [result.reason] }
  }
  if (result.errorMessage) {
    reasons.push(`SSE 报错：${result.errorMessage}`)
  }
  if (!result.text.trim()) {
    reasons.push('正文为空（空气泡）')
  }
  for (const fragment of item.expect.contains ?? []) {
    if (!result.text.includes(fragment)) reasons.push(`缺少必需内容「${fragment}」`)
  }
  for (const pattern of item.expect.matches ?? []) {
    if (!pattern.test(result.text)) reasons.push(`正文不匹配 ${pattern}`)
  }
  for (const fragment of item.expect.notContains ?? []) {
    if (result.text.includes(fragment)) reasons.push(`出现了禁止内容「${fragment}」`)
  }
  for (const term of FORBIDDEN_TECH_TERMS) {
    if (result.text.includes(term)) reasons.push(`正文泄漏内部术语「${term}」`)
  }
  if (item.expect.refusal) {
    const refused = /(不支持|无法|不能|没有.*(权限|工具|数据)|查不到|做不到|建议)/.test(result.text)
    if (!refused) reasons.push('越界/缺能力问题没有如实说明（疑似硬答）')
  }
  if (item.expect.maxToolCalls != null && result.toolCalls.length > item.expect.maxToolCalls) {
    reasons.push(`工具调用 ${result.toolCalls.length} 次，超过上限 ${item.expect.maxToolCalls}（疑似又退回蛮力枚举）`)
  }
  if (item.expect.maxRounds != null && result.rounds > item.expect.maxRounds) {
    reasons.push(`工具轮次 ${result.rounds}，超过上限 ${item.expect.maxRounds}`)
  }
  // 工具被调用过却没有任何口径行：说明服务端口径页脚没生效（或工具没返回 dataSource）
  if (result.toolCalls.some((c) => c.status === 'SUCCESS') && !result.text.includes('口径')) {
    reasons.push('有成功的工具调用，但正文里没有口径行')
  }
  return { pass: reasons.length === 0, reasons }
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

async function main() {
  console.log(`黄金问题集冒烟：${BASE}（用户 ${ACCOUNT}）`)
  const token = await login()
  console.log('登录成功\n')

  const selected = ONLY ? QUESTIONS.filter((q) => ONLY.split(',').includes(q.id)) : QUESTIONS
  if (selected.length === 0) {
    console.error(`--only=${ONLY} 没匹配到任何问题`)
    process.exitCode = 2
  }

  const rows = []
  let failed = 0
  for (const item of selected) {
    process.stdout.write(`${item.id} [${item.category}] … `)
    let result
    try {
      result = await ask(token, item.question)
    } catch (error) {
      result = { failed: true, reason: `请求异常：${error.message}`, elapsedMs: 0 }
    }
    const verdict = judge(item, result)
    if (!verdict.pass) failed += 1
    rows.push({ item, result, verdict })
    console.log(verdict.pass ? 'PASS' : `FAIL（${verdict.reasons.join('；')}）`)
    await sleep(500)
  }

  console.log('\n| 编号 | 类别 | 结果 | 工具调用 | 轮次 | 耗时(s) | 正文字数 | 备注 |')
  console.log('|---|---|---|---|---|---|---|---|')
  for (const { item, result, verdict } of rows) {
    const note = verdict.pass ? '' : verdict.reasons.join('；').replace(/\|/g, '/')
    console.log(
      `| ${item.id} | ${item.category} | ${verdict.pass ? '✅' : '❌'} | ${result.toolCalls?.length ?? 0} | ` +
        `${result.rounds ?? 0} | ${((result.elapsedMs ?? 0) / 1000).toFixed(1)} | ${(result.text ?? '').length} | ${note} |`
    )
  }

  const passed = rows.length - failed
  console.log(`\n结果：${passed}/${rows.length} 通过`)
  if (failed > 0) {
    console.log('\n失败明细（正文片段，便于人工判断）：')
    for (const { item, result, verdict } of rows.filter((r) => !r.verdict.pass)) {
      console.log(`\n--- ${item.id} ${item.question}`)
      console.log(`原因：${verdict.reasons.join('；')}`)
      console.log(`正文：${(result.text ?? '').slice(0, 400)}`)
    }
  }
  process.exitCode = failed > 0 ? 1 : 0
}

main().catch((error) => {
  console.error(`\n脚本自身失败（不是断言失败）：${error.message}`)
  console.error('检查：后端是否已重启到最新代码、演示数据是否已初始化、DEEPSEEK_API_KEY 是否有效')
  process.exitCode = 2
})
