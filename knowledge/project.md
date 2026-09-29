> 本文件是 **PROJECT.md 的知识源**（用于初始化与批量修订），不是知识库本身。
> 知识库在 Obsidian Vault 里。应用方式：
> `node tools/knowledge-os-mcp/scripts/apply-knowledge.mjs`
>
> 每个 `# 标题` 会成为 PROJECT.md 里的一个 `## 标题` 小节。

# Objective 目标

构建**智能电子保函运营管理平台**：面向电子保函（投标保函 / 履约保函）业务的运营管理后台，并在其之上提供可审计、数据不编造的 AI 业务助手。

**第一阶段**（见下文「Roadmap 阶段路线图」）的目标是交付一个**可演示、可验证、数据可复现**的完整闭环：

```text
登录 → 浏览真实业务数据 → 打开 AI 助手 → 提问
→ AI 调用真实业务 Tool 查库 → 流式返回真实统计 → 与页面统计交叉验证一致
```

# Background 背景

- 电子保函业务的核心对象是**投标订单**与**履约订单**，围绕机构、部门、用户、险种、区域、企业、项目组织。
- 运营人员需要按区域 / 机构 / 险种 / 时间多维分析业务，并回答「哪些机构在下滑」「哪个区域更多」这类问题。
- 传统做法是固定报表，扩展性差。本项目用 **AI 助手 + Tool 调用**替代固定报表：模型不直接接触数据库、不生成 SQL，只能调用受控的业务 Tool，从而在保留自然语言灵活性的同时保证**口径可核对、数字不编造**。
- 为了让 AI 的正确性**可被自动验证**，演示数据不是纯随机，而是按显式业务规律加权采样（详见 `技术 - 演示数据与可验证业务规律`），使「浙江 > 江苏」「2026Q3 后 6 个机构下滑」这类结论可以被断言。

# Scope 范围

**业务域**

- 系统管理：机构（`sys_org`）、部门（`sys_department`，树形）、用户（`sys_user`）、角色（`sys_role`）、权限（`sys_permission`）、用户-角色 / 角色-权限关联、险种（`insurance_type`）、地区字典（`sys_region`）
- 订单：投标订单（`tender_order`）、履约订单（`performance_order`），支持按订单号 / 区域 / 机构 / 险种 / 状态 / 日期 / 项目 / 企业过滤
- 业务分析：概览指标、订单趋势（月 / 日）、区域分布、险种分布、机构排行；企业与项目管理
- AI：会话与消息、SSE 流式对话、Tool 调用与记录、操作审计、**写操作提案（Proposal）**流程

**技术范围**

- 7 个 Maven 模块的模块化单体（`guarantee-common/auth/system/order/analysis/ai/web`）
- Vue 3 + TypeScript + Vite 管理后台（含全局 AI Copilot）
- 后端同时承载前端构建产物（单端口部署）
- 逻辑删除（`is_deleted` / `deleted_at` / `deleted_by`）全表统一
- 全链路 TraceId 与统一响应结构

# Success Criteria 成功标准

- [x] `mvn verify` 全绿（逻辑删除交付时为 133 项，部门树改造后 139 项）
- [x] `npx vue-tsc --noEmit` 与 `npm run build` 均 exit 0
- [x] `AiToolChainIT` 用**确定性 Stub ChatModel**（不需要 API Key）验证完整链路：模型发起 Tool Call → Tool → Service → Mapper → MySQL，并断言 Tool 返回的 5 个指标与直接调用 Service 的结果**逐一相等**
- [x] 演示数据可复现：固定随机种子 `20260920`，`sys_user` 非空时幂等跳过
- [x] AI 回答可交叉验证：AI 的季度统计与「数据概览」页面同条件统计**完全一致**
- [x] 未配置 `DEEPSEEK_API_KEY` 时**明确报错、不编造数字**
- [ ] 真实模型（DeepSeek）端到端问答 —— **尚未验证**
- [ ] Docker 容器化构建与运行 —— **尚未验证**（开发机未装 Docker）

