---
scope: project
---

> 本文件是 **TASKS.md 的知识源**。每个 `# 标题` 会成为 TASKS.md 里的一个 `## 标题` 小节。
> 依据：`README.md`、`docs/` 下的 `REQ-`/`PLAN-`/`DEC-`/`IMPL-` 文档，以及代码实现现状。
> 「已完成」勾选项均有代码 / 测试 / 文档作为依据；未验证的事项一律不勾。
>
> ⚠️ 本文件里刻意保留了模板骨架留下的占位小节标题
> （`Phase 1 — <阶段名>`），并用 `__KOS_DELETE_SECTION__` 哨兵把它删掉 ——
> 否则 Vault 里会永久留下一条「（待补充）」的孤儿小节。
> 应用时请带 `--prune-placeholders`。

# Phase 1 — <阶段名>

__KOS_DELETE_SECTION__

# 未计划 / 新发现

执行过程中发现、但不在原计划里的工作。放在这里，而不是就地扩大当前任务的范围。

- [ ] 把 `knowledge/` 与 Vault 的关系写进 README，让新人知道知识库的源在哪
- [ ] `scripts/onetime-logical-delete/` 下大量一次性改造脚本：确认是否还需要保留（它们已完成使命，但作为变更痕迹有审计价值）
- [ ] `knowledge_upsert_wiki` 的幂等判据是「整段内容包含」（`existing.raw.includes(content)`），对空白差异极敏感：wiki 源文件与 Vault 页面只要有一处空格不同，就会把**整份内容**当成新内容追加一遍（2026-09-29 实测发生在《后端写接口已通而前端入口未接》上，已还原页面并同步源文件）。建议改为按小节标题 + 归一化内容比对；在修好之前，改过 Vault 页面就必须同步改 `knowledge/` 源文件，并且要检查 apply 输出里的 `appended=true`，不能只看「全部成功」

# 已推迟 / 超出范围

> 分层归属见 `PROJECT.md` 的「Roadmap 阶段路线图」——**阶段路线图是唯一的阶段定义真源**，
> 这里只记「哪些事现在不做」。

- 微服务拆分：当前阶段明确不做
- **按路线图阶段推进，不提前引入**：
  - 第三阶段：RAG / 业务知识检索（含向量库、embedding）
  - 第四阶段：AI 配置写入（模型 / 提示词 / 能力开关的配置化）
  - 第五阶段：MCP Server / Evaluation / Observability（Prometheus-OTel 等）
  - 尚未排期：多模型路由、LangChain4j、MQ、跨会话长期记忆、自主规划型 Agent
- 分库分表 / 读写分离 / 多租户
- 数据导出与报表生成
- AI 对话生成图表（当前 AI 只返回文本与统计数字，图表由页面固定渲染）

# 已完成（汇总）

一段简短的汇总，避免文件长大之后难以阅读。细节留在 `LOG.md`。

- Phase 0 工程骨架完成 — 见 git 历史（`9c7c27f` 及更早）
- Phase 1 认证与系统管理完成 — 含角色权限、首次登录改密、部门树、移除机构归属
- Phase 2 订单与业务分析完成 — 含地区字典与筛选口径统一
- Phase 3 AI 业务助手完成 — READ 工具链 + SSE + 审计 + Stub 模型集成测试
- Phase 4 AI 写操作提案完成 — 5 个执行器 + 能力开关 + 确认与审计
- Phase 5 逻辑删除完成 — 18 张表 + 13 个函数索引唯一键，139 项测试全绿
- Phase 6 本地开发体验完成 — 一键启停脚本、端口避让、单端口演示
- 路线图第一阶段（传统后台 → 真实业务数据 → AI Chat → Tool Calling）完成 — 以上 Phase 0–6 全部落在第一阶段之内；阶段定义见 `PROJECT.md` 的「Roadmap 阶段路线图」
- 路线图第四阶段的一部分提前交付 — 写操作提案（人工确认）与审计闭环，对应 Phase 4
- 当前分支：`feature/ai-system-assistant`

# Phase 0 — 工程骨架

