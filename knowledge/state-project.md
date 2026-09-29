---
scope: project
---

> 本文件是**项目 STATE 的知识源**（用于初始化与批量修订）。
> `### 字段名` 对应 `knowledge_update_state` 的语义字段；
> 未列在固定字段表里的标题会作为自定义小节写入。
> 注意：STATE 是**指针**，历史在 `LOG.md`。

### Current Objective 当前目标

让任何 Agent 在任意新会话里都能凭 Vault 恢复本项目上下文并继续工作，同时把本项目的**真实实现事实**沉淀为可查询的长期知识（而不是散落在 README 与聊天里）。

### Current Milestone 当前里程碑

**第二阶段 · 收尾**（多 Tool → Agent → 业务分析）

- 五阶段路线图的完整定义在 `PROJECT.md` 的「Roadmap 阶段路线图」。
- 当前阶段的**权威指针就是本节**（不要在别处再写一份）。
- 第一阶段（传统后台 → 真实业务数据 → AI Chat → Tool Calling）已交付，遗留项见 Verification → 尚未验证。

### Completed 已完成

- 建立 Portable Knowledge OS：三层身份绑定（`.agent/project.yaml` ↔ `.agent/vault.local.yaml` ↔ `VAULT_ID.md`）
- 交付独立标准 MCP Server `tools/knowledge-os-mcp`（strict TS/ESM/stdio，10 个语义化工具）
- 交付 canonical Skill `.agents/skills/knowledge-continuity`（SKILL.md + 3 个 references）
- 交付 4 个 Runtime adapter（dsh / OpenCode / Codex / Claude Code）与 `.mcp.json`
- MCP 增加 `project-definition` 的完整小节字段与「按标题写小节」（`sections`），使 PROJECT.md / TASKS.md 可被语义化更新
- 通读代码与 `docs/` 文档，核对出 README 与实现的偏差（表数量 16 → 20、AI 已含写操作提案能力、逻辑删除已交付）
- 完成**项目知识库初始化**：PROJECT.md 14 个小节、TASKS.md 8 个 Phase、5 个 Decision、6 个技术页、1 个概念页
- 知识内容以可评审可 diff 的 Markdown 源文件落在仓库 `knowledge/`，交付可重跑的应用器 `scripts/apply-knowledge.mjs`
- MCP 补强：`project-definition` 的 14 个语义小节字段、`sectionsFile`、受限删除哨兵；测试 124 → 132 项
- **补齐机构写入入口（P-01 全部完成）**：`Orgs.vue` 两个 `disabled` 占位按钮接上真实对话框——「新增机构」（工具栏，`parentId` 默认 0/顶级）、「新增下级」（树节点，`parentId` 预填该节点 id）、「修改」；新增与修改共用同一表单，层级由上级自动推导（父层级 + 1）、上级选项排除自身与全部下级、`orgCode` 修改时只读、区划始终回传当前值
- 沉淀「后端写接口已通而前端入口未接」这一 V-1 / V-2 模式为经验教训页（见 `03_Wiki/Lessons/后端写接口已通而前端入口未接.md`）
- **统一树形表格的展开/收起（含一次错误修复的纠正）**：新增 `frontend/src/utils/treeExpand.ts` 收敛口径；机构与部门配置页的「全部展开 / 全部收起」与行内箭头全部实测通过。**前两次修复都是错的**（先误判为 `default-expand-all` 冲突、后写出恒等比对），最终经 CDP 驱动真实浏览器逐项对照才定下正确方案：`:expand-row-keys` 在挂载后赋值时不生效，必须用 ref 的 `toggleRowExpansion` 下发 + 从 DOM 回读 `aria-expanded` 定案。经验教训页已按这个真实过程重写（见 `03_Wiki/Lessons/树形表格的展开状态必须从 DOM 回读.md`）
- **记录五阶段 AI 能力路线图**（2026-09-29，用户确认）：`PROJECT.md` 新增「Roadmap 阶段路线图」小节（五阶段链路 + 每阶段 DoD + 各阶段明确不做 + 「怎么判断当前阶段」）；本文件的 Current Milestone 切换到**第二阶段 · 收尾**；`TASKS.md` 增补阶段归属与「第二阶段 · 收尾」任务清单（M2.1~M2.4）。同时写清它与工程 Phase 0–7 的对应关系：**Phase 0–6 都在第一阶段之内**，两套编号不要混用

### In Progress 进行中

