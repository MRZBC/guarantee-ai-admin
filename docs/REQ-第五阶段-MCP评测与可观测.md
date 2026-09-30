# 需求文档：路线图第五阶段 —— MCP → Evaluation → Observability

> 版本：v1.0（2026-09-30）
> 上游：`PROJECT.md` 的「Roadmap 阶段路线图」第五阶段；`docs/REQ-助手业务分析能力阶段二收尾.md` §5.3（本阶段的雏形）
> 相关：`docs/TEST-助手黄金问题集.md`（现有评测基线）、`docs/REQ-第三阶段-RAG业务知识.md`、`docs/REQ-第四阶段-AI配置与确认审计.md`
> 一句话：把"能力关在平台里、评测靠手点、运行靠翻库"变成**受控能力可被外部 Agent 复用、评测可自动跑并对比、轮次/调用/成本/耗时可观测**。

---

## 0. 先看结论：需要你拍板的 7 件事

> **决策状态（2026-09-30）：以上（含补充未决项 Q-MCP-08/09）已由用户确认"按建议执行"，编号即拍板结论，实现期间不再逐项确认。**
> 据此：业务 MCP 走 **Node stdio 网关**、只暴露只读工具、默认关闭；指标走 Micrometer + `/actuator/prometheus`（若构件不可得则按 RK-MCP-03 降级为落库 + 页面聚合，并把降级事实写进报告）；
> **不引入 OTel**；成本只报 token 与耗时。
> 实现若发现建议不可行，**先记录不打断**，等三个阶段全部完成后统一讨论。

| 编号 | 问题 | 建议 | 影响面 |
|---|---|---|---|
| Q-MCP-01 | 业务 MCP Server 的实现形态 | **B：独立进程 stdio 网关（Node/TypeScript），经 HTTP 调用平台受控只读接口**。理由：本机 `.m2` **没有任何 MCP 相关构件**，Java 侧要新增依赖并联网下载（可行性未验证）；而 `tools/knowledge-os-mcp/` 已经跑通"TypeScript + MCP SDK + stdio + 真实 JSON-RPC 测试"的完整先例 | 决定是新增 Java 依赖还是复用既有 Node 技术栈 |
| Q-MCP-02 | 外部 Agent 用什么身份 | **新增机器凭据**：服务账号 + 可撤销的 MCP Token，**最小权限只读**。现状**没有任何客户端凭据机制**（全仓 `ApiKey\|client_credentials\|X-API-KEY` 0 命中），拿令牌只能用"用户名 + 密码"登录，且新账号首登会被**强制改密闸门**拦住 | 决定是否新增 token 表与权限码 |
| Q-MCP-03 | MCP 暴露哪些能力 | **只暴露只读工具**（**13 个 `@Tool` 方法**，含第三阶段新增的 `queryBusinessKnowledge`）。写能力**不暴露**；若将来要暴露，只暴露"生成提案"，确认仍必须发生在平台页面内 | 决定外部 Agent 的风险面 |
| Q-MCP-04 | 指标栈 | **Micrometer + `/actuator/prometheus`**。注意本机 `~/.m2` **没有** `micrometer-registry-prometheus`（也没有 `micrometer-tracing`、OTel/zipkin jar，只有 BOM pom），需联网拉取；拉不到则退化为"落库 + 页面聚合"（Q-MCP-06） | 决定依赖与运维形态 |
| Q-MCP-05 | "成本"的口径 | 现状**没有货币成本概念**（全仓搜 `单价\|price\|计费\|USD` 0 命中，`AI_TURN_COST` 的 "cost" 指 token + 耗时）。建议：**先只报 token 与耗时**；若要报金额，必须由第四阶段的配置化提供**单价表**（分模型、可改、可审计） | 决定"成本可观测"这句 DoD 的验收方式 |
| Q-MCP-06 | 观测数据落不落库 | **落库**：新增轻量 `ai_turn_metric`（每次问答一行）。阶段二的 Q-BA-04 写的是"先只落日志"，现在日志已有一年，趋势查询仍答不上来 | 表数 +1 |
| Q-MCP-07 | 是否引入 OTel / 链路追踪后端 | **不引入**。只做两件事：① 修好 AI 链路（含 SSE/Reactor 线程）的 traceId 完整性；② 让指标与审计都带 `trace_id` | 决定是否新增 3 个以上依赖与一套采集服务 |

> 编号说明：本文件使用 `REQ-MCP-xx` / `AC-MCP-xx` / `TEST-MCP-xx` / `Q-MCP-xx` / `RK-MCP-xx` 前缀。
> **不要**使用连续 `AC-xx`：已用到 `AC-71`，且 `AC-42` 已被两处占用。
> **命名警告（真实现状）**：本仓库里 `mcp` 这个标识符**已经被 JWT 的一个 claim 占用**——`JwtTokenProvider.java:49` 的 `CLAIM_MUST_CHANGE_PASSWORD = "mcp"`（首次登录强制改密）。本文档一律写 **"MCP"（Model Context Protocol）** 或 **`ai:mcp:*`**，不得使用裸 `mcp` 做新标识符；也**不要**去改那个既有 claim（会破坏已签发令牌的语义）。

---

## 1. 背景与问题

### 1.1 现状（可核对，2026-09-30 实测）