# Roadmap 阶段路线图

> **五阶段的 AI 能力演进路线**（2026-09-29 由用户确认，链路按用户原话保留）。
> 它描述的是「AI 能力」如何逐级长出来，**与 `TASKS.md` 里的工程 Phase 0–7 不是同一套编号**：
> 工程 Phase 0–6 全部落在**第一阶段**之内。
>
> **「现在到哪个阶段」只有一个权威指针**：`04_Work/Active/guarantee-ai-admin/STATE.md` 的
> 「Current Milestone 当前里程碑」。本小节只定义每个阶段是什么、做到什么算完成、以及各阶段的**明确不做**。

| 阶段 | 链路（用户原话） | 完成判据（DoD） | 状态（2026-09-29） |
| --- | --- | --- | --- |
| 第一阶段 | 传统后台 → 真实业务数据 → AI Chat → Tool Calling | 登录 → 浏览真实业务数据 → AI Chat 提问 → Tool Calling 查库 → 流式返回真实统计 → 与页面统计交叉验证一致 | ✅ 已交付 |
| 第二阶段 | 多 Tool → Agent → 业务分析 | 多 Tool 覆盖 订单汇总 / 分布 / 趋势 / 企业 / 项目 五个面；**受约束** Agent（轮次 ≤ 4、单轮调用 ≤ 12、整体 ≤ 60 s，任何受限情形都给出可读结论或可读原因）；黄金问题集 15 条可验收 | 🚧 收尾中 |
| 第三阶段 | RAG → 业务知识 | 业务知识（口径 / 制度 / 文档）可被检索并溯源，答案不编造 | ⬜ 未开始 |
| 第四阶段 | AI 配置 → Human Confirmation → Audit | AI 的模型 / 提示词 / 能力开关可配置；写操作 100% 经人工确认并可全量审计 | 🟡 人工确认与审计**已提前交付**；AI 配置未做 |
| 第五阶段 | MCP → Evaluation → Observability | 受控能力以 MCP 暴露给外部 Agent；评测可自动跑；轮次 / 调用 / 成本 / 耗时可持续观测 | ⬜ 未开始（仅有 `/actuator/health`） |

### 第一阶段 · 传统后台 → 真实业务数据 → AI Chat → Tool Calling

- **交付事实**：MySQL 20 张表 + 固定种子（`20260920`）演示数据（`tender_order` 100,000 / `performance_order` 50,000）；`POST /api/ai/chat` SSE（`meta` / `delta` / `tool_call` / `done` / `error`）；会话与消息落库；受控 Tool 查库（`Tool → Service → Mapper → DB`，模型不碰 DB、不生成 SQL）。
- **判据证据**：`AiToolChainIT`（Stub ChatModel，不需要 API Key）断言 Tool 返回的 5 个指标与直接调用 Service **逐一相等**，并校验 SSE 事件序列与落库；未配 `DEEPSEEK_API_KEY` 时明确报错、不编造数字。
- **遗留**：真实模型（DeepSeek）端到端问答**从未验证**（缺 API Key）——它不是阶段一的完成条件，但仍是全项目最大的未验证项。

### 第二阶段 · 多 Tool → Agent → 业务分析（当前阶段）

- **已完成的主体**：16 个工具（11 只读 + 5 写提案），按权限裁剪注册；订单汇总 + 区域 / 机构 / 险种三维分布；每次工具调用的入参 / 结果 / 耗时 / 状态落库 `ai_tool_call`；轮次用尽会强制收口，空回答有兜底。
- **尚未完成**：分布工具的交叉过滤（区域 / 机构）、趋势工具、企业维度、项目维度、查询预算与超时护栏、每次分析的成本可观测、黄金问题集 15 条。
- **需求真源**：`docs/REQ-助手业务分析能力阶段二收尾.md`（v1.0，2026-09-29），里程碑 M2.1 → M2.2 → M2.4 →（M2.3），合计约 6 人日。
- **红线（不因推进阶段而放松）**：模型不直接改数据（写操作一律走 提案 → 人工确认 → 执行 → 审计）；不生成 SQL、不在 Tool 里注入 Mapper；业务口径只有一个来源（页面与 AI 同 Service、同 SQL）。
- **术语澄清**：这里的 Agent 指**受约束的多步执行**（预算 + 收口 + 降级），**不是**自主规划型 Agent —— 后者明确不做。

