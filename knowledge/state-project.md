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

Knowledge OS 基础设施 + 项目知识库初始化

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

### In Progress 进行中

- 无。前端写入口已全部接线（见 Verification 的覆盖清单）；下一项待指派。

### Next Action 下一步行动

打开 `README.md` 第 565 行附近「前端运行验证」那一条，以及 `PROJECT.md` 的 Open Questions 一节。

先做**前端与 SSE 链路的端到端实测**（这是当前最不需要外部依赖的未验证项）：

```text
1. 确认本地环境已起：
   pwsh -File scripts/start-local-env.ps1
   （MySQL 3307 + Redis 6379；它们是普通进程，不会随系统自启）

2. 起后端与前端：
   mvn -pl guarantee-web -am spring-boot:run
   cd frontend; npm run dev        # http://localhost:5273

3. 用 admin / Admin@123 登录，打开投标订单确认有数据，
   再打开右下角 AI Copilot 发问「2026年第三季度投标订单有多少？」。
   未配 DEEPSEEK_API_KEY 时应当**明确报错且不编造数字** —— 这本身就是可验证行为。

4. 把结果写回知识库：
   - 若通过：用 knowledge_update_state(scope="project") 把
     「前端浏览器实测」「SSE 事件序列实测」从 Verification→尚未验证 移到 已验证；
     并把 knowledge/tasks.md 的 Phase 7 对应项勾掉，然后重跑
     node tools/knowledge-os-mcp/scripts/apply-knowledge.mjs
   - 若失败：把现象与 stderr 写进 Blockers，并用 knowledge_append_log 记录，
     不要改动 Verification 的结论。
```

如果环境无法起前端（例如无浏览器 / 端口被占），改为做**README 刷新**（表数量、AI 能力范围、`docs/` 索引），因为那是纯仓库内改动、可完全验证。

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

#### Not Yet Verified 尚未验证

- 真实模型（DeepSeek）端到端问答 —— 本机从未配置 `DEEPSEEK_API_KEY`
- `docker-compose.yml` / `Dockerfile` 在容器中的构建与运行
- 前端 Vite dev server 与 SSE 客户端在浏览器中的实际表现
- 接口层细粒度授权（当前只下发权限编码给前端按菜单展示）
- 演示数据规模（10 万投标 + 5 万履约）下的分析查询实测耗时

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
