# guarantee-ai-admin · 智能电子保函运营管理平台

第一阶段：**传统电子保函管理后台 + AI 基础能力**。
模块化单体（Modular Monolith），一个 Spring 上下文聚合 7 个 Maven 模块，不引入微服务。

第一阶段（工程骨架 → 真实业务数据 → AI Chat → Tool Calling）与**第三 / 四 / 五阶段的 AI 能力**均已交付：

- **第三阶段 · RAG → 业务知识**：18 条业务知识真源（Markdown）+ 幂等导入 + `queryBusinessKnowledge` 检索工具 + 服务端「知识来源」行（模型自写的会被剥离）
- **第四阶段 · AI 配置化 + 确认 + 审计**：模型/提示词/能力开关可在「系统管理 → AI 配置」页调整（**不重新打包**，改动经 DB 配置快照在下一个请求生效）；提示词版本化（草稿/发布/回滚 + 发布门禁）；写操作提案与审计链路 + 指纹闭环 + 审计归档脚本
- **第五阶段 · MCP → Evaluation → Observability**：业务 MCP（只读、默认关闭、Token 鉴权、限流配额）、自动评测（**37 条**黄金问题；发布门禁 = 确定性集 12/12 + **拒答类 ×3 全通过**）、观测（`/actuator/prometheus` + `ai_turn_metric` + 「AI 运行」页）

**仍未排期**（刻意不做）：多模型路由、Agent Planner、跨会话长期记忆、LangChain4j、MQ、Grafana 大屏、OTel 导出。

---

## 一、技术栈

| 层面 | 选型 | 版本 |
|---|---|---|
| 语言 | Java | 21（Temurin 21.0.12.1） |
| 框架 | Spring Boot | 4.1.1 |
| AI | Spring AI | 2.0.1（`spring-ai-starter-model-openai`） |
| 持久层 | MyBatis Spring Boot Starter | 4.0.0 |
| 数据库 | MySQL | 8.0.29 |
| 缓存 | Redis | 7 / 5.0.14 |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus + ECharts + Pinia + Axios | 见 `frontend/package.json` |
| 模型 | OpenAI-compatible API，默认 DeepSeek（`deepseek-chat`） | — |

> **Spring Boot 4 / Spring AI 2.0 的关键差异**（本项目已按 2.0.1 官方文档实现，未沿用 1.x 写法）：
> 1. OpenAI 聊天模型的属性名是 **`spring.ai.openai.chat.model`**，1.x 的 `spring.ai.openai.chat.options.model` 已失效。
> 2. Spring Boot 4.1 默认使用 **Jackson 3**（`tools.jackson.databind.ObjectMapper`），不是 Jackson 2 的 `com.fasterxml.jackson.databind`。
> 3. 工具注册用 **`.tools(...)`**；`ChatClient.toolCallbacks(...)` 在 2.0.1 中已标记 `@Deprecated(forRemoval = true)`。
> 4. 向模型传 options 时**必须基于模型自身的 options 派生**：`OpenAiChatModel` 会把 `prompt.getOptions()` 强转为
>    `OpenAiChatOptions`，若传入通用的 `ToolCallingChatOptions` 会在运行时抛 `ClassCastException`。
>    正确做法是 `chatModel.getOptions().mutate()` 后再挂载 `toolCallbacks` / `toolContext`。
> 5. 流式输出只支持响应式栈，因此 `guarantee-ai` 依赖 `spring-boot-starter-webflux`（仅提供 Reactor），
>    应用仍以 Servlet(MVC) 方式运行（`spring.main.web-application-type=servlet`）。
>
> 关于工具调用循环，见 §8.2 的说明：本项目**显式**使用框架的 `ToolCallingManager` 驱动循环，
> 而不是依赖 `ToolCallingAdvisor` 的隐式自动装配。

---

## 二、模块结构

