# scripts/

对**真实项目与真实 Vault** 运行 Knowledge OS 的入口。
与 `tests/` 的区别：`tests/` 用的是临时夹具（可重复、不碰真数据），
本目录直接作用于 `.agent/vault.local.yaml` 指向的那个真实 Vault。

> ⚠️ `real-vault-*.json` 会**写入真实 Vault**。只有在确实要维护本项目知识库时才运行它们。

---

## `apply-knowledge.mjs` — 把仓库里的知识源应用进 Vault（推荐入口）

```bash
# 预演：只打印将执行哪些调用，不连接 MCP
node tools/knowledge-os-mcp/scripts/apply-knowledge.mjs --dry-run

# 应用（幂等，可反复执行）
node tools/knowledge-os-mcp/scripts/apply-knowledge.mjs

# 应用并清除模板骨架留下的占位小节（标题里带 <...>，如 `## Phase 1 — <阶段名>`）
node tools/knowledge-os-mcp/scripts/apply-knowledge.mjs --prune-placeholders
```

知识内容本身是仓库 `knowledge/` 下**可评审、可 diff 的 Markdown 源文件**，
不是转义过的 JSON：

| 源文件 | 写入目标 | 约定 |
| --- | --- | --- |
| `knowledge/project.md` | `PROJECT.md` | 每个 `# 标题` → 一个 `## 标题` 小节 |
| `knowledge/tasks.md` | `TASKS.md` | 同上；frontmatter 可给 `scope` / `sectionsFile` |
| `knowledge/state-*.md` | `STATE.md` | `### 字段名` → 语义字段；其它标题 → 自定义小节 |
| `knowledge/wiki-*.md` | `03_Wiki/<Type>/` | frontmatter 给 `type` / `title` / `related` |
| `knowledge/decision-*.md` | `03_Wiki/Decisions/` | frontmatter 给 `title`；`### decision` / `### why` 必填 |

**为什么用「源文件 + 应用器」而不是一次性 JSON：**

1. 可读可评审可 diff —— 它就是 Markdown，不是转义字符串；
2. 可重跑 —— MCP 的合并 / 追加语义让它天然幂等（实测第二次执行全部 `changed=0`）；
3. 可审计 —— 仓库里能看到「到底往 Vault 写了什么」。

> ⚠️ `knowledge/` **不是**知识库本身。知识库是 Vault 里的 Markdown。
> 这里是它的**源**，用于初始化与批量修订。改完源文件必须重跑本脚本才生效。

### 关于占位小节

Vault 初始化时 `knowledge_bootstrap` 会用模板骨架建出
`## Phase 1 — <阶段名>` 这类占位小节。真实内容以别的标题写进去后，占位就成了孤儿。
`--prune-placeholders` 会找出标题里带 `<...>` 的小节并删除。

**为什么需要 `__KOS_DELETE_SECTION__` 哨兵**：MCP 刻意不提供通用删除能力
（`delete` / `arbitrary_write` 都在禁止清单里），因此用一个**受限的语义化哨兵**
表达「移除这一个小节」—— 只能删小节、不能删文件，目标范围仍由
`scope` / `sectionsFile` 决定，调用方依然给不出路径。

---

## `kos.mjs` — 手动调用任意 MCP 工具

```bash
# 快捷方式
node tools/knowledge-os-mcp/scripts/kos.mjs health
node tools/knowledge-os-mcp/scripts/kos.mjs resolve
node tools/knowledge-os-mcp/scripts/kos.mjs state

# 单个工具调用（第二个参数是 JSON 形式的 arguments）
node tools/knowledge-os-mcp/scripts/kos.mjs call knowledge_search '{"query":"MCP","limit":5}'
node tools/knowledge-os-mcp/scripts/kos.mjs call knowledge_read '{"path":"STATE.md"}'

# 一次跑一串调用
node tools/knowledge-os-mcp/scripts/kos.mjs script path/to/calls.json
```

`calls.json` 就是 `[{ "name": "...", "arguments": { ... } }]`。

退出码：任一调用返回 `isError` 时为 1。

**为什么需要它**：MCP 只讲标准协议，没有给人类用的 CLI。
排障时手写 JSON-RPC 帧很容易出错，这个脚本提供一个已验证过的最小客户端 ——
它和测试用的是同一套协议路径（真实子进程 + stdin/stdout 帧）。

---

## `cold-start-check.mjs` — 冷启动连续性验证

```bash
node tools/knowledge-os-mcp/scripts/cold-start-check.mjs
```

模拟规范第 50 节的核心场景，且**只读**，不修改任何文件：

```text
Session A  全新 Server 进程，模拟 Agent 只收到一句「继续。」
           → knowledge_health → knowledge_resolve → knowledge_state
           → 断言：身份 OK、可写、Next Action 具体可执行、Handoff Notes 非空

Session B  又一个全新进程（与 A 没有任何共享内存，唯一媒介是 Vault 里的 Markdown）
           → 独立恢复出**相同**的 Next Action
           → knowledge_search / knowledge_read 取回本次的决策与知识页

交叉核对   Documented State ↔ Actual Implementation
           → STATE 里声称交付的东西在仓库里真的存在吗？
```

全部通过时退出码 0。

用途：

- 交接前跑一次，确认「下一个 Agent 真的能接上」
- 改完 Vault 内容后跑一次，确认没有把状态写坏

---

## `real-vault-*.json` — 本项目的真实调用记录

这些是本次建立 Knowledge OS 时实际执行过的调用序列，保留下来有两个作用：
**可复现**（照着重跑就知道当时写了什么）与**可审计**（写了什么一目了然）。

| 文件 | 作用 | 会写吗 |
| --- | --- | --- |
| `real-vault-global-state.json` | 填 `STATE.md` 的 active_project / phase / 目标 / 里程碑 / 完成项 / Next Action / 验证 / 交接 | ✅ 写全局 STATE |
| `real-vault-decision.json` | 创建 `03_Wiki/Decisions/决策 - Knowledge OS 采用标准 MCP over stdio.md` | ✅ 写决策页 |
| `real-vault-wiki.json` | 创建 `03_Wiki/Technologies/Knowledge OS 的路径安全与身份校验.md` | ✅ 写知识页 |
| `real-vault-log.json` | 向 `LOG.md` 追加这次 MIGRATION 记录 | ✅ 追加 |

用法：

```bash
node tools/knowledge-os-mcp/scripts/kos.mjs script tools/knowledge-os-mcp/scripts/real-vault-global-state.json
```

**注意**：`knowledge_create_decision` 与 `knowledge_upsert_wiki` 对已存在的页面
**不会覆盖**（决策页只填空白小节；Wiki 页只追加带日期的小节，内容重复则跳过）。
因此重复运行这些 JSON 是安全的幂等操作，不会破坏后来人工编辑的内容。

`knowledge_append_log` 则是**每次都真的追加**一条 —— 重复运行会产生重复的历史条目。
只在确实需要记录一次工作时运行它。
