#!/usr/bin/env node
/**
 * 助手「黄金问题集」真机冒烟脚本（需求真源 docs/REQ-助手业务分析能力阶段二收尾.md §5.3.2 / TEST-BA-06；
 * 阶段三扩容见 docs/REQ-第三阶段-RAG业务知识.md §5.2.1 / REQ-RAG-10）。
 *
 * 它做一件事：把固定的 25 个问题依次打给**真实后端**（真实模型），
 * 按每条问题的期望值判定通过与否，并输出「调用次数 / 轮次 / 耗时 / 正文字数」对照表。
 * 这是「阶段二 + 阶段三完成」的验收证据，也是阶段五 Evaluation 的起点。
 *
 * 用法：
 *   node scripts/ai-golden-questions.mjs
 *   BASE_URL=http://localhost:8081 GOLDEN_USER=admin GOLDEN_PASSWORD=Admin@123 node scripts/ai-golden-questions.mjs
 *   node scripts/ai-golden-questions.mjs --only=GQ-01,GQ-16     # 只跑指定几条
 *   node scripts/ai-golden-questions.mjs --self-check           # 静态自检（不用后端/模型）：
 *                                                              # id 唯一、知识类带 mustCall、
 *                                                              # 与 docs 的编号一一对应
 *
 * 阶段三新增的三类问题需要额外条件（不满足时**报"未跑"而不是"通过"**）：
 *   - GQ-24 用**只读账号**（VIEWER）提问，验证审计口径类知识不出现；
 *     可用 GOLDEN_VIEWER_USER / GOLDEN_VIEWER_PASSWORD 覆盖（默认 user0015 / User@123）。
 *   - GQ-25 需要后端以 `guarantee.ai.knowledge.enabled=false` 重启；
 *     设 `GOLDEN_KNOWLEDGE_DISABLED=1` 声明"当前实例确实关掉了知识层"，否则该项报"未跑"。
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
 *     逐字断言只会带来假失败；
 *   - **"未跑"必须显式**：缺少只读账号、实例没关知识层等都属于"没验证"，
 *     既不算通过也不算失败，单独统计（阶段三的红线是"不得把未跑写成通过"）。
 */