| 能力 | 现状 | 证据 |
|---|---|---|
| Observability · 端点 | `management.endpoints.web.exposure.include: health,info`，**仅此一处** management 配置；无 `management.metrics.*`、无 tracing 配置、`show-details` 未设置 | `application.yml:149-153`；全仓 grep 0 命中 |
| Observability · 健康检查 | **只有一个**自定义指示器 `authRevocation`（探 Redis 一个永不写入的探针 key；DOWN 时只报异常类型，不回显地址/凭据），另有 Boot 自动配置的 `db`/`redis`/`diskSpace`/`ping` | `AuthRevocationHealthIndicator.java:16-55`；`RevocationFailClosedIT.java:127-143`（AC-49） |
| Observability · 依赖 | **只有 `spring-boot-starter-actuator` 一个**（仅 `guarantee-web`）；**无** micrometer-registry-prometheus、无 tracing、无 OTel/zipkin jar（`.m2` 里只有 BOM 的 pom） | 全 pom 实测 + `.m2` 实测 |
| Observability · 已有的结构化日志 | **唯一一条成本日志**：`AI_TURN_COST conversationId=… rounds=… toolCalls=… toolCostMs=… inputTokens=… outputTokens=… totalCostMs=… capped=…`（每轮一次、去重） | `AiChatService.java:164-165/1299-1313` |
| Observability · 数据的真实缺口 | ① `ai_message.token_count` 是**字数估算**不是模型用量（注释写明"仅用于展示"）；② `ai_tool_call` **无轮次、无 token、无 traceId、无模型名**；③ **没有任何把轮次/成本写库的代码路径**；④ 货币成本不存在 | `AiConversationService.java:82/307-315`；`schema.sql:353-372`；阶段二 REQ §6.2 |
| Observability · TraceId | 全链路 `TraceIdFilter`（`HIGHEST_PRECEDENCE` + MDC + `X-Trace-Id`）；AI 侧在**请求线程**把 traceId 快照进 `ToolContext`，工具线程优先用它（因为工具跑在流式期间、MDC 可能已被清理）；而那条成本日志**字段里没有 traceId**、且运行在 Reactor 线程 → `%X{traceId:-}` **大概率渲染为空**（**推测，未运行验证**；仓库内无任何 Reactor→MDC 传播配置） | `TraceIdFilter.java:21-40`；`AiChatService.java:1038/1019-1028`；`AiToolCallRecorder.java:179-189` |
| Evaluation · 基线 | `docs/TEST-助手黄金问题集.md`（176 行）**15 条**（`GQ-01`…`GQ-15`），脚本 `scripts/ai-golden-questions.mjs`（444 行、**零依赖**、手工解析 SSE），退出码语义化（0 全通过 / 1 断言失败 / 2 环境问题） | 文档 §3、脚本实测 |
| Evaluation · 性质 | 文档自我定位："这是**手动冒烟**，不是评测平台"；断言种类 `contains/matches/notContains/refusal/maxToolCalls/maxRounds` + 三条全局检查（非空气泡、正文禁用内部术语、有工具调用必须有口径行） | `TEST-助手黄金问题集.md:5/54/75-80/168` |
| Evaluation · 阻塞 | 需要**真实后端 + 真实模型 + `DEEPSEEK_API_KEY`**（本机未配置）；**未接入任何 CI** | 脚本 `:16-17/442`；仓库无 `.github`、无 Jenkinsfile |
| Evaluation · 确定性侧 | `AiToolChainIT`（Stub ChatModel，不需要 Key，需要 MySQL，`mvn verify` 跑）3 个用例；仓库只有 **1 个 git hook**（`commit-msg`，无 pre-commit/pre-push） | `AiToolChainIT.java:36-70`；`.githooks/` 实测 |
| Evaluation · **单一事实源缺失** | "有多少测试"在三处文档里写的是 **176/9**、**137/68**、**13**，而本地报告文件是 **186/74/16**（合计 410，且报告早于 HEAD）——**数字不一致本身就是事实** | `PROJECT.md:265`、`DEC-助手回答的可见性与口径呈现.md:827`、`README.md:501` vs `target/**/surefire-reports` |
| MCP · 业务侧 | **不存在**。Java 里 8 处 `mcp` 命中全是"首登强制改密" claim；无 MCP 依赖、无 `McpServer` 类；README 明确列为本阶段未实现 | `JwtTokenProvider.java:49` 等；`README.md:6` |
| MCP · 可参考先例 | `tools/knowledge-os-mcp/`（**开发流程用**，不是业务 MCP）：Node 20 + TS ESM + `@modelcontextprotocol/sdk` 1.30.1 + zod 4.6.5，stdio（stdout 只走协议帧、日志走 stderr），**10 个工具**，三层身份校验 + 路径守卫（`pathguard.ts`），**刻意不提供** delete/move/arbitrary_write/execute_command，测试**通过真实 MCP JSON-RPC 驱动真实子进程**（8 个测试文件） | `tools/knowledge-os-mcp/src/index.ts:12-47`；`src/tools/index.ts:16-17/98-537` |
| MCP · 现有可用能力 | **18 个 `@Tool` 方法**（分布在 16 个类：12 只读类含 **13** 个方法 + 5 个写提案类）；按权限**注册期裁剪**；双层校验（注册 + 工具内 `AiPermissionGuard`）；数据范围由 `DataScopeService` 判定（页面与助手同源） | `AiToolRegistry.java:40-230`；`AiDataScopeResolver.java:9-37` |
| MCP · 授权现状 | JWT（权限编码直接写进令牌，靠 `jti` 白名单 + Redis 撤销）；**Redis 是鉴权强依赖**且默认 **fail-closed**；**没有机器身份机制**；新账号首登令牌会被改密闸门拦下 | `JwtTokenProvider.java:25-27`；`TokenRevocationService.java:16-34`；`PasswordChangeRequiredFilter.java:30` |
| 已承诺未实现的指标 | `SYS-NF-08` 要求"提案数（按状态）/ 确认率 / 拒绝率 / 过期率 / 执行失败率 / 平均确认耗时"，**代码里没有任何 metric 注册** | `docs/REQ-系统管理助手能力.md:757` vs 全仓 0 命中 |

### 1.2 问题：能力很强，但"看不见、测不了、拿不出去"

**问题 A：可观测性只有"一条日志"。**
轮次 / 调用数 / token / 耗时 / 是否触顶确实在采集（`AI_TURN_COST`），但只写日志、不落库、不进指标、没有端点。"上周平均轮次是多少""哪类问题最常触顶""换模型后成本变化多少"——今天都答不上来，只能人工翻日志。而 `ai_message.token_count` 是**字数估算**，拿它当 token 用会得出错误结论。

**问题 B：TraceId 在 AI 链路是"半覆盖"。**
工具调用与审计有 traceId（靠请求线程快照 + 显式下传），但成本日志跑在 Reactor 线程、字段里又没有 traceId。也就是说：**当你最需要按 traceId 定位一次异常问答的成本时，那条日志恰好串不起来**（推测，需实测确认）。

**问题 C：评测靠手点，且"有多少测试"说不清。**
黄金问题集是**手动机冒烟**、需要真实 Key、不进 CI；同时文档里三处测试数字互相矛盾。没有单一事实源，"这次改动有没有让质量下降"只能靠人肉比对。

**问题 D：受控能力出不去。**
平台已经有一套设计良好的受控取数能力（分层铁律、权限裁剪、双层校验、数据范围、审计、结果边界），但只能被**页面里的 Copilot**使用。任何外部 Agent（IDE、其它系统、评测工具）想复用，都只能重新实现一遍取数与权限逻辑——这既浪费，又会立刻产生"口径不一致"。

**问题 E：命名冲突。**
`mcp` 已经是 JWT 的"强制改密"claim。如果本阶段随手用 `mcp` 做表名/权限码/配置键，会造成长期歧义（本仓库已有"同值定义必然漂移"的教训）。

### 1.3 本需求的目标（第五阶段 DoD）

> **① MCP 暴露**：受控只读能力以 MCP Server 形式对外提供，**身份可识别、权限可裁剪、调用可审计、开关可关闭**；
> **② Evaluation**：评测可自动跑，产出**可对比**的报告（通过率 + 指标对照 + 与基线 diff），确定性集纳入 `mvn verify`；
> **③ Observability**：轮次 / 调用数 / token / 耗时 / 触顶 / 失败率**可查询、可聚合、可看趋势**，且每条记录能按 traceId 串回会话与审计；
> **④ 单一事实源**：测试项数、评测集条数、指标口径由**脚本产出**，不再手写进文档。

---

## 2. 范围

### 2.1 In Scope

1. 业务 MCP Server（第一版**只读**）：工具映射、身份与授权、限流、审计、默认关闭的开关；
2. 机器凭据：服务账号 + 可撤销 MCP Token + 最小权限（新增权限码 `ai:mcp:read`）；
3. 评测框架：数据集（GQ 顺延 + 分类 + 打分）、运行器（确定性集 / 真机集）、报告（JSON + Markdown + 基线 diff）、门禁（供第四阶段"提示词发布"复用）；
4. 观测指标：Micrometer meters 清单 + `/actuator/prometheus`；
5. 单轮指标落库：`ai_turn_metric`（含真实 usage、模型、提示词版本、traceId、触顶原因）；
6. TraceId 完整性：AI 链路（含 SSE/Reactor 线程）与 MCP/评测链路；
7. 最小可视化：一个只读的"AI 运行"视图（或复用审计页的过滤能力）；
8. 单一事实源：`scripts/` 产出的"测试/评测/指标"清单文档（自动生成，人工不手写数字）。

