# ARCHITECTURE

> knowledge-os MCP Server 的分层、数据流与设计前提。
> 面向维护者：想知道「为什么这么写」「改这里会破坏什么」时读本文。

---

## 1. 全景

```text
                        AI Agent（任意 Runtime）
                              │
                ┌─────────────┴─────────────┐
                │                           │
              Skill                        MCP
        （工作方法 / 流程）          （查询 / 写入 / 访问）
                │                           │
                └─────────────┬─────────────┘
                              ↓
                        Knowledge OS
                              ↓
                       Obsidian Vault
                              │
              ┌───────────────┼───────────────┐
              ↓               ↓               ↓
            STATE           WIKI          DECISION
              │               │               │
              └───────────────┼───────────────┘
                              ↓
                             LOG
```

### 分层（Server 内部）

```text
Project Repository
        ↓
  project.yaml                    project.id / project.name
        ↓
  vault.local.yaml                vault.id / vault.path（本机私有）
        ↓
   Vault Resolver                 config.ts — 只读配置，不扫盘
        ↓
 Identity Validator               identity.ts — 三层 ID 校验
        ↓
  Vault Repository                vault.ts + pathguard.ts + fsx.ts
        ↓
 Domain Operations                state.ts / wiki.ts / decisions.ts / log.ts / search.ts
        ↓
    MCP Tools                     tools/index.ts — 语义化、无任意路径
```

### 依赖方向（单向，无环）

```text
tools/index.ts
   ├── health.ts      ──┐
   ├── vault.ts       ──┤
   ├── state.ts       ──┤
   ├── wiki.ts        ──┼──→ identity.ts ──→ config.ts ──→ yaml.ts
   ├── decisions.ts   ──┤         │                │
   ├── log.ts         ──┤         ↓                ↓
   ├── search.ts      ──┤    markdown.ts       layout.ts
   └── read.ts        ──┘         │                │
                                 └────────┬───────┘
                                          ↓
                              pathguard.ts → fsx.ts → errors.ts
```

`markdown.ts` 与 `state.ts` 之间用 `SECTION_ANCHORS` 注册表**反向解耦**：
`state.ts` 在模块初始化时把自己的 SectionSpec 注册进 `markdown.ts`，
因此 `markdown.ts` 不需要 import `state.ts`。

---

## 2. 配置解析（`config.ts`）

### 项目根解析顺序

```text
1. 显式 projectRoot 参数（测试用）
2. 环境变量 KNOWLEDGE_PROJECT_ROOT
3. 从 Server 自身模块位置向上，找第一个含 .agent/project.yaml 的目录
4. 环境变量 KNOWLEDGE_VAULT_PATH（仅当没有 vault.local.yaml 时兜底）
```

第 3 条让 Server 在**任何 cwd** 下都能工作 —— 这是必要的，因为不同 Runtime
启动 MCP 子进程时的工作目录各不相同。

### 硬约束

```text
❌ 不扫描磁盘
❌ 不遍历 C:/ 或 D:/
❌ 不搜索其它 Obsidian Vault
❌ 不寻找 STATE.md 来反推目录
```

配置缺失 → 抛 `vault_config_missing` / `project_config_missing`，
并在 `knowledge_health` 的 `recommendedActions` 里给出**具体**修复步骤。

**为什么这么严格**：猜错 Vault 的代价不是「报错」，而是**静默污染另一个项目的知识库**。
这个代价不可接受，因此宁可失败。

### YAML 子集解析器（`yaml.ts`）

只支持：注释、缩进映射、引号字符串、纯量、流式序列 `[a, b]`。
明确**不支持**并在遇到时报错：块标量 `|` `>`、锚点 `&`、别名 `*`、多文档 `---`、流式映射 `{}`。

选择报错而不是尝试解析，是因为**静默误解配置**比拒绝启动更危险。

---

## 3. 身份校验（`identity.ts`）

```text
project.yaml project.id
        ==
vault.local.yaml vault.id
        ==
VAULT_ID.md frontmatter.project_id
```

还要检查 `VAULT_ID.md` 的 `type: vault-identity`。

### 为什么需要第三层

