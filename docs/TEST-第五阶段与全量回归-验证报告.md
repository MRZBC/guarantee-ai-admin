# 第五阶段（MCP → Evaluation → Observability）+ 跨阶段全量回归 独立验证报告

| 项 | 内容 |
|---|---|
| 验证人 | `verifier`（独立验证员，未参与任何阶段实现） |
| 验证对象 | 路线图第五阶段 T5-00 ~ T5-04（task-12/13/14/15/16）+ 跨阶段回归（阶段三 / 阶段四 / 阶段五） |
| 需求真源 | `docs/REQ-第五阶段-MCP评测与可观测.md` §8 测试要求、§9 验收标准（L372–405） |
| 分支 / **快照** | `feature/ai-roadmap-phase3-5`，**HEAD = `817d74c`**（2026-09-30 04:34:18，phase4 D1/D2/D3 修复 + README 更新；此后工作区无在途改动） |
| 环境 | Windows + pwsh；MySQL 3307（`guarantee_ai_admin`）、Redis 6379 运行中；Java 21；Node 20+；**无 `DEEPSEEK_API_KEY`** |
| 验证时间 | 2026-09-30 04:34 – 04:52（Asia/Shanghai） |
| 方法 | 亲自复跑（全量 `mvn verify` / 前端 build / 评测脚本 / **真实 MCP 网关 stdio** / 真机 HTTP / 真机 prometheus）；独立只读核对真库；跨阶段静态比对 |

## 0. 口径与免责

**三态定义**：**成立** = AC 的全部子句均有可复核证据；**不成立** = 与 AC 字面矛盾（含子句缺失）；**无法验证** = 证据不足。

- 页面面 API = **HTTP 200 + 业务码**；**MCP 协议面 `/api/ai/mcp/**` = 真实 HTTP 状态 + 同值业务码**（两者不一致是**有意**的，见 REQ §6.2 v1.1；本报告不据此判失败）。
- 本机**无 `DEEPSEEK_API_KEY`** → `--suite=live` 真机评测一律 **未跑**，不计入通过。
- 验证纪律：Maven 串行（持 `maven.lock`，已释放）；**未执行 `mvn clean`**；8088 临时实例我自起自停并复核端口；**8081 用户实例全程未动**（每轮后确认仍在监听）。

**结论总览（x/y/z = 成立 / 不成立 / 无法验证）**

> # **成立 12 / 不成立 1 / 无法验证 0**

| 编号 | 三态 | 证据类型 | 一句话结论 |
|---|---|---|---|
| AC-MCP-01 | **成立** | **真机（真实 stdio 网关 + 真机 HTTP）** + IT | `initialize → tools/list(13) → tools/call(queryOrg)` 全通；结果与平台同一 ToolCallback 同源（逐字段一致），inputSchema 与描述直接复用 `@Tool` 定义 |
| AC-MCP-02 | **成立** | **真机** + IT | 签发（明文只出现一次）→ 撤销 → 下一次调用**立即 HTTP 401「已被撤销」**；库中只有哈希+前缀 |
| AC-MCP-03 | **成立** | IT（真实库）+ 静态 | 无 `ai:mcp:read` → **空清单 + 调用 403**（硬门禁 fail-closed）；数据范围走 `DataScopeService`（与页面/助手同源，IT 断言口径文本一致） |
| AC-MCP-04 | **成立** | **真机**（HTTP + 网关） | `proposeOrgChange` → HTTP 403 `TOOL_NOT_ALLOWED`；网关侧**"未发出任何 HTTP 请求"**即拒绝；无提案、无数据变更 |
| AC-MCP-05 | **成立** | **真机 DB** + IT | 每次调用落 `ai_tool_call`（`source=MCP` + 工具名 + `trace_id`）；限流拒绝另写 `ai_operation_audit`（`source=MCP`/`MCP_RATE_LIMITED`，实测 7 条） |
| AC-MCP-06 | **成立** | **真机**（网关启动）+ 单测 | 平台侧 `@ConditionalOnProperty` Bean 不注册（单测 3/3）；网关默认关闭 → **exit 1 + 可读 stderr**；平台自身不受影响 |
| AC-MCP-07 | **成立**（2 项子集未跑） | **真机 prometheus** + IT | `/actuator/prometheus` 200（63,417 B）：`ai_chat_requests/duration/rounds`、`ai_tool_calls/duration` **均可见且有数据**；`ai_tokens`/`ai_proposals` 真机无数据（无 Key、无提案）→ 埋点存在由源码+IT 覆盖 |
| AC-MCP-08 | **成立** | **真机 prometheus** + IT | 标签只有 `source/status/tool/outcome/model/capped/direction` 等**枚举维度**；`AiObservabilityIT` 遍历全部 `ai.*` meter 断言标签键 ⊆ 白名单；`AiChatMetrics` 无 id/文本入参且有归一截断 |
| AC-MCP-09 | **成立** | **真机 API vs 真库聚合** | overview/trend/tools/top 数字与 SQL 聚合**逐字段一致**（333 轮 / 平均 1.985 / 失败 11 / 触顶 12 / 平均 104.2853ms / queryOrderSummary 1001 次 / p95 92ms；`proposals:[]` 与 24h 内 0 条一致） |
| AC-MCP-10 | **成立**（附缺陷 D3） | **真机失败路径** + IT | 失败轮 `AI_TURN_COST traceId == ai_turn_metric.trace_id`（实测 `ec576625…`，非空）；三段（指标/工具调用/审计）一致由 IT 断言并经我复跑。**但日志字段错位（D3）** |
| AC-MCP-11 | **成立** | 单测/IT + 真机命令 | `EvaluationDeterministicIT` **12/12** 纳入 `mvn verify`；脚本 `--suite=deterministic` **exit 0**；`--baseline` diff **新增失败 0**；live 报告 `notRun=1` → 如实"未跑"；评测后业务基线未变（tender 100000 / 险种 6 / 机构 21） |
| AC-MCP-12 | **不成立（按字面）** | 脚本输出 + 静态 | 脚本产出 **13** 个只读工具，README 与 `MCP-外部接入.md` 均为 13；**但 REQ-第五阶段 3 处仍写"12 个"** → 文档间数字仍互相矛盾；另 SSOT 测试项数被陈旧报告污染（见 §7-D1/D2） |
| AC-MCP-13 | **成立** | **真机** + 全量回归 | `/actuator/health` → 200 `{"groups":["liveness","readiness"],"status":"UP"}`，`exposure` 仍含 health/info；`RevocationFailClosedIT` 3/3；阶段二/三断言未变；`mvn verify` 全绿 |