import { setTimeout as sleep } from 'node:timers/promises'
import { readFileSync } from 'node:fs'

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
 * expect.notContains   正文不得出现的片段（内部术语/越界承诺/不该出现的知识条目号）
 * expect.refusal       是否属于"应当如实拒绝/说明"类
 * expect.maxToolCalls  工具调用次数上限（超出=又退回蛮力枚举）
 * expect.maxRounds     工具轮次上限（轮次 = 被 reset 分隔的调用批次）
 * expect.mustCall      必须真的调用过这些工具（**正向**断言：新工具落地后，"模型是否真的用了它"
 *                      不能只看正文措辞——不看这个，模型继续用旧工具蛮力枚举也照样"看起来对"）
 *
 * as                   该条问题用哪个账号提问（阶段三 GQ-24 需要只读账号；缺省用 GOLDEN_USER）
 * requires             该条问题的额外前置声明（'knowledge-disabled' = 当前实例必须已关闭知识层，
 *                      否则报"未跑"）
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
    expect: {
      contains: ['浙江', '险种'],
      mustCall: ['queryOrderDistribution'],
      maxToolCalls: 8,
      maxRounds: 3
    }
  },
  {
    id: 'GQ-04',
    category: '交叉维度（M2.1 新增能力）',
    question: '江苏省 2026 年第二季度各承保机构的订单量排名如何？',
    expect: {
      contains: ['江苏', '机构'],
      mustCall: ['queryOrderDistribution'],
      maxToolCalls: 6,
      maxRounds: 3
    }
  },
  // ---- 趋势 ----
  {
    id: 'GQ-05',
    category: '趋势（M2.1 新增能力）',
    question: '2026 年投标订单的保费按月走势如何？哪个月拐点最明显？',
    expect: {
      contains: ['月'],
      // 按月趋势必须出现形如 2026-01 的周期（否则"趋势"是模型自己编的叙述）
      matches: [/\d{4}-\d{2}/],
      mustCall: ['queryOrderTrend'],
      maxToolCalls: 6,
      maxRounds: 3
    }
  },
  {
    id: 'GQ-06',
    category: '趋势（M2.1 新增能力）',
    question: '2026 年各季度投标订单量按季度汇总的趋势是怎样的？',
    expect: {
      contains: ['季度'],
      mustCall: ['queryOrderTrend'],
      maxToolCalls: 6,
      maxRounds: 3
    }
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
    expect: { contains: ['投标保函（标准）'], maxToolCalls: 3, maxRounds: 3 }
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
  },
  // ---- 阶段三：业务知识检索（RAG → 业务知识，REQ-RAG-10 / docs/REQ-第三阶段-RAG业务知识.md §5.2.1）----
  // 知识类问题一律正向断言 mustCall: queryBusinessKnowledge——不这样断，
  // "模型凭记忆瞎答"与"真的查了知识库"在正文上无法区分（阶段二为防蛮力枚举补 mustCall 的同款理由）。
  // 知识来源行的断言用正则而不是逐字：条目内容改了会涨版本（v2/v3），逐字断言会假失败。
  {
    id: 'GQ-16',
    category: '知识·定义（有收录）',
    question: '停用和删除有什么区别？',
    expect: {
      contains: ['停用', '删除'],
      matches: [/知识来源：[^\n]*KB-SYSTEM-(0009|0011)/],
      mustCall: ['queryBusinessKnowledge'],
      maxToolCalls: 4,
      maxRounds: 2
    }
  },
  {
    id: 'GQ-17',
    category: '知识·定义（有收录）',
    question: '保额区间的口径是怎么规定的？只讲规定，不要给统计数字。',
    expect: {
      contains: ['保额区间'],
      matches: [/知识来源：[^\n]*KB-ORDER-0001/],
      // 定义类问题不得顺手给统计数字：服务端摘要/口径行一旦出现，说明模型误用了业务工具
      notContains: ['数据摘要（服务端生成）', '口径：订单统计'],
      mustCall: ['queryBusinessKnowledge'],
      maxToolCalls: 4,
      maxRounds: 2
    }
  },
  {
    id: 'GQ-18',
    category: '知识·定义（有收录）',
    question: '区域编码的层级前缀匹配是什么意思？选省和选市有什么区别？',
    expect: {
      contains: ['区域'],
      matches: [/知识来源：[^\n]*KB-ORDER-0002/],
      mustCall: ['queryBusinessKnowledge'],
      maxToolCalls: 4,
      maxRounds: 2
    }
  },
  {
    id: 'GQ-19',
    category: '知识·定义（有收录）',
    question: '逻辑删除是什么意思？删除之后还能恢复吗？',
    expect: {
      contains: ['删除'],
      matches: [/知识来源：[^\n]*KB-SYSTEM-(0009|0011)/],
      mustCall: ['queryBusinessKnowledge'],
      maxToolCalls: 4,
      maxRounds: 2
    }
  },
  {
    id: 'GQ-20',
    category: '知识·混合（定义 + 统计）',
    question: '保额区间的规则是怎么规定的？另外，平台上「投标保函（标准）」现在配置的区间是多少？',
    expect: {
      contains: ['保额区间'],
      // 混合类必须**两行都在**且分开：知识来源行（服务端）+ 数据口径行（服务端）
      matches: [/知识来源：/, /口径：/],
      mustCall: ['queryBusinessKnowledge', 'queryInsuranceType'],
      maxToolCalls: 6,
      maxRounds: 3
    }
  },
  {
    id: 'GQ-21',
    category: '知识·混合（定义 + 统计）',
    question: '险种的基准费率口径是什么？顺便告诉我「投标保函（标准）」现在的基准费率是多少。',
    expect: {
      contains: ['费率'],
      matches: [/知识来源：/, /口径：/],
      mustCall: ['queryBusinessKnowledge', 'queryInsuranceType'],
      maxToolCalls: 6,
      maxRounds: 3
    }
  },
  {
    id: 'GQ-22',
    category: '知识·未收录',
    question: '保证金退还流程是怎样的？',
    expect: {
      // 未收录：如实说没有这条知识，且**不得**出现任何知识来源行（AC-RAG-03）
      matches: [/未收录|没有收录|未收集|知识库里没有|查不到/],
      notContains: ['知识来源：'],
      mustCall: ['queryBusinessKnowledge'],
      maxToolCalls: 4,
      maxRounds: 2
    }
  },
  {
    id: 'GQ-23',
    category: '知识·未收录',
    question: '保证金专户的规定是什么？',
    expect: {
      matches: [/未收录|没有收录|未收集|知识库里没有|查不到/],
      notContains: ['知识来源：'],
      mustCall: ['queryBusinessKnowledge'],
      maxToolCalls: 4,
      maxRounds: 2
    }
  },
  {
    id: 'GQ-24',
    category: '知识·越权（只读用户不得看到审计口径）',
    question: '操作审计记录里敏感字段是怎么记录的？',
    // 只读用户（VIEWER）：没有 system:audit:view → 审计口径条目必须被服务端裁掉（AC-RAG-06）
    as: {
      user: process.env.GOLDEN_VIEWER_USER || 'user0015',
      password: process.env.GOLDEN_VIEWER_PASSWORD || 'User@123'
    },
    expect: {
      // 断言口径按 Lead 裁定：条目号与标题**不出现**，而不是要求"返回 0 条"
      // （同一次检索可能命中其它无关条目，那不是越权）
      notContains: ['KB-SYSTEM-0010', '操作审计记录的内容与渠道'],
      mustCall: ['queryBusinessKnowledge'],
      maxToolCalls: 4,
      maxRounds: 3
    }
  },
  {
    id: 'GQ-25',
    category: '知识·降级（关掉知识层）',
    question: '2026 年第二季度投标订单量是多少？另外，你现在还能查业务知识库吗？',
    // 需要后端以 guarantee.ai.knowledge.enabled=false 重启；
    // 未声明该前置条件时报"未跑"，绝不算通过
    requires: 'knowledge-disabled',
    expect: {
      contains: ['订单'],
      matches: [/不可用|没有|未开通|无法.*知识|关闭|不能/],
      notContains: ['知识来源：', 'queryBusinessKnowledge'],
      maxToolCalls: 6,
      maxRounds: 3
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

async function login(user = ACCOUNT, secret = SECRET) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user, password: secret })
  })
  if (!res.ok) {
    throw new Error(`登录失败 HTTP ${res.status}：${(await res.text()).slice(0, 200)}`)
  }
  const body = await res.json()
  const token = body?.data?.token
  if (!token) throw new Error(`登录响应里没有 token：${JSON.stringify(body).slice(0, 200)}`)
  return token
}