前两层都是**本机配置**。如果复制仓库、改了 `project.yaml` 却指向了旧的
`vault.local.yaml`，前两层可能「看起来自洽」而指向错误 Vault。
`VAULT_ID.md` 在 **Vault 内部**，是唯一的「对方自报身份」，构成真正的双向确认。

### 失败语义

```text
读操作：允许（Agent 需要诊断）
写操作：assertWritable() 抛 identity_mismatch，附完整不一致详情
```

`VaultContext` 在装配时就固定了 `writable`，每个写工具的第一行都是 `requireWritable(ctx)`。
**没有第二条写入路径。**

---

## 4. 路径守卫（`pathguard.ts`）

所有 Vault 内访问的唯一入口。

```text
normalizeRelativePath()   纯字符串检查（可穷举单测）
        ↓
path.join(vaultRoot, …)   拼绝对路径
        ↓
isInside() 词法兜底        防未来改动绕过归一化
        ↓
nearestExistingAncestor() + realpath()   符号链接逃逸检查
```

### 拒绝清单

| 输入 | 原因 |
| --- | --- |
| `..` | 目录穿越 |
| `/etc/passwd` | POSIX 绝对路径 |
| `C:\x` | Windows 盘符绝对路径（也覆盖 `C:relative`） |
| `\\srv\share` `//srv/share` | UNC 路径 |
| `\\?\C:\x` | Windows 设备命名空间 |
| `CON` `NUL` `COM1`… | Windows 保留设备名（会导致诡异 IO） |
| `file.md:ads` | NTFS 备用数据流 |
| `a/b. ` | 段以点/空格结尾（Windows 会静默改写实际路径） |
| `~` | home 展开 |
| 控制字符 | 无意义且可用于绕过后续检查 |
| 符号链接指向 Vault 外 | 逃逸 |
| `.` / `''` | Vault 根自身不是合法写入目标 |

### 两个设计细节

**反斜杠一律当分隔符。** 只检查 `/` 的实现会被 `a\..\..\b` 绕过。

**符号链接检查用「最近的存在祖先」。** 目标文件可能还不存在（新建笔记），
但它的某个祖先目录可能是指向 Vault 外的链接。因此向上找最近的已存在路径做 realpath，
再确认仍在 `vaultRealRoot` 内。

**大小写**：Windows / macOS 文件系统大小写不敏感，包含性判断统一小写比较，
否则 `VAULT/x` 能绕过 `vault/` 前缀检查。

**`resolveSearchRoot()` 是唯一的例外**：它允许 `''` / `'.'` 表示 Vault 根，
但只用于**只读遍历**（search），不参与任何写路径。

---

## 5. 文件系统原语（`fsx.ts`）

### 换行风格保持

```text
readSnapshot()   → 探测 CRLF/LF 与 BOM，连同 sha256 一起返回
atomicWrite()    → 按探测到的风格写回
```

本仓库同时存在 `.gitattributes` 的 `* text=auto` 与 `core.autocrlf=true`，
Vault 现存文件实测为 LF —— 但代码**不把它写死**，避免某天把用户的 CRLF 笔记整体改成 LF。

### 原子写

```text
同目录临时文件 (.NAME.kos-<pid>-<ts>.tmp)
   → writeFile
   → fsync            （先落盘内容，再改名）
   → rename 替换       （原子）
   → 失败则清理临时文件
```

- **同目录**：跨卷 rename 会退化成复制，失去原子性
- **fsync**：避免「文件已改名但内容还在页缓存」的窗口
- **Windows rename 重试**：杀毒/索引器持有句柄时 `EPERM`/`EBUSY`，短暂退避重试 8 次。
  **不会**为了成功而先删目标文件（那会破坏原子性承诺）

### 并发保护

```text
expectedHash 不匹配 → conflict，拒绝写入
```

`knowledge_state` 返回的 `hash` 就是写入时传回的 `expectedHash`。
配合原子 rename，把「读到旧版本 → 覆盖别人修改」的窗口压到最小。

`LOG.md` 用 `appendText()`（`fs.appendFile`），**不需要**锁：
追加天然不冲突，这是 append-only 的额外收益。