```
guarantee-ai-admin/
├── pom.xml                     # 聚合 POM：统一版本、Lombok、surefire/failsafe
├── docker-compose.yml          # MySQL 8 + Redis（+ 可选 app profile）
├── Dockerfile                  # 后端多阶段构建
├── guarantee-common/           # 统一响应、异常、TraceId、分页、当前用户上下文
├── guarantee-auth/             # 登录、JWT、Spring Security、Redis 撤销列表
├── guarantee-system/           # 机构/部门/用户/角色/权限/险种
├── guarantee-order/            # 投标订单、履约订单、订单统计
├── guarantee-analysis/         # 数据概览、区域/险种/机构分析、项目、企业
├── guarantee-ai/               # 会话、SSE 流式聊天、只读 Tool、Tool Call 审计、Prompt、时间语义
├── guarantee-web/              # 启动模块：主类、application.yml、建表 SQL、演示数据初始化
└── frontend/                   # Vue 3 管理后台 + 全局 AI Copilot
```

依赖方向（单向，无环）：

```
web ──> auth ──> system ──> common
 │       │         ↑
 ├──> order ───────┤
 ├──> analysis ──> order, system
 └──> ai ───────> order, system, analysis
```

`common` 不依赖任何业务模块；`CurrentUser` 放在 common，使 `ai` / `analysis` 无需反向依赖 `auth`。

---

## 三、快速开始

### 3.1 环境要求

- JDK 21
- Maven 3.8+
- MySQL 8.0
- Redis 5+
- Node.js 18+ / npm

### 3.2 启动 MySQL 与 Redis

**方式 A：Docker（推荐）**

```bash
docker compose up -d mysql redis
```

MySQL 首次启动会自动执行 `db/schema.sql` 建表（见 `docker-compose.yml` 挂载）。

**方式 B：本机已安装的实例**

```sql
CREATE DATABASE guarantee_ai_admin
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'guarantee'@'%' IDENTIFIED BY 'guarantee@2026';
GRANT ALL PRIVILEGES ON guarantee_ai_admin.* TO 'guarantee'@'%';
FLUSH PRIVILEGES;
```

```bash
redis-server --port 6379
```

> 本仓库开发环境使用便携版 MySQL 8.0.29，因 3306 已被本机 MySQL 5.7 占用，故监听 **3307**。
> 端口/账号全部可通过环境变量覆盖（`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD`），默认值见 `application.yml`。

**本机便携版的启动方式**

便携版 MySQL 与 Redis 都是**普通进程，不会随系统自启**（也没有注册成 Windows 服务，
注册服务需要管理员权限）。机器重启、或终端关闭后它们就没了，需要手动拉起：

```powershell
# 一键启动 MySQL(3307) + Redis(6379)
pwsh -File scripts/start-local-env.ps1

# 连后端一起启动
pwsh -File scripts/start-local-env.ps1 -WithBackend

# 停止
pwsh -File scripts/start-local-env.ps1 -Stop
```

**连接信息（DataGrip / IDEA 数据库工具 / 客户端）**

| 项 | 值 |
|---|---|
| Host | `127.0.0.1`（或 `localhost`） |
| Port | `3307` |
| Database | `guarantee_ai_admin` |
| User / Password | `guarantee` / `guarantee@2026` |
| root 密码 | 空 |

> ✅ MySQL 同时绑定 `127.0.0.1` 与 `::1`（`bind-address=127.0.0.1,::1`）。
> 因为 Windows 上 `localhost` 会**优先解析到 IPv6 的 `::1`**，若只绑 IPv4，
> JDBC 用 `localhost` 就会直接连不上。两个回环都绑上即可，同时**不会暴露到局域网**。
>
> 想确认某个地址通不通：
> ```powershell
> Test-NetConnection -ComputerName ::1 -Port 3307 -InformationLevel Quiet
> ```

### 3.3 启动后端

```bash
# 全量构建（跳过测试）
mvn clean install -DskipTests

# 启动
java -jar guarantee-web/target/guarantee-ai-admin.jar
```

或直接：`mvn -pl guarantee-web -am spring-boot:run`