/** 按账号缓存 token：同一次运行里每个账号只登录一次。 */
const tokenCache = new Map()

async function tokenFor(user, secret) {
  const key = `${user}::${secret}`
  if (!tokenCache.has(key)) {
    tokenCache.set(key, await login(user, secret))
  }
  return tokenCache.get(key)
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
    // 模型自己写的正文（剥掉服务端追加的尾部）。内部术语检查只看这一段：
    // 口径行/数据摘要由服务端生成、内容直接取自工具返回值，那里出现编码属于
    // dataSource 的问题（例如 GQ-10 抓到的「险种类别：TENDER」），不是模型违规。
    prose: proseOf(text),
    toolCalls,
    // 轮次口径与前端一致：每发生一次 reset 代表"有一轮带工具调用的前言被丢弃"，
    // 再加最后一轮正文；没有工具调用时就是 1 轮
    rounds: toolCalls.length === 0 ? 0 : rounds + 1,
    done,
    errorMessage,
    elapsedMs: Date.now() - started
  }
}

/** 服务端尾部（口径页脚 + 数据摘要 + 知识来源行）的起始标记；正文 = 这些标记之前的部分。 */
const SERVER_TAIL_MARKERS = ['\n\n口径：', '\n\n数据摘要（服务端生成）', '\n\n知识来源：']

