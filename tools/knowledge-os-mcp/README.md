# knowledge-os MCP Server

> **Portable Knowledge OS** —— 把项目上下文从聊天历史中抽离出来，
> 形成独立、持久化、跨 Agent 的 Knowledge OS。
>
> 本目录是一个**完全独立的标准 MCP Server**：只实现 Model Context Protocol，
> 不依赖 Claude SDK / Codex SDK / OpenCode SDK / DeepSeek Harness SDK。

---

## 目录

- [系统目标](#系统目标)
- [架构](#架构)
- [安装与构建](#安装与构建)
- [测试](#测试)
- [MCP 启动方式](#mcp-启动方式)
- [Vault 配置](#vault-配置)
- [项目身份（Project ID）](#项目身份project-id)
- [身份校验（Identity）](#身份校验identity)
- [工具列表](#工具列表)
- [Skill](#skill)
- [各 Runtime 接入](#各-runtime-接入)
- [设计取舍](#设计取舍)
- [故障排查](#故障排查)

---

## 系统目标

代码仓库与 Obsidian Vault 是**两个物理独立的目录**，但一一对应：

```text
代码项目    D:/Users/12209/localhostProjects/guarantee-ai-admin
Obsidian    D:/Users/12209/Documents/guarantee-ai-admin-obsidian
```

目标不是让某个 Agent「记住聊天内容」，而是让**任何**支持 Agent Skills 和/或 MCP 的 Agent 都能：

```text
读取当前项目状态 → 找到对应 Obsidian Vault → 查询项目知识与历史决策
→ 继续之前未完成的工作 → 完成工作 → 验证
→ 更新状态 → 记录日志 → 持久化长期知识
→ 让下一个 Agent 直接接手
```

### 职责严格分离

```text
Skill   = 如何使用 Knowledge OS（工作方法）
MCP     = 如何安全访问 Knowledge OS（路径解析、身份校验、原子写、并发保护）
Vault   = 真正的持久化数据（普通 Markdown / YAML）
Runtime = 执行任务的消费者（可替换）
```

**唯一的事实真源始终是 Vault 里的 Markdown。** 本 Server 内部不做任何持久状态缓存。

---

## 架构

```text
Project Repository
        ↓  .agent/project.yaml            project.id / project.name
        ↓  .agent/vault.local.yaml        vault.id / vault.path（本机私有，.agent/ 整体不入库）
   Vault Resolver                         只信任配置，不扫描磁盘
        ↓
 Identity Validator                       project.id == vault.id == VAULT_ID.md.project_id
        ↓
  Vault Repository                       路径守卫 + 原子写 + 并发保护
        ↓
 Domain Operations                       State / Wiki / Decision / Log / Search
        ↓
    MCP Tools                            10 个语义化工具，无任意路径写入
```

**每个写操作的固定顺序**（任一步失败即禁止写入）：

```text
resolve project → resolve vault → validate identity
  → resolve semantic target → validate target path → write → return result
```

详见 [`ARCHITECTURE.md`](ARCHITECTURE.md)。

---

## 安装与构建

要求 **Node.js >= 20**（开发与测试使用 Node 24）。

```bash
cd tools/knowledge-os-mcp
npm ci            # 或 npm install
npm run build     # tsc → dist/index.js
```

其他脚本：

```bash
npm run typecheck   # tsc --noEmit
npm test            # vitest run
npm start           # node dist/index.js
```

依赖刻意保持极小：

| 包 | 版本 | 用途 |
| --- | --- | --- |
| `@modelcontextprotocol/sdk` | 1.30.1 | 标准 MCP（stdio transport） |
| `zod` | 4.6.5 | 工具入参校验 |

`yaml` 没有引入：两个约 10 行的配置文件用自写子集解析器（`src/yaml.ts`）足够，
引完整 YAML 库只会扩大依赖面与安全面。

---

## 测试

```bash
cd tools/knowledge-os-mcp
npm run build      # 集成测试会 spawn dist/index.js，必须先构建
npm test
```

测试**不是**直接 import 内部函数，而是通过**真实的 MCP stdio JSON-RPC 协议**
（`initialize` / `tools/list` / `tools/call`）驱动一个真实的子进程 Server。

| 文件 | 覆盖 |
| --- | --- |
| `tests/stdio.test.ts` | MCP 握手、工具清单、stdout 协议纯净性、禁用工具不存在 |
| `tests/resolve-identity.test.ts` | 项目解析、缺失配置、Vault 不存在、三层 ID 一致/不一致/缺失、初始化死锁 |
| `tests/pathguard.test.ts` | `../`、绝对路径、UNC、NTFS 流、保留设备名、符号链接逃逸 |
| `tests/state.test.ts` | STATE 读/写、字段保留、创建、乐观并发、CRLF 保持、frontmatter 自愈、原子性 |
| `tests/log.test.ts` | 追加、不覆盖、自动创建、连续追加、CRLF |
| `tests/decision-wiki-search.test.ts` | Decision 创建/合并、Wiki 自动落位/追加、搜索各维度与过滤 |
| `tests/integration.test.ts` | 跨目录（project 与 vault 是两个不同目录）全链路 |
| `tests/continuity.test.ts` | Session A→B 连续性、Agent A→B→C 连续性、并发冲突保护 |

### 对真实项目 / 真实 Vault 的验证

```bash
# 手动调用任意工具（人类可用的最小 MCP 客户端）
node tools/knowledge-os-mcp/scripts/kos.mjs health
node tools/knowledge-os-mcp/scripts/kos.mjs script tools/knowledge-os-mcp/scripts/real-vault-log.json

# 冷启动连续性验证（只读）：模拟「新会话 + 只说一句继续」
node tools/knowledge-os-mcp/scripts/cold-start-check.mjs
```

详见 [`scripts/README.md`](scripts/README.md)。

---

## MCP 启动方式

```bash
node tools/knowledge-os-mcp/dist/index.js
```

**stdio transport**。stdout **只**承载 JSON-RPC 帧；所有日志写 stderr。

冒烟验证：

```bash
node tools/knowledge-os-mcp/dist/index.js
# stderr: [knowledge-os] server ready (v1.0.0, node v24.x, pid N)
# Ctrl+C 退出
```

### 环境变量（全部可选）

| 变量 | 作用 |
| --- | --- |
| `KNOWLEDGE_PROJECT_ROOT` | 显式指定项目根。默认从 Server 所在目录向上查找含 `.agent/project.yaml` 的目录 |
| `KNOWLEDGE_PROJECT_ID` | 覆盖 `project.id` |
| `KNOWLEDGE_PROJECT_NAME` | 覆盖 `project.name` |
| `KNOWLEDGE_VAULT_PATH` | **兜底**：仅当没有 `vault.local.yaml` 时使用（临时/容器场景） |
| `KNOWLEDGE_VAULT_ID` | 与上面配套的 `vault.id` |

正常使用**不需要**任何环境变量。

---

## Vault 配置

Vault 路径**只**来自 `.agent/vault.local.yaml`（本机私有；本仓库中 `.agent/` 整体不入库）：

```bash
cp .agent/vault.local.yaml.example .agent/vault.local.yaml
```

```yaml
version: 1

vault:
  id: "guarantee-ai-admin"                    # 必须与 project.id 一致
  path: "D:/Users/12209/Documents/guarantee-ai-admin-obsidian"   # 绝对路径
```

### 硬性规则

```text
✅ 绝对路径（Windows 优先用 /）
✅ 只信任这一个来源
✅ 配置缺失 → 返回明确错误 + 修复指引

❌ 不扫描 C:/ 或 D:/
❌ 不搜索其它 Obsidian Vault
❌ 不寻找 STATE.md 来猜目录
❌ 不猜测哪个目录是当前项目的 Vault
```

### 可选：Vault 布局覆盖

既有 Vault 的目录名不同时，不改代码，改配置：

```yaml
layout:
  wiki: "03_Wiki"
  decisions: "03_Wiki/Decisions"
  active: "04_Work/Active"
  state: "STATE.md"
  log: "LOG.md"
```

未列出的项沿用默认值（见 `src/layout.ts` 的 `DEFAULT_LAYOUT`）。

---

## 项目身份（Project ID）

`.agent/project.yaml`：

```yaml
version: 1

project:
  id: "guarantee-ai-admin"
  name: "智能电子保函运营管理平台（guarantee-ai-admin）"
```

> **在本仓库里，`.agent/` 整个目录都不入库**（见 `.gitignore` 里的
> 「Portable Knowledge OS —— 本机开发辅助工具」一段）。这是有意的：
> 这个仓库要分享给只需要跑项目的人，而 Knowledge OS 是作者本机的
> Agent 上下文工具链，对启动项目零贡献。
>
> 如果你把本工具链搬到你自己的项目并希望团队共用，就把 `.gitignore`
> 里那一段整体删掉 —— 那时 `project.yaml` 与 `vault.local.yaml.example`
> 应当入库，而 `vault.local.yaml`（含本机绝对路径）永远不该入库。

`project.id` 是**稳定标识**：

- 全生命周期不变
- 被用作 `04_Work/Active/<project-id>/` 的目录名
- 校验：只允许 `[A-Za-z0-9._-]`，以字母或数字开头
- **不要用随机 UUID** —— 每次重新生成会让绑定失效

---

## 身份校验（Identity）

三层必须完全一致：

```text
.agent/project.yaml        project.id
        ↓
.agent/vault.local.yaml    vault.id
        ↓
<Vault>/VAULT_ID.md        frontmatter.project_id
```

`VAULT_ID.md` 的内容：

```markdown
---
type: vault-identity
project_id: "guarantee-ai-admin"
project_name: "智能电子保函运营管理平台（guarantee-ai-admin）"
---

# Vault Identity

Project ID: `guarantee-ai-admin`

Project Name: `智能电子保函运营管理平台（guarantee-ai-admin）`
```

### 不一致时的行为

```text
✅ 允许：读取诊断信息（knowledge_health / knowledge_resolve / knowledge_state 等）
❌ 禁止：一切写操作（返回 identity_mismatch，附完整不一致详情）
```

这条规则的唯一目的：**绝对不允许静默写进错误的 Vault。**

| status | 含义 | 可写 |
| --- | --- | --- |
| `ok` | 三层一致 | ✅ |
| `mismatch` | 某层 id 不一致，或缺少 `type: vault-identity` | ❌ |
| `missing` | 没有 `VAULT_ID.md` | ❌（可用 `knowledge_bootstrap` 初始化） |
| `invalid` | `VAULT_ID.md` 无法解析 | ❌ |
| `unresolved` | 配置不完整或 Vault 目录不存在 | ❌ |

---

## 工具列表

第一版 10 个工具（**没有** delete / move / arbitrary_write / execute_command）：

| 工具 | 读写 | 作用 |
| --- | :-: | --- |
| `knowledge_health` | 只读 | 检查配置、Vault 可访问性、ID 匹配、State 与目录齐备性；返回 warnings 与建议 |
| `knowledge_resolve` | 只读 | 解析 `projectId` / `projectName` / `vaultId` / `vaultPath`，返回三层校验结果 |
| `knowledge_state` | 只读 | 读 global / project / project-definition 三个 scope 的状态 |
| `knowledge_search` | 只读 | 本地检索（文件名 / 标题 / heading / frontmatter / 正文） |
| `knowledge_read` | 只读 | 按 Vault 内相对路径读取页面，返回 frontmatter + 正文 |
| `knowledge_update_state` | 写 | 语义化更新 STATE / PROJECT，只替换对应小节 |
| `knowledge_append_log` | 写 | 向 `LOG.md` 追加历史（append-only） |
| `knowledge_create_decision` | 写 | 创建 / 合并 `03_Wiki/Decisions/决策 - <标题>.md` |
| `knowledge_upsert_wiki` | 写 | 按 type 自动落位，新建或追加 `03_Wiki/**` |
| `knowledge_bootstrap` | 写 | 幂等补齐**缺失**的基础设施，默认 dryRun |

### 为什么是语义化而不是 `write_file(path, content)`

```text
❌ write_file("../../etc/passwd", "...")      ← 模型永远拿不到这种能力
✅ knowledge_update_state({ nextAction })      ← 只表达意图，路径由 Server 决定
```

模型**无法**指定任意写入路径。`knowledge_read` 也拒绝绝对路径、`../`、UNC 与符号链接逃逸。

### 写入安全

```text
临时文件 → 写入 → fsync → 原子 rename
```

另外：

- **换行风格保持**：读写时探测 LF / CRLF，按原样写回
- **只改目标小节**：STATE 更新绝不整文件重写
- **乐观并发锁**：`knowledge_update_state` 的 `expectedHash` 不匹配 → `conflict`，拒绝覆盖
- **Decision / Wiki 不覆盖**：已存在则合并（填空白小节）或追加（带日期的小节）

---

## Skill

canonical Skill（**唯一真源**）：

```text
.agents/skills/knowledge-continuity/
├── SKILL.md
└── references/
    ├── state-schema.md
    ├── knowledge-schema.md
    └── decision-schema.md
```

Skill 只定义**工作方法**（什么时候读什么、什么时候写什么、怎么交接），
不含路径解析、文件访问、搜索实现 —— 那些属于 MCP。

各 Runtime 若需要自己的目录，只提供**最薄的兼容层**（symlink / junction / 一行指向），
**不允许出现多份独立维护、逐渐分叉的核心 Skill**。

---

## 各 Runtime 接入

详见 [`adapters/README.md`](../../adapters/README.md)。

| Runtime | Skill 发现 | MCP 接入 |
| --- | --- | --- |
| **DeepSeek Harness** | ✅ 原生支持 `.agents/skills` | profile 的 `cordis.patch.yml` |
| **OpenCode** | ✅ 原生支持 `.agents/skills` | `opencode.json` 的 `mcp` 段 |
| **Codex CLI** | ⚠️ 需兼容层到 `$CODEX_HOME/skills/` | `~/.codex/config.toml` 的 `[mcp_servers]` |
| **Claude Code** | ⚠️ 需 `.claude/skills/`（已建 junction） | 项目级 `.mcp.json` |

**所有 Runtime 共用同一个 Skill + 同一个 MCP + 同一个 Vault。**

---

## 设计取舍

| 决定 | 理由 |
| --- | --- |
| 只实现标准 MCP，不做 Runtime 插件 | 换 Agent 不影响知识库；核心逻辑只有一份 |
| Vault 路径不写进 MCP 配置 | 路径是本机私有信息；配置入库会让所有协作者指向错误 Vault |
| 不扫描磁盘找 Vault | 猜错会写坏别人的 Vault，代价不可接受 |
| 语义化工具而非任意写 | 安全边界只能有一个实现，且不能被绕过 |
| 不引入 embedding / 向量库 / RAG | 第一版用文件系统检索足够；架构已预留接口 |
| 自写 YAML 子集解析器 | 只为读两个 10 行文件，不值得引完整 YAML 库 |
| Decision / Wiki 已存在只追加不覆盖 | 无法机器判断哪个认识更新更对；保留时间顺序，让人整合 |
| `LOG.md` append-only | 追加天然无冲突，且历史不可被改写 |

搜索的第一版能力与未来扩展见 [`ARCHITECTURE.md`](ARCHITECTURE.md#search)。

---

## 故障排查

| 现象 | 错误码 | 处理 |
| --- | --- | --- |
| 找不到 `.agent/project.yaml` | `project_config_missing` | 创建它；或设 `KNOWLEDGE_PROJECT_ROOT` |
| `project.id` 为空或含非法字符 | `project_config_invalid` | 用 `[A-Za-z0-9._-]`，以字母数字开头 |
| 找不到 `.agent/vault.local.yaml` | `vault_config_missing` | 从 `.example` 复制并填真实绝对路径 |
| `vault.path` 不是绝对路径 | `vault_config_invalid` | 改用绝对路径（Windows 用 `/`） |
| Vault 目录不存在 | `vault_not_found` | 检查路径；或先创建 Vault |
| Vault 目录不可读/不可写 | `vault_not_readable` | 检查权限与占用 |
| 缺少 `VAULT_ID.md` | `vault_id_missing` | 跑 `knowledge_bootstrap`（需三层配置层一致） |
| 三层 id 不一致 | `identity_mismatch` | 跑 `knowledge_health`，按 `recommendedActions` 修 |
| 读到了 Vault 之外 | `path_escape` / `invalid_path` | 路径必须是不含 `..` 的 Vault 内相对路径 |
| 符号链接指向 Vault 外 | `symlink_escape` | 移除该链接，或把它纳入 Vault |
| 写被拒绝（文件被改过） | `conflict` | 重新 `knowledge_state` 拿新 hash，合并后再写 |
| 目标文件不存在 | `not_found` | 检查路径 |
| 方向不对：不知道从哪开始 | — | 先调 `knowledge_health`，读它的 `recommendedActions` |

### 常见误区

```text
✗ 在 MCP 配置里写 Vault 路径         → 应该只写在 .agent/vault.local.yaml
✗ 把 vault.local.yaml 提交入库        → 本仓库中 .agent/ 整体不入库
✗ 用随机 UUID 当 project.id           → 必须是稳定标识
✗ 遇到 conflict 就重试覆盖             → conflict 是保护，不是错误
✗ 每次进项目加载整个 Vault             → 先 knowledge_health → state，需要时才 search
✗ 只构建不测试就宣布完成               → npm test 会真的起子进程跑 stdio 协议
```
