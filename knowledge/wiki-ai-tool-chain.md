---
type: technology
title: AI 工具调用链路
status: active
related:
  - 决策 - 显式驱动工具调用循环
  - 决策 - AI 写操作只产出提案
  - 技术 - 模块化单体与依赖方向
tags: [ai, spring-ai, tool-calling, sse]
---

> 「模型不碰数据库、不生成 SQL，只能调用受控业务 Tool」——
> 这是整个 AI 能力的安全地基。这一页记清链路、四条铁律，以及为什么工具循环是**显式驱动**的。

## 是什么 / 链路

```text
frontend（fetch + ReadableStream 手工解析 SSE）
   │  POST /api/ai/chat  { conversationId, message }
   ▼
AiController ──> AiChatService（显式驱动工具调用循环，最多 4 轮）
                     │
                     ├─ BusinessAssistantPrompt
                     │    business-assistant.st + 当前系统日期 + 服务端预解析的时间范围
                     ├─ TimeSemanticParser ──> TimeRange(startDate, endDate, description)
                     ├─ AiToolRegistry ──> 按权限裁剪 READ / WRITE 工具集
                     │      └─ RecordingToolCallback（装饰器，采集精确耗时）
                     │         BoundedToolCallback（结果边界与字段策略）
                     ├─ ToolCallingManager.executeToolCalls(prompt, response)（框架，显式调用）
                     └─ ToolContext 里的 ToolCallEventSink ──> SSE tool_call 事件
```

**READ 工具集**（`AiToolRegistry`）：订单统计、机构 / 部门 / 用户 / 角色 / 险种查询、
操作审计查询、我的工具调用、我的提案。

**WRITE 工具集**：机构 / 部门 / 用户 / 角色 / 险种 5 个 `propose*` 工具 —— 见
[[决策 - AI 写操作只产出提案]]。

## 四条铁律

```text
1. Tool → Service → Mapper → DB      Tool 只注入 Service，不注入 Mapper
2. 禁止 AI 直接访问数据库
3. 禁止 AI 生成任意 SQL
4. 禁止 AI 直接操作 Mapper
```

`OrderSummaryTool` 只注入 `OrderStatisticsService`，**不注入任何 Mapper、不生成 SQL**。
在 Tool 里看到 Mapper 就应视为架构违规。

## 为什么工具循环要显式驱动

`AiChatService` 自己驱动循环：

```text
流式调用模型
→ 若返回 tool_calls，交给 ToolCallingManager.executeToolCalls(prompt, response)
→ 把 ToolExecutionResult.conversationHistory() 回灌，继续下一轮
→ 直到模型给出最终答案（最多 4 轮，防死循环）
```

**为什么不用 `ToolCallingAdvisor` 的自动装配**（已实测）：

> 在本项目的装配方式下，`DefaultChatClient` **并不会**把 `ToolCallingAdvisor`
> 放进顾问链 —— 自定义顾问会被调用，而它不会。
> 无论用 `.tools()` 还是显式 `defaultAdvisors(...)`、
> 是否开启 `AdvisorParams.toolCallingAdvisorAutoRegister(true)`，结果都一样。

改为直接使用框架的 `ToolCallingManager` 后：行为可控、可测试、可精确计时。

**附带收益**：工具调用轮次的 assistant 消息**只含 tool_calls、没有正文**，
所以每轮流式正文可以直接转发给前端而不会泄漏中间态 —— 最终答案依然是**真流式**。

## 为什么用装饰器记录 Tool Call

`RecordingToolCallback` 装饰 `ToolCallback`，逐次写入 `ai_tool_call`：

| 字段 | 含义 |
| --- | --- |
| `tool_name` | 工具名 |
| `tool_type` | `READ` / `WRITE` |
| `arguments` | 入参 JSON |
| `result` | 执行结果 |
| `status` | `SUCCESS` / `FAILED` |
| `duration_ms` | 单次执行耗时 |
| `error_message` | 失败原因 |

用装饰器而非全局 `ToolCallingManager`，是为了拿到**每次调用**的精确耗时
（Manager 只能拿到一批的总耗时）。

## 时间语义：双保险

`TimeSemanticParser` 支持：今天、昨天、前天、本月、上月、本季度、上季度、今年、去年、
Q1–Q4、`2026年第三季度`、`2026年7月`、`2026年`、`最近N天/周/月`，
统一转换为：

```java
record TimeRange(LocalDate startDate, LocalDate endDate, String description)
```

流程是**双保险**：服务端先解析出明确日期并注入 System Prompt，模型再据此调用 Tool。
这样避免模型自己算错季度边界（这是实测容易出错的地方）。
另有只读工具 `getCurrentDate` 让模型在换算相对时间前拿到可信基准日期。

## SSE 事件协议

| event | data |
| --- | --- |
| `meta` | `{conversationId, conversationNo, title}` |
| `delta` | `{content}` |
| `tool_call` | `{id, toolName, toolType, arguments, result, status, durationMs}` |
| `done` | `{conversationId, messageId}` |
| `error` | `{message}` |

因为是 POST，浏览器 `EventSource` 不适用；前端用
`fetch` + `ReadableStream` 手工解析帧（`frontend/src/utils/sse.ts`）。

## 坑 / 限制

- **最多 4 轮工具调用**。这是防死循环的硬上限，增加它会放大延迟与成本。
- **SSE 必须放行 `DispatcherType.ASYNC`**，否则事件流会被静默截断
  （表现不是 403，而是流提前结束）—— 见 [[技术 - 平台技术栈与运行时差异]]。
- **未配置 `DEEPSEEK_API_KEY` 时明确报错、不编造数字**。这是设计行为，不是缺陷；
  它本身正是 Prompt 约束生效的证据。
- **真实模型的端到端问答从未验证过**，确定性验证靠的是 `AiToolChainIT` 的 Stub ChatModel。