### 2.2 Out of Scope（本阶段明确不做）

| 不做 | 原因 |
|---|---|
| 对公网暴露 MCP | 默认关闭、仅内网/白名单；暴露面必须可控（Q-MCP-03） |
| 通过 MCP 执行写操作（直接改数据） | 违反"写操作必须经人工确认"的红线。若将来暴露，只允许"生成提案"，确认仍在平台内 |
| 多模型路由 / 自动降级 | `PROJECT.md:57` 明确"尚未排期" |
| 引入 OTel / Jaeger / Tempo / Grafana 大屏与告警平台 | 与"最小可用"原则不符；先用 Spring Boot 标准端点 + 一个只读页满足需求（Q-MCP-07） |
| 评测平台化（用例管理 UI、人工打分、多人协作） | 本阶段只做"可自动跑 + 可对比"；平台化收益不明 |
| 用 LLM 当裁判（LLM-as-judge）做主观打分 | 引入新的不确定性；第一版只用确定性断言 + 数值对照 |
| 把评测跑进一个新建的 CI 平台 | 仓库当前**没有任何 CI**（无 `.github`、无 Jenkinsfile）。本阶段把确定性评测挂进 `mvn verify` 与脚本，不引入平台 |
| 阶段二 M2.3（企业 / 项目维度） | 独立排期项 |

### 2.3 红线（不因本需求放松）

1. **MCP 不得绕过权限**：MCP 调用者必须走与页面/助手**同一套**权限裁剪（`AiToolRegistry` 语义）与**同一套**数据范围（`DataScopeService`），不得另写一份判定。
2. **MCP 不得绕过确认**：任何写操作都不经 MCP 直接执行；写能力默认不暴露。
3. **不暴露明细全表**：MCP 工具沿用既有的 `limit` 归一、16 KB 结果上限与 `truncated` 标记。
4. **评测不得污染共享开发库**：沿用既有决策（`DEC-助手回答的可见性与口径呈现.md` §7.5）：IT / 评测必须隔离数据或幂等复原，不得像历史事故那样把险种留在"停用"状态。
5. **观测不得记录正文与敏感字段**：指标标签与落库记录只放可枚举维度（工具名、状态、模型名、触顶原因），**不放问题正文、不放用户输入、不放参数值**。
6. **不新增与 `mcp` 同名的标识符**：见 §0 命名警告。

---

## 3. 术语与角色

| 术语 | 含义 |
|---|---|
| MCP | Model Context Protocol。本文档一律指它；**不是** JWT 里的 `mcp`（强制改密 claim） |
| 业务 MCP（本项目的） | 把**业务只读工具**暴露给外部 Agent 的 MCP Server。与 `tools/knowledge-os-mcp/`（**开发流程用**的知识库 MCP）**完全不同** |
| 服务账号 | 专供机器调用的账号（非人类用户），有独立的凭据与最小权限 |
| MCP Token | 可撤销的机器凭据，绑定服务账号与权限范围 |
| 评测集 | 固定问题 + 可判定断言 + 期望指标（现在 = GQ-01…GQ-15） |
| 确定性集 / 真机集 | 用 Stub ChatModel 跑（无需 Key、可进 `mvn verify`）/ 用真实模型跑（需 Key、手动或夜间） |
| 打分 | 把"断言通过/失败"升级为可比较的分数（通过率、口径正确率、引用完整率、调用/耗时分布） |
| 基线 diff | 本次评测结果与上一份存档结果的逐项差异 |
| 单轮指标 | 一次问答的聚合数据（轮次、调用数、token、耗时、是否触顶、模型、提示词版本） |
| 单一事实源 | 测试项数、评测条数、指标清单由脚本从真实产物生成，文档只引用生成结果 |

| 角色 | 关注点 |
|---|---|
| 外部 Agent 使用者（IDE / 其它系统） | 能不能安全地复用受控取数能力；权限与数据范围是否与我一致 |
| 开发 | 改了提示词/工具后，评测能不能自动告诉我退化了；出问题能不能按 traceId 串起来 |
| 运维 | 服务健康、轮次/失败/成本是否异常，能不能看到趋势 |
| 审计 / 安全 | 外部 Agent 以什么身份做了什么；能否撤销；有没有越权 |
| 产品 / 业务 | AI 的质量与成本是否持续可见，而不是"感觉还行" |

---

## 4. 用户故事

| # | 角色 | 故事 | 现状 | 本阶段 |
|---|---|---|---|---|
| US-1 | 外部 Agent 使用者 | 我想在 IDE 里问"本季度投标订单量"，用平台的受控能力取数，而不是自己写 SQL | ❌ 无 MCP | REQ-MCP-01 |
| US-2 | 安全 | 我要能给外部 Agent 单独发凭据、限定只读、随时撤销 | ❌ 只能给用户名+密码，且首登被改密闸门拦 | REQ-MCP-02 |
| US-3 | 审计 | 我要能查到"某个外部 Agent 昨天调了哪些工具、拿到什么范围的数据" | ❌ 无来源标识 | REQ-MCP-03 |
| US-4 | 开发 | 我改完提示词，想一键跑评测并看到"比上次好还是差" | 🟡 15 条手动跑、无基线 diff | REQ-MCP-06/07 |
| US-5 | 运维 | 我想看近 7 天的平均轮次、失败率、触顶率、token 消耗趋势 | ❌ 只有日志 | REQ-MCP-08/09/11 |
| US-6 | 开发 | 一次异常问答，我要按 traceId 把会话、工具调用、审计、成本日志串起来 | 🟡 成本日志无 traceId、跑在 Reactor 线程 | REQ-MCP-10 |
| US-7 | 全员 | "本项目现在有多少测试、评测多少条"要有一个不会互相矛盾的答案 | ❌ 三处文档数字不一致 | REQ-MCP-12 |

---

## 5. 功能需求

### 5.1 MCP 暴露

#### 5.1.1 REQ-MCP-01 业务 MCP Server（P0）

| 项 | 内容 |
|---|---|
| 形态 | **独立进程 stdio 网关**（推荐，Q-MCP-01）：MCP 客户端 ↔ stdio ↔ 网关 ↔ HTTPS ↔ 平台受控接口。与 `tools/knowledge-os-mcp/` 同构（stdout 只走 JSON-RPC 帧、日志走 stderr） |
| 位置 | 建议 `tools/business-mcp/`（与知识库 MCP 平级，明确区分用途）；**不要**放进 `guarantee-*` 模块 |
| 暴露范围 | **第一版只读**：`queryOrderSummary` / `getCurrentDate` / `queryOrderDistribution` / `queryOrderTrend` / `queryBusinessKnowledge` / `queryOrg` / `queryDepartment` / `queryUser` / `queryRole` / `queryInsuranceType` / `queryOperationAudit` / `queryMyToolCalls` / `queryMyProposals`（**13 个 `@Tool` 方法 / 12 个只读类**，v1.1 更正：v1.0 写 12/11，未含第三阶段新增的知识检索工具） |
| 工具命名 | `mcp__guarantee__<toolName>`（沿用 DSH 的 `mcp__<server>__<tool>` 展示约定）；`tools/list` 里的 description **直接复用既有 `@Tool` 描述**（避免第二份工具说明漂移） |
| 结果契约 | 与页面/助手一致：`dataSource` 口径文本、`truncated` 标记、`limit` 归一；不返回明细全表 |
| 明确不做 | 不暴露 `propose*`（写）；不暴露 `resources`/`prompts` 能力（第一版只做 `tools`）；不提供"任意 HTTP 透传"这类万能口 |