- **第二阶段 · 收尾**：需求真源 `docs/REQ-助手业务分析能力阶段二收尾.md`（v1.0，2026-09-29，≈6 人日）已定稿；四个里程碑**都还没开工**（建议顺序 M2.1 → M2.2 → M2.4 →（再决定）M2.3）。
- 第二阶段的「已完成主体」与「缺口清单」见 `PROJECT.md` 的「Roadmap 阶段路线图 · 第二阶段」。
- 前端写入口已全部接线（见 Verification 的覆盖清单），无遗留。

### Next Action 下一步行动

开工**第二阶段收尾的 M2.1「取数面补齐」**（P0，≈1.5 人日）。
需求真源：`docs/REQ-助手业务分析能力阶段二收尾.md` 的 §5.1.1 / §5.1.2 / §5.2.2 / §10。

```text
1. 先确认基线：git status（本仓库当时有未提交改动，见 Handoff Notes 第 13 条）
   + 读 docs/REQ-助手业务分析能力阶段二收尾.md 的 §5.1、§5.2.2。

2. REQ-BA-01 分布工具交叉过滤：
   打开 guarantee-ai/src/main/java/com/guarantee/ai/tool/OrderDistributionTool.java，
   给 queryOrderDistribution 增加两个**可选**参数 regionCode / orgId
   （AnalysisCriteria 早已支持这两个字段，四个分布查询共用 criteriaFilter → 不需要新 SQL），
   口径行按 DataSourceText 既有键追加「区域：330000（浙江省）」/「机构：2」，
   并按区域层级前缀匹配（选省 = 含其全部市 / 区县，与订单列表同口径）。
   实测前提（2026-09-29 本知识库核对）：该工具目前**没有**这两个参数，前提成立。

3. REQ-BA-02 新增趋势工具 queryOrderTrend：
   复用 OrderAnalysisService.trend(...)（零新 SQL）；默认 24 个点、上限 120、
   超出时从最近端截断并在 truncated 里说明；风险：日粒度跨 21 个月约 640 个点。

4. REQ-BA-07 计划式取数（只改提示词）：
   改 guarantee-ai/src/main/resources/prompts/business-assistant.st ——
   首次调用工具前用 1~2 句说明要查哪几个维度 / 区间，并在一轮内查齐；
   不新增 SSE 事件、不改协议。

5. 验证（区分已实现与已验证）：
   为两个工具补单测（字段映射 / 维度别名 / limit 归一 / 截断标记 / 口径文本 / 非法参数），
   然后 mvn -DskipITs test；再跑 -Dit.test=OrderDistributionToolIT 等既有分布 IT。
   不要用 mvn clean（见 Handoff Notes 第 5 条）。

6. 完成后写回知识库：
   - 勾掉 knowledge/tasks.md 里「第二阶段 · 收尾」的 M2.1；
   - 更新本文件的 In Progress / Verification；
   - 重跑 node tools/knowledge-os-mcp/scripts/apply-knowledge.mjs，并 knowledge_append_log。
```

不做 M2.3（企业 / 项目维度，需要 4 条新聚合 SQL），除非 Q-BA-01 被确认要一起做。

### Verification 验证

#### Verified 已验证