### 第三阶段 · RAG → 业务知识

- **要长出来的能力**：业务知识（业务口径、制度文档、领域概念）可被检索并**溯源**，回答引用来源，而不是靠模型记忆或把知识硬塞进提示词。
- **现状**：未开始。业务知识目前**全部由系统提示词承载**（`guarantee-ai/src/main/resources/prompts/business-assistant.st`）。
- **明确不做（在第二阶段）**：RAG / 向量库 / embedding，见 `docs/REQ-助手业务分析能力阶段二收尾.md` §2.2。

### 第四阶段 · AI 配置 → Human Confirmation → Audit

- **要长出来的能力**：① AI 配置（模型、提示词、能力开关的配置化，不必改代码重启）；② Human Confirmation；③ Audit。
- **现状 ② ③ 已提前交付**：`ProposalService` + 5 个执行器（机构 / 部门 / 用户 / 角色 / 险种）、前端 `ProposalCard.vue` 确认卡、`ai_operation_audit`（按月分区 + 90 天护栏 + 归档 + 脱敏）、审计页与自查工具。
- **现状 ① 仍未做**：`README.md` 明确把「AI 配置写入」列为本阶段未实现。
- **为什么顺序会提前**：提前交付的是「写操作的安全闭环」，它是让 AI 改数据的**前提**，因此先于配置化落地。这不改变阶段编号，只说明第四阶段的一部分已经完成。

### 第五阶段 · MCP → Evaluation → Observability

- **要长出来的能力**：① 把受控能力（工具 / 查询）以 **MCP Server** 形式暴露，供外部 Agent 复用；② Evaluation（评测可自动跑）；③ Observability（轮次 / 调用 / 成本 / 耗时可持续观测）。
- **现状**：业务侧未开始。现存的只有 `/actuator/health`（含 `authRevocation` 指示器，免登录）。
- **已有的起点**：第二阶段的「黄金问题集 + 真机冒烟脚本」与「每次分析的轮次 / 调用 / token / 耗时日志」被显式设计为**第五阶段 Evaluation / Observability 的雏形**（见 REQ §5.3）。
- **注意区分**：`tools/knowledge-os-mcp/` 是**开发流程**用的知识库 MCP，**不是**本项目的业务 MCP。

### 怎么判断「我们现在在哪个阶段」

```text
1. 读 04_Work/Active/guarantee-ai-admin/STATE.md 的「Current Milestone 当前里程碑」
   —— 那里写着阶段号与当前里程碑（唯一权威指针）。
2. 要阶段细节 → 本节各阶段的 DoD + 该阶段的需求真源文档（docs/ 下）。
3. 要「还差什么」→ STATE.md 的 In Progress / Verification，或 TASKS.md 里该阶段的任务清单。
```

**判据从严**：一个阶段只有在其 DoD 全部有证据（测试 / 真机 / 产物）时才从 🚧 改成 ✅；
「代码写完」不等于「阶段完成」，未验证的事项一律标注出来。

# Non-Goals 非目标

- **不做微服务拆分**：明确选择模块化单体，一个 Spring 上下文聚合 7 个模块
- **按阶段推进，不提前引入**（见「Roadmap 阶段路线图」）：RAG / 向量库 = 第三阶段；AI 配置写入 = 第四阶段；MCP / Evaluation / Observability = 第五阶段；多模型路由、LangChain4j、MQ、跨会话记忆、自主规划型 Agent = 尚未排期
- **不做分库分表 / 读写分离**
- **接口层不做细粒度 `@PreAuthorize` 拦截**：第一阶段只下发权限编码供前端按菜单展示
- **不做多租户**
- **不做实时消息推送**：AI 只用 SSE 单向流式
- **不做数据导出 / 报表生成**（`05_Output` 层面的人工交付不在本期）

