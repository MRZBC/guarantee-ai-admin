# 后端 API 概览

> 从 [README](../README.md) 的「后端 API」一节搬来（2026-10-09）。
> 这是**对外接口的形状**（路径 + 参数口径），不是逐字段的接口契约；
> 字段级契约以 Controller/DTO 源码与对应需求文档为准。

## 统一约定

- 统一响应结构：`{ "code": 0, "message": "成功", "data": ..., "traceId": "..." }`，`code === 0` 为成功。
- 分页结构：`{ pageNum, pageSize, total, list }`。
- 时间参数统一 `yyyy-MM-dd`（分析类接口的 `startDate` / `endDate` 同此口径）。
- 除 `/api/auth/login`、`/actuator/health` 等少数入口外，均需 JWT（`Authorization: Bearer <token>`）。

## 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录，返回 JWT 与用户信息 |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/auth/logout` | 登出（Redis 撤销当前令牌） |

## 订单

| 方法 | 路径 |
|---|---|
| GET | `/api/orders/tender` |
| GET | `/api/orders/tender/{id}` |
| GET | `/api/orders/performance` |
| GET | `/api/orders/performance/{id}` |

过滤参数：`orderNo`、`regionCode`、`orgId`、`insuranceTypeId`、`status`、`startDate`、`endDate`、`projectId`、`enterpriseId`。

## 业务分析

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/analysis/overview` | 数据概览总览指标 |
| GET | `/api/analysis/order-trend` | 订单趋势（`granularity=month\|day`） |
| GET | `/api/analysis/order-region` | 区域分布 |
| GET | `/api/analysis/order-insurance` | 险种分布 |
| GET | `/api/analysis/order-institution` | 机构排行 |
| GET | `/api/projects` / `/api/projects/{id}` | 项目管理 |
| GET | `/api/enterprises` / `/api/enterprises/{id}` | 企业管理 |

> `orderType=ALL` 时所有分析查询都对 `tender_order` 与 `performance_order` 做 `UNION ALL` 后再聚合，保证去重企业数/项目数口径正确。
> `orderType` 支持 `TENDER` / `PERFORMANCE` / `ALL`，也接受中文「投标」「履约」。

## 系统配置

| 方法 | 路径 |
|---|---|
| GET / POST / PUT | `/api/system/insurance-types`、`/api/system/insurance-types/{id}` |
| GET | `/api/system/insurance-types/options` |
| GET | `/api/system/orgs`、`/api/system/orgs/options`、`/api/system/orgs/{id}` |
| GET | `/api/system/departments`、`/api/system/departments/options` |
| GET | `/api/system/users`、`/api/system/users/{id}` |
| GET | `/api/system/roles`、`/api/system/roles/{id}` |
| GET | `/api/system/permissions` |

用户接口任何读路径都**不返回** `password` 字段。

> 系统管理域还有一批"手动操作能力补齐"的写接口（新增/修改/启停/删除/恢复等），
> 口径与拒绝理由见 [REQ-系统管理手动操作能力补齐方案.md](REQ-系统管理手动操作能力补齐方案.md)；
> 逻辑删除与恢复的通用语义见 [DEC-逻辑删除设计方案.md](DEC-逻辑删除设计方案.md)。

## AI

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/chat` | **SSE 流式**对话（事件协议见 [AI-能力与演示.md](AI-能力与演示.md)） |
| GET | `/api/ai/conversations` | 会话列表 |
| GET | `/api/ai/conversations/{id}` | 会话详情（含全部消息） |
| GET | `/api/ai/tool-calls/{conversationId}` | 该会话的 Tool Call 记录 |
| GET | `/api/ai/config*` | AI 配置（第四阶段，见下） |
| GET | `/api/ai/mcp/tools`、POST `/api/ai/mcp/tools/{name}` | 业务 MCP（第五阶段，默认关闭） |
| GET | `/api/system/mcp-tokens` | MCP Token 管理（权限 `ai:mcp:manage`） |

```bash
# 例：SSE 流式对话（需要 DEEPSEEK_API_KEY）
curl -N -X POST http://localhost:8081/api/ai/chat \
  -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"conversationId":null,"message":"2026年第三季度投标订单有多少？"}'
```

AI 域的能力清单、事件协议与演示步骤见 [AI-能力与演示.md](AI-能力与演示.md)；
可观测指标与 MCP 接入分别见 [REQ-第五阶段-MCP评测与可观测.md](REQ-第五阶段-MCP评测与可观测.md)、[MCP-外部接入.md](MCP-外部接入.md)。