## 1. 我亲自复跑的命令与输出摘要

| # | 命令 | 结果 | 摘要 |
|---|---|---|---|
| 1 | `mvn -B verify`（持 maven.lock，04:34–04:37） | **BUILD SUCCESS** | 各模块 SUCCESS；单测：common 7 / system 120 / auth 28 / order 2 / analysis 6 / **ai 420** / web 11；**guarantee-web IT 113 run / 0 failures**（failsafe 汇总行见 §1.1） |
| 2 | `npx vue-tsc --noEmit` / `npx vite build`（frontend） | **exit 0 / exit 0** | `✓ built in 5.83s`（注：本次 frontend.lock 因相对路径解析失败未实际持有，见 §7-D6） |
| 3 | `node scripts/ai-golden-questions.mjs --suite=deterministic` | **exit 0** | `确定性集 12/12 通过（未跑 0）`；通过率 1 / 口径正确率 1 / 引用完整率 1 / 禁用术语违规 0 |
| 4 | `node scripts/ai-golden-questions.mjs --suite=deterministic --baseline=reports/eval-deterministic-2026-09-30.json` | **exit 0** | `与基线的 diff：新增失败 0 / 新修复 0 / 基线里没有的新题 无` |
| 5 | `node scripts/single-source-of-truth.mjs` | **exit 0** | 评测 33 条（脚本=文档 GQ-01…GQ-33）；只读工具 **13**（Java=网关白名单）；指标 10 项；MCP 白名单 ⊆ Java `@Tool`：是；暴露写工具：否 |
| 6 | **真实 MCP 网关 stdio** × 真机后端（8088，`mcp.enabled=true`） | 成功 | `initialize` → `tools/list`（13，无 propose）→ `tools/call mcp__guarantee__queryOrg`（654 B 结果）→ 写工具调用被拒（`isError=true`，stderr：**未发出任何 HTTP 请求**） |
| 7 | 真机 HTTP：MCP 协议面 | 见 §2 | 无 token/bad token → **401 + code 401**；有效 token → 200 + 13 工具；写工具名 → **403**；限流 burst 10 次 → **5×200 + 5×429**；撤销后 → **401「已被撤销」** |
| 8 | 真机：`/actuator/health` 与 `/actuator/prometheus` | 200 / 200 | health=`{"status":"UP"}`；prometheus 63,417 B，`ai_tool_calls_total{source="mcp",status="success",tool="getcurrentdate"}` 等 |
| 9 | 真机：失败轮（无 Key）+ 三段 trace 核对 | 成功 | `event:error`「AI 模型调用失败：API Key 未配置或无效…」；`ai_turn_metric(outcome=ERROR, trace_id=ec576625…)` 与 `AI_TURN_COST traceId=ec576625…` **一致** |
| 10 | 真机：`/api/ai/metrics/*` vs SQL 聚合 | 见 AC-MCP-09 | 数字逐字段一致；24h 提案 0 条与 `proposals:[]` 一致 |
| 11 | 真机：网关**关闭**时启动 | exit 1 | `[business-mcp] ERROR 拒绝启动：业务 MCP 默认关闭（REQ-MCP-05）…` |
| 12 | 真库只读核对 | — | 业务基线 tender 100000 / perf 50000 / ent 3000 / proj 5000 / 险种 6 / 知识 18（与文档口径一致，评测未污染） |

### 1.1 全量 `mvn verify` 的 failsafe 关键汇总（原样）

```
[INFO] Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- com.guarantee.web.ai.mcp.McpBackendIT
[INFO] Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0 -- com.guarantee.web.ai.mcp.McpQuotaIT
[INFO] Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0 -- com.guarantee.web.ai.mcp.McpRateLimitIT
[INFO] Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- com.guarantee.web.ai.AiObservabilityIT
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- com.guarantee.web.ai.EvaluationDeterministicIT
[INFO] Tests run: 113, Failures: 0, Errors: 0, Skipped: 0
[INFO] guarantee-web ...................................... SUCCESS [ 57.410 s]
[INFO] BUILD SUCCESS
```

