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
`regionExact=true` 用于图表下钻，按完整地区编码匹配；缺省保持原有地区层级前缀匹配。

## 业务分析

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/analysis/overview` | 数据概览总览指标 |
| GET | `/api/analysis/order-trend` | 订单趋势（`granularity=month\|day`） |
| GET | `/api/analysis/order-region` | 区域分布 |
| GET | `/api/analysis/order-insurance` | 险种分布 |
| GET | `/api/analysis/order-institution` | 机构排行 |
| GET | `/api/analysis/order-comparison` | 本期与前一等长周期比较 |
| GET | `/api/analysis/order-report` | 同一快照内返回比较、趋势及三种分布 |
| GET | `/api/analysis/order-report/export` | 聚合摘要 CSV，含 BOM、统计条件及 Top N 说明 |
| GET | `/api/projects` / `/api/projects/{id}` | 项目管理 |
| GET | `/api/enterprises` / `/api/enterprises/{id}` | 企业管理 |

既有趋势与分布接口的 `orderType` 支持 `TENDER` / `PERFORMANCE` / `ALL`，也接受中文「投标」「履约」；ALL 合并两类订单后聚合。

新增 comparison/report/export 接口只接受 `TENDER` 或 `PERFORMANCE`（大小写不敏感），拒绝其他值。
通用参数为 `orderType`、`startDate`、`endDate`、`regionCode`、`orgId`；report 可带 `granularity`。
日期需同时填写，跨度 1–366 天，按申请日期且含首尾；都不填时，以该类型/地区/机构已知数据结束日取最近最多 30 天。
比较结果返回 `criteria`（归一后的实际条件）、`previousStartDate/previousEndDate`、已知覆盖日期、`comparisonComplete`、`changeAlert`，
以及 `orderCount/guaranteeAmount/premiumAmount` 三个指标。每项含 `current/previous/change/changeRate/status`；
零基数的变化率为空（JSON 可省略），状态 NEW/UNCHANGED/UP/DOWN。比较不完整时不生成提醒。
地区榜为完整地区编码分组，机构榜实际按机构＋地区分组，均为 Top 10；下钻机构榜须同时保留机构与精确地区。

新增读取入口要求 `analysis:overview:view`；导出同时要求 `analysis:export`，默认仅 ADMIN/ANALYST。
导出重新查询并标注生成时间，返回 `text/csv;charset=UTF-8` 和附件头；拒绝仍按统一错误协议返回，前端不得将错误 JSON 当文件下载。
导出通过既有审计端口记录 WEB/EXPORT/ANALYSIS_REPORT、查询条件、结果条数与 traceId，不记录文件正文；仅管理员可查看该目标类型的审计。
无新增表结构，权限由 `PermissionCatalog` 的新库播种与存量同步初始化器补齐；手动部署可执行 `V11__analysis_export_permission.sql`。

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


## 提示词候选评测与发布（2026-10-21）

接口、请求字段、权限、任务状态、发布证据校验与迁移说明见 [IMPL-提示词候选评测与发布](IMPL-提示词候选评测与发布.md)。发布请求现须包含 `versionNo + contentHash + evaluationId`；保存和发布不执行 Maven。旧 `GET /api/ai/config/prompts/gate` 仅返回兼容提示，不触发评测。


### 提示词基础规则与业务补充（2026-10-22）

`GET /api/ai/config/prompts/base`（ai:config:view）：基础version/content/contentHash，只读。
`POST /api/ai/config/prompts/draft`（ai:config:update）：businessContent（允许空串）、note、baseVersion、baseHash；旧全文迁移还须migrationSourceVersion、migrationSourceHash、migrationConfirmed=true。后端生成最终正文与内容哈希；不再接收可编辑的完整content。
版本详情增加baseVersion/baseHash/baseContent/businessContent/migrationSourceVersion/migrationSourceHash；旧版这些字段为null。评测/发布只接收已迁移的组合草稿；回滚使用历史原始完整快照。表结构见V13；部署与兼容说明见 [实施记录](IMPL-提示词候选评测与发布.md)。