#### 5.1.2 REQ-MCP-02 机器身份与授权（P0）

| 项 | 内容 |
|---|---|
| 服务账号 | 新增"服务账号"类型（或在 `sys_user` 上加 `account_type=SERVICE`，**不参与登录、不计入人类用户统计**），绑定最小角色（只读） |
| 凭据 | 新增可撤销 **MCP Token**（建议存哈希 + 前缀展示，可设有效期与最后使用时间）；**复用既有 Redis 撤销机制**（`jti` 白名单 + 用户级撤销） |
| 权限码 | 新增 `ai:mcp:read`（放进**危险权限清单**：`frontend/src/components/PermissionTree.vue:83-89` 与 `docs/REQ-角色管理与权限分配页面.md:323-333` 两处需同步） |
| 数据范围 | **必须**走 `DataScopeService`（`AiDataScopeResolver` 同源），不得为 MCP 另写判定 |
| 越权语义 | 无权限 → **不注册该工具**（沿用 fail-closed：权限快照为空则只剩 4 个公开只读工具）；工具内第二道 `AiPermissionGuard` 仍在 |
| 与改密闸门的关系 | 服务账号**不适用**首登改密；文档必须写清"账密登录只给人类用户，机器一律走 MCP Token"，避免"新建账号被拦在改密页"这类事故 |

#### 5.1.3 REQ-MCP-03 调用审计、来源标识与限流（P0）

- 每次 MCP 调用写 `ai_tool_call`（复用 `RecordingToolCallback` 链路），并新增**来源标识**：
  - `ai_tool_call` 新增 `source`（`CHAT` / `MCP` / `EVAL`）——现状该表**没有来源列**，无法区分"助手调的"与"外部 Agent 调的"；
  - `ai_audit_log` 的 `source` 或 action 同步标记（沿用既有 `AuditSourceContext` 的"渠道可区分"口径）；
- 审计需能回答："哪个服务账号、什么时候、调了哪个工具、参数范围、耗时、结果状态"；
- **限流与配额**：按 token/账号维度限制 QPS 与每日调用数；超限返回可读错误（不是 500）；
- **成本归属**：MCP 调用产生的 token 若走模型（第一版 MCP 只做透传取数，不经模型），统一记入 `ai_turn_metric.source=MCP`。

#### 5.1.4 REQ-MCP-04 写能力边界（P0）

- 第一版**不暴露**任何写工具（`propose*`）；
- 若将来暴露，约束是：只暴露"生成提案"，**确认与执行必须发生在平台页面**（确认卡）；MCP 侧只能拿到提案号与预览，不得有"确认"入口；
- 该边界要写进 MCP 工具的 description 与文档，防止外部 Agent 误以为可以直接改数据。

#### 5.1.5 REQ-MCP-05 部署、开关与安全（P0）

| 项 | 内容 |
|---|---|
| 默认关闭 | 配置开关 `guarantee.ai.mcp.enabled=false` 为默认；未开启时网关拒绝启动或直接报错退出 |
| 网络 | 仅内网/白名单；不接受公网直连；TLS 由部署层负责 |
| 撤销 | Token 可在页面上撤销（即时生效，走 Redis） |
| 审计 | 网关自身也记录"谁在什么时候启动了它、用什么配置" |
| 文档 | 必须显式说明"业务 MCP ≠ `tools/knowledge-os-mcp`（知识库 MCP）"，避免使用者接错 |

### 5.2 Evaluation

#### 5.2.1 REQ-MCP-06 评测数据集（P0）

- **不另起一套问题集**（沿用 `TEST-助手黄金问题集.md:175-176` 的约定）：在现有 `QUESTIONS` 数组续写，`id` **从 GQ-16 顺延**；
- 目标规模 **≥ 30 条**，分类与配比：

| 类别 | 现有 | 目标 | 说明 |
|---|---|---|---|
| 单维度统计 | 有 | ≥ 8 | 与页面同条件数字一致 |
| 交叉/趋势/分布 | 有 | ≥ 6 | 断言轮次 ≤ 3、调用 ≤ 12 |
| 定义/知识类 | 0（第三阶段新增 10 条） | ≥ 10 | 断言"知识来源行存在且与检索结果一致"（跨阶段复用） |
| 越界拒答 | 有 | ≥ 4 | 不得含糊应承 |
| 降级/失败 | 有 | ≥ 2 | 知识层关闭、工具失败时的如实说明 |

- **打分口径**（把断言升级为分数）：通过率、口径正确率（口径行与工具返回值逐字一致的比例）、引用完整率（有工具调用必有口径行 / 有知识检索必有来源行）、轮次与耗时分布、禁用术语违规数；
- 断言格式向后兼容：现有 `contains/matches/notContains/refusal/maxToolCalls/maxRounds` 全部保留。

#### 5.2.2 REQ-MCP-07 评测运行、报告与门禁（P0）

| 项 | 内容 |
|---|---|
| 确定性集 | 用 Stub ChatModel（`AiToolChainIT` 同款做法），**无需 API Key**，纳入 `mvn verify`；覆盖工具链路、预算护栏、来源行追加、权限裁剪 |
| 真机集 | 现有 `scripts/ai-golden-questions.mjs` 升级：支持 `--suite=all\|deterministic\|live`、`--baseline=<file>`、输出 **JSON + Markdown** |
| 报告 | 每次运行产出 `docs/TEST-评测报告-<日期>.md`（或 `reports/` 目录）+ 机器可读 JSON；含逐题结果与**与基线的 diff**（新增失败 / 新修复 / 指标变化） |
| 基线 | 最近一次"全绿"结果存档为基线；退化项必须在报告里显著标出 |
| 门禁 | 确定性集失败 → `mvn verify` 失败；真机集作为**发布门禁的建议项**（第四阶段提示词发布复用），缺 Key 时标注"未跑"而非"通过" |
| 隔离 | 评测不得污染共享开发库（红线 §2.3-4）；脚本先校验数据基线（订单量 / 区间 / seed 20260920），不满足即报"数据未初始化"而不是断言失败 |
| 退出码 | 沿用 0/1/2 语义，并区分"断言失败"与"环境/凭据问题" |

#### 5.2.3 REQ-MCP-12 单一事实源（P0，可与评测同批）

- 新增脚本产出"质量与规模清单"：当前单测/IT 用例数（从 surefire/failsafe 报告统计，而不是手写）、评测集条数、指标清单、MCP 工具清单；
- `README.md` / `PROJECT.md`（Vault）/ 评审文档只引用该脚本的输出，**禁止手写数字**；
- 目的：本次实测已发现三处文档测试数字互相矛盾（176/9、137/68、13 vs 报告 186/74/16），这不是笔误，是缺少单一事实源。

### 5.3 Observability

#### 5.3.1 REQ-MCP-08 Micrometer 指标（P0）