# Constraints 约束

**技术**

- Java 21（Temurin 21.0.12.1）、Spring Boot 4.1.1、Spring AI 2.0.1（`spring-ai-starter-model-openai`）、MyBatis Spring Boot Starter 4.0.0
- MySQL 8.0（本机便携版 8.0.29，监听 **3307**，因 3306 被本机 MySQL 5.7 占用）、Redis 7 / 5.0.14
- Vue 3 + TypeScript + Vite 6 + Element Plus + ECharts + Pinia + Axios
- 模型走 OpenAI 兼容 API，默认 DeepSeek `deepseek-chat`
- Spring Boot 4.1 默认 **Jackson 3**（`tools.jackson.databind`），不是 Jackson 2
- AI 侧禁止直接访问数据库、禁止生成任意 SQL、禁止直接操作 Mapper

**产品 / 架构**

- **分层铁律**：`Tool → Service → Mapper → DB`，Tool 只注入 Service
- **能力对齐原则**：AI 助手能做的事必须与人工在页面能做的事对齐
- **写操作必须走**：`AI Plan → Permission Check → Preview → User Confirmation → Execute → Audit`
- 同一份业务口径只能有一处实现（页面与 AI 共用同一个 Service）

**协作**

- 分支模型：`main` / `develop` / `release_v<版本>` / `feature/<模块>-<简述>` / `hotfix/<简述>`
- 提交信息：Conventional Commits，主题用中文（如 `feat(order): 新增投标订单导出接口`）
- 详见 `docs/GIT_CONVENTION.md`；克隆后执行 `scripts/setup-git.ps1` 启用钩子

**时间**

- 需求文档普遍以「人日」估算，例如逻辑删除方案预估 11~15 人日

# Architecture 架构

```text
                        ┌──────────────── frontend (Vue 3 + Vite) ────────────────┐
                        │  管理后台页面 + 全局 AI Copilot（fetch + ReadableStream）│
                        └───────────────────────────┬─────────────────────────────┘
                                                    │ /api/**（同源；dev 时经 Vite 代理）
                        ┌───────────────────────────▼─────────────────────────────┐
                        │                  guarantee-web（启动模块）               │
                        │   主类 / application.yml / schema.sql / DataInitializer  │
                        │   同时承载 frontend/dist（hash 路由，单端口部署）        │
                        └───┬──────────┬──────────┬──────────┬──────────┬─────────┘
                            │          │          │          │          │
                       ┌────▼───┐ ┌────▼───┐ ┌────▼────┐ ┌───▼────┐ ┌───▼────┐
                       │  auth  │ │ system │ │  order  │ │analysis│ │   ai   │
                       └────┬───┘ └────┬───┘ └────┬────┘ └───┬────┘ └───┬────┘
                            │          │          │          │          │
                            └──────────┴────┬─────┴──────────┘          │
                                            │                           │
                                       ┌────▼───────────────────────────▼────┐
                                       │           guarantee-common          │
                                       │ 统一响应 / 异常 / TraceId / 分页 /  │
                                       │ CurrentUser / 逻辑删除基础           │
                                       └─────────────────────────────────────┘
```

**依赖方向（单向，无环）**

```text
web     ──> auth ──> system ──> common
 │         │          ↑
 ├──> order ──────────┤
 ├──> analysis ──> order, system
 └──> ai ───────> order, system, analysis
```

`common` 不依赖任何业务模块。`CurrentUser` 放在 `common`，使 `ai` / `analysis` 无需反向依赖 `auth`。

**AI 内部结构**

