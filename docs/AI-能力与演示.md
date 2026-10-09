# AI 能力与演示

> 从 [README](../README.md) 的「AI 第一阶段」「AI 第三/四/五阶段能力」「第一条 AI Demo」
> 「安全原则」几节搬来（2026-10-09）。**能力总览与交付状态**看
> [交付汇总-AI第三四五阶段.md](交付汇总-AI第三四五阶段.md) 与 [RELEASE-AI第三四五阶段.md](RELEASE-AI第三四五阶段.md)；
> 本文保留**实现口径与可复现的演示步骤**。

## 0. 框架差异（实现前必读）

Spring Boot 4.1 / Spring AI 2.0.1 与 1.x 的写法差异，本项目按 2.0.1 官方文档实现：

1. OpenAI 聊天模型的属性名是 **`spring.ai.openai.chat.model`**，1.x 的 `spring.ai.openai.chat.options.model` 已失效。
2. Spring Boot 4.1 默认使用 **Jackson 3**（`tools.jackson.databind.ObjectMapper`），不是 Jackson 2 的 `com.fasterxml.jackson.databind`。
3. 工具注册用 **`.tools(...)`**；`ChatClient.toolCallbacks(...)` 在 2.0.1 中已标记 `@Deprecated(forRemoval = true)`。
4. 向模型传 options 时**必须基于模型自身的 options 派生**：`OpenAiChatModel` 会把 `prompt.getOptions()` 强转为
   `OpenAiChatOptions`，若传入通用的 `ToolCallingChatOptions` 会在运行时抛 `ClassCastException`。
   正确做法是 `chatModel.getOptions().mutate()` 后再挂载 `toolCallbacks` / `toolContext`。
5. 流式输出只支持响应式栈，因此 `guarantee-ai` 依赖 `spring-boot-starter-webflux`（仅提供 Reactor），
   应用仍以 Servlet(MVC) 方式运行（`spring.main.web-application-type=servlet`）。

> 关于工具调用循环，见 §2：本项目**显式**使用框架的 `ToolCallingManager` 驱动循环，
> 而不是依赖 `ToolCallingAdvisor` 的隐式自动装配。

## 1. Tool：`queryOrderSummary`（第一阶段）

定义在 `guarantee-ai/.../tool/OrderSummaryTool.java`，使用 Spring AI 2.0 的 `@Tool` / `@ToolParam`。

入参：`orderType`、`startDate`、`endDate`、`regionCode`、`orgId`（日期必须是 `yyyy-MM-dd` 明确格式）。
出参：`orderCount`、`guaranteeAmount`、`premiumAmount`、`enterpriseCount`、`projectCount`，并回显查询条件与 `dataSource` 便于核对口径。

**分层铁律**：

```
Tool -> Service -> Mapper -> DB
```

`OrderSummaryTool` 只注入 `OrderStatisticsService`，**不注入任何 Mapper，不生成 SQL**。

另附一个只读工具 `getCurrentDate`，用于让模型在换算相对时间前拿到可信基准日期。

## 2. 工具调用循环与 Tool Call 记录

`AiChatService` **显式驱动**工具调用循环：流式调用模型 → 若返回 `tool_calls`，
交给框架的 `ToolCallingManager.executeToolCalls(prompt, response)` 执行 → 把
`ToolExecutionResult.conversationHistory()` 回灌继续下一轮，直到模型给出最终答案（最多 4 轮，防止死循环）。

**为什么不用 `ToolCallingAdvisor` 的自动装配**：已实测在本项目的装配方式下，
`DefaultChatClient` 并不会把 `ToolCallingAdvisor` 放进顾问链（自定义顾问会被调用，而它不会；
无论用 `.tools()` 还是显式 `defaultAdvisors(...)`、是否开启
`AdvisorParams.toolCallingAdvisorAutoRegister(true)` 都一样）。因此改为直接使用框架的
`ToolCallingManager` 显式驱动，行为可控、可测试，也便于精确计时。

这样做还有一个好处：**工具调用轮次的 assistant 消息只包含 tool_calls、没有正文**，
所以可以把每轮流式正文直接转发给前端而不会泄漏中间态，最终答案依然是**真流式**。

`RecordingToolCallback` 装饰 `ToolCallback`，逐次采集并写入 `ai_tool_call`：

| 字段 | 含义 |
|---|---|
| `tool_name` | 工具名 |
| `tool_type` | `READ` / `WRITE` |
| `arguments` | 入参 JSON |
| `result` | 执行结果 |
| `status` | `SUCCESS` / `FAILED` |
| `duration_ms` | 单次执行耗时 |
| `error_message` | 失败原因 |