> ⚠️ **用 IDEA 运行 / 调试时注意**：IDEA 是从 `target/classes` 直接运行源码的，
> 所以 `application.yml` 的改动**不需要重新打包**就生效；
> 但反过来，**只要 IDEA 的运行实例还开着，就不要执行 `mvn clean`** ——
> `clean` 会删掉它正在使用的 `target/classes`，运行中的进程虽然不会立刻崩，
> 但后续懒加载的类会抛 `NoClassDefFoundError`。需要重建时先停掉 IDEA 里的运行实例。

启动时会自动：
1. 执行 `guarantee-web/src/main/resources/db/schema.sql`（全部 `CREATE TABLE IF NOT EXISTS`，可重复执行）；
2. 若 `sys_user` 为空，则由 `DataInitializer` 生成演示数据（**首次约 30–60 秒**）。

后端地址：<http://localhost:8081>，健康检查：`GET /actuator/health`。

### 3.4 配置模型 Key

```bash
# Linux / macOS
export DEEPSEEK_API_KEY=sk-xxxxxxxx

# Windows PowerShell
$env:DEEPSEEK_API_KEY="sk-xxxxxxxx"
```

未配置 Key 时应用**仍可正常启动**（占位 Key），浏览后台一切正常；只有调用 AI 对话时会返回明确提示：

> AI 模型调用失败：API Key 未配置或无效（请设置环境变量 DEEPSEEK_API_KEY 后重启服务）

可覆盖的模型配置：`DEEPSEEK_BASE_URL`（默认 `https://api.deepseek.com`）、`DEEPSEEK_MODEL`（默认 `deepseek-chat`）。

### 3.5 启动前端

有**两种**方式，可任选其一（也可以并存）。

**方式 A：直接访问后端端口（推荐，最省事）**

后端会自动挂载前端构建产物，构建一次后直接访问**后端端口**即可：

```bash
cd frontend
npm install
npm run build          # 产出 frontend/dist
```

然后浏览器打开 <http://localhost:8081/>（改过端口就用你自己的端口，由 `BACKEND_PORT` 对齐）。
前端使用 hash 路由，所以 `http://localhost:8081/#/dashboard` 这类深链接也由后端同一个地址承载。

> ⚠️ `frontend/dist` 不存在时首页返回 404（启动日志会给出提示），后端本身仍正常工作。

**方式 B：Vite 开发服务器（带 HMR，改前端代码即时生效）**

```bash
cd frontend
npm install
npm run dev            # http://localhost:5273
```

访问 <http://localhost:5273>。

> ⚠️ **为什么不是 Vite 默认的 5173**：部分 Windows 机器（启用 Hyper-V / WSL2 / Docker Desktop 后）
> 会保留动态端口段 5121–5220，5173 落在其中，绑定会直接失败并报
> `EACCES: permission denied`。本仓库改用 **5273**（不在任何保留段内）。
> 查看本机保留段：`netsh int ipv4 show excludedportrange protocol=tcp`
>
> ⚠️ **后端端口通过环境变量对齐**：代理目标默认 `http://localhost:8081`，
> 由 `vite.config.ts` 里的 `BACKEND_PORT` 决定。后端换端口时无需改代码：
> ```powershell
> $env:BACKEND_PORT=8082; npm run dev
> ```

> 📱 **局域网访问**：开发服务器默认监听 `0.0.0.0`，同一局域网的手机或同事电脑
> 可用本机 IPv4 访问，例如 `http://192.168.3.86:5273/`
> （Vite 启动时会打印 `➜ Network:` 地址，直接用它即可）。
> 前端接口走相对路径 `/api`，经 Vite 代理转发，因此**从别的设备访问也不会跨域**。
>
> - 只想本机访问（更安全）：`$env:DEV_HOST='127.0.0.1'; npm run dev`
> - 若开启了 Windows 防火墙且连不上，需放行该端口入站：
>   ```powershell
>   New-NetFirewallRule -DisplayName "Vite Dev 5273" -Direction Inbound `
>     -Protocol TCP -LocalPort 5273 -Action Allow
>   ```
> - 注意 Vite dev server 是**开发用**的，不带认证、可读取源码，仅在可信网络内开放。

**两种方式的区别**

| | 方式 A（后端承载） | 方式 B（Vite dev） |
|---|---|---|
| 访问地址 | 后端端口，如 `http://localhost:8081` | `http://localhost:5273` |
| 前端改动 | 需重新 `npm run build` | 热更新，即时生效 |
| 跨域 | 同源，无跨域 | 由 Vite 代理转发 |
| 适合 | 演示、验收、单端口部署 | 前端开发 |