- [x] 聚合 POM + 7 个 Maven 模块，单向依赖无环
- [x] 统一响应结构 `{ code, message, data, traceId }`、分页结构 `{ pageNum, pageSize, total, list }`
- [x] 全局异常收敛（校验失败 / 业务异常 / 唯一约束冲突 / 未预期异常），不泄漏堆栈
- [x] TraceId 全链路：MDC + 响应头 `X-Trace-Id` + 响应体 `traceId` + 日志格式
- [x] `CurrentUser` 下沉到 `guarantee-common`，断掉 `ai`/`analysis` 对 `auth` 的反向依赖
- [x] `.gitattributes` / `.gitignore` / Git 钩子与提交模板（`scripts/setup-git.ps1`、`docs/GIT_CONVENTION.md`）

# Phase 1 — 认证与系统管理

- [x] 登录 / 当前用户 / 登出（JWT + Redis 撤销列表）
- [x] 机构、部门（树形）、用户、角色、权限的管理页面与接口
- [x] 角色-权限分配（`PermissionTree.vue`）
- [x] 险种维护
- [x] 首次登录强制改密、用户新增与重置密码
- [x] 用户接口所有读路径不返回 `password` 字段
- [x] 部门配置页由分页表格改为 `机构 → 部门 → 子部门` 树（`GET /api/system/departments/tree`）
- [x] 移除用户与部门的「机构」归属概念（见 `docs/PLAN-移除用户与部门的机构归属.md`）

# Phase 2 — 订单与业务分析

- [x] 投标订单 / 履约订单列表与详情，多维过滤（订单号 / 区域 / 机构 / 险种 / 状态 / 日期 / 项目 / 企业）
- [x] 数据概览、订单趋势（月 / 日）、区域分布、险种分布、机构排行
- [x] 企业与项目管理页面
- [x] `orderType=ALL` 时对两张订单表 `UNION ALL` 后聚合，保证去重企业数 / 项目数口径正确
- [x] 订单表索引（`apply_date`、`region_code`、`org_id`、`insurance_type_id`、`(org_id, apply_date)`）保证 15 万行下的分析性能
- [x] 地区基础信息表 `sys_region` 与区域级联筛选下拉（见 `docs/REQ-地区基础信息与区域筛选下拉.md`）
- [x] 筛选下拉的选项口径与授权口径统一（见 `docs/DEC-订单筛选下拉的选项口径.md`、`docs/DEC-业务筛选下拉的授权口径.md`）

# Phase 3 — AI 业务助手

- [x] 会话与消息持久化（`ai_conversation` / `ai_message`）
- [x] SSE 流式对话（`meta` / `delta` / `tool_call` / `done` / `error`），前端用 fetch + ReadableStream 手工解析
- [x] READ 工具集：订单统计、机构 / 部门 / 用户 / 角色 / 险种查询、操作审计查询、我的工具调用、我的提案
- [x] `RecordingToolCallback` 逐次记录工具调用（含精确 `duration_ms`）到 `ai_tool_call`
- [x] `TimeSemanticParser` 中文时间语义解析 + 双保险（服务端预解析后注入 Prompt）
- [x] `business-assistant.st` Prompt 的 7 项约束（业务身份 / 数据必须来自 Tool / 不编造 / 时间转明确日期 / 推测需标注 / Tool 失败须说明 / 输出数据与来源）
- [x] 操作审计 `ai_audit_log`（`CHAT` / `TOOL_CALL` / `ERROR` + `trace_id`）与操作审计页面
- [x] 未配置 API Key 时明确报错、不编造数字
- [x] `AiToolChainIT`：Stub ChatModel 确定性验证完整 Tool 链路与 SSE 事件序列
- [ ] 真实模型（DeepSeek）端到端问答实测 —— 缺 `DEEPSEEK_API_KEY`
- [ ] 接口层细粒度 `@PreAuthorize` 拦截 —— 第一阶段只下发权限编码

# Phase 4 — AI 写操作能力（提案模式）

- [x] WRITE 工具只产出**提案**，绝不直接落库（SYS-W-08）
- [x] 提案流程：Preview → 用户确认 → 执行 → 审计
- [x] 5 个执行器：机构 / 部门 / 用户 / 角色 / 险种
- [x] 能力开关 `ai:system:write`：无此权限时不注册任何 `propose*` 工具（与域权限是「与」关系）
- [x] `ProposalClaimGuard` 防止提案被重复认领；`ProposalFailureRecorder` 记录失败
- [x] `ProposalMaintenanceJob` 清理过期提案
- [x] 敏感信息（如初始密码）走 `ai_operation_secret` 独立表，**该表不加逻辑删除字段**
- [x] `DataSourceClaimGuard` 防止模型虚构数据来源
- [x] 前端 `ProposalCard.vue` 提案卡片与确认文案（`utils/confirmText.ts`）
- [x] 助手回答的可见性与口径呈现规则（见 `docs/DEC-助手回答的可见性与口径呈现.md`）