用「装饰器」而非全局 `ToolCallingManager`，是为了拿到**每次调用**的精确耗时（Manager 只能拿到一批的总耗时）。
执行过程中同时通过 `ToolContext` 里的 `ToolCallEventSink` 把 `tool_call` 事件实时推给前端。

## 3. Prompt

`guarantee-ai/src/main/resources/prompts/business-assistant.st`，包含规范要求的 7 项：业务身份、数据必须来自 Tool、不允许编造数据、时间语义必须转换为明确日期、无法证明的只能作为推测、Tool 失败必须明确说明、输出关键数据与数据来源。

每轮运行时还会追加「当前系统日期」与「系统预解析的时间范围」。

> 第四阶段起提示词**版本化**（草稿/发布/回滚 + 发布门禁），DB 里有发布版时优先用 DB 正文，见 §6。

## 4. 时间语义：`TimeSemanticParser`

`guarantee-ai/.../time/TimeSemanticParser.java`，统一转换为：

```java
record TimeRange(LocalDate startDate, LocalDate endDate, String description)
```

支持：今天、昨天、前天、本月、上月、本季度、上季度、今年、去年、Q1–Q4、`2026年第三季度`、`2026年7月`、`2026年`、`最近N天/周/月`。

流程是**双保险**：服务端先解析出明确日期并注入 System Prompt，模型再据此调用 Tool，避免模型自己算错季度边界。

> 单测 `TimeSemanticParserTest`（`guarantee-ai`）：13 个用例覆盖全部时间语义，基准日固定为 2026-09-21，断言与运行时间无关。

## 5. SSE 事件协议

`POST /api/ai/chat` 请求体 `{ "conversationId": number|null, "message": string }`，响应 `text/event-stream`：

| event | data |
|---|---|
| `meta` | `{"conversationId":1,"conversationNo":"CV...","title":"..."}` |
| `delta` | `{"content":"文本片段"}` |
| `tool_call` | `{"id":1,"toolName":"queryOrderSummary","toolType":"READ","arguments":"{...}","result":"{...}","status":"SUCCESS","durationMs":12}` |
| `done` | `{"conversationId":1,"messageId":9}` |
| `error` | `{"message":"..."}` |

> 因为是 POST，浏览器 `EventSource` 不适用；前端用 `fetch` + `ReadableStream` 手工解析 SSE 帧（`frontend/src/utils/sse.ts`）。

`AiToolChainIT`（`guarantee-web`）用确定性 **Stub ChatModel** 替换真实模型，**无需 API Key** 即可验证完整链路：
模型发起 Tool Call → `ToolCallingManager` 执行 → `OrderSummaryTool` → `OrderStatisticsService` →
`OrderStatisticsMapper` → MySQL，并断言 **Tool 返回的 5 个指标与直接调用 Service 的结果逐一相等**，
同时校验 `ai_message` / `ai_tool_call` 落库（含 `duration_ms`、`message_id` 关联）与 SSE 事件序列。

## 6. 第三 / 四 / 五阶段能力（2026-09-30 交付）

### 6.1 业务知识检索与溯源（第三阶段）

- 真源：`guarantee-ai/src/main/resources/knowledge/**`（**18 条**，Markdown + YAML front-matter，编号 `KB-<DOMAIN>-NNNN`）
- 启动时幂等导入 `ai_knowledge_item`（内容变化 → `version+1` 并写 `ai_knowledge_import_log`；真源消失 → `RETIRED`，不物理删除）
- 工具 `queryBusinessKnowledge`（只读、登录可见；条目级 `permission_code` 在**服务端**裁剪）；
  回答末尾的「知识来源：KB-…《…》vN」由**服务端**按本轮真实检索结果追加，模型自写的会被剥离
- 迁移脚本：`guarantee-web/src/main/resources/db/migration/V7__ai_knowledge.sql`

### 6.2 AI 配置化 + 人工确认 + 审计（第四阶段）

- 页面：`系统管理 → AI 配置`（模型 / 提示词 / 能力开关 / 变更历史四页签）；接口 `/api/ai/config*`
- 配置真源是 `AiConfigCatalog`（**20 项**）；运行期快照在 `ai_config_item`，**改配置不重新打包、下一个请求生效**
- 提示词版本化：`ai_prompt_version`（DRAFT → PUBLISHED → ARCHIVED）+ 发布门禁
  （`node scripts/ai-golden-questions.mjs --suite=deterministic` 退出码 0 才允许发布；缺 Key 的真机集如实标注"未跑"）
- 审计：`CONFIG_UPDATE` / `AI_CONFIG`（before → after；密钥类只记 `<changed>`）；提案指纹闭环；
  归档执行体 `scripts/archive-operation-audit.ps1`（默认 DRY-RUN）