### 3.6 打不开页面时的排查顺序

1. **拼写**：是 `localhost`，不是 `loclhost` / `localhos`（域名解析失败会直接"无法访问"）。
2. **端口**：确认后端实际监听的端口 —— `Get-NetTCPConnection -State Listen | Where-Object LocalPort -in 8080,8081`。
   注意 `application.yml` 的 `server.port` 只影响后端；方式 B 的前端在 5273。
3. **后端是否活着**：`http://localhost:<port>/actuator/health` 应返回 `{"status":"UP"}`。
   该地址不需要登录，最适合判断"服务起来了没"。
4. **是不是只打开了 API**：业务接口都在 `/api/**`，直接访问
   `http://localhost:8081/api/orders/tender` 只会得到 401/JSON —— 这是正常的，它不是一个网页。
   想看到界面，请按 §3.5 的方式 A（后端承载 dist）或方式 B（Vite 5273）。
5. **前端能开但没数据**：方式 B 下确认 Vite 的代理目标与后端端口一致（默认 8081，见 §3.5）。
6. **`EACCES: permission denied` 绑不上端口**：该端口落在 Windows 保留段里了。
   用 `netsh int ipv4 show excludedportrange protocol=tcp` 查看保留段，换一个不在其中的端口。
   本仓库前端已避开 5173，改用 5273。

---

## 四、测试账号

| 账号 | 密码 | 角色 | 说明 |
|---|---|---|---|
| `admin` | `Admin@123` | ADMIN | 全部权限 |
| `operator` | `Operator@123` | OPERATOR | 订单与基础配置运营 |
| `analyst` | `Analyst@123` | ANALYST | 业务分析 + AI 助手 |

另有 297 个演示用户 `user0004` ~ `user0300`，统一密码 `User@123`。

---

## 五、数据库

26 张表，DDL 见 **`guarantee-web/src/main/resources/db/schema.sql`**。

| 域 | 表 |
|---|---|
| 系统配置 | `sys_org`、`sys_department`、`sys_user`、`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission` |
| 险种 | `insurance_type` |
| 业务分析 | `enterprise`、`project` |
| 订单 | `tender_order`、`performance_order` |
| AI | `ai_conversation`、`ai_message`、`ai_tool_call`、`ai_audit_log` |

订单表在 `apply_date`、`region_code`、`org_id`、`insurance_type_id` 以及 `(org_id, apply_date)` 上建了索引，保证 15 万行下的分析查询性能。

---

## 六、初始化数据逻辑

`guarantee-web/.../init/DataInitializer.java`，**固定随机种子 `20260920`**，生成顺序与随机数消耗顺序完全固定，因此数据可复现。

数据量：20 机构 / 80 部门 / 300 用户 / 6 险种 / 3000 企业 / 5000 项目 / **100000 投标订单 + 50000 履约订单**。

### 人为注入的业务规律（AI 分析可验证）

数据**不是**纯随机，而是显式按业务规则加权采样（`DaySampler` 为每个机构预计算按天累积权重，订单日期用二分查找采样）：

1. **区域分布**（合计 100）：浙江 **35%** > 江苏 **24%** > 广东 14% > 山东 9% > 四川 6% > 湖北 5% > 北京 4% > 上海 3%。
2. **机构季节性**（2026 Q3）：20 个机构各带系数 `ORG_Q3_FACTOR` —— 前 7 个显著上调（1.45 → 1.08）、
   中间 7 个接近持平、后 6 个明显下调（0.82 → 0.60）。
   实测 Q2→Q3 环比：14 个机构增长（高的 +38%），5 个机构逆季节性下降（低的 −21%）。
   → 可用「机构分析 + 2026Q3 过滤」直接验证「哪些机构在增长、哪些在下降」。