function proseOf(text) {
  let cut = text.length
  for (const marker of SERVER_TAIL_MARKERS) {
    const index = text.indexOf(marker)
    if (index !== -1 && index < cut) cut = index
  }
  return text.slice(0, cut)
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
    if ((result.prose ?? result.text).includes(term)) {
      reasons.push(`正文泄漏内部术语「${term}」`)
    }
  }
  if (item.expect.refusal) {
    const refused = /(不支持|无法|不能|没有.*(权限|工具|数据)|查不到|做不到|建议)/.test(result.text)
    if (!refused) reasons.push('越界/缺能力问题没有如实说明（疑似硬答）')
  }
  for (const name of item.expect.mustCall ?? []) {
    if (!result.toolCalls.some((call) => call.name === name && call.status === 'SUCCESS')) {
      reasons.push(`没有成功调用必需的工具 ${name}（说明模型没走新能力，可能又退回旧工具蛮力枚举）`)
    }
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

/**
 * 某条问题的额外前置条件；不满足时返回原因（该条记"未跑"）。
 *
 * <p>为什么必须显式：阶段三有两条问题依赖**环境**（只读账号、知识层关闭）。
 * 把"没验证"算成"通过"是本项目明确禁止的做法。</p>
 */
function skipReasonFor(item) {
  if (item.requires === 'knowledge-disabled' && process.env.GOLDEN_KNOWLEDGE_DISABLED !== '1') {
    return '需要后端以 guarantee.ai.knowledge.enabled=false 重启，并设 GOLDEN_KNOWLEDGE_DISABLED=1'
  }
  return null
}

async function main() {
  console.log(`黄金问题集冒烟：${BASE}（默认用户 ${ACCOUNT}）`)
  await tokenFor(ACCOUNT, SECRET)
  console.log('登录成功\n')

  const selected = ONLY ? QUESTIONS.filter((q) => ONLY.split(',').includes(q.id)) : QUESTIONS
  if (selected.length === 0) {
    console.error(`--only=${ONLY} 没匹配到任何问题`)
    process.exitCode = 2
  }

  const rows = []
  let failed = 0
  let skipped = 0
  for (const item of selected) {
    process.stdout.write(`${item.id} [${item.category}] … `)

    const skipReason = skipReasonFor(item)
    if (skipReason) {
      skipped += 1
      const verdict = { pass: false, skipped: true, reasons: [skipReason] }
      rows.push({ item, result: { skipped: true }, verdict })
      console.log(`SKIP（未跑：${skipReason}）`)
      continue
    }

    const user = item.as?.user || ACCOUNT
    const secret = item.as?.password || SECRET
    let token
    try {
      token = await tokenFor(user, secret)
    } catch (error) {
      skipped += 1
      const reason = `账号 ${user} 登录失败（${error.message}）→ 未跑`
      const verdict = { pass: false, skipped: true, reasons: [reason] }
      rows.push({ item, result: { skipped: true }, verdict })
      console.log(`SKIP（${reason}）`)
      continue
    }

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
    const note = verdict.pass
      ? ''
      : (verdict.skipped ? `未跑：${verdict.reasons.join('；')}` : verdict.reasons.join('；')).replace(/\|/g, '/')
    const mark = verdict.pass ? '✅' : verdict.skipped ? '⏭ 未跑' : '❌'
    console.log(
      `| ${item.id} | ${item.category} | ${mark} | ${result.toolCalls?.length ?? 0} | ` +
        `${result.rounds ?? 0} | ${((result.elapsedMs ?? 0) / 1000).toFixed(1)} | ${(result.text ?? '').length} | ${note} |`
    )
  }

  const passed = rows.length - failed - skipped
  const skippedNote = skipped > 0 ? `，${skipped} 条未跑（前置条件不满足，不计入通过）` : ''
  console.log(`\n结果：${passed}/${rows.length - skipped} 通过${skippedNote}`)
  if (failed > 0) {
    console.log('\n失败明细（正文片段，便于人工判断）：')
    for (const { item, result, verdict } of rows.filter((r) => !r.verdict.pass && !r.verdict.skipped)) {
      console.log(`\n--- ${item.id} ${item.question}`)
      console.log(`原因：${verdict.reasons.join('；')}`)
      console.log(`正文：${(result.text ?? '').slice(0, 400)}`)
    }
  }
  process.exitCode = failed > 0 ? 1 : 0
}

// ---------------------------------------------------------------------------
// 静态自检（--self-check）：不需要后端、不需要模型，验证"问题集本身"的一致性
// ---------------------------------------------------------------------------

/**
 * 静态自检：id 唯一且合法、每条都有可判定的期望、知识类必须带 mustCall、
 * 且与 `docs/TEST-助手黄金问题集.md` 的编号一一对应（文档与脚本不许各说各话）。
 *
 * <p>为什么值得有：真机集合依赖 API Key，本机跑不了；但"脚本漏了一条""文档多了一条"
 * 这类问题不需要模型就能发现，而且正是它们会让验收结论对不上号。</p>
 */
function selfCheck() {
  const problems = []
  const seen = new Set()
  for (const item of QUESTIONS) {
    if (!/^GQ-\d{2}$/.test(item.id ?? '')) problems.push(`id 不是 GQ-NN 形式：${item.id}`)
    if (seen.has(item.id)) problems.push(`id 重复：${item.id}`)
    seen.add(item.id)
    if (!item.question || !item.expect) {
      problems.push(`${item.id} 缺少 question 或 expect`)
      continue
    }
    // 知识类问题必须正向断言"真的调用了检索"；唯一例外是**降级类**（知识层被关闭，
    // 它断言的是"不调用也照常答数字"）
    const mustRetrieve = (item.expect.mustCall ?? []).includes('queryBusinessKnowledge')
    if (item.category?.startsWith('知识') && !mustRetrieve && item.requires !== 'knowledge-disabled') {
      problems.push(`${item.id} 是知识类问题，但没有 mustCall: queryBusinessKnowledge（无法证明真的用了检索）`)
    }
  }
  if (QUESTIONS.length < 25) problems.push(`问题集条数 ${QUESTIONS.length} < 25`)

  let doc = ''
  try {
    doc = readFileSync(new URL('../docs/TEST-助手黄金问题集.md', import.meta.url), 'utf8')
  } catch (error) {
    problems.push(`读不到 docs/TEST-助手黄金问题集.md：${error.message}`)
  }
  const docIds = new Set([...doc.matchAll(/GQ-\d{2}/g)].map((m) => m[0]))
  for (const id of seen) {
    if (!docIds.has(id)) problems.push(`文档缺少 ${id}（脚本有、文档没有）`)
  }
  for (const id of docIds) {
    if (!seen.has(id)) problems.push(`脚本缺少 ${id}（文档有、脚本没有）`)
  }

  if (problems.length > 0) {
    console.error('静态自检失败：')
    for (const problem of problems) console.error(`  - ${problem}`)
    return 1
  }
  console.log(`静态自检通过：${QUESTIONS.length} 条问题，编号与 docs/TEST-助手黄金问题集.md 一一对应`)
  return 0
}

if (process.argv.includes('--self-check')) {
  process.exitCode = selfCheck()
} else {
  main().catch((error) => {
    console.error(`\n脚本自身失败（不是断言失败）：${error.message}`)
    console.error('检查：后端是否已重启到最新代码、演示数据是否已初始化、DEEPSEEK_API_KEY 是否有效')
    process.exitCode = 2
  })
}