- `mvn verify` 139 项全绿（逻辑删除交付 133 项 + 部门树改造新增 6 项）—— 依据 `docs/IMPL-逻辑删除-进度.md`
- `npx vue-tsc --noEmit` 与 `npm run build` 均 exit 0 —— 依据 README §十三 与进度文档
- `AiToolChainIT`（Stub ChatModel，无需 API Key）断言 Tool 返回的 5 个指标与直接调用 Service 逐一相等，并校验 `ai_message` / `ai_tool_call` 落库与 SSE 事件序列
- `TimeSemanticParserTest` 13 个用例，基准日固定为 2026-09-21，断言与运行时间无关
- 逻辑删除：13 个唯一键改为函数索引，DDL 已在 `schema.sql` 中落地（本知识库实测确认）
- 数据库 20 张表（本知识库实测 `schema.sql` 统计，与 README 的「16 张」不一致）
- `schema.sql` 全部 `CREATE TABLE IF NOT EXISTS`，可重复执行
- 逻辑删除已落地：`schema.sql` 中 `is_deleted` 出现 38 处、函数索引唯一键 15 个（知识库实测）
- Knowledge OS MCP：`tsc` 构建通过；132 项自动化测试全绿（含真实 stdio JSON-RPC 握手）
- 知识库应用器幂等：连续两次执行，第二次全部 `changed=0` / 未重复追加
- 冷启动连续性验证：34 项全通过（对真实 Vault 只读执行）
- 机构「修改」入口补齐后：`npx vue-tsc --noEmit` exit 0、`npx vite build` exit 0（本次实测；未起后端，接口调用未在浏览器验证）
- 机构「新增」入口补齐后：`npx vue-tsc --noEmit` exit 0、`npx vite build` exit 0，产物 `Orgs-*.js` 13.90 kB / gzip 5.50 kB（本次实测）
- 前端写入口覆盖情况（本次实测）：`createOrg` 已有调用点，`Orgs.vue` 不再有 `disabled` 占位按钮；险种新增/修改/**启停**、部门新增/修改/启停、角色新增/修改、用户新增/修改均已接线 —— 即 `docs/REQ-系统管理手动操作能力补齐方案.md` 的 P-01、P-02 已闭合
- 树形表格展开/收起统一后：`npx vue-tsc --noEmit` exit 0、`npx vite build` exit 0（本次实测）
- **展开/收起在真实浏览器中已验收**（CDP + headless Chrome，admin 登录真实后端）：机构页 21↔1↔21 行、部门页 11↔1↔11 行，行内箭头 21→9→1 亦正确；控制台无异常
- **机构「修改 / 新增」对话框经真实浏览器验收**：修改对话框回填（ORGHQ / 平台总部 / 北京市，orgCode 只读，层级=总部）、新增对话框 orgCode 可填、层级推导为总部
- 展开失效的根因经**真实浏览器对照实验**确认（element-plus 2.14.6）：`:expand-row-keys` 在挂载后赋值时四种写法全部不生效，行级 `toggleRowExpansion` 生效
- 知识库侧（2026-09-29 本次实测）：五阶段路线图写入后重跑 `node tools/knowledge-os-mcp/scripts/apply-knowledge.mjs`，全部小节均为 unchanged / 「内容已存在」，无新增或重复内容（幂等）；PROJECT.md / STATE.md / TASKS.md 只替换了目标小节，其余内容与写入前备份比对一致
- 第二阶段 M2.1 的前提经代码核对成立（2026-09-29 本次实测）：`OrderDistributionTool` 当前**没有** `regionCode` / `orgId` 入参，且 `OrderTrendTool` **不存在**

#### Not Yet Verified 尚未验证

- 真实模型（DeepSeek）端到端问答 —— 本机从未配置 `DEEPSEEK_API_KEY`
- `docker-compose.yml` / `Dockerfile` 在容器中的构建与运行
- 前端 Vite dev server 与 SSE 客户端在浏览器中的实际表现
- 接口层细粒度授权（当前只下发权限编码给前端按菜单展示）
- 演示数据规模（10 万投标 + 5 万履约）下的分析查询实测耗时
- 第三阶段（RAG → 业务知识）与第五阶段（MCP / Evaluation / Observability）目前只是路线图，**尚未做技术选型与可行性验证**

### Blockers 阻塞项

- **真实模型验证缺凭证**：需要 `DEEPSEEK_API_KEY`，本仓库/本机均未提供
- **容器化验证缺环境**：开发机未安装 Docker
- **四个 Runtime 的 MCP 接入未实测**：环境未安装 OpenCode / Codex CLI / Claude Code，且未改动 dsh 的用户级 profile 配置

### Important Decisions 重要决策

- [[决策 - 全表逻辑删除设计]]
- [[决策 - AI 写操作只产出提案]]
- [[决策 - 采用模块化单体而非微服务]]
- [[决策 - 显式驱动工具调用循环]]
- [[决策 - 业务口径唯一实现]]
- [[决策 - Knowledge OS 采用标准 MCP over stdio]]

### Handoff Notes 交接说明

1. **README 已滞后于实现**，至少三处：表数量（README 说 16，实际 20）、AI 能力（README 说「只有查询能力」，实现已有提案写流程）、`docs/` 文档未索引。以 `schema.sql` 与代码为准。
2. **`docs/` 里的 `DEC-` / `REQ-` / `PLAN-` / `IMPL-` 文档是设计真源**，比 README 更细。改动相关模块前先读对应文档。
3. **知识库的源在仓库 `knowledge/`**，不是 Vault。修改 `knowledge/*.md` 后必须重跑
   `node tools/knowledge-os-mcp/scripts/apply-knowledge.mjs` 才会生效。
4. **本地 MySQL 在 3307**（3306 被本机 MySQL 5.7 占用），且 MySQL/Redis 是普通进程、不会自启，机器重启后需 `scripts/start-local-env.ps1` 重新拉起。
5. **不要用 `mvn clean`**：IDEA 运行实例还在用 `target/classes`，clean 会让懒加载抛 `NoClassDefFoundError`。
6. **前端端口是 5273 而不是 Vite 默认的 5173**：5173 落在 Windows 保留端口段（Hyper-V/WSL2/Docker Desktop）会导致 `EACCES`。
7. 改 MCP 代码后必须 `npm run build`，否则 Runtime 用的是旧 `dist`。
8. **判断"某功能是否真的做完"不能只看后端接口与 `api/*.ts` 的导出**：`frontend/src` 里可能
   存在"API 函数已定义但零调用"的情况，此时页面按钮会以 `disabled` 占位形态渲染出来，
   看起来像权限问题（2026-09-26 机构新增/修改就是这个形态，现已修复）。排查口诀：
   **渲染但 disabled = 没接线；不渲染 = 权限问题**。前端没有单元测试，
   `vue-tsc` / `vite build` 都不会报未使用的导出。
9. **`.gitignore` 不再排除 `knowledge/` 与 `tools/knowledge-os-mcp/`**（2026-09-26 按 STATE
   口径修正）。原排除规则与本文件第 3 条「知识库的源在仓库 `knowledge/`」及 Completed 列表
   自相矛盾——排除后别人 clone 不到源文件与 `apply-knowledge.mjs`，指令无法执行。
   仍排除的是本机身份/接入配置：`.agent/`、`.agents/`、`.mcp.json`、`adapters/`。
   若确实不想随仓库分享知识库，改回排除时**必须同时**改本文件第 3 条，否则会再次矛盾。
10. **前端交互缺陷优先用真实浏览器验证，不要只读源码推理。** 树形表格展开/收起那个 bug
   花 4 轮才修对，前 3 轮都是"读源码 → 推理出根因 → 改代码"，全部无效（其中一次还写出
   了恒等比对这种静默空转）。可用 CDP 驱动 headless Chrome：登录拿 token 写 localStorage →
   导航到 `#/<路由>`（**注意是 hash 路由**）→ 在页面里构造对照实验 → 读回结果。
   本项目前端没有测试框架，`vue-tsc` / `vite build` 都发现不了这一类错误。
11. **树形表格（`el-table` + `row-key`）不要再写 `:expand-row-keys`**：它在挂载后赋值时不生效
   （element-plus 2.14.6 实测）。展开状态统一走 `frontend/src/utils/treeExpand.ts`：
   用 ref 的 `toggleRowExpansion` 下发，用 `readOpenKeysFromDom` 从 `aria-expanded` 回读定案。
   注意树形表格的 `<tr>` 上没有 `data-row-key`，回读靠"可展开行 ↔ 展开图标"的顺序对齐。
12. **「咱们现在到哪个阶段」怎么回答**：五阶段路线图的定义在 `PROJECT.md` 的「Roadmap 阶段路线图」；
    当前阶段**只看本文件的「Current Milestone 当前里程碑」**（现在 = 第二阶段 · 收尾）。
    不要把路线图的「阶段」和 `TASKS.md` 的工程 Phase 0–7 混为一谈 —— Phase 0–6 都在第一阶段之内。
    阶段是否算完成按 DoD 从严判定：「代码写完」不等于「阶段完成」，没有证据就标「尚未验证」。
    判定顺序：先读本节 → 再读 `PROJECT.md` 的 Roadmap → 需要「还差什么」时读 In Progress / Verification / TASKS.md。
13. **（2026-09-29 23:5x 观察）代码仓库有未提交改动**：分支 `feature/ai-system-assistant`，
    新增 `ProposalNumberGuard.java` / `ProposalNoFormat.java` / `TurnFacts.java` + 2 个单测（未跟踪），
    另有 `AiChatService` / `DataSourceClaimGuard` / `AiToolCallRecorder` / `ToolResultMeta` / `DataSourceText` /
    `business-assistant.st` 等已修改未提交。下次开工前先 `git status`，确认这批改动是否已提交，
    再决定在哪个基线上做 M2.1 —— 本次只写知识库，没有碰任何代码。
14. **改过 Vault 里的 wiki 页面之后，必须同步改 `knowledge/` 源文件**。`knowledge_upsert_wiki` 的幂等判据是
    「整段内容包含」，wiki 源与页面只要有一处**空格**差异，它就会把整份内容当作新内容**追加一遍**
    （2026-09-29 在《后端写接口已通而前端入口未接》上真的发生了一次：页面里代码围栏的对齐空格被改过，
    于是又追加了一份完整副本；已手工还原页面并把源文件对齐到页面现状）。
    因此：① 跑完 `apply-knowledge.mjs` 要逐条看输出，**`appended=true` 或 `newSections` 出乎意料就是信号**，
    不要只看最后那句「全部成功」；② 需要用 Obsidian 直接改 wiki 页面时，改完把同一段内容回写到对应的
    `knowledge/wiki-*.md`；③ 这个判据本身待改进，已记进 `TASKS.md` 的「未计划 / 新发现」。
