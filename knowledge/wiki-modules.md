---
type: technology
title: 模块化单体与依赖方向
status: active
related:
  - 决策 - 采用模块化单体而非微服务
  - 技术 - 平台技术栈与运行时差异
tags: [architecture, maven, modular-monolith]
---

> 7 个 Maven 模块、一个 Spring 上下文、**单向无环**依赖。
> 边界靠模块与依赖方向保住，而不是靠部署单元。

## 是什么

```text
guarantee-ai-admin/
├── pom.xml                     # 聚合 POM：统一版本、Lombok、surefire/failsafe
├── guarantee-common/           # 统一响应、异常、TraceId、分页、当前用户上下文、逻辑删除基础
├── guarantee-auth/             # 登录、JWT、Spring Security、Redis 撤销列表
├── guarantee-system/           # 机构/部门/用户/角色/权限/险种/地区字典
├── guarantee-order/            # 投标订单、履约订单、订单统计
├── guarantee-analysis/         # 数据概览、区域/险种/机构分析、项目、企业
├── guarantee-ai/               # 会话、SSE 流式聊天、Tool、Tool Call 审计、Prompt、时间语义
├── guarantee-web/              # 启动模块：主类、application.yml、建表 SQL、演示数据初始化
└── frontend/                   # Vue 3 管理后台 + 全局 AI Copilot
```

**依赖方向（单向，无环）**

```text
web     ──> auth ──> system ──> common
 │         │          ↑
 ├──> order ──────────┤
 ├──> analysis ──> order, system
 └──> ai ───────> order, system, analysis
```

## 为什么重要

- `common` **不依赖任何业务模块**。这是整张依赖图能无环的前提。
- **`CurrentUser` 刻意放在 `common`**，而不是 `auth`。
  否则 `ai` 与 `analysis` 需要知道「当前登录用户」，就得反向依赖 `auth`，形成环。
  把上下文对象下沉到 `common` 是这类分层的通用解法。
- `ai` 是最上层业务模块，可依赖 `order` / `system` / `analysis`；
  这正好支撑「AI 工具只能调用业务 Service」这条铁律。

## 怎么用 / 关键细节

新增一个跨模块能力时，按依赖方向选落点：

| 需求 | 落点 | 理由 |
| --- | --- | --- |
| 所有模块都要用的工具 | `common` | 不引入环 |
| 订单统计口径 | `order` 的 Service | 页面与 AI 共用，唯一实现 |
| 聚合多域的分析查询 | `analysis` | 它已依赖 `order` + `system` |
| AI 工具 | `ai/tool` | 只能注入 Service，不能注入 Mapper |

**判断改动的安全性**：如果你发现自己在 `order` 里 import 了 `ai` 的类，
那一定是设计错了 —— 依赖方向反了。

## 坑 / 限制

- 模块化单体**只有编译期边界**，没有运行期隔离。绕过边界（例如在 `order` 里直接用
  `ai` 的 Mapper）在技术上做得到，只能靠 review 与约定拦住。
- 因此「分层铁律」（`Tool → Service → Mapper → DB`）必须写进规范和知识库，
  而不能指望编译器。