```text
AiController ──> AiChatService（显式驱动工具调用循环，最多 4 轮）
                    │
                    ├─ BusinessAssistantPrompt（business-assistant.st + 系统日期 + 预解析时间范围）
                    ├─ TimeSemanticParser ──> TimeRange(startDate, endDate, description)
                    ├─ AiToolRegistry ──> 按权限裁剪 READ / WRITE 工具集
                    │      └─ RecordingToolCallback / BoundedToolCallback（装饰器）
                    ├─ ToolCallingManager.executeToolCalls(...)（框架，显式调用）
                    └─ ToolContext 里的 ToolCallEventSink ──> SSE tool_call 事件
```

# Key Components 关键组件

| 组件 | 位置 | 职责 |
| --- | --- | --- |
| `DataInitializer` | `guarantee-web/.../init/` | 固定种子生成可复现演示数据；`sys_user` 非空则跳过 |
| `CurrentUser` / 统一响应 | `guarantee-common` | 断掉 `ai`/`analysis` 对 `auth` 的反向依赖 |
| JWT + Redis 撤销列表 | `guarantee-auth` | 登录、令牌生命周期、登出即撤销 |
| `DepartmentService` | `guarantee-system` | 部门树（`机构 → 部门 → 子部门`），含 `GET /departments/tree` |
| 逻辑删除基础设施 | `guarantee-common` + 全模块 Mapper | `is_deleted` / `deleted_at` / `deleted_by` 与函数索引唯一键 |
| `OrderStatisticsService` | `guarantee-order` | 所有订单统计口径的**唯一实现**，页面与 AI 共用 |
| `OrderStatisticsMapper` | `guarantee-order` | `orderType=ALL` 时对两张订单表 `UNION ALL` 后聚合 |
| `OrderSummaryTool` | `guarantee-ai/tool` | `@Tool` 定义的统计入口，只注入 Service |
| `AiToolRegistry` | `guarantee-ai/tool` | 工具注册与**权限裁剪**；无 `ai:system:write` 则不注册任何 `propose*` |
| `RecordingToolCallback` | `guarantee-ai/tool` | 装饰器，逐次采集精确耗时并写 `ai_tool_call` |
| `BoundedToolCallback` | `guarantee-ai/tool` | 对工具结果做边界/字段策略约束（`ToolFieldPolicy`） |
| `AiDataScopeResolver` | `guarantee-ai/tool` | 把「当前用户能看的数据范围」解析成查询条件 |
| `AiPermissionGuard` | `guarantee-ai/tool` | 工具级权限校验 |
| `ProposalService` + 5 个 `*ProposalExecutor` | `guarantee-ai/service` | 写操作**提案**：预览、确认、执行、审计；机构/部门/用户/角色/险种各一个执行器 |
| `ProposalClaimGuard` / `DataSourceClaimGuard` | `guarantee-ai/service` | 防止提案被重复认领、防止模型虚构数据来源 |
| `ProposalSecretStore` / `ai_operation_secret` | `guarantee-ai` | 提案中敏感信息（如初始密码）的独立存取，**唯一不加逻辑删除的表** |
| `OperationAuditService` | `guarantee-ai/service` | 操作审计（`CHAT` / `TOOL_CALL` / `ERROR`，带 `trace_id`） |
| `TimeSemanticParser` | `guarantee-ai/time` | 中文时间语义 → 明确日期区间，服务端预解析后注入 Prompt |

# Current Strategy 当前策略

- **模块化单体而非微服务**：本阶段团队与部署规模不需要微服务的运维成本；用 Maven 模块 + 单向依赖保住边界，将来若要拆，边界已经清晰。
- **AI 只经 Service，不碰 DB**：这是整个 AI 能力的安全地基。Tool 里出现 Mapper 会被视为架构违规。
- **显式驱动工具调用循环**：不用 `ToolCallingAdvisor` 隐式自动装配（实测在本项目装配方式下不会进入顾问链），改为直接调用框架的 `ToolCallingManager`。行为可控、可测试、可精确计时。
- **读先写后**：第一阶段只上 READ 工具；WRITE 以「提案」形式落地 —— 模型只产出提案，落库必须经用户确认，从而把「AI 改数据」的风险收敛到可审计的动作上。
- **用数据规律换可验证性**：通过人为注入区域 / 季节性 / 险种结构规律，使 AI 分析结论可以被测试断言，而不是只能靠人眼觉得「看起来对」。
- **逻辑删除用函数索引唯一键**：`(业务键, IFNULL(deleted_at, '1970-01-01'))`，使「删除后业务键可复用」与「同一业务键只能有一条有效行」同时成立。