3. **月份季节性** `MONTH_FACTOR`：2 月（春节）最低 0.60，Q3 为全年高点（7/8/9 月 1.25/1.30/1.35）。
4. **险种结构因区域而异**：浙江偏向标准/电子投标保函；江苏履约保函占比更高；其他区域居中。
5. **数据时间范围**：2025-01-01 ~ 2026-09-30，使「今年/去年/本季度/上季度」都有数据。
6. 金额取平方分布（大额项目更少，更贴近真实）；投标保函金额为项目额的 4%–14%，履约保函 8%–25%；保费 = 保额 × 费率。

幂等：`sys_user` 非空时直接跳过，不会重复灌数据。

---

## 七、后端 API

统一响应结构：`{ "code": 0, "message": "成功", "data": ..., "traceId": "..." }`，`code === 0` 为成功。
分页结构：`{ pageNum, pageSize, total, list }`。

### 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录，返回 JWT 与用户信息 |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/auth/logout` | 登出（Redis 撤销当前令牌） |

### 订单

| 方法 | 路径 |
|---|---|
| GET | `/api/orders/tender` |
| GET | `/api/orders/tender/{id}` |
| GET | `/api/orders/performance` |
| GET | `/api/orders/performance/{id}` |

过滤参数：`orderNo`、`regionCode`、`orgId`、`insuranceTypeId`、`status`、`startDate`、`endDate`、`projectId`、`enterpriseId`。

### 业务分析

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

### 系统配置

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