- **本期未接线**：`model.max-tokens` / `model.timeout` / `model.max-retries`（页面标注、改了不生效）

### 6.3 MCP / Evaluation / Observability（第五阶段）

- **业务 MCP**：网关 `tools/business-mcp`（Node + stdio，**15 个只读工具**）+
  平台侧 `GET /api/ai/mcp/tools`、`POST /api/ai/mcp/tools/{name}`、`/api/system/mcp-tokens`（权限 `ai:mcp:read` / `ai:mcp:manage`）；
  **默认关闭**（`guarantee.ai.mcp.enabled=false`，yml 级、需重启）；接入说明见 [MCP-外部接入.md](MCP-外部接入.md)
- **评测**：[TEST-助手黄金问题集.md](TEST-助手黄金问题集.md)（**37 条**，GQ-01~37）+ `scripts/ai-golden-questions.mjs`
  （`--suite=all|deterministic|live|refusal`、`--repeat=N`、`--baseline=<file>`）；
  确定性集由 `EvaluationDeterministicIT` 承载并纳入 `mvn verify`；
  拒答类（`--suite=refusal`）默认 **3 轮，N 次全通过才算通过**（方差敏感）
- **观测**：`/actuator/prometheus`（免登录，仅内网/白名单）+ `ai_turn_metric`（每轮问答一行，含 trace_id）+
  `系统管理 → AI 运行` 页
- **单一事实源**：`node scripts/single-source-of-truth.mjs`（测试项数 / 评测条数 / 指标清单 / MCP 工具清单，文档不再手写数字）

## 7. 安全原则

第一阶段只有查询能力，但架构上已提前区分读写：

- `ToolKind.READ` / `ToolKind.WRITE` 枚举，`AiToolRegistry` 当前只注册 READ 工具（业务 MCP 也只读）。
- 禁止 AI 直接访问数据库；禁止 AI 生成任意 SQL；禁止 AI 直接操作 Mapper。
- 所有 Tool 只能调用业务 Service。

后续所有 WRITE Tool 必须走：

```
AI Plan -> Permission Check -> Preview -> User Confirmation -> Execute -> Audit
```

`ai_audit_log` 已预留并已在写入（`CHAT` / `TOOL_CALL` / `ERROR`，带 `trace_id`）。

## 8. 第一条 AI Demo 操作说明

> 目标：**登录后台 → 打开投标订单 → 打开 AI Copilot → 输入问题 → AI 调用真实业务 Tool → 返回真实统计结果 → 前端流式展示。**

**前置**：配置好 `DEEPSEEK_API_KEY`，后端与前端均已启动（见 [本地开发环境与演示数据.md](本地开发环境与演示数据.md)）。

1. 浏览器打开 <http://localhost:5273>（方式 A 则是后端端口 <http://localhost:8081>）。
2. 用 `admin` / `Admin@123` 登录。
3. 左侧菜单进入 **投标订单**，确认列表有真实数据（可分页浏览）。
4. 点击右下角悬浮按钮，打开 **业务分析助手（AI Copilot）**。
5. 点击「新建会话」，输入：

   ```
   2026年第三季度投标订单有多少？
   ```

6. 发送。观察右侧对话面板依次出现：
   - **工具调用卡片**：`queryOrderSummary` · `READ` · `SUCCESS` · 耗时 xx ms，可展开查看入参与返回的 JSON；
   - **流式正文**：逐字出现，包含订单量、保函金额、保费、企业数、项目数；
   - 末尾标注「数据来源：queryOrderSummary(orderType=TENDER, startDate=2026-07-01, endDate=2026-09-30)」。
7. 交叉验证：进入 **数据概览**，把时间范围设为 `2026-07-01 ~ 2026-09-30`，订单类型选「投标订单」，页面统计数字应与 AI 回答**完全一致**。

**可验证的其它问题**：

- 「2026年第三季度哪些机构订单量在下滑？」→ 应识别出后 6 个机构（区域看机构分析）。
- 「浙江省和江苏省哪个地区的投标订单更多？」→ 浙江应显著高于江苏。
- 「最近三个月履约订单的保费合计是多少？」

**没有 API Key 时**：第 6 步会明确返回「AI 模型调用失败：API Key 未配置或无效…」，不会编造任何数字——这本身就是 Prompt 约束生效的体现。

**真机评测**：`pwsh -File scripts/run-live-eval.ps1 -Port 8092`（详见 [CI-真机评测.md](CI-真机评测.md) 与 [TEST-助手黄金问题集.md](TEST-助手黄金问题集.md)）。