## 2. 真机 MCP 报文片段（可复核）

**A. 真实网关（`tools/business-mcp`，stdio）→ 真机后端**

```
>>> {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05",...}}
<<< {"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},
     "serverInfo":{"name":"guarantee-business","version":"0.1.0"},
     "instructions":"guarantee-ai-admin 业务 MCP（只读）…不提供任何写操作…"}}
>>> {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
<<< tools/list -> count=13 first=mcp__guarantee__getCurrentDate hasPropose=0
>>> {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"mcp__guarantee__queryOrg","arguments":{"limit":2}}}
<<< {"total":21,"items":[{"id":1,"orgCode":"ORGHQ","orgName":"平台总部",...}]}   (654 B)
>>> {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"mcp__guarantee__proposeOrgChange",...}}
<<< isError=true  ❌ [TOOL_NOT_ALLOWED] 工具 mcp__guarantee__proposeOrgChange 不是业务 MCP 暴露的只读工具
--- stderr ---
[business-mcp] INFO 工具清单已从平台拉取 {"count":13,"droppedWriteLike":0}
[business-mcp] WARN 拒绝调用未暴露的工具（未发出任何 HTTP 请求） {"tool":"mcp__guarantee__proposeOrgChange"}
```

**B. MCP 协议面（HTTP + Bearer）**

```
GET  /api/ai/mcp/tools（无 token）      -> HTTP 401 code=401  "缺少 MCP Token：请带上请求头 Authorization: Bearer <MCP-Token>"
GET  /api/ai/mcp/tools（假 token）      -> HTTP 401 code=401  "MCP Token 无效（未签发或已下架）"
GET  /api/ai/mcp/tools（有效 token）    -> HTTP 200 code=0    count=13  hasPropose=0
POST /api/ai/mcp/tools/queryOrg         -> HTTP 200 code=0    dataSource="机构配置 · 数据范围：全量（阶段一 O3：机构维度已移除）" truncated=false
POST /api/ai/mcp/tools/proposeOrgChange -> HTTP 403 code=403  "工具 proposeOrgChange 不是业务 MCP 暴露的只读工具（写能力只能在平台页面内确认执行）"
POST /api/ai/mcp/tools/dropTable        -> HTTP 403 code=403  （同上）
burst 10 × getCurrentDate (qps=5)       -> 200/0 200/0 200/0 200/0 200/0 429/429 429/429 429/429 429/429 429/429
DELETE /api/system/mcp-tokens/{id}      -> 200 {"revoked":true,"message":"已撤销：下一次调用立即失败（鉴权每次直查库，无缓存窗口）"}
GET  /api/ai/mcp/tools（撤销后）        -> HTTP 401 code=401  "MCP Token 已被撤销，请重新签发"
```

## 3. 逐条 AC 详证

### 3.1 AC-MCP-01（MCP 客户端完成 initialize → tools/list → tools/call，结果与页面逐字段一致）—— **成立**

- **真机全链路**（§2-A）：真实网关进程 + 真机后端，13 个工具、调用 `queryOrg` 返回 654 B JSON。
- 契约与实现：`McpController.tools/call`（`McpController.java:139-186`）返回 `{data:[{name,description,inputSchema}]}` 与 `{data:{result,dataSource,truncated}}`；`inputSchema` 取自 `ToolDefinition.inputSchema()` **原文**（`McpToolInvoker.java:129`），description 复用既有 `@Tool` 描述。
- **"逐字段一致"的结构性保证**：MCP 调用走的就是 `AiToolRegistry` 注册的同一 `ToolCallback`（`McpToolInvoker.java:63-88`），结果原样透传（只额外解析 `dataSource`/`truncated`）；IT `McpBackendIT:260-281` 断言 MCP 结果的 `dataSource` 与 `DataScopeService.resolve(同一账号)` 的口径文本一致。
- 工具白名单：`McpToolCatalog.java:34-49`（13 个，与 `tools/business-mcp/src/catalog.ts` 逐名一致，由 `McpToolCatalogTest` 读源文件断言）。

### 3.2 AC-MCP-02（签发 / 撤销；撤销后立即失败且可读）—— **成立**

- **真机**：`POST /api/system/mcp-tokens` → 明文 `mcp_…`（47 字符，仅本次响应）→ `DELETE` → 下一次 `GET /api/ai/mcp/tools` **HTTP 401「MCP Token 已被撤销，请重新签发」**（§2-B）。
- "撤销即时生效"的实现：鉴权每次直查库，无缓存窗口（`McpController.java:238-255` 注释 + `McpTokenService.verify`）。
- 明文纪律：签发响应只出现一次；列表不含明文/哈希（`McpTokenView`；`McpBackendIT:139-158` 断言库中只有 hash、列表不含明文）。
- 状态可区分：`TOKEN_INVALID / TOKEN_REVOKED / TOKEN_EXPIRED` 三个错误码分别映射 401（`McpErrorCode.java:16-23`），外部 Agent 能区分。

### 3.3 AC-MCP-03（无 `ai:mcp:read` 看不到工具；数据范围同源）—— **成立**