| 指标 | 类型 | 标签 | 说明 |
|---|---|---|---|
| `ai.chat.requests` | Counter | `outcome`(success/error/capped), `model` | 每次问答 |
| `ai.chat.duration` | Timer | `outcome` | 端到端耗时（与 `totalCostMs` 同源） |
| `ai.chat.rounds` | DistributionSummary | `capped` | 每轮次数的分布（回答"平均几轮"） |
| `ai.tool.calls` | Counter | `tool`, `status`(SUCCESS/FAILED), `source`(CHAT/MCP/EVAL) | 每次工具调用 |
| `ai.tool.duration` | Timer | `tool` | 单次工具耗时（已有 `duration_ms`，改为同时进指标） |
| `ai.tokens` | Counter | `direction`(input/output), `model` | 真实 usage（不是字数估算） |
| `ai.proposals` | Counter | `status`(CREATED/CONFIRMED/REJECTED/EXPIRED/FAILED), `source` | 兑现 `SYS-NF-08` 的承诺 |
| `ai.prompt.publish` | Counter | `result`(ok/gate_failed) | 第四阶段发布门禁的观测面 |
| `ai.knowledge.retrieval` | Counter | `domain`, `hit`(true/false) | 第三阶段知识检索（跨阶段复用） |
| `ai.knowledge.retrieval.duration` | Timer | `domain`, `hit`(true/false) | **v1.1 拆分**：v1.0 写 "Counter/Timer"，但 Micrometer **不允许同名 meter 两种类型**（实测 `CumulativeCounter vs Timer`），故拆成两个 meter 名 |

- 端点：`management.endpoints.web.exposure.include` 增加 `prometheus`（可选 `metrics`）；需新增 `micrometer-registry-prometheus` 依赖（本机 `.m2` 无，需联网——**Q-MCP-04 的可行性前置**）；
- **标签基数控制**：`tool`（固定枚举）、`status`、`source`、`model` 可用；**禁止**把 `conversationId`、`userId`、问题文本、参数值作为标签（高基数会拖垮监控）；
- 采集开销 < 1%（埋点只在收尾与工具装饰器，沿用既有装饰链位置）。

#### 5.3.2 REQ-MCP-09 单轮指标落库（P0）

新增 `ai_turn_metric`（每次问答一行，与 `AI_TURN_COST` 日志同源）：

| 列 | 说明 |
|---|---|
| `conversation_id` / `message_id` | 关联会话与回答 |
| `user_id` | 归属 |
| `model` / `prompt_version` | 用了哪个模型、哪一版提示词（第四阶段提供） |
| `rounds` / `tool_calls` / `tool_cost_ms` / `total_cost_ms` | 与现有日志字段一一对应 |
| `input_tokens` / `output_tokens` | **真实 usage**（现有 `ai_message.token_count` 是字数估算，两者并存但口径必须写清） |
| `capped` / `cap_reason` | 是否触顶及原因（软超时 / 轮次 / 框架上限） |
| `outcome` | **补列（v1.1，实现期新增）**：`SUCCESS` / `ERROR` / `CAPPED`，与 §5.3.1 的 `ai.chat.requests{outcome}` 标签**同值域**。没有它，AC-MCP-09 的"失败率"只能靠猜；两处口径共用一套枚举以免漂移 |
| `source` | `CHAT` / `MCP` / `EVAL` |
| `trace_id` | 与审计、日志串起来 |
| `created_at` | 分区/索引依据 |

- 写入失败**不得**影响回答（与审计不同：审计写失败要回滚业务，指标写失败只告警）；
- 索引：`(created_at)`、`(user_id, created_at)`、`(model, created_at)`；
- 口径：一次问答 = 一行（含失败与触顶）。

#### 5.3.3 REQ-MCP-10 TraceId 完整性（P0）

1. **修复成本日志无 traceId**：`AI_TURN_COST` 日志加上 `traceId`；
2. **解决 Reactor 线程 MDC 丢失**：在流式链路上显式传递 traceId（把 `TraceContext` 的值随流式上下文下传，或在订阅时重新注入 MDC），不得依赖 MDC 自然传播（仓库内**无任何** Reactor→MDC 传播配置）；
3. **断言**：新增 IT，断言"一次问答的成本日志、工具调用记录、审计记录的 `trace_id` 三者相同且非空"；
4. MCP 与评测链路同样注入 traceId（`source=MCP|EVAL`），使外部调用也能串起来。

#### 5.3.4 REQ-MCP-11 最小可视化（P1）

- 一个只读视图（建议复用"操作审计"页的筛选能力 + 新增页签，或新增独立的"AI 运行"只读页）：

| 区域 | 内容 |
|---|---|
| 概览卡 | 近 24h / 7d：问答数、失败率、触顶率、平均轮次、平均耗时、token 合计 |
| 趋势 | 按天：轮次 / 耗时 / token / 失败数（ECharts，页面已有图表能力） |
| Top 工具 | 调用次数与 p95 耗时 Top 10 |
| 提案 | 创建 / 确认 / 拒绝 / 过期 / 失败（兑现 SYS-NF-08） |

- 权限：沿用 `system:audit:view`（管理员）或新增只读权限；**不做** Grafana 大屏（Q-MCP-07）。

---

## 6. 接口与数据设计汇总

### 6.1 数据变更

- 新增 `ai_turn_metric`（见 §5.3.2）；
- 新增 `ai_mcp_token`（`token_hash` / `token_prefix` / `service_account_id` / `permissions` / `expires_at` / `last_used_at` / `revoked_at` / **`created_by`** / **`revoked_by`**（v1.1 补：谁签发、谁撤销）/ 逻辑删除三列）；
- `ai_tool_call` 新增 `source VARCHAR(8) DEFAULT 'CHAT'`（`CHAT`/`MCP`/`EVAL`）；
- `ai_message` 的 `token_count` **保留但改注释**："字数估算，非模型用量；真实用量见 `ai_turn_metric`"（避免继续被误用）；
- 表数：本阶段 **+2**（`ai_turn_metric`、`ai_mcp_token`）。按三份阶段文档的建议口径累计：20（现状）→ 22（第三阶段：知识条目 + 导入留痕）→ 24（第四阶段：配置项 + 提示词版本）→ **26**（本阶段）；任一派生取舍以对应文档为准；
- 迁移脚本：观测用 `db/migration/V9__ai_observability.sql`、MCP 用 `db/migration/V10__ai_mcp.sql`
  （**编号更正，v1.1**：v1.0 这里误写为 `V8__ai_observability.sql`，而 V8 已由第四阶段的 `V8__ai_config.sql` 占用；
  沿用无 Flyway 的手工幂等惯例）。

### 6.2 接口与端点

