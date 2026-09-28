---
title: 显式驱动工具调用循环
status: active
---

# 决策 - 显式驱动工具调用循环

### decision

`AiChatService` **显式驱动**工具调用循环：自己调用模型 → 若返回 `tool_calls`
则调用框架的 `ToolCallingManager.executeToolCalls(prompt, response)` →
回灌 `ToolExecutionResult.conversationHistory()` 继续下一轮，直到模型给出最终答案（最多 4 轮）。

**不使用** `ToolCallingAdvisor` 的隐式自动装配。

### why

**因为它在当前装配方式下不生效。** 已实测：本项目的 `DefaultChatClient` 并不会把
`ToolCallingAdvisor` 放进顾问链 —— 自定义顾问会被调用，而它不会。
无论用 `.tools()` 还是显式 `defaultAdvisors(...)`、
是否开启 `AdvisorParams.toolCallingAdvisorAutoRegister(true)`，结果都一样。

显式驱动的额外收益：

- 行为**可控**：轮次上限、每轮做什么都写在代码里，不依赖框架内部自动装配的隐式行为
- 可**精确计时**：能在每轮前后埋点，而 Manager 只能拿到一批的总耗时
- **可测试**：`AiToolChainIT` 能用 Stub ChatModel 确定性验证整条链路
- 一个重要副作用：工具调用轮次的 assistant 消息**只含 tool_calls、没有正文**，
  因此每轮流式正文可以直接转发给前端而不会泄漏中间态 —— 最终答案依然是**真流式**

### context

Spring AI 2.0.1 提供了两条路：`ToolCallingAdvisor` 自动装配，
或直接用 `ToolCallingManager`。按官方示例的默认预期，前者应该开箱可用。
但在本项目的装配方式下实测不生效，而这一点从报错上完全看不出来 ——
表现只是「模型说了要调用工具，但工具从未被调用」。

如果不做这个决策而继续依赖自动装配，会得到一个**静默失效**的 AI 能力。
因此选择显式驱动，并在 README §8.2 固化这个结论，避免后来者再踩。

### alternatives

- **依赖 `ToolCallingAdvisor` 自动装配** —— 实测在本项目装配下不进入顾问链，
  工具调用静默失效，不可接受
- **改用 `ChatClient` 的其他装配方式去「修好」自动装配** —— 已试过 `.tools()`、
  显式 `defaultAdvisors(...)`、`toolCallingAdvisorAutoRegister(true)` 三种，均无效；
  继续试错的收益不确定，而显式驱动的成本很低
- **自己实现工具执行** —— 重复框架已有能力（参数解析、异常包装、结果序列化），
  且会偏离框架演进
- **用非流式调用简化循环** —— 会失去「真流式」体验，与产品目标冲突

### constraints

- 向模型传 options 必须**基于模型自身 options 派生**
  （`chatModel.getOptions().mutate()`），否则 `OpenAiChatModel` 强转 `prompt.getOptions()`
  时会抛 `ClassCastException`
- 必须有轮次上限（当前 4 轮）防止死循环
- 必须能通过 Stub ChatModel 做无 API Key 的确定性测试
- 工具执行必须逐次记录（`RecordingToolCallback`）以便拿精确耗时

### consequences

**正面**

- 工具调用行为确定、可测、可精确计时
- 最终答案是**真流式**（中间轮次无正文，可直接转发 delta）
- 轮次上限、异常处理、事件推送都在自己手里
- 不依赖框架自动装配的隐式行为，升级框架时风险更可控

**负面**

- 承担了框架本可代劳的编排逻辑（约百行），后续框架若改进自动装配，这份代码需要重新评估
- 与官方示例写法不同，新人容易疑惑「为什么不直接用 Advisor」——必须靠 README §8.2 与知识库解释
- 4 轮上限对复杂多步问题的能力有约束

### revisitConditions

- 当 Spring AI 新版本修好 `ToolCallingAdvisor` 在本项目装配方式下的自动注册时
- 当需要支持更长的工具调用链（超过 4 轮）或多工具并行调用时
- 当框架提供更细粒度的工具调用可观测性（如原生逐次耗时）时

### related

- [[技术 - AI 工具调用链路]]
- [[技术 - 平台技术栈与运行时差异]]
- [[决策 - AI 写操作只产出提案]]
