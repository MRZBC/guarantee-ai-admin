# guarantee-ai-admin · 智能电子保函运营管理平台

模块化单体（Modular Monolith）：一个 Spring 上下文聚合 7 个 Maven 模块 + 1 个 Vue 3 前端，不引入微服务。

已交付：**工程骨架 → 真实业务数据 → AI Chat → Tool Calling**（第一阶段），以及

- **第三阶段 · RAG → 业务知识**：18 条知识真源 + 幂等导入 + `queryBusinessKnowledge` + 服务端「知识来源」行
- **第四阶段 · AI 配置化 + 确认 + 审计**：模型/提示词/能力开关可在页面调整（不重新打包）；提示词版本化 + 发布门禁；写操作提案 + 指纹闭环 + 审计归档
- **第五阶段 · MCP → Evaluation → Observability**：业务 MCP（只读、默认关闭、Token + 限流配额）、自动评测（**37 条**；发布门禁 = 确定性集 12/12 + 拒答类 ×3 全通过）、`/actuator/prometheus` + `ai_turn_metric`

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

> ⚠️ Spring Boot 4.1 / Spring AI 2.0.1 与 1.x 的写法差异有 5 条**实测坑**
> （属性名、Jackson 3、`.tools()`、options 强转 `ClassCastException`、流式只支持响应式栈）——
> 动手改 AI 代码前先看 [AI-能力与演示.md](docs/AI-能力与演示.md) §0。

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

依赖方向（单向、无环）：

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

```bash
# 1) 起依赖：Docker（推荐）；本机便携版则 pwsh -File scripts/start-local-env.ps1
docker compose up -d mysql redis

# 2) 构建并启动后端（自动建表；sys_user 为空时生成演示数据，约 6s）
mvn clean install -DskipTests
java -jar guarantee-web/target/guarantee-ai-admin.jar

# 3) 前端：开发用 Vite（5273）；或 npm run build 后直接访问后端端口
cd frontend && npm install && npm run dev
```

| 入口 | 地址 |
|---|---|
| 后端 + 健康检查 | <http://localhost:8081> · `GET /actuator/health` |
| 前端（Vite dev） | <http://localhost:5273>（`npm run build` 后可直接用后端端口） |
| MySQL | `127.0.0.1:3307`（本机便携版；Docker 用 3306）· 库 `guarantee_ai_admin` |
| Redis | `127.0.0.1:6379` |

AI 对话需要模型 Key；**不配也能启动**，只有对话会明确报错、不编造数据：

```powershell
$env:DEEPSEEK_API_KEY="sk-xxxxxxxx"     # Linux/macOS: export DEEPSEEK_API_KEY=...
```

> 环境安装细节（便携版 MySQL/Redis、连接信息、IDEA 运行注意）、前端两种方式与端口坑
> （含 Windows 保留段 / 动态端口范围会把 5273 抢走的实测）、**打不开页面的 6 步排查**、
> 演示数据口径与"人为注入的业务规律"：见
> **[docs/本地开发环境与演示数据.md](docs/本地开发环境与演示数据.md)**。

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

26 张表，DDL 见 **`guarantee-web/src/main/resources/db/schema.sql`**（空库自建；存量库靠
`db/migration/V1..V10` 手工幂等脚本，**两者必须同步改**）。

| 域 | 表 |
|---|---|
| 系统配置 | `sys_org`、`sys_department`、`sys_user`、`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission` |
| 险种 | `insurance_type` |
| 业务分析 | `enterprise`、`project` |
| 订单 | `tender_order`、`performance_order` |
| AI | `ai_conversation`、`ai_message`、`ai_tool_call`、`ai_audit_log` |

订单表在 `apply_date`、`region_code`、`org_id`、`insurance_type_id` 以及 `(org_id, apply_date)` 上建了索引，
保证 15 万行下的分析查询性能。表结构以外的约定（逻辑删除、审计分区等）见
[DEC-逻辑删除设计方案.md](docs/DEC-逻辑删除设计方案.md)、[IMPL-审计归档-分区命名.md](docs/IMPL-审计归档-分区命名.md)。

---

## 六、常用命令

```bash
# 构建 / 启动
mvn clean install -DskipTests
java -jar guarantee-web/target/guarantee-ai-admin.jar

# 测试：单测不需要 DB；集成测试需要 MySQL + Redis 且已初始化演示数据
mvn test
mvn verify

# 前端
cd frontend && npm install && npm run dev      # 5273
cd frontend && npm run build                   # 产出 dist，后端直接承载

# AI 评测（确定性集 12 条 / 真机集 / 拒答类 ×3）
node scripts/ai-golden-questions.mjs --suite=deterministic
pwsh -File scripts/run-live-eval.ps1 -Port 8092

# 文档数字的单一事实源（测试项数 / 评测条数 / MCP 工具清单）
node scripts/single-source-of-truth.mjs --check
```

> ⚠️ **本机改代码后跑 Maven 一律用 `pwsh -File scripts/mvn-locked.ps1 …`**：
> 本机所有阶段共用同一个 MySQL(3307)/Redis，并发构建会互删 IT 夹具（真实踩过）。
> 协议与逃生舱见 [本地构建与锁.md](docs/本地构建与锁.md)；**CI 内不需要**（runner 环境隔离）。