### AI

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/chat` | **SSE 流式**对话 |
| GET | `/api/ai/conversations` | 会话列表 |
| GET | `/api/ai/conversations/{id}` | 会话详情（含全部消息） |
| GET | `/api/ai/tool-calls/{conversationId}` | 该会话的 Tool Call 记录 |

---

## 八、AI 第一阶段

### 8.1 Tool：`queryOrderSummary`

定义在 `guarantee-ai/.../tool/OrderSummaryTool.java`，使用 Spring AI 2.0 的 `@Tool` / `@ToolParam`。

入参：`orderType`、`startDate`、`endDate`、`regionCode`、`orgId`（日期必须是 `yyyy-MM-dd` 明确格式）。
出参：`orderCount`、`guaranteeAmount`、`premiumAmount`、`enterpriseCount`、`projectCount`，并回显查询条件与 `dataSource` 便于核对口径。

**分层铁律**：

```
Tool -> Service -> Mapper -> DB
```

`OrderSummaryTool` 只注入 `OrderStatisticsService`，**不注入任何 Mapper，不生成 SQL**。

另附一个只读工具 `getCurrentDate`，用于让模型在换算相对时间前拿到可信基准日期。

### 8.2 工具调用循环与 Tool Call 记录

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

### 8.3 Prompt

`guarantee-ai/src/main/resources/prompts/business-assistant.st`，包含规范要求的 7 项：业务身份、数据必须来自 Tool、不允许编造数据、时间语义必须转换为明确日期、无法证明的只能作为推测、Tool 失败必须明确说明、输出关键数据与数据来源。

每轮运行时还会追加「当前系统日期」与「系统预解析的时间范围」。

### 8.4 时间语义：`TimeSemanticParser`

`guarantee-ai/.../time/TimeSemanticParser.java`，统一转换为：

```java
record TimeRange(LocalDate startDate, LocalDate endDate, String description)
```

支持：今天、昨天、前天、本月、上月、本季度、上季度、今年、去年、Q1–Q4、`2026年第三季度`、`2026年7月`、`2026年`、`最近N天/周/月`。

流程是**双保险**：服务端先解析出明确日期并注入 System Prompt，模型再据此调用 Tool，避免模型自己算错季度边界。

### 8.5 SSE 事件协议

`POST /api/ai/chat` 请求体 `{ "conversationId": number|null, "message": string }`，响应 `text/event-stream`：

| event | data |
|---|---|
| `meta` | `{"conversationId":1,"conversationNo":"CV...","title":"..."}` |
| `delta` | `{"content":"文本片段"}` |
| `tool_call` | `{"id":1,"toolName":"queryOrderSummary","toolType":"READ","arguments":"{...}","result":"{...}","status":"SUCCESS","durationMs":12}` |
| `done` | `{"conversationId":1,"messageId":9}` |
| `error` | `{"message":"..."}` |

> 因为是 POST，浏览器 `EventSource` 不适用；前端用 `fetch` + `ReadableStream` 手工解析 SSE 帧（`frontend/src/utils/sse.ts`）。

---

## 八·五、AI 第三 / 四 / 五阶段能力（2026-09-30 交付）

### 8.5.1 业务知识检索与溯源（第三阶段）

- 真源：`guarantee-ai/src/main/resources/knowledge/**`（**18 条**，Markdown + YAML front-matter，编号 `KB-<DOMAIN>-NNNN`）
- 启动时幂等导入 `ai_knowledge_item`（内容变化 → `version+1` 并写 `ai_knowledge_import_log`；真源消失 → `RETIRED`，不物理删除）
- 工具 `queryBusinessKnowledge`（只读、登录可见；条目级 `permission_code` 在**服务端**裁剪）；
  回答末尾的「知识来源：KB-…《…》vN」由**服务端**按本轮真实检索结果追加，模型自写的会被剥离
- 迁移脚本：`guarantee-web/src/main/resources/db/migration/V7__ai_knowledge.sql`

### 8.5.2 AI 配置化 + 人工确认 + 审计（第四阶段）

- 页面：`系统管理 → AI 配置`（模型 / 提示词 / 能力开关 / 变更历史四页签）；接口 `/api/ai/config*`
- 配置真源是 `AiConfigCatalog`（**20 项**）；运行期快照在 `ai_config_item`，**改配置不重新打包、下一个请求生效**
- 提示词版本化：`ai_prompt_version`（DRAFT → PUBLISHED → ARCHIVED）+ 发布门禁
  （`node scripts/ai-golden-questions.mjs --suite=deterministic` 退出码 0 才允许发布；缺 Key 的真机集如实标注"未跑"）
- 审计：`CONFIG_UPDATE` / `AI_CONFIG`（before → after；密钥类只记 `<changed>`）；提案指纹闭环；
  归档执行体 `scripts/archive-operation-audit.ps1`（默认 DRY-RUN）
- **本期未接线**：`model.max-tokens` / `model.timeout` / `model.max-retries`（页面标注、改了不生效）

### 8.5.3 MCP / Evaluation / Observability（第五阶段）

- **业务 MCP**：网关 `tools/business-mcp`（Node + stdio，**15 个只读工具**）+
  平台侧 `GET /api/ai/mcp/tools`、`POST /api/ai/mcp/tools/{name}`、`/api/system/mcp-tokens`（权限 `ai:mcp:read` / `ai:mcp:manage`）；
  **默认关闭**（`guarantee.ai.mcp.enabled=false`，yml 级、需重启）；接入说明见 `docs/MCP-外部接入.md`
- **评测**：`docs/TEST-助手黄金问题集.md`（**37 条**，GQ-01~37）+ `scripts/ai-golden-questions.mjs`
  （`--suite=all|deterministic|live|refusal`、`--repeat=N`、`--baseline=<file>`）；确定性集由 `EvaluationDeterministicIT` 承载并纳入 `mvn verify`；
  拒答类（`--suite=refusal`）默认 **3 轮，N 次全通过才算通过**（方差敏感）
- **观测**：`/actuator/prometheus`（免登录，仅内网/白名单）+ `ai_turn_metric`（每轮问答一行，含 trace_id）+
  `系统管理 → AI 运行` 页
- **单一事实源**：`node scripts/single-source-of-truth.mjs`（测试项数 / 评测条数 / 指标清单 / MCP 工具清单，文档不再手写数字）

---

## 九、第一条 AI Demo 操作说明

> 目标：**登录后台 → 打开投标订单 → 打开 AI Copilot → 输入问题 → AI 调用真实业务 Tool → 返回真实统计结果 → 前端流式展示。**

**前置**：配置好 `DEEPSEEK_API_KEY`，后端与前端均已启动。

1. 浏览器打开 <http://localhost:5273>。
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

---

## 十、测试

```bash
# 单元测试（不需要数据库）
mvn test

# 集成测试（需要 MySQL + Redis 已启动，且已初始化演示数据）
mvn verify
```

- `TimeSemanticParserTest`（`guarantee-ai`）：13 个用例覆盖全部时间语义，基准日固定为 2026-09-21，断言与运行时间无关。
- `AiToolChainIT`（`guarantee-web`）：用确定性 **Stub ChatModel** 替换真实模型，**无需 API Key** 即可验证完整链路——
  模型发起 Tool Call → `ToolCallingAdvisor` 执行 → `OrderSummaryTool` → `OrderStatisticsService` → `OrderStatisticsMapper` → MySQL，
  并断言 **Tool 返回的 5 个指标与直接调用 Service 的结果逐一相等**，同时校验 `ai_message` / `ai_tool_call` 落库（含 `duration_ms`、`message_id` 关联）与 SSE 事件序列（`meta`/`tool_call`/`delta`/`done`）。

---

## 十一、安全原则

第一阶段只有查询能力，但架构上已提前区分读写：

- `ToolKind.READ` / `ToolKind.WRITE` 枚举，`AiToolRegistry` 当前**只注册 READ 工具**。
- 禁止 AI 直接访问数据库；禁止 AI 生成任意 SQL；禁止 AI 直接操作 Mapper。
- 所有 Tool 只能调用业务 Service。

后续所有 WRITE Tool 必须走：

```
AI Plan -> Permission Check -> Preview -> User Confirmation -> Execute -> Audit
```

`ai_audit_log` 已预留并已在写入（`CHAT` / `TOOL_CALL` / `ERROR`，带 `trace_id`）。

---

## 十二、可观测性

- 每个请求生成/透传 **TraceId**：写入 MDC、响应头 `X-Trace-Id`、统一响应体的 `traceId` 字段、`ai_audit_log.trace_id`。
- 日志格式包含 `traceId`：`%d ... [%thread] [%X{traceId:-}] %logger - %msg`。
- 全局异常处理把校验失败、业务异常、唯一约束冲突、未预期异常统一收敛为 `Result`，不泄漏堆栈。
- 访问不存在的路径返回 404（`ResultCode.NOT_FOUND`），不会被兜底分支误报成 500。

---

## 十二·五、协作规范

分支模型与提交信息规范见 **[docs/GIT_CONVENTION.md](docs/GIT_CONVENTION.md)**：

- **集成分支**：`main`（主分支，随时可发布）、`develop`（开发分支）、`release_v<版本>`（发布分支）
- **功能分支**：`feature/<模块>-<简述>`
- **热修复分支**：`hotfix/<简述>`
- **提交信息**：Conventional Commits，主题用中文，如 `feat(order): 新增投标订单导出接口`

克隆后执行一次即可启用内置的提交校验钩子与提交模板：

```bash
pwsh -File scripts/setup-git.ps1     # Windows
sh scripts/setup-git.sh              # Linux / macOS / Git Bash
```

---

## 十三、已知偏差与说明

| 项 | 说明 |
|---|---|
| MySQL 端口 | 开发机 3306 被已有 MySQL 5.7 占用，本仓库本地实例跑在 **3307**；可用 `DB_PORT` 覆盖，`docker-compose.yml` 用标准 3306。 |
| Docker | 开发机未安装 Docker，本项目的 `docker-compose.yml` / `Dockerfile` 未在容器中实测；本地验证使用便携版 MySQL 8.0.29 + Redis。 |
| Jackson | Spring Boot 4.1 默认 Jackson 3（`tools.jackson.*`），代码已按此实现。 |
| AI 流式 | `guarantee-ai` 引入 `spring-boot-starter-webflux` 仅为提供 Reactor；应用类型显式固定为 Servlet（`spring.main.web-application-type=servlet`）。 |
| 工具循环 | 未使用 `ToolCallingAdvisor` 的隐式自动装配（实测在本项目装配下不会进入顾问链），改为显式调用 `ToolCallingManager` 驱动，详见 §8.2。 |
| SSE 与 Spring Security | 必须放行 `DispatcherType.ASYNC`，否则异步派发时会因上下文已清理而抛 `Access Denied` 并截断事件流（已在 `SecurityConfig` 中处理）。 |
| 前端 | 使用 hash 路由（`createWebHashHistory`），避免静态部署需要 history fallback。 |
| 权限 | 登录态与权限编码已下发，前端按菜单展示；第一阶段未在接口上开启 `@PreAuthorize` 细粒度拦截。 |
| 真实模型验证 | `DEEPSEEK_API_KEY` 就位后**已做端到端实测**。当前发布口径（T7 收口轮）：**全量 37 题 → 36 PASS / 0 FAIL / 1 未跑**（未跑的 GQ-25 关知识层，已在独立实例单独 PASS）；**拒答类 8 题 × 3 轮 = 24/24 全通过**（`--suite=refusal`）；`forbiddenTermViolations = 0`、口径正确率与引用完整率均 1.0。历史快照（`33 题 32/32`、`35 题 31/3/1`、`35 题 34/0/1`）均保留在 `reports/archive/` 作为对照；其中 `31/3/1` 那 3 处已定性（1 项是**评测脚本断言假失败**、2 项是**已修的模型行为缺陷**），全程**未放宽断言**。报告见 `reports/README.md`。工具链路由仍由 `AiToolChainIT`（Stub 模型）确定性验证；未配 Key 时的行为也已实测为「明确报错、不编造数据」。 |
| 前端运行验证 | 前端 `npm run build` 通过（vue-tsc 类型检查 + 打包）；SSE 客户端已按后端实测事件协议对齐。但本次开发会话的沙箱禁止 Node 监听端口（`listen EACCES`），**未能启动 Vite dev server 做浏览器实测**；在你自己的终端里 `npm run dev` 可正常启动。 |
| 本地构建（**多人/多 Agent 共库时必读**） | 改代码后跑 Maven **一律用** `pwsh scripts/mvn-locked.ps1 <mvn 参数…>`：它先取 `.agent/locks/maven.lock`（>20 分钟视为陈旧可抢占），跑完在 `finally` 释放。**未持锁的并发构建会共用一个 MySQL 并互相删 IT 夹具**（本轮真实发生过 `LogicalDeleteWebIT` 因两构建并发而红、隔离重跑全绿）。协议、实测与逃生舱见 [本地构建与锁.md](docs/本地构建与锁.md)。**CI 内不需要**（runner 环境隔离）。 |

---

## 知识库（跨 Agent 项目上下文）

本项目的长期上下文（状态、任务、决策、Wiki、日志）存放在**外部 Obsidian Vault**，
它就是知识的**唯一真源**：**仓库内不再保留知识源副本**（仓库侧 `knowledge/` 已于 2026-09-30 退役并删除）。

- Vault 路径：以 `.agent/vault.local.yaml` 的 `vault.path` 为准（本机配置，不入库）。
- 读写入口：经 knowledge-os MCP；本项目提供 CLI 入口
  `node tools/knowledge-os-mcp/scripts/kos.mjs`（`health` / `resolve` / `state` / `call` / `script`）。
- 工作方法：`.agents/skills/knowledge-continuity`（何时读、何时写、如何交接）。
- 工具链说明：`tools/knowledge-os-mcp/scripts/README.md`；
  冷启动连续性验证：`node tools/knowledge-os-mcp/scripts/cold-start-check.mjs`。

> 这套能力是**作者本机**的 Agent 协作设施，对「跑起本项目」零贡献：
> 相关配置（`.agent/`、`.agents/`、`.mcp.json`、`adapters/`）已在 `.gitignore` 中排除。