- **硬门禁**：`McpToolInvoker.listTools` 先判 `hasMcpRead` → 无权限直接**空清单**（`:109-112`）；`invoke` 先 `requireMcpRead` → 抛 `PERMISSION_REQUIRED`(403)（`:63-64,146-149`）。IT `McpBackendIT:229-241` 用只含 `system:org:view` 的 token 断言：清单空 + 调用 403 可读。
- **两层裁剪一致**：清单 = `AiToolRegistry.callbacks(tokenPermissions)` ∩ 只读白名单（`:113-130`），保证"清单是调用面的子集"。
- **数据范围同源**：`buildToolContext(principal)` 把 token 权限/账号写入 `ToolContext`，工具内部走既有 `AiPermissionGuard` + `DataScopeService`（`:30-31,70`）；`McpBackendIT:260-281` 断言与 `DataScopeService.resolve(同账号, 角色)` 的口径一致。
- 与"聊天链路登录可见 4 个公开工具"的两层语义差异已在 `McpController.java:65-76` 与 `McpErrorCode.java:31-37` 明确登记（**不是**不一致缺陷）。

### 3.4 AC-MCP-04（不暴露任何写工具；调用被明确拒绝且无副作用）—— **成立**

- **真机 HTTP**：`proposeOrgChange` / `dropTable` → **HTTP 403 + code 403** + 可读中文（§2-B）。
- **真机网关**：被拒时 `isError=true`，且 stderr 明示 **"未发出任何 HTTP 请求"** —— 拒绝发生在网关本地（`backend.ts`/`catalog.ts` 白名单），连平台都不会被打到。
- 拒绝顺序：`McpToolCatalog.requireReadOnlyTool`（`McpToolCatalog.java:69-79`）在任何调用前执行；`McpToolInvoker` 还有"注册集里混进写工具也不暴露"的双保险（`:124-128`）。
- 无副作用：被拒请求在平台侧不产生 `ai_tool_call`/提案/数据变更（拒绝发生在调用之前）。

### 3.5 AC-MCP-05（可识别 `source=MCP`，可追到服务账号与 traceId）—— **成立**

- **真机真库**：`ai_tool_call` 出现 `source=MCP` 行，含 `tool_name`、`status=SUCCESS`、`trace_id`（示例 `0da245c5…`）；限流拒绝另写 `ai_operation_audit`（`action=MCP_RATE_LIMITED`、`source=MCP`、`result=REJECTED`，实测 7 条）。
- 实现：`McpController` 协议面写明 `CONTEXT`/`source`；`AiToolContextKeys.CALL_SOURCE` 由 invoker 写入，`RecordingToolCallback` 落库（IT `McpBackendIT:283-308` 断言 `ai_tool_call(source=MCP, trace_id)` + 审计归属服务账号）。
- 审计口径：签发/撤销记 `MCP_TOKEN_ISSUE/MCP_TOKEN_REVOKE`（`source=WEB`），限流记 `MCP_RATE_LIMITED`（`source=MCP`），目标类型 `MCP_TOKEN`（`McpController.java:95-106`）。

### 3.6 AC-MCP-06（默认关闭；关闭时网关不提供服务；平台自身不受影响）—— **成立**

- **平台侧**：`McpController` 带 `@ConditionalOnProperty(name="guarantee.ai.mcp.enabled", havingValue="true")`（`McpController.java:86-88`）→ 默认 false 时 Bean 与两套接口**都不存在**；单测 `McpEnabledConditionTest` 3/3。
- **网关侧**：`GUARANTEE_AI_MCP_ENABLED` 默认 false → `ConfigError` → **exit 1 + 可读 stderr**（我实测，§1#11；`config.ts:60-67`）。
- **登记**：开关是 **yml 级、需重启**（`McpController.java:68-71`），并说明"关闭时协议面与凭据管理面一起消失"的操作顺序。
- **平台自身不受影响**：我在整个验证过程中未触碰 8081；关闭网关不改变平台任何行为。

### 3.7 AC-MCP-07（`/actuator/prometheus` 7 类指标可见且有数据）—— **成立（2 项子集未跑）**

- **真机抓取**：`GET /actuator/prometheus` → HTTP 200，63,417 B。实测存在**且有数据**：
  ```
  ai_chat_requests_total{model="deepseek-chat",outcome="error"} 1.0
  ai_chat_duration_seconds_count{outcome="error"} 1 / _sum 0.839
  ai_chat_rounds_count{capped="false"} 1 / _sum 1.0
  ai_tool_calls_total{source="mcp",status="success",tool="getcurrentdate"} 6.0
  ai_tool_duration_seconds_count{...}
  ```
- **未跑（环境受限，如实标注）**：`ai_tokens`（无 Key → 真实 usage 恒 0，埋点在 `AiChatMetrics.java:119-131` 只在 >0 时注册计数器）与 `ai_proposals`（本环境无提案）在真机上**无数据**；其埋点存在性由源码 + `AiChatMetrics` 单测/`AiObservabilityIT` 覆盖。
- 依赖：`micrometer-registry-prometheus` 已在 `guarantee-web/pom.xml`；`application.yml:229` include `health,info,metrics,prometheus`。

### 3.8 AC-MCP-08（标签不含会话 id / 用户 id / 问题文本，基数可控）—— **成立**