---

## 七、测试与 CI

- **单测**：`mvn test`（不需要数据库）
- **集成测试**：`mvn verify`（需要 MySQL + Redis + 演示数据；CI 由 workflow 先起一次后端播种）

CI（`.github/workflows/ai-eval.yml`）三道门：**Job1** `mvn -B verify`（先播种演示数据，8 模块全量）、
**Job2** 确定性集 12/12（发布门禁的确定性半边）、**Job3** 真机集 + 拒答类 ×3（需 `DEEPSEEK_API_KEY`，缺 Key 跳过）。
首跑与后续各轮的根因、修复与教训见 **[docs/CI-真机评测.md](docs/CI-真机评测.md)**；
评测数据与运行器见 **[docs/TEST-助手黄金问题集.md](docs/TEST-助手黄金问题集.md)**；
历次报告与工件见 [reports/README.md](reports/README.md)。

---

## 八、已知偏差与说明

| 项 | 说明 |
|---|---|
| MySQL 端口 | 开发机 3306 被已有 MySQL 5.7 占用，本仓库本地实例跑在 **3307**；可用 `DB_PORT` 覆盖，`docker-compose.yml` 用标准 3306。 |
| Docker | 开发机未安装 Docker，`docker-compose.yml` / `Dockerfile` 未在容器中实测；本地验证使用便携版 MySQL 8.0.29 + Redis。 |
| Jackson | Spring Boot 4.1 默认 Jackson 3（`tools.jackson.*`），代码已按此实现。 |
| AI 流式 | `guarantee-ai` 引入 `spring-boot-starter-webflux` 仅为提供 Reactor；应用类型显式固定为 Servlet。 |
| 工具循环 | 未使用 `ToolCallingAdvisor` 的隐式自动装配（实测在本项目装配下不会进入顾问链），改为显式调用 `ToolCallingManager`，详见 [AI-能力与演示.md](docs/AI-能力与演示.md) §2。 |
| SSE 与 Spring Security | 必须放行 `DispatcherType.ASYNC`，否则异步派发时会因上下文已清理而抛 `Access Denied` 并截断事件流（已在 `SecurityConfig` 处理）。 |
| 前端路由 | 使用 hash 路由（`createWebHashHistory`），避免静态部署需要 history fallback。 |
| 接口级鉴权 | 登录态与权限编码已下发、前端按菜单展示；第一阶段未在接口上开启 `@PreAuthorize` 细粒度拦截。 |
| 真实模型验证 | `DEEPSEEK_API_KEY` 就位后**已端到端实测**。发布口径（T7 收口轮）：全量 37 题 **36 PASS / 0 FAIL / 1 未跑**（未跑的 GQ-25 关知识层，已在独立实例单独 PASS）；拒答类 8 题 × 3 轮 **24/24**；`forbiddenTermViolations = 0`。历史快照（`32/32`、`31/3/1`、`34/0/1`）保留在 `reports/archive/` 作对照，全程**未放宽断言**。 |
| 本地构建（共库必读） | 见上方「常用命令」的警示与 [本地构建与锁.md](docs/本地构建与锁.md)。 |

---

## 九、文档

完整索引见 **[docs/README.md](docs/README.md)**（需求 / 决策 / 计划 / 实施 / 验收 / 运维，共 42 篇；含本机运维与 CI 口径）。常用入口：

| 想做什么 | 看哪篇 |
|---|---|
| 跑起来 / 环境排查 / 演示数据 | [docs/本地开发环境与演示数据.md](docs/本地开发环境与演示数据.md) |
| 调接口 | [docs/API-概览.md](docs/API-概览.md) |
| 改 AI（Tool / Prompt / SSE / MCP / 配置 / 评测） | [docs/AI-能力与演示.md](docs/AI-能力与演示.md) |
| 看 CI 与真机评测 | [docs/CI-真机评测.md](docs/CI-真机评测.md) |
| 提交 / 分支规范 | [docs/GIT_CONVENTION.md](docs/GIT_CONVENTION.md) |
| 验收与交付结论 | [docs/交付汇总-AI第三四五阶段.md](docs/交付汇总-AI第三四五阶段.md)、[docs/RELEASE-AI第三四五阶段.md](docs/RELEASE-AI第三四五阶段.md) |

---

## 知识库（跨 Agent 项目上下文）

本项目的长期上下文（状态、任务、决策、Wiki、日志）存放在**外部 Obsidian Vault**，
它就是知识的**唯一真源**：**仓库内不再保留知识源副本**（仓库侧 `knowledge/` 已于 2026-09-30 退役并删除）。

- Vault 路径：以 `.agent/vault.local.yaml` 的 `vault.path` 为准（本机配置，不入库）。
- 读写入口：经 knowledge-os MCP；本项目提供 CLI 入口
  `node tools/knowledge-os-mcp/scripts/kos.mjs`（`health` / `resolve` / `state` / `call` / `script`）。
- 工作方法：`.agents/skills/knowledge-continuity`（何时读、何时写、如何交接）。

> 这套能力是**作者本机**的 Agent 协作设施，对「跑起本项目」零贡献：
> 相关配置（`.agent/`、`.agents/`、`.mcp.json`、`adapters/`）已在 `.gitignore` 中排除。