| 类型 | 内容 |
|---|---|
| 指标端点 | `/actuator/prometheus`（新增暴露）；`/actuator/health` 保持不变（免登录，含 `authRevocation`） |
| 可视化 API | `GET /api/ai/metrics/overview?range=24h\|7d\|30d`（响应含 `overview` + **`proposals` 提案状态计数**）、`GET /api/ai/metrics/trend?days=7`（1~90）、`GET /api/ai/metrics/tools/top?limit=10&range=24h`（limit 1~20）（权限：`system:audit:view`；**v1.1 补全参数与返回块**） |
| MCP Token 管理 | `POST /api/system/mcp-tokens`（签发）、`DELETE /api/system/mcp-tokens/{id}`（撤销）、`GET /api/system/mcp-tokens`（列表，**不返回明文**） |
| MCP 网关（stdio） | `initialize` / `tools/list` / `tools/call`（标准 JSON-RPC；不新增自定义扩展）|
| MCP 平台 HTTP 面（**v1.1 补**） | `GET /api/ai/mcp/tools`（清单+inputSchema）、`POST /api/ai/mcp/tools/{name}`（调用）；`Authorization: Bearer <MCP-Token>`；安全层放行 `/api/ai/mcp/**`，鉴权在 controller 内（`ai:mcp:read` 硬门禁）|
| MCP Token 管理 | `POST`/`GET`/`DELETE /api/system/mcp-tokens[/{id}]`（权限 `ai:mcp:manage`；明文仅签发响应出现一次）|
| MCP 协议面 HTTP 状态（**v1.1 补**） | **真实 HTTP 状态 + 同值业务码**（迁就冻结网关：它只在非 2xx 读 `message`）：`400` INVALID_ARGUMENT；`401` TOKEN_INVALID/REVOKED/EXPIRED；`403` TOOL_NOT_ALLOWED/TOOL_UNAVAILABLE/PERMISSION_REQUIRED/ACCOUNT_DISABLED；`429` RATE_LIMITED/DAILY_QUOTA_EXCEEDED；`503` LIMITER_UNAVAILABLE；`500` TOOL_FAILED。**页面面 API 仍是 HTTP 200 + 业务码**——两套口径并存是有意的 |

### 6.3 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `guarantee.ai.mcp.enabled` | `false` | 业务 MCP 总开关 |
| `guarantee.ai.mcp.gateway-url` | 空 | 网关回连平台的地址 |
| `guarantee.ai.mcp.qps-limit` | `5` | 每 token 的 QPS 上限 |
| `guarantee.ai.mcp.daily-quota` | `10000` | 每 token 每日调用上限 |
| `guarantee.ai.observability.metrics-enabled` | `true` | 指标采集开关（故障时可关） |
| `guarantee.ai.observability.turn-metric-persist` | `true` | 单轮指标落库开关 |
| `guarantee.ai.observability.token-pricing` | — | **v1.1 删除（键未落地）**：实现里**没有**这个键；Q-MCP-05 已拍板"只报 token 与耗时，不报金额"，要报金额必须先有单价表（未排期） |
| `guarantee.ai.eval.deterministic-in-verify` | `true` | 确定性评测是否挂进 `mvn verify`；**v1.1 更正**：实现载体是 **JVM 系统属性 `-D`**（`EvaluationDeterministicIT` 的 `@EnabledIf` 读 `System.getProperty`），不在 `application.yml` |

### 6.4 与既有产物的关系

- **不新建问题集**：评测集就是 `docs/TEST-助手黄金问题集.md` 的顺延；
- **不新造指标命名空间**：指标前缀统一 `ai.`；
- **不替换审计**：`ai_operation_audit` 仍是"谁改了什么"的唯一真源，指标只做聚合与趋势；
- **`reports/` 是可再生的评测产物（v1.1 说明，D10）**：`reports/eval-<suite>-<日期>.{md,json}` 每次跑评测都会重写，
  因此反复运行会让这两份文件在 git 里"变脏"（diff 主要是时间戳与逐题耗时）。当前**保留跟踪**作为可 diff 的基线；
  若后续觉得噪音大，可改为写 `target/` 并只在需要存档时显式复制一份。

---

## 7. 非功能需求

| 类别 | 要求 |
|---|---|
| 性能 | 指标埋点开销 < 1%；MCP 单次调用 p95 ≤ 500 ms（不含模型）；MCP 不得显著增加平台负载（限流兜底） |
| 容量 | `ai_turn_metric` 按天量级约 = 问答数；预留按月分区或归档策略（可先只建索引，行数超 100 万再分区） |
| 安全 | 默认关闭；Token 可撤销、只读、最小权限；审计含来源；不记录正文与敏感字段；网关不提供万能透传 |
| 可靠性 | 指标写失败不影响回答；评测隔离数据；MCP 故障不影响平台自身 |
| 可维护 | 测试/评测/指标数字由脚本产出（REQ-MCP-12）；指标清单与代码只有一处真源 |
| 兼容 | `/actuator/health` 行为不变（AC-49 不回归）；SSE 事件集合不变；既有测试（数量以 REQ-MCP-12 脚本产出的单一事实源为准）与 15 条黄金问题集不回归 |
| 可复现 | 评测集固定输入 + 固定数据基线（seed 20260920）；报告可存档、可 diff |

---

## 8. 测试要求

| 编号 | 类型 | 内容 |
|---|---|---|
| TEST-MCP-01 | 单测 | 指标埋点：各 meter 在成功/失败/触顶路径上的增减与标签正确；标签不含高基数值 |
| TEST-MCP-02 | 单测 | 单轮指标落库：字段与 `AI_TURN_COST` 日志一一对应；写失败只告警不影响回答 |
| TEST-MCP-03 | IT（真实库） | TraceId 一致性：成本日志 / `ai_tool_call` / 审计三者的 `trace_id` 相同且非空（含流式与失败路径） |
| TEST-MCP-04 | IT（真实库） | MCP 越权：无 `ai:mcp:read` 时对应工具不注册；数据范围与页面同源（同用户同条件结果一致） |
| TEST-MCP-05 | 协议测试 | MCP stdio JSON-RPC：`initialize` / `tools/list` / `tools/call` 真实子进程握手（沿用 `knowledge-os-mcp` 的做法）；工具数与清单与代码一致 |
| TEST-MCP-06 | 单测 + IT | 限流与配额：超限返回可读错误；Token 撤销后立即失效 |
| TEST-MCP-07 | 评测 | 确定性集纳入 `mvn verify` 且全绿；真机集脚本可用 `--baseline` 产出 diff；数据未初始化时报"未初始化"而非断言失败 |
| TEST-MCP-08 | 回归 | `/actuator/health` 与 `authRevocation` 行为不变（AC-49）；既有单测 + IT 全绿；黄金问题集 15 条不回归 |

---

## 9. 验收标准

| 编号 | 验收标准（可判定） |
|---|---|
| AC-MCP-01 | 外部 MCP 客户端能完成 `initialize` → `tools/list`（只读工具清单）→ `tools/call`，并拿到与页面同条件**逐字段一致**的结果 |
| AC-MCP-02 | 服务账号 + MCP Token 可签发、可撤销；撤销后**立即**调用失败并给出可读错误 |
| AC-MCP-03 | 无 `ai:mcp:read` 的服务账号看不到对应工具；数据范围与页面/助手**同源**（同用户同条件结果一致） |
| AC-MCP-04 | MCP **不暴露**任何写工具；尝试调用写能力被明确拒绝且不产生任何提案或数据变更 |
| AC-MCP-05 | 每次 MCP 调用在 `ai_tool_call` 中可识别来源（`source=MCP`），并可追溯到服务账号与 traceId |
| AC-MCP-06 | MCP 默认关闭；`enabled=false` 时网关不提供服务（或明确退出），平台自身不受影响 |
| AC-MCP-07 | `/actuator/prometheus` 可抓取：`ai.chat.requests`、`ai.chat.duration`、`ai.chat.rounds`、`ai.tool.calls`、`ai.tool.duration`、`ai.tokens`、`ai.proposals` 均可见且有数据 |
| AC-MCP-08 | 指标标签中不含会话 id / 用户 id / 问题文本（基数可控） |
| AC-MCP-09 | 「近 7 天平均轮次 / 失败率 / 触顶率 / token 趋势」可在页面上查到；数字与 `ai_turn_metric` 聚合结果一致 |
| AC-MCP-10 | `AI_TURN_COST` 日志、工具调用记录、审计记录的 `trace_id` 三者一致且非空（含流式与失败路径） |
| AC-MCP-11 | 评测可一键跑：确定性集全绿并入 `mvn verify`；真机集产出含**与基线 diff** 的 Markdown + JSON 报告；退化项被显著标出 |
| AC-MCP-12 | "测试项数 / 评测条数 / 指标清单 / MCP 工具清单"由脚本产出，文档数字与脚本输出一致（消除三处文档互相矛盾） |
| AC-MCP-13 | `/actuator/health` 与 `authRevocation` 行为不变（AC-49 不回归）；既有测试与黄金问题集 15 条不回归 |