- **真机**：prometheus 里 `ai_*` 的标签键只有 `application/source/status/tool/outcome/model/capped/direction`（§3.7 片段）。
- **静态**：`AiChatMetrics` 的公开方法**不接受** conversationId/userId/文本参数（类注释 `:32`）；`sanitize()` 归一 + 截断到 `MAX_TAG_LENGTH`（`:212-225`）。
- **测试**：`AiObservabilityIT:214-224` 遍历 `meterRegistry.getMeters()` 中所有 `ai.*`，断言每个标签键都在 `ALLOWED_TAG_KEYS` 白名单内。

### 3.9 AC-MCP-09（近 7 天指标页可查，且与 `ai_turn_metric` 聚合一致）—— **成立**

- **真机 API vs SQL（我逐字段比对）**：

  | 指标 | API（`/api/ai/metrics/overview?range=24h`） | SQL 聚合（`ai_turn_metric` 24h） | 一致 |
  |---|---|---|---|
  | 轮次总数 | `turns=333` | 333 | ✅ |
  | 平均轮次 | `avgRounds=1.985` | 1.985 | ✅ |
  | 失败轮 | `errorTurns=11`（errorRate 0.03303） | 11 | ✅ |
  | 触顶轮 | `cappedTurns=12`（cappedRate 0.03604） | 12（`capped=1` 也是 12） | ✅ |
  | 平均耗时 | `avgTotalCostMs=104.2853` | 104.2853 | ✅ |
  | token 合计 | `inputTokens=0 / outputTokens=0` | 0 / 0 | ✅ |
  | 提案状态 | `proposals:[]` | 24h 内 0 条（历史 38 条在 09-22~09-25） | ✅ |

- `trend?days=7` → 单点（今天）；`tools/top` → `queryOrderSummary` 1001 次 / p95 92ms / avg 69.3207ms（= CHAT 1000 + MCP 1，跨来源聚合一致）。
- 页面：`frontend/src/views/system/AiRuntime.vue` + 路由/菜单 `meta.permission='system:audit:view'`；前端构建 exit 0。

### 3.10 AC-MCP-10（日志 / 工具调用 / 审计三者 trace_id 一致且非空）—— **成立（附缺陷 D3）**

- **真机失败路径**（我无 Key 触发）：
  ```
  SSE: event:error  data:{"message":"AI 模型调用失败：API Key 未配置或无效（请设置环境变量 DEEPSEEK_API_KEY 后重启服务）"}
  ai_turn_metric: conversation_id=1582 outcome=ERROR source=CHAT trace_id=ec576625ce4f4edf816c70a418ea6327
  AI_TURN_COST  : traceId=ec576625ce4f4edf816c70a418ea6327 conversationId=1582 rounds=1 toolCalls=0 ...
  ```
  → 日志与指标行 **同一 traceId 且非空**。
- 三段一致（指标 / `ai_tool_call` / 审计）由 `AiObservabilityIT:142-195` 断言，我复跑 2/2 绿；失败路径另有 `:231-264`。
- **但日志字段错位（D3，见 §7）**：同一行的 `capped/capReason/source/outcome` 四个字段被参数错位、最后一个占位符无实参（实测 `capped=none capReason=CHAT source=ERROR outcome={}`）。traceId 不受影响，但"日志与指标字段一一对应"（REQ-MCP-09）**不成立**。

### 3.11 AC-MCP-11（一键评测：确定性集入 verify；真机集含基线 diff；退化显著标出）—— **成立**

- **确定性集纳入 verify**：`EvaluationDeterministicIT` 12/12（`-Dguarantee.ai.eval.deterministic-in-verify=true`，v1.1 澄清是 JVM 系统属性）；属 `mvn verify` 的一部分（我复跑 12/12）。
- **脚本能力**：`--suite=all|deterministic|live`、`--baseline=<file>`、JSON+Markdown 报告、退出码 0/1/2（断言失败=1、环境问题=2，`ai-golden-questions.mjs:1346-1349`）。我实测：`--suite=deterministic` exit 0；带 `--baseline` 输出 `新增失败：0 / 新修复：0 / 基线里没有的新题：无`。
- **真机集如实"未跑"**：现存 `reports/eval-live-2026-09-30.json` 为 `notRun=1` → 门禁 `liveGate()` 与页面均显示"未跑"（不冒充通过）；第四阶段报告 §复验已验证。
- **隔离（不污染共享库）**：脚本对确定性集明示"不需要真实数据基线"，真机集才校验订单/区县/seed 基线（`REQ §5.2.2`）；`EvaluationDeterministicIT` 内**无任何 `jdbcTemplate.update/INSERT/DELETE`**（grep 0 命中），只经只读工具链读写会话遥测；评测后业务基线仍为文档口径（tender **100000** / 险种 **6** / 机构 **21**）。
- 退化标注：报告含"新增失败 / 新修复 / 指标变化"，并通过 `--baseline` 显著标出。

### 3.12 AC-MCP-12（测试项数 / 评测条数 / 指标清单 / MCP 工具清单由脚本产出，文档数字与脚本一致）—— **不成立（按字面）**

**脚本产出（我实跑）**：评测 **33** 条（脚本与 `docs/TEST-助手黄金问题集.md` 双向一致 GQ-01…GQ-33）；只读工具 **13**（Java `@Tool` 只读 13 = 网关白名单 13，且 ⊆ Java 全量 18，无写工具）；指标 **10** 项。