---

## 6. Markdown 定点修改（`markdown.ts`）

STATE 更新**绝不整文件重写**。流程是：

```text
parseMarkdown()      解析 frontmatter + 标题树
        ↓
locateSection()      按别名找到目标小节及其行范围
        ↓
replaceSection()     只替换该小节的正文行
        ↓
upsertFrontmatter()  只更新指定键（如 updated）
        ↓
lines.join()         其余行逐字保留
```

### 标题别名匹配

Vault 用 `## English 中文` 双标题惯例：

```text
## Current Objective 当前目标      （既有模板风格）
## Objective 目标                  （规范风格）
## 下一步行动                      （纯中文）
```

`headingKeys()` 把标题拆成多个归一化键（English 段、中文段、整串），
任一命中即算命中。因此同一份代码能读写两套惯例。

**更新既有文件时标题行保持原样** —— 不会把用户的中文标题改成英文。

### 小节缺失时

按 `canonicalHeading` 新建，并插到 `before` 指定的字段之前（通过 `SECTION_ANCHORS` 查锚点）。
这样新建的 `Next Action` 会出现在语义上合理的位置，而不是文件末尾。

---

## 7. 领域操作

### State（`state.ts`）

三种 scope 由 **Server** 决定落盘位置，模型不能指定路径：

| scope | 文件 | 回答 |
| --- | --- | --- |
| `global` | `STATE.md` | 这个 Vault 现在最重要的工作状态 |
| `project` | `04_Work/Active/<project-id>/STATE.md` | 这个项目现在在哪、下一步做什么 |
| `project-definition` | `04_Work/Active/<project-id>/PROJECT.md` | 项目为什么存在 |

**未传入的字段绝不被触碰。** 内容与磁盘一致时不写入（幂等，避免无意义改动）。

文件不存在时按模板骨架创建 —— 但 `knowledge_bootstrap` **不会**替 Agent 凭空生成
`PROJECT.md` / `STATE.md`：没有信息的骨架会变成「看起来做完了其实没有」的假象。
骨架只在 Agent 真的提供了字段时生成。

#### 状态与事实的边界

```text
代码 / 项目文件  = Implementation Reality
Obsidian STATE   = Documented Working State
```

Server 不试图自动核对两者（它读不到项目代码语义）。
这个交叉验证是 **Skill 的职责**，写进了 `SKILL.md` 第 6 节。

### Log（`log.ts`）

**append-only**。不解析、不重排、不改写既有条目，新条目加在文件末尾。
写入前若文件不以换行结尾则补一个，保证分隔。

不需要乐观锁 —— 这是设计选择而非疏漏。

### Decision（`decisions.ts`）

落盘 `03_Wiki/Decisions/决策 - <标题>.md`（沿用既有 Vault 命名惯例）。

同名已存在时**合并**，逐字段决定动作：

```text
小节不存在          → created-section
小节存在但为空       → filled-empty        （识别「（待补充）」等模板占位符）
小节存在且有内容     → conflict-kept-existing（保留既有结论，冲突写进返回值）
内容完全相同         → unchanged
```

**为什么不覆盖**：Decision 的价值包含「曾经选过 B，后来改成 A」这条历史。
机器无法判断哪个认识更新更对，因此保留时间顺序，由人整合。

`isEmptySection()` 的判据是「逐行去掉 Markdown 结构（标题/引用/列表符号/表格分隔线）
与占位符后，还剩不剩实质内容」—— 而不是维护一份「什么算空」的白名单。

### Wiki（`wiki.ts`）

`type` → 目录由 Server 决定（`layout.ts` 的 `WIKI_TYPE_DIRS`）：

```text
concept → Concepts      technology → Technologies     project → Projects
lesson  → Lessons       source     → Sources          synthesis → Syntheses
person  → People        company    → Companies        work → Works
```

已存在时**追加**一个带日期的小节（`## 更新 YYYY-MM-DD`），不覆盖。
若新内容已逐字存在于文件中，则不重复追加（幂等）。

### Search（`search.ts`）

第一版：**纯文件系统检索**。刻意不引入 embedding / 向量库 / RAG / Elasticsearch。