---

## 10. 实施计划与里程碑

| 里程碑 | 内容 | 依赖 | 工作量 |
|---|---|---|---|
| **M5.1 观测底座（P0）** | REQ-MCP-09 单轮指标落库、REQ-MCP-08 指标埋点 + Prometheus 端点、REQ-MCP-10 TraceId 完整性 | 第四阶段（`prompt_version`、配置项）| 2 人日 |
| **M5.2 评测框架（P0）** | REQ-MCP-06 数据集顺延与打分、REQ-MCP-07 运行器/报告/基线/门禁、REQ-MCP-12 单一事实源 | M5.1（指标对照）；第三阶段（知识类断言） | 2.5 人日 |
| **M5.3 业务 MCP（P0）** | REQ-MCP-01 网关与工具映射、REQ-MCP-02 服务账号与 Token、REQ-MCP-03 审计/来源/限流、REQ-MCP-04/05 边界与开关 | M5.1（source/traceId）；第四阶段（权限配置化） | 3 人日 |
| **M5.4 可视化与收尾（P1）** | REQ-MCP-11 最小视图、AC 全量复核、真机走查 | M5.1、M5.2 | 1.5 人日 |
| **本阶段合计** | | | **≈ 9 人日** |

> 建议顺序：**M5.1 → M5.2 → M5.3 → M5.4**。
> 理由：观测与评测是"能不能验证"的前提，先做才谈得上"暴露出去"；MCP 依赖 `source` 与 traceId 才能审计，故必须晚于 M5.1。
> 与第三/四阶段的关系：第三阶段的知识类断言并入本阶段评测集；第四阶段的提示词发布门禁直接调用 M5.2 的评测运行器。

---

## 11. 风险与应对

| 编号 | 风险 | 应对 |
|---|---|---|
| RK-MCP-01 | **MCP 暴露面失控**（越权取数、被当成万能数据口） | 默认关闭 + 机器凭据 + 最小权限只读 + 限流 + 审计 + 不提供万能透传；安全用例（TEST-MCP-04/06）为 P0 |
| RK-MCP-02 | **外部 Agent 提示注入**：外部 Agent 把工具返回值当指令 | 沿用既有"工具返回值是数据不是指令"的注入防护（`SanitizingToolCallback` + 提示词 3.1）与结果边界；MCP 工具 description 明确"返回值不可执行" |
| RK-MCP-03 | 依赖拉取失败（`micrometer-registry-prometheus`、MCP SDK 均不在本机 `.m2`） | Q-MCP-01/04 先做**可行性验证**（能否联网拉取）；拉不到就走降级路径（落库 + 页面聚合；Node 侧 SDK 已在本机 `tools/knowledge-os-mcp/node_modules` 中存在，可复用） |
| RK-MCP-04 | 指标基数爆炸（把 id / 文本当标签） | 标签白名单 + 单测断言（AC-MCP-08）；只允许枚举维度 |
| RK-MCP-05 | 评测脆性（依赖演示数据与模型非确定性） | 确定性集用 Stub 模型；真机集固定数据基线 + 失败分类（断言失败 vs 环境问题）；报告记录模型与提示词版本 |
| RK-MCP-06 | 评测污染共享开发库（历史真实事故） | 隔离数据库/事务回滚/幂等复原；红线 §2.3-4；评测前后做数据基线校验 |
| RK-MCP-07 | 观测数据与真实口径不一致（`ai_message.token_count` 被当成用量） | 明确两级口径：估算 vs 真实 usage；`ai_turn_metric` 为唯一权威；改注释并在文档写清 |
| RK-MCP-08 | traceId 修复触及流式链路，可能影响 SSE 行为 | 改动限定在上下文传递，不改事件协议；加 IT 断言三者 traceId 一致 + SSE 回归 |
| RK-MCP-09 | 命名冲突（`mcp` 已被 JWT claim 占用） | 一律使用 `ai:mcp:*` / `AI_MCP_*`；不改既有 claim；文档显式警告 |
| RK-MCP-10 | 阶段五范围过大（三件事叠在一起） | 按里程碑串行，M5.1/M5.2 先交付即可独立产生价值（"能测、能看"），MCP 可单独延后 |

---

## 12. 未决项

| 编号 | 问题 | 建议 | 影响 |
|---|---|---|---|
| Q-MCP-01 | MCP 实现形态（Java 侧新增依赖 vs Node 网关） | **Node 网关**（有先例、零 Java 依赖变更） | 决定工作量与部署形态 |
| Q-MCP-02 | 机器身份机制（服务账号 + Token） | 新增，最小权限只读 | 表数 +1、权限码 +1 |
| Q-MCP-03 | 暴露范围 | 只读 **13** 个 `@Tool` 方法（含 `queryBusinessKnowledge`） | 风险面 |
| Q-MCP-04 | 指标栈与依赖可得性 | Micrometer + Prometheus；先验证能否联网拉取 | 决定是否有 Grafana 之外的端点 |
| Q-MCP-05 | 成本是否需要货币口径 | 先只报 token/耗时；要金额则需单价表（第四阶段配置化） | 决定 AC 表述 |
| Q-MCP-06 | 观测数据是否落库 | **落库**（`ai_turn_metric`） | 表数 +1 |
| Q-MCP-07 | 是否引入 OTel / 链路后端 | 不引入；只补 traceId 完整性 | 依赖数量 |
| Q-MCP-08 | 确定性评测是否默认挂进 `mvn verify` | 建议默认挂（耗时可控），可用配置关闭 | 影响本地开发体验 |
| Q-MCP-09 | 是否为 MCP 单独出"外部接入文档" | 建议**必须**（含"业务 MCP ≠ 知识库 MCP"的显式区分、Token 申请流程、示例 `mcp.json`） | 决定可交付性 |

---

## 13. 需求追踪矩阵