**已一致的文档**：`README.md`（第五阶段章：**33 条**、**13 个只读工具**）与 `docs/MCP-外部接入.md`（§6.1「13 个」）与脚本输出一致 ✅。

**仍矛盾的两处 → 故判不成立**：

1. **`docs/REQ-第五阶段-MCP评测与可观测.md` 3 处仍写"12 个"**：§0 Q-MCP-03（L21）、§5.1.1 暴露范围（L168）、§13 追踪矩阵（L446），与脚本/README/MCP 接入文档的 **13** 直接矛盾（实现期把白名单扩到 13：新增 `queryBusinessKnowledge`，见 `McpToolCatalog.java:37-39`；`McpBackendIT:213-227` 也断言 13）。→ 建议把这三处改为 13 并注明变更来源。
2. **SSOT 的"测试项数"被陈旧 surefire 报告污染**：脚本输出单测 **682** / IT 125 / 合计 **807**，而我在同一快照上单次 `mvn verify` 的实际汇总为单测 **594**（7+120+28+2+6+420+11）/ IT 125；差额 **88** 全部来自 `guarantee-web/target/surefire-reports` 里 **03:17 遗留的 IT 类 XML**（21 个文件 87 条）与 analysis 的 1 条旧报告——那是 phase4 在 03:17 用另一种 maven 调用方式跑 IT 留下的。脚本的 stale 判定只比较"最新报告 mtime vs 源码 mtime"（`single-source-of-truth.mjs:202`），新报告会让整体看起来"不 stale"，从而把旧报告一起计入。当前**没有**任何文档引用 807/682（我已 grep 确认），所以尚未扩散，但"单一事实源"的数字不可信。

### 3.13 AC-MCP-13（`/actuator/health` 与 `authRevocation` 行为不变；既有测试与黄金 15 条不回归）—— **成立**

- **真机**：`GET /actuator/health` → HTTP 200，`{"groups":["liveness","readiness"],"status":"UP"}`；`application.yml:226-229` 的 `exposure.include` 仍含 `health,info`（新增 `metrics,prometheus`）。
- 撤销失败模式未变：`RevocationFailClosedIT` 3/3、`TokenLifecycleIT` 6/6、`AuthLoginGuardIT` 5/5 全绿。
- 既有测试：`mvn verify` **113 IT / 0 failures**、单测 594 / 0 failures（唯一失败为零）。
- 阶段二黄金 15 条断言未改：跨版本逐字比对仅数组结束符差异（阶段三报告已证），GQ-01~15 的 `expect` 一字未动。

## 4. TEST-MCP-01~08 状态

| 编号 | 类型 | 状态 | 证据 |
|---|---|---|---|
| TEST-MCP-01 | 单测（指标） | 就绪·通过 | `AiChatMetricsTest` 8、`AiObservabilityIT:198-224`（含标签白名单） |
| TEST-MCP-02 | IT（指标落库） | **就绪·通过（我复跑 2/2）** | `AiObservabilityIT`：指标行字段与结构化日志同源 + traceId |
| TEST-MCP-03 | IT（traceId 完整性） | **就绪·通过** | `AiObservabilityIT:142-195`（三段一致）+ `:231-264`（失败路径） |
| TEST-MCP-04 | 单测/IT（越权、限流、撤销） | **就绪·通过** | `McpBackendIT` 8/8、`McpRateLimitIT` 1/1、`McpQuotaIT` 1/1、`McpToolInvokerGateTest` 5、`McpRateLimiterTest` 8 |
| TEST-MCP-05 | 协议测试（stub 后端 + **真机后端**） | **就绪·通过** | 网关 vitest 协议测试（stub 后端）+ **我用真实 stdio 网关对真机后端跑通 initialize/tools/list/tools/call**（§2-A） |
| TEST-MCP-06 | IT（服务账号/数据范围/开关） | **就绪·通过** | `McpBackendIT`（SERVICE 强校验、DataScope 同源、无 `ai:mcp:read` fail-closed）、`McpEnabledConditionTest` 3/3 |
| TEST-MCP-07 | 评测脚本/baseline | **就绪·通过** | `--suite=deterministic` exit 0；`--baseline` diff 新增失败 0；退出码语义 0/1/2 |
| TEST-MCP-08 | 单一事实源 + 回归 | **机制就绪、数字待修** | 脚本 exit 0 且 README 一致；但 REQ 的 12 vs 脚本 13、SSOT 测试项数污染（§3.12） |

## 5. 跨阶段回归