# Phase 5 — 逻辑删除（已交付）

- [x] 18 张业务表统一新增 `is_deleted` / `deleted_at` / `deleted_by`（`ai_operation_secret` 为唯一例外）
- [x] `status` 与 `is_deleted` 保持**两个正交维度**：删除不隐式修改 `status`
- [x] 唯一键改为函数索引 `(业务键, IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))`（13 个唯一键）
- [x] 迁移脚本 `V1__logical_delete.sql`（加列 + 索引）、`V2__logical_delete_unique_keys.sql`（唯一键改造）
- [x] 绝对禁止项已遵守：未引入存储过程 / 触发器 / 函数；未改主键为雪花 ID
- [x] 后端回归 93 → **133 项全绿**；部门树改造后 **139 项全绿**
- [x] 撤除前端「显示已删除」与「恢复」入口（5 个系统管理页面），后端接口与契约保留
- [x] 见 `docs/DEC-逻辑删除设计方案.md`（v2.2）、`docs/IMPL-逻辑删除-任务书.md`、`docs/IMPL-逻辑删除-进度.md`

# Phase 6 — 本地开发体验与可观测性

- [x] 便携版 MySQL(3307) + Redis(6379) 一键启停 `scripts/start-local-env.ps1`
- [x] 前端 dev server 避开 Windows 保留端口段，改用 **5273**；后端端口经 `BACKEND_PORT` 对齐
- [x] 后端承载 `frontend/dist`，支持单端口演示
- [x] `/actuator/health` 健康检查（免登录）
- [x] 演示数据复现与重置 `scripts/reset-demo-data.ps1`

# Phase 7 — 待办 / 未验证

- [ ] **真实模型端到端验证**：配置 `DEEPSEEK_API_KEY` 后跑通「2026年第三季度投标订单有多少？」并与数据概览页交叉验证
- [ ] **容器化实测**：`docker-compose.yml` 与 `Dockerfile` 从未在容器中运行过
- [ ] **前端浏览器实测**：Vite dev server 与 SSE 客户端未在浏览器中端到端走查
- [ ] **接口层细粒度授权**：评估开启 `@PreAuthorize`
- [ ] **README 刷新**：表数量（16 → 20）、AI 能力范围（已含写操作提案）、`docs/` 索引
- [ ] **多环境配置**：当前端口 / 账号全部走环境变量默认值，尚无 profile 化配置

# 路线图 · 第二阶段收尾（多 Tool / Agent / 业务分析）

> ⚠️ 本节属于**路线图的第二阶段**（见 `PROJECT.md` 的「Roadmap 阶段路线图」），
> **不是**上面的工程 Phase 0–7 —— 那套编号全部落在路线图第一阶段之内。
>
> 需求真源：`docs/REQ-助手业务分析能力阶段二收尾.md`（v1.0，2026-09-29）。
> 这里只放勾选框与工作量，细节（验收标准 / 测试 / 未决项）一律以那份需求为准。

- [ ] **M2.1 取数面补齐**（P0，≈1.5 人日）：REQ-BA-01 分布工具交叉过滤（区域 / 机构）、REQ-BA-02 趋势工具 `queryOrderTrend`、REQ-BA-07 计划式提示词（一轮内查齐）
- [ ] **M2.2 护栏与降级**（P0，≈1 人日）：REQ-BA-06 轮次 / 单轮调用 / 超时预算、REQ-BA-08 失败与缺维度降级话术、REQ-BA-10 越界拒绝清单
- [ ] **M2.3 主体维度**（P1，≈2.5 人日，待 Q-BA-01 确认）：REQ-BA-03 企业维度、REQ-BA-04 项目维度（含 4 条新聚合 SQL + IT）
- [ ] **M2.4 可观测与验收**（P0，≈1 人日）：REQ-BA-11 每次分析的轮次 / 调用数 / token / 耗时日志、REQ-BA-12 黄金问题集 15 条 + 真机冒烟脚本
- [ ] 第二阶段 DoD 复核：多 Tool 覆盖订单汇总 / 分布 / 趋势 / 企业 / 项目 五个面；受约束 Agent 的预算可判定；黄金问题集 100% 通过
- [ ] 收尾后宣布第二阶段完成，并把 Current Milestone 推进到第三阶段（RAG → 业务知识）