| 需求 | 用户故事 | 验收标准 | 测试 |
|---|---|---|---|
| REQ-MCP-01 业务 MCP Server | US-1 | AC-MCP-01 / 06 | TEST-MCP-05 |
| REQ-MCP-02 机器身份与授权 | US-2 | AC-MCP-02 / 03 | TEST-MCP-04 / 06 |
| REQ-MCP-03 审计/来源/限流 | US-3 | AC-MCP-05 | TEST-MCP-04 / 06 |
| REQ-MCP-04 写能力边界 | — | AC-MCP-04 | TEST-MCP-04 |
| REQ-MCP-05 部署与开关 | US-2 | AC-MCP-06 | TEST-MCP-05 / 06 |
| REQ-MCP-06 评测数据集 | US-4 | AC-MCP-11 | TEST-MCP-07 |
| REQ-MCP-07 运行/报告/门禁 | US-4 | AC-MCP-11 | TEST-MCP-07 |
| REQ-MCP-08 Micrometer 指标 | US-5 | AC-MCP-07 / 08 | TEST-MCP-01 |
| REQ-MCP-09 单轮指标落库 | US-5 | AC-MCP-09 | TEST-MCP-02 |
| REQ-MCP-10 TraceId 完整性 | US-6 | AC-MCP-10 | TEST-MCP-03 |
| REQ-MCP-11 最小可视化 | US-5 | AC-MCP-09 | 手工验收 + 前端 build |
| REQ-MCP-12 单一事实源 | US-7 | AC-MCP-12 | TEST-MCP-07 / 08 |
| 回归（本阶段引入的改动不得破坏既有行为） | — | AC-MCP-13 | TEST-MCP-08 |

---

## 14. 实施记录（2026-09-30，v1.1）

**已交付**

- **观测**：`ai_turn_metric`（每轮一行，含 `outcome`/`trace_id`/`source`）+ `ai_tool_call.source/trace_id` + `ai_message.token_count` 注释更正；`AiChatMetrics` **9 项指标**（标签经 `sanitize()` 卡基数）；`/actuator/prometheus` 免登录放行；`AI_TURN_COST` 日志带 traceId，Reactor 线程 traceId 显式下传；`AiRuntimeController` + 「AI 运行」页
- **评测**：`scripts/ai-golden-questions.mjs` 升级（`--suite=all|deterministic|live`、`--baseline`、JSON+Markdown、基线 diff、打分、内嵌 SSOT）；评测集 **33 条**；确定性集由 `EvaluationDeterministicIT` 承载并纳入 `mvn verify`；`scripts/single-source-of-truth.mjs` 成为测试项/评测条数/指标/MCP 工具清单的唯一事实源
- **MCP**：网关 `tools/business-mcp`（Node stdio，**13 个只读工具**，17 个协议用例）+ 平台侧 `McpController`（`GET/POST /api/ai/mcp/tools[/{name}]`、`/api/system/mcp-tokens`）+ `McpRateLimiter`（Redis QPS + 每日配额）+ 权限码 `ai:mcp:read`（硬门禁）/`ai:mcp:manage`；默认关闭（yml 级、需重启）
- **T5-06 附带**：服务账号 `account_type` 映射 + 登录拒绝 SERVICE + MCP 签发强校验

**证据**

| 项 | 结果 |
|---|---|
| 单测 | guarantee-ai 418 例全绿（含 `AiChatMetricsTest` 8、`McpRateLimiterTest` 8、`McpToolCatalogTest` 13、`McpControllerProtocolTest` 12） |
| IT | `AiObservabilityIT` 2/2（指标/工具调用/审计 trace_id 三段一致 + 失败路径 `outcome=ERROR`）；`McpBackendIT` 8/8（含 HUMAN 账号签发被拒 + `COUNT(*)==0`）；`McpRateLimitIT`/`McpQuotaIT` 各 1/1；`LogicalDeleteSchemaIntegrationTest` 11/11 |
| 真机 | `/actuator/prometheus` **200 / 441 行**，含 `userId\|conversationId\|question=\|prompt=` 的行 **0**；MCP e2e：签发→`tools/list` 13（与 `catalog.ts` 逐名一致）→三条 `tools/call`→**撤销后 401**→写工具 **403**→同秒第 6 次 **429**；真实 stdio 网关全链路跑通；默认关闭实例三类路径 **404** 且平台自身正常 |
| 评测 | `--self-check` 通过（**35 条** ↔ 脚本/文档一一对应，v1.2 由 33 扩容）；`--suite=deterministic` **12/12、exit 0**；`--baseline` diff 全 0；SSOT `--check` **exit 0** |
| 可视化 | CDP 实测：24h 概览 32/4/12.5%/1.88 轮/98ms、7d 提案 11/10/2、Top 工具计数与 p95 —— 与 SQL 同刻聚合一致 |

**偏差（如实登记）**

1. **MCP 开关是 yml 级、需重启**（与第四阶段"DB 配置项不重启生效"是两条路径；对外暴露面重启更保守）；
2. **协议面用真实 HTTP 状态**（401/403/429）以迁就冻结网关，**页面面仍 HTTP 200 + 业务码**——两套口径并存是有意的；
3. **`ai:mcp:read` 是 MCP 入口硬门禁**（无它 → 空清单 + 403），与聊天链路"登录可见 4 个公开工具"不同层；
4. `ai.knowledge.retrieval` 拆成 counter + `.duration`（Micrometer 不允许同名不同类型）；`token-pricing` 键未落地（Q-MCP-05 只报 token 与耗时）；
5. `docs/MCP-外部接入.md` 的"12 个工具"漂移已更正为 13；`reports/` 是**可再生**评测产物（每次跑会重写，diff 主要是时间戳）。

**本阶段后续已闭环的项（T6 收口轮）**：真机集已跑通（`DEEPSEEK_API_KEY` 就位）；`ai_tokens_total`/`ai_proposals_total` 均已真机取证（后者原为**死指标**，已在 T6-06 修复）；审计分区**新建库**命名口径已修正（既有库给 DRY-RUN 重分区脚本）；`DataSourceClaimGuard` 已与知识守卫对称化。
**仍未闭环**：`ProposalNumberGuard` 的形态规避（登记不修，边界已钉成测试）；收紧断言后真机暴露的 3 处**模型行为缺陷**（GQ-27 周期不连续、GQ-31/GQ-35 拒答泄漏「SQL」、GQ-34 方差）→ 正在 T6-07 修提示词。

---

## 15. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-30 | v1.0 | 首版：基于当日只读实测（actuator 仅 health/info、唯一自定义指示器 `authRevocation`、只有 starter-actuator 一个依赖且 `.m2` 无 prometheus/tracing/OTel 构件；`AI_TURN_COST` 是唯一成本日志且不含 traceId、`ai_message.token_count` 是字数估算、轮次与成本不落库；黄金问题集 15 条需真实 Key 且无 CI、仓库仅 `commit-msg` 钩子；业务侧无 MCP 且 `mcp` 标识符已被 JWT claim 占用；工具口径为 17 个 `@Tool` 方法 / 16 个类；`SYS-NF-08` 承诺的指标至今未实现；三处文档测试数字互相矛盾），给出第五阶段的范围、需求、验收与 ≈9 人日计划；登记 7 项待拍板（Q-MCP-01~07）与补充未决项（Q-MCP-08~09）及 10 项风险 |
| 2026-09-30 | v1.1 | ① §0 决策按用户确认**全部照建议执行**；② 新增 §14 实施记录（交付/证据/偏差）；③ 更正 §6.1/§6.2/§6.3：`ai_mcp_token` 补 `created_by`/`revoked_by`、可视化 API 补 `30d`/`limit`/`proposals`、区分 MCP 平台 HTTP 面与网关 stdio 面并补 12 个状态码、删除未落地的 `token-pricing`、`deterministic-in-verify` 注明是 `-D` 系统属性；④ §5.3.1 的 `ai.knowledge.retrieval` 拆成 counter + `.duration`；⑤ 补 `outcome` 列与 `V9/V10` 迁移编号更正 |