| 回归项 | 结论 | 证据 |
|---|---|---|
| **阶段三黄金问题集断言**未回归 | 成立 | GQ-01~15 `expect` 逐字未改（阶段三报告 §3.9 + git 比对）；GQ-16~25 保留并扩到 33（脚本↔文档一致）；`EvaluationDeterministicIT` 覆盖 12 条跨阶段用例并 12/12 |
| **阶段四配置缺省一致性**未回归 | 成立 | `AiConfigWiringTest` 10/10（常量=目录默认值逐项）+ `AiConfigWiringIT` 3/3（真库缺省不覆盖 starter 的 0.2、config_version 落库）；本快照新增 D1/D2 修复后 `PromptVersionServiceTest` 19/19 |
| **阶段五评测**是否破坏三/四 | 未破坏 | 评测确定性集只走只读工具链；提示词发布门禁仍只用确定性集（真机集只标注，D2 已验证） |
| `business-assistant.st` 迁移后**红线完整** | 成立 | 268 行；规则编号 **1~48 连续齐全**（我按 `^(\d+)\.` 抽取）；5 个受保护标记全部存在且与 `PromptVersionService.PROTECTED_MARKERS` **逐字一致**（`# 铁律：数据必须来自 Tool` L31 / `# 事实与推测必须分开` L55 / `# 写操作铁律（最重要）` L79 / `# 敏感信息规则` L97 / `# 权限与可见性` L105）——这也保证"发布保护标记校验"不会把正常提示词判死 |
| `AiChatService` 三阶段改动**自洽** | 成立 | 第四阶段配置快照在请求入口取一次（`AiChatService.java:354`）并同时用于工具裁剪与预算；第三阶段 `knowledgeTurn` 与既有 `turnFacts` 分工不重叠（来源行 vs 口径行，`KnowledgeClaimGuard.excludingKnowledgeDataSources`）；第五阶段把"成本日志 + `ai_turn_metric` + Micrometer"收敛到**同一个收尾方法** `finishTurnCost`（`:1420-1458`），字段一一对应；失败/成功路径都由 `TurnCost.log` 的 CAS 保证只收尾一次 |
| **SSE 事件集合**未变 | 成立 | `AiChatService` 中的事件名仍为 `meta/delta/reset/tool_call/proposal/proposal_result/error/done`，无新增类型；`KnowledgeRetrievalIT.sseEventSetIsUnchanged` 断言 ⊆ {meta,delta,reset,tool_call,done} |
| **`/actuator/health` 与 AC-49** | 成立 | 真机 200 + `status=UP`；`exposure` 仍含 health/info；`RevocationFailClosedIT` 3/3 |
| 评测**不污染共享库** | 成立 | 评测 IT 无业务写入；评测后 tender 100000 / perf 50000 / ent 3000 / proj 5000 / 险种 6 / 知识 18 = 文档基线 |
| 既有测试 | 成立 | `mvn verify` 全模块 SUCCESS，113 IT + 594 单测 0 失败 |

## 6. 对抗式检查逐项

| 对抗点 | 结论 | 证据 |
|---|---|---|
| MCP 是否真的只读 | **是** | 真机写工具名 → 403；网关侧未发出 HTTP；白名单 ⊆ Java `@Tool`；无写工具入清单 |
| 构造越权 token | **被拒** | 无/假 token → 401；无 `ai:mcp:read` → 空清单 + 403（IT）；数据范围同源 |
| 撤销后立即调用 | **立即 401** | 真机 DELETE → 下一次调用 401「已被撤销」（无缓存窗口） |
| 限流 | **生效且可读** | 真机 burst：5×200 + 5×429；超限写 `MCP_RATE_LIMITED` 审计 |
| 指标标签含高基数 | **无** | 真机标签只有枚举维度；IT 遍历 `ai.*` 断言白名单 |
| `/actuator/health` 与 AC-49 回归 | **未回归** | 见 §3.13 |
| `ai_turn_metric` 与日志字段一致 | **指标行一致；日志行错位（D3）** | 真机失败轮对照；`AiChatService.java:1608-1612` 参数错位 |
| 评测污染共享库 | **未污染** | 业务基线未变；IT 无业务写入；脚本对 live 才校验数据基线 |
| SSE 事件集合未变 | **未变** | 事件名集合静态核对 + IT 断言 |
| MCP 前后端工具清单一致 | **一致（13）** | `McpToolCatalogTest` 读 `tools/business-mcp/src/catalog.ts` 源文件断言；SSOT 脚本也一致 |
| 网关对真机后端 | **跑通** | §2-A 真实 stdio 报文 |

## 7. 缺陷与处置汇总