```text
collectFiles()     递归遍历，排除工具目录（.obsidian/.claude/copilot/.git/node_modules）
                  不跟随符号链接（与 pathguard 策略一致）
        ↓
逐文件解析 frontmatter / heading / 正文
        ↓
多维评分：文件名(60/30) > frontmatter title(25) > heading(20)
          > 正文短语(18) > frontmatter(10) > 逐 token
        ↓
token 覆盖率加成 → 归一化到 0..1 → 排序 → limit
```

`tokenize()` 为中文补 2-gram（让「会话记忆」能命中「会话」「记忆」），
为英文做轻量去后缀。

**模块边界的意义**：只暴露 `searchVault(ctx, options)` 这一个窄接口。
未来换成 SQLite FTS5 / 混合检索时，`tools/index.ts` 无需改动。
进程内不做持久缓存 —— **事实真源始终是 Vault 里的 Markdown**。

---

## 8. 写操作的统一顺序

```text
1. resolve project          config.ts
2. resolve vault            config.ts
3. validate identity        identity.ts  → 失败即 throw identity_mismatch
4. resolve semantic target  state.ts / wiki.ts / decisions.ts / log.ts 决定路径
5. validate target path     pathguard.ts（绝对路径/../UNC/符号链接）
6. write                    fsx.ts（原子写 + 可选 expectedHash 校验）
7. return result            含 newHash，供下一次写入做乐观锁
```

**任何一步失败都禁止写入。** 这个顺序只有一份实现（`tools/index.ts` 的 `run()` 包装 +
各领域函数首行的 `requireWritable`），因此不可能被某个工具绕过。

---

## 9. 错误模型（`errors.ts`）

每个失败都有稳定的机器可读 `code`，Agent 据此判断下一步，而不是解析人类可读文本。

`tools/index.ts` 把任意抛出转换为：

```json
{
  "ok": false,
  "error": { "code": "identity_mismatch", "message": "...", "details": { } }
}
```

并设置 `isError: true`。**失败永远不会以「半成功」的形式返回。**

---

## 10. 刻意不做的事

| 不做 | 原因 |
| --- | --- |
| delete / move / rename 工具 | 破坏性操作不该由模型自主发起 |
| `write_file(path, content)` | 会让所有安全边界失效 |
| execute_command | 与「工具只表达知识意图」的定位冲突 |
| 扫描磁盘找 Vault | 猜错的代价是静默污染别人的知识库 |
| 自动迁移/整理旧 Vault | 规范明确禁止；本任务只建立 infrastructure |
| 依赖 Obsidian 插件（Dataview/Templater/Tasks） | Obsidian 只是人读写 Markdown 的界面 |
| 把事实状态存进内存/SQLite/JSON | 唯一真源必须是 Vault 里的 Markdown |
| embedding / 向量库 / RAG（第一版） | 文件系统检索足够；接口已预留 |
| 依赖任何 Agent SDK | Runtime 必须可替换，否则系统就被绑定了 |

---

## 11. 扩展点

### 换搜索后端

改 `search.ts` 的 `searchVault()` 实现，保留签名。
`tools/index.ts`、Skill、Vault 结构都不需要变。

### 换 Vault 布局

改 `.agent/vault.local.yaml` 的 `layout:` 段（无需改代码）。
若需要新的布局键，加到 `layout.ts` 的 `DEFAULT_LAYOUT` 与 `OVERRIDABLE_LAYOUT_KEYS`。

### 加一个语义化工具

1. 在对应领域模块（或新模块）实现纯函数，首行 `requireWritable(ctx)`（若写）
2. 在 `tools/index.ts` 用 `server.registerTool()` 注册，附 zod schema 与 description
3. 在 `tests/stdio.test.ts` 的工具清单断言里加上它
4. 更新 `README.md` 的工具表

**不要**加任何接受绝对路径或任意路径的参数。

### 接入新 Runtime

在 `adapters/<runtime>/` 加一个 README 与配置示例。
只解决「怎么找到 Skill + 怎么启动 MCP」，**不要**复制任何知识管理逻辑。
