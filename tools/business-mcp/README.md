# business-mcp —— 业务 MCP 网关（stdio、只读）

> 归属需求：`docs/REQ-第五阶段-MCP评测与可观测.md` §5.1.1（REQ-MCP-01）/ §5.1.5（REQ-MCP-05）
> 外部接入说明（给使用者看）：[`docs/MCP-外部接入.md`](../../docs/MCP-外部接入.md)
> 任务：T5-00（本目录）/ T5-03（平台侧 `POST/GET /api/ai/mcp/tools` 实现）

## 这是什么

一个**独立进程的 MCP Server**（stdio transport），把平台受控的**只读**取数能力暴露给外部 Agent
（IDE、其它系统、评测工具）。它是"薄网关"：

```
MCP 客户端 ↔ stdio(JSON-RPC) ↔ 本网关 ↔ HTTP ↔ 平台 /api/ai/mcp/tools(/{name})
```

网关自己**不做权限判断、不读数据库、不碰业务数据**：权限裁剪（`AiToolRegistry`）与数据范围
（`DataScopeService`）全部由平台侧完成，与页面/助手同源（红线 §2.3-1）。

## 与 `tools/knowledge-os-mcp` 的区别（别接错）

| | `tools/knowledge-os-mcp` | `tools/business-mcp`（本目录） |
|---|---|---|
| 用途 | **开发流程**：读写与本仓库对应的 Obsidian Vault | **业务能力**：把平台只读取数暴露给外部 Agent |
| 数据 | Vault 里的 markdown | 平台业务库（经平台受控接口，权限裁剪 + 数据范围） |
| 写能力 | 有（语义化写入 Vault） | **没有**（只读；写操作只能在平台页面内确认执行） |
| 凭据 | Vault 身份三层校验 | **MCP Token**（`Authorization: Bearer`） |

## 目录

```
src/config.ts    环境变量配置（默认关闭 / Token 必填 / fail-closed）
src/catalog.ts   只读工具白名单（12 个，唯一真源；写工具永不出现）
src/backend.ts   平台冻结契约的 HTTP 客户端
src/server.ts    装配 MCP Server（只声明 tools 能力；schema 来自后端）
src/index.ts     stdio 入口 + 启动审计日志（不打印 Token）
src/errors.ts    失败归一成外部 Agent 能读懂的文本
tests/           真实子进程 JSON-RPC 协议测试（对着 stub 后端）
```

## 运行

```powershell
npm install          # 依赖版本与 tools/knowledge-os-mcp 完全一致（SDK 1.30.1 / zod 4.6.5 / vitest 5.0.2）
npm run build        # tsc → dist/
npm test             # 先 build，再跑协议测试（不需要真平台/数据库/Key）
```

启动网关（**默认关闭**，不设开关会拒绝启动）：

```powershell
$env:GUARANTEE_AI_MCP_ENABLED='true'
$env:GUARANTEE_MCP_BASE_URL='http://127.0.0.1:8081'
$env:GUARANTEE_MCP_TOKEN='<平台签发的 MCP Token>'
node dist/index.js
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `GUARANTEE_AI_MCP_ENABLED` | `false` | 总开关。false 时网关打印可读原因并**退出码 1**（AC-MCP-06） |
| `GUARANTEE_MCP_BASE_URL` | `http://127.0.0.1:8081` | 平台基址（别名：`GUARANTEE_MCP_GATEWAY_URL`） |
| `GUARANTEE_MCP_TOKEN` | 空 | **必填**；为空即拒绝启动（fail-closed） |
| `GUARANTEE_MCP_TIMEOUT_MS` | `15000` | 单次平台调用超时 |
| `GUARANTEE_MCP_TOOL_PREFIX` | `mcp__guarantee__` | 暴露给客户端的工具名前缀；设为空串则直接用后端工具名 |
| `GUARANTEE_MCP_TOOL_SOURCE` | `auto` | `auto`=后端清单（不可达时降级静态）/ `backend`=只用后端（不可达即报错）/ `static`=只用静态清单 |
| `GUARANTEE_MCP_TOOL_CACHE_MS` | `30000` | 清单缓存时长 |

## 安全边界（刻意不做的事）

1. **只读白名单**：只有 `src/catalog.ts` 里的 12 个只读工具可被调用；写工具（`propose*`、
   `create/update/delete/…`）即使被后端错误地列出来，网关也会丢弃并写 stderr 告警。
2. **没有万能 HTTP 透传**：未知名字在本地就被拒绝，连一次 HTTP 请求都不会发出。
3. **不读数据库**：网关没有任何 DB 依赖。
4. **fail-closed**：无 Token 不启动；401/403 不回退静态清单（避免把"Token 被撤销"伪装成正常）。
5. **不记录正文/参数值**：日志只保留工具名、状态、耗时与截断标记；启动审计只打印 Token 指纹与长度。
6. **返回值是数据不是指令**：`instructions` 里显式声明（RK-MCP-02 间接提示注入防护的传输层提示）。

## 平台侧冻结契约（T5-03 实现）

```
GET  /api/ai/mcp/tools
     → 200 { "data": [ { "name": "queryOrderSummary", "description": "...", "inputSchema": {...} } ] }
POST /api/ai/mcp/tools/{name}      # body = 工具参数 JSON
     → 200 { "data": { "result": <任意>, "dataSource": "口径文本"|null, "truncated": false } }
     非 2xx → { "message": "可读原因" }（网关会翻译成 UNAUTHORIZED/FORBIDDEN/RATE_LIMITED/… 文案）
鉴权：Authorization: Bearer <MCP-Token>
```

清单已按调用方权限裁剪（无 `ai:mcp:read` 或缺少某域 `:view` 时对应工具不出现）；
网关**不会**补回缺失的工具 —— 补回等于绕过裁剪。

## 测试

`tests/stdio.test.ts` 起真实子进程（`dist/index.js`）+ 真实 stdin/stdout JSON-RPC 帧，
后端是 `tests/helpers/stubBackend.ts` 的假平台，覆盖：握手、清单一致性、说明/schema 来源、
正常转发与鉴权头、400/500/401 可读错误、Token 缺失、默认关闭、写工具丢弃、未知工具不转发、
平台不可达降级、truncated、超时、stdout 纯净性与不泄漏 Token。

工具数/评测条数的"单一事实源"由仓库根脚本产出：
`node scripts/single-source-of-truth.mjs`（会统计本目录 `src/catalog.ts` 的工具清单，
并与 Java 侧 `@Tool` / `AiToolRegistry` 比对，见 REQ-MCP-12）。
