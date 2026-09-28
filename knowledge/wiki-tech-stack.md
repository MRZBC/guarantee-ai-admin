---
type: technology
title: 平台技术栈与运行时差异
status: active
related:
  - 技术 - 模块化单体与依赖方向
  - 决策 - 显式驱动工具调用循环
tags: [spring-boot, spring-ai, vue, mybatis]
---

> 本项目用的是**很新的版本组合**（Spring Boot 4.1.1 + Spring AI 2.0.1 + Jackson 3），
> 大量写法与 1.x / Boot 3.x 的公开示例不同。这一页把差异与坑集中记下来，
> 避免后续 Agent 按旧记忆写出「能编译但不生效」的代码。

## 是什么

`guarantee-ai-admin` 的技术选型：

| 层面 | 选型 | 版本 |
| --- | --- | --- |
| 语言 | Java | 21（Temurin 21.0.12.1） |
| 框架 | Spring Boot | 4.1.1 |
| AI | Spring AI | 2.0.1（`spring-ai-starter-model-openai`） |
| 持久层 | MyBatis Spring Boot Starter | 4.0.0 |
| 数据库 | MySQL | 8.0（本机便携版 8.0.29，端口 3307） |
| 缓存 | Redis | 7 / 5.0.14 |
| JWT | jjwt | 0.12.6 |
| 前端 | Vue 3 + TypeScript + Vite 6 + Element Plus + ECharts + Pinia + Axios | 见 `frontend/package.json` |
| 模型 | OpenAI 兼容 API，默认 DeepSeek `deepseek-chat` | — |

## 为什么重要

这 6 条差异如果不清楚，**代码会编译通过但运行时行为不对**，而且很难查：

1. **OpenAI 聊天模型的属性名是 `spring.ai.openai.chat.model`。**
   1.x 的 `spring.ai.openai.chat.options.model` 已失效 —— 写错了不会报错，只会静默用默认模型。

2. **Spring Boot 4.1 默认 Jackson 3**（`tools.jackson.databind.ObjectMapper`），
   不是 Jackson 2 的 `com.fasterxml.jackson.databind`。混用会导致序列化行为不一致。

3. **工具注册用 `.tools(...)`。**
   `ChatClient.toolCallbacks(...)` 在 2.0.1 已标记 `@Deprecated(forRemoval = true)`。

4. **向模型传 options 必须基于模型自身的 options 派生。**
   `OpenAiChatModel` 会把 `prompt.getOptions()` 强转为 `OpenAiChatOptions`；
   若传入通用的 `ToolCallingOptions`，运行时抛 `ClassCastException`。
   正确做法：`chatModel.getOptions().mutate()` 后再挂 `toolCallbacks` / `toolContext`。

5. **流式输出只支持响应式栈。**
   因此 `guarantee-ai` 依赖 `spring-boot-starter-webflux`（**仅为提供 Reactor**），
   而应用仍以 Servlet(MVC) 运行：`spring.main.web-application-type=servlet`。
   这一条特别反直觉 —— 看到 webflux 依赖容易误以为整个应用是响应式的。

6. **SSE 必须放行 `DispatcherType.ASYNC`。**
   否则异步派发时会因 Security 上下文已清理而抛 `Access Denied`，
   表现是**事件流被截断**（不是明确的 403），排查成本高。已在 `SecurityConfig` 处理。

## 怎么用 / 关键细节

排查「AI 相关改动没生效」时的顺序：

```text
1. 属性名对不对？      spring.ai.openai.chat.model（不是 .options.model）
2. options 是不是从    chatModel.getOptions().mutate() 派生的？
3. 工具是不是用        .tools(...) 注册的？
4. Jackson 类型对不对？ tools.jackson.* 而不是 com.fasterxml.jackson.*
5. SSE 相关改动？      确认 ASYNC dispatch 仍被放行
```

## 坑 / 限制

- **`mvn clean` 会打断 IDEA 运行实例。** IDEA 从 `target/classes` 直接运行源码，
  `clean` 删掉它正在用的目录后，运行中的进程不会立刻崩，但后续懒加载的类会抛
  `NoClassDefFoundError`。要重建就先停掉 IDEA 的运行实例。
- **前端 dev server 端口是 5273，不是 Vite 默认的 5173。** 5173 落在 Windows
  动态保留端口段（启用 Hyper-V / WSL2 / Docker Desktop 后为 5121–5220），
  绑定会直接 `EACCES: permission denied`。查看本机保留段：
  `netsh int ipv4 show excludedportrange protocol=tcp`。
- **MySQL 同时绑定 `127.0.0.1` 与 `::1`。** Windows 上 `localhost` 会优先解析到 IPv6 的 `::1`，
  只绑 IPv4 会让 JDBC 用 `localhost` 直接连不上。
- **`frontend/dist` 不存在时首页返回 404**，但后端本身正常工作（启动日志有提示）。