| # | 缺陷 | 严重度 | 状态 | 处置建议 |
|---|---|---|---|---|
| **D1** | **SSOT 测试项数被陈旧报告污染**：脚本输出单测 682 / 合计 807，而单次 `mvn verify` 实际单测 594；差额 88 来自 `guarantee-web/target/surefire-reports` 里 03:17 遗留的 **IT 类 surefire XML**；stale 判定只看最新报告 mtime（`single-source-of-truth.mjs:202`） | 中 | 新发现 | 只统计"同一 verify 时间窗内"的报告，或在统计前只清 `target/*/surefire-reports`（不需要 `mvn clean`）；并补一条自检：surefire 报告里的类名必须匹配 `*Test`，出现 `*IT` 即告警 |
| **D2** | **REQ-第五阶段 3 处仍写"12 个只读工具"**（L21/L168/L446），与脚本/README/MCP 接入文档的 **13** 矛盾 | 中 | 新发现 | 三处改为 13，并注明"实现期纳入 `queryBusinessKnowledge`"（或反向：若产品口径就是 12，则应从白名单移除该工具并同步 README/网关） |
| **D3** | **`AI_TURN_COST` 日志字段错位**：实测 `capped=none capReason=CHAT source=ERROR outcome={}`；模板 13 个占位符只传 12 个实参，`capped→capReason.get()`、`capReason→SOURCE_CHAT`、`source→outcome`、`outcome` 无值（`AiChatService.java:1608-1612`）。**最小复现**：任一问答（含失败轮）后的应用日志；`AiObservabilityIT` 只断言 metric 不断言日志文本 → 未被发现 | 中 | 新发现 | 补上 `capped` 实参并按模板顺序传参（或改用结构化 KV 日志）；同时给 `AiObservabilityIT` 加一条"日志行字段=指标行字段"的断言 |
| **D4** | **失败轮不回填 `ai_conversation.prompt_version/config_version`**：`recordConfigVersion`（`AiChatService.java:724`）只在成功收尾路径；失败路径只写 `ai_turn_metric`（实测 conversation 1582 两列为 NULL）→ AC-CFG-09 的"每轮"在失败轮不成立 | 低 | 新发现 | 在 `onErrorResume` 收尾处也调用 `recordConfigVersion`（成本极低） |
| **D5** | 第四阶段 D3 的"未接线三键"只有**前端**防护，服务端仍接受写入（写了不生效） | 低 | 新发现（延续 phase4 复验） | 目录加 `wired` 元数据并在 `AiConfigService.update` 拒绝，或登记为已知 UI-only 防护 |
| **D6** | **验证过程记录**：本次 `npx vue-tsc/vite build` 的 `frontend.lock` 因在 `frontend/` 目录下用相对路径写锁而失败（实际未持锁执行；无并发前端构建，无冲突）。`node.lock`/`maven.lock` 均正确持有并已释放 | 低（工程） | 已记录 | 锁路径改用工作区绝对路径；我已复核 `.agent/locks/` 为空 |
| **D7** | **副作用，需 Lead 处置**：我为验证评测再次运行脚本，重新生成了被 git 跟踪的 `reports/eval-deterministic-2026-09-30.{md,json}`（生成时间与逐题耗时差异） | 低 | 待处置 | 我按纪律**未 `git checkout`**；请决定是否还原（与 phase4-D10 同一类） |
| **D8** | `ai.chat.duration` 等仪表的 `application` 标签来自 Micrometer 公共标签，非业务维度 | 观察 | 非缺陷 | 与高基数无关（固定值） |

**AC-MCP-12 不成立的处置建议**：① 修 REQ 三处 12→13；② 修 SSOT 统计口径（D1）；③ 修完后重跑 `node scripts/single-source-of-truth.mjs` 并把脚本输出作为唯一数字来源（README 已如此）。

## 8. 未跑项与前置条件

1. **`--suite=live` 真机评测（33 条中 12 条确定性 + live 集）**：**未跑（缺 `DEEPSEEK_API_KEY`）**；现存 live 报告 `notRun=1`，门禁/页面如实显示"未跑"。
2. **`ai_tokens` / `ai_proposals` 真机有数据**：未跑（无 Key → token 恒 0；本环境无提案）→ 仅源码+单测覆盖。
3. **MCP 限流的"每日配额"（`DAILY_QUOTA_EXCEEDED`）真机**：未跑（需把配额调到极小并消耗，属破坏性演练）；由 `McpQuotaIT` 1/1 覆盖。
4. **`ai:mcp:read` 缺失的**真机** token**：未跑（未签发无权限 token）；由 `McpBackendIT:229-241` 覆盖（真实库 + 真实 Controller）。
5. **AC-CFG-10 的真机发布正反例**：属第四阶段复验范畴，phase4 已做（§17 e2e）；本轮我只复核了修复代码与测试。

## 9. 证据文件索引（可复核）

| 文件 | 内容 |
|---|---|
| `.agent/verify/t5-mvn-verify.log` | 全量 `mvn -B verify`（BUILD SUCCESS / 113 IT） |
| `.agent/verify/t5-vue-tsc.log` / `t5-vite-build.log` | 前端 typecheck / build（exit 0） |
| `.agent/verify/t5-gate-deterministic.log`（见 `.agent/verify/`） | 门禁命令 exit 0 / 12-12 |
| `reports/eval-deterministic-2026-09-30.{md,json}` | 确定性评测报告（含 baseline diff 结果） |
| `reports/eval-live-2026-09-30.json` | 真机集报告（`notRun=1` → 页面/门禁显示未跑） |
| `.agent/verify/t5-app-8088-mcp.log` | MCP 开启的真机实例日志（含失败轮 `AI_TURN_COST` 原始行） |
| `tools/business-mcp/` + `guarantee-ai/.../mcp/` | 网关与平台侧实现（本报告引用的行号） |
| `scripts/single-source-of-truth.mjs` / `scripts/ai-golden-questions.mjs` | 单一事实源与评测脚本 |

## 10. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-30 | v1.0 | 首版：AC-MCP-01~13 逐条三态（**成立 12 / 不成立 1 / 无法验证 0**）、TEST-MCP-01~08 状态、跨阶段回归（阶段三红线与黄金断言 / 阶段四缺省一致性 / SSE / health）、对抗式检查、8 项缺陷与副作用、未跑项清单。验证快照 = HEAD `817d74c`（04:34:18）；真机证据包含**真实 stdio 网关对真机后端**的 initialize/tools/list/tools/call、真机 MCP 协议面 401/403/429/撤销、真机 prometheus 与 `/actuator/health`、失败轮三段 traceId 对照；`--suite=live` 未跑（缺 `DEEPSEEK_API_KEY`）。 |