# Risks 风险

| 风险 | 可能性 | 影响 | 缓解措施 |
| --- | --- | --- | --- |
| Spring Boot 4 / Spring AI 2.0.1 与 1.x 写法差异大，易按旧记忆写出不生效代码 | 高 | 高 | README §一 固化差异清单；用 `AiToolChainIT`（Stub 模型）做确定性回归 |
| 真实模型（DeepSeek）行为未端到端验证 | 高 | 中 | 已实测「未配 Key 时明确报错、不编造数字」；Prompt 强制数据来源可核对 |
| README 已滞后于实现（如表数量、AI 能力范围） | 高 | 中 | 以 `schema.sql` 与代码为准；本知识库按实现取事实 |
| 逻辑删除改造面极广（18 张表 + 唯一键 + 全模块 Mapper） | 中 | 高 | 迁移脚本 `V1__` / `V2__` + 133 项回归；已交付 |
| 演示数据规律被误当真实业务结论 | 中 | 中 | 本知识库与 README 均显式声明「人为注入、用于可验证」 |
| Docker 产物未在容器中实测 | 中 | 中 | README §十三 显式记录；本地用便携版 MySQL + Redis 验证 |
| 前端 Vite dev server 未做浏览器实测 | 中 | 低 | `npm run build` 与类型检查通过；SSE 客户端按实测事件协议对齐 |

# Open Questions 待解决问题

- [ ] 是否在接口层启用细粒度 `@PreAuthorize`？现状只下发权限编码给前端按菜单展示。
- [ ] 是否需要对真实模型做端到端验证（需要 `DEEPSEEK_API_KEY`）？
- [ ] `docker-compose.yml` / `Dockerfile` 是否要在容器中实测？
- [ ] README 的过时描述（表数量、AI 能力范围、`docs/` 索引）何时刷新？
- [x] 混合检索 / 向量能力是否进入下一阶段？—— **已定：进入第三阶段（RAG → 业务知识），尚未排期**（见「Roadmap 阶段路线图」）。

# Related Knowledge 相关知识

- [[技术 - 平台技术栈与运行时差异]]
- [[技术 - 模块化单体与依赖方向]]
- [[技术 - 数据模型与逻辑删除]]
- [[技术 - AI 工具调用链路]]
- [[技术 - 演示数据与可验证业务规律]]
- [[技术 - 本地开发环境与端口]]
- [[概念 - 保函业务领域模型]]

# Related Decisions 相关决策

- [[决策 - 全表逻辑删除设计]]
- [[决策 - AI 写操作只产出提案]]
- [[决策 - 采用模块化单体而非微服务]]
- [[决策 - 显式驱动工具调用循环]]
- [[决策 - 业务口径唯一实现]]

# Related Artifacts 相关产物

- `README.md` — 项目主文档（唯一较完整的技术说明，但已部分滞后于实现）
- `pom.xml` — 聚合 POM，7 个模块与统一版本
- `guarantee-web/src/main/resources/db/schema.sql` — 20 张表的权威 DDL
- `docs/` — 需求 / 方案 / 决策 / 实施文档（`REQ-` `PLAN-` `DEC-` `IMPL-`）
- `docs/GIT_CONVENTION.md` — 分支与提交规范
- `frontend/` — Vue 3 管理后台源码
- `scripts/` — 本地环境启停、迁移与一次性改造脚本
- `.agents/skills/knowledge-continuity/` — 本知识库的 canonical Skill
- `tools/knowledge-os-mcp/` — 本知识库的 MCP Server
