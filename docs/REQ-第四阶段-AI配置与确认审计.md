# 需求文档：路线图第四阶段 —— AI 配置 → Human Confirmation → Audit

> 版本：v1.0（2026-09-30）
> 上游：`PROJECT.md` 的「Roadmap 阶段路线图」第四阶段；`docs/REQ-系统管理助手能力.md`（写操作提案的能力来源）
> 相关：`docs/REQ-操作审计页面.md`（审计页已交付）、`docs/DEC-助手回答的可见性与口径呈现.md`（事实内容由服务端产出）、`docs/REQ-第三阶段-RAG业务知识.md`（知识与配置的分工）
> 一句话：把"改模型 / 改提示词 / 关能力"从**改代码 + 重新打包 + 重启**变成**页面可配置、变更走确认、全程可审计、可一键回滚**。

---

## 0. 先看结论：需要你拍板的 6 件事

> **决策状态（2026-09-30）：以上（含补充未决项 Q-CFG-07~09）已由用户确认"按建议执行"，编号即拍板结论，实现期间不再逐项确认。**
> 据此：**第一版不给助手写配置的能力**（Q-CFG-07），配置变更只走页面渠道（表单 + 二次确认 + `CONFIG_UPDATE` WEB 审计）；
> `ConfigProposalExecutor` 不在本期实现，作为后续扩展点登记。
> 实现若发现建议不可行，**先记录不打断**，等三个阶段全部完成后统一讨论。

| 编号 | 问题 | 建议 | 影响面 |
|---|---|---|---|
| Q-CFG-01 | 配置存哪 | **新建 DB 表**（`ai_config_item` / `ai_prompt_version`）。现状 20 张表里**没有任何配置表**（`sys_config\|sys_param\|sys_setting` 全仓 0 命中），落库是从零新建 | 决定表数（本阶段 +2）；决定多实例一致性方案 |
| Q-CFG-02 | 怎么生效 | **内存快照 + DB 版本号轮询（≤30s）**；**不引入** Nacos/Apollo/Spring Cloud Config（PROJECT.md 未排期"配置中心"，且当前 `@RefreshScope`、`spring-cloud` 全仓 0 命中） | 决定"是否需要重启"这一 DoD 是否可达 |
| Q-CFG-03 | 提示词能不能在线编辑 | **能，但必须版本化（草稿 → 发布 → 回滚）+ 发布前过评测门禁**。提示词现在**每请求重读** classpath 文件（唯一的免重启先例），缺的是"运行期可写 + 有版本" | 决定是否新增 1 张版本表与一门评测门禁 |
| Q-CFG-04 | API Key 能不能在后台填 | **不允许明文入库**。库里只存**引用名**（环境变量名 / 密钥服务 key），值仍来自环境；库中只记"是否已配置" | 决定安全边界与页面形态 |
| Q-CFG-05 | 能力开关的粒度 | **工具组开关 + 写能力总开关 + 预算参数**；**不做**"按用户/角色"的开关（那是权限码的职责）。开关与权限码是**与**关系（沿用 `AiToolRegistry` 的 fail-closed 语义） | 决定注册矩阵与测试面 |
| Q-CFG-06 | 配置变更是否也走提案确认 | **是**。红线明确"写操作必须走 AI Plan → Permission Check → Preview → User Confirmation → Execute → Audit"，改模型/改提示词/关工具都是写操作；危险项二次确认 | 决定是否新增第 6 个执行器与审计动作 |

> 编号说明：本文件使用 `REQ-CFG-xx` / `AC-CFG-xx` / `TEST-CFG-xx` / `Q-CFG-xx` / `RK-CFG-xx` 前缀。
> **不要**使用连续 `AC-xx`：已用到 `AC-71`，且 `AC-42` 已被两处占用（`docs/REQ-操作审计页面.md:307` 与 `docs/REQ-登录安全与令牌生命周期加固方案.md:665`）。

---

## 1. 背景与问题

### 1.1 现状（可核对，2026-09-30 实测）

| 能力 | 现状 | 证据 |
|---|---|---|
| ② Human Confirmation | **已提前交付**：`ProposalService` 状态机 7 态（PENDING → EXECUTING → EXECUTED/FAILED ∥ REJECTED/EXPIRED/INVALIDATED）、**15 分钟 TTL**、条件 UPDATE 防重复确认、5 个执行器（机构/部门/用户/角色/险种）、`ProposalSecretStore`（AES-256-GCM，过期拒绝解密）、前端 `ProposalCard.vue`（危险动作二次确认 + 倒计时） | `ProposalService.java:32-39/58/267-363`；`AiOperationProposalMapper.xml:108-111`；`ProposalCard.vue:37/43-67/153-184` |
| ③ Audit | **已提前交付**：`ai_operation_audit`（按月分区 `p202601`…`p202812` + `pmax`、复合主键 `(id, operated_at)`、写入前脱敏、8KB 截断、`source=AI/WEB`、90 天查询护栏）、审计页（筛选/字段级 diff/可回溯 traceId）、自查工具 | `schema.sql:440-508`；`OperationAuditService.java:45/116-168`；`docs/REQ_操作审计页面.md`（AC-38~AC-42） |
| ① AI 配置（模型） | **未做**。AI 相关配置**只有 4 个键**：`spring.ai.openai.api-key`（`${DEEPSEEK_API_KEY:not-configured}`）、`base-url`、`chat.model`（`${DEEPSEEK_MODEL:deepseek-chat}`）、**`chat.temperature: 0.2`（字面量，非环境变量）**。无 `max-tokens` / `timeout` / `top-p` / 多模型 / fallback | `application.yml:57-65`；`application-prod.yml` **无任何 AI 键**（prod 完全继承本地默认） |
| ① AI 配置（提示词） | 提示词是 **classpath 资源、每请求重读**（`BusinessAssistantPrompt.loadTemplate()` 无缓存），**改文件不需要重启**——但它打包在 jar 内，**运行期不可写**，改一句仍要走 PR + 重新打包 | `BusinessAssistantPrompt.java:26-38`；`AiChatService.java:315` |
| ① AI 配置（能力开关） | **只有权限码**：`ai:chat` / `ai:system:query` / `ai:system:write` / `ai:debug:view`；`AiToolRegistry` 按权限**注册期裁剪**（写工具必须含 `ai:system:write`，为 **AND**；域动作权限为 **OR**；权限为空 fail-closed）。改权限码需**重启后端** | `AiToolRegistry.java:73-116/189-211`；`PermissionCatalog.java:72-78`；`docs/REQ-用户管理新增与修改.md:273` |
| 预算参数 | **硬编码**在 `AiChatService`：`HISTORY_LIMIT=20`、`MAX_TOOL_ROUNDS=4`、`MAX_TOOL_CALLS_PER_ROUND=12`、`SOFT_TIMEOUT_MS=60_000`；工具级超时在 `BoundedToolCallback`，也不来自配置 | `AiChatService.java:89/92/116/126`；`BoundedToolCallback.java:116-143` |
| 配置类与热刷新 | **无任何 AI `*Properties` 类**（`guarantee-ai` 只有 5 处 `@Value`）；**无** `@RefreshScope`；pom 中 **无** `spring-cloud\|nacos\|apollo`；actuator 只暴露 `health,info` | 全仓 grep 实测 |
| 运行期配置后台先例 | **没有**。15 个前端页面里没有任何"系统设置 / AI 配置"页。唯一的"运行期可变"先例是**主数据**（险种启停 `PATCH /{id}/status`、角色授权、会话踢出），它们走业务表 + API + 权限码，并写 WEB 审计 | `frontend/src/views/**`；`InsuranceTypeController.java:90-91`；`WebAuditor.java:51-63` |

### 1.2 问题：能力已经很强，但"调不动"

**问题 A：任何 AI 行为调整都要走一次发布。**
换模型、调温度、加 max-tokens、改提示词措辞、临时关掉某个工具——现状全部需要**改代码或改 yml → 重新打包 → 重启**。阶段三（知识）与阶段五（评测）都要求高频迭代提示词，这个成本会成为瓶颈。

**问题 B：提示词的"免重启"是巧合，不是能力。**
每请求重读 classpath 只是"读文件"，改的仍是 jar 内资源。没有版本、没有草稿/发布、没有回滚、没有 diff。

**问题 C：能力开关语义混在权限码里。**
`ai:system:write` 既是"这个人能不能让 AI 改数据"的**授权**，又被当成"系统的写能力开不开"的**开关**。两者本应正交：前者按人，后者按环境（比如演示环境整体关闭写能力）。现状要关写能力只能回收所有人的权限码并重启。

**问题 D：配置变更没有审计。**
第四阶段的审计已经覆盖"谁把某个用户改了名"，但**改配置这件事本身**（谁把 temperature 从 0.2 调到 0.8、谁换了模型、谁把提示词第 39 条删了）在当前形态下是"改代码提交"，落不进统一审计。

**问题 E：三个既有缺口（不是新需求，但属于本阶段 DoD 的收口）**
1. **`target_fingerprint` 写入了但执行期从不比对**（T-09 未闭环）：预览与确认之间目标被改动，确认仍会执行——"人工确认确认的是当时看到的东西"这一保证不完整；
2. **审计归档只有设计没有执行体**：`purgeBefore` / `deleteBefore` 全仓无调用点，`ProposalMaintenanceJob` 只做容量巡检告警，没有 `DROP PARTITION` 任务或脚本；
3. **导出/回滚手段缺失**：配置一旦在线可改，就必须有"改坏了 1 分钟内回到上一版"的能力。

### 1.3 本需求的目标（第四阶段 DoD）

> **① 可配置**：模型参数、提示词、能力开关、预算参数可在页面上调整，**不重新打包、不重启**即生效；
> **② 可确认**：所有配置变更走 `提案 → 人工确认 → 执行 → 审计`，危险项二次确认；
> **③ 可审计**：每次配置变更记录**谁、何时、改了什么（before → after）、按哪一版**，密钥类只记"是否变化"；
> **④ 可回滚**：配置有版本、可回滚，误改后可在一个明确的时间窗内恢复；
> **⑤ 收口**：确认链路的指纹比对缺口闭环，审计归档有可执行体（或明确登记为运维手工项）。
>
> 判据从严（沿用 `PROJECT.md:299`）：没有证据（测试 / 真机 / 产物）就不算完成。

---

## 2. 范围

### 2.1 In Scope

1. 配置模型与存储（配置项表 + 提示词版本表），含**类型/范围/默认值/是否危险**的元数据；
2. 运行期读取：内存快照 + 版本号，改配置**不重启**生效；
3. 提示词版本化：草稿 / 发布 / 回滚 / diff，回答与审计可绑定 `promptVersion`；
4. 能力开关：工具组启停、写能力总开关、预算参数（轮次 / 单轮调用 / 软超时 / 结果上限）；
5. 配置变更的提案与审计：新增 `AiConfigProposalExecutor` + `CONFIG_UPDATE` 审计动作 + 危险项二次确认；
6. 权限与菜单：新增权限码与"系统管理 → AI 配置"页（模型 / 提示词 / 能力开关 / 变更历史）；
7. 启动期校验与故障降级（配置非法 → 用默认值 + 显式告警，绝不静默）；
8. 收口项：`target_fingerprint` 执行期比对闭环；审计归档执行体（脚本或定时任务，二选一，见 Q-CFG-08）。

### 2.2 Out of Scope（本阶段明确不做）

| 不做 | 原因 |
|---|---|
| **多模型路由 / 负载均衡 / 自动降级到备用模型** | `PROJECT.md:57` 明确"多模型路由 = 尚未排期"。"模型可配置"指**改哪个模型**，不是**同时用多个模型** |
| 引入配置中心（Nacos / Apollo / Spring Cloud Config） | 未排期；当前无 Spring Cloud 依赖。用 DB + 版本号即可满足单实例/多实例最终一致 |
| 提示词的 A/B 实验平台、灰度按用户分流 | 评测与灰度属第五阶段；本阶段只做"版本 + 回滚" |
| API Key 明文入库 / 在页面回显 | 安全红线（Q-CFG-04）。只允许引用环境变量或密钥服务 |
| 让 AI 直接改配置而不经确认 | 违反 `AI Plan → … → Audit` 红线 |
| 知识条目的在线编辑 | 属第三阶段（`docs/REQ-第三阶段-RAG业务知识.md` 的 REQ-RAG-09，P1）。本阶段只提供"承载机制"，不替第三阶段做知识编辑 |
| JVM / 数据源 / 日志级别等非 AI 配置的在线化 | 超出"AI 配置"边界；风险与收益不对称 |
| 阶段二 M2.3（企业 / 项目维度） | 独立排期项，不顺手做掉 |

### 2.3 红线（不因本需求放松）

1. **写操作必须走确认与审计**：配置变更也是写操作，禁止任何"直接改库/直接调接口即生效"的后门（含运维脚本）。**唯一例外**：启动时的真源/默认值装载（无变更语义）。
2. **AI 侧禁止直接访问数据库、禁止生成 SQL、禁止操作 Mapper**：配置工具同样遵守 `Tool → Service → Mapper`。
3. **能力对齐原则**（`C_manual = C_ai`）：AI 能改的配置，页面上必须也能改；页面上有而 AI 没有的配置项不违规，但**不得**让 AI 拥有页面没有的配置能力。
4. **密钥不落库、不回显、不进日志**：库里只存引用名与"是否已配置"。
5. **权限与开关是"与"关系**：开关关闭时，即使有权限也不注册该工具（fail-closed 语义不变）。
6. **配置错误不得导致"静默降级为编造"**：模型不可用/配置非法时，必须明确报错，不编造数字（沿用"未配 Key 时明确报错"的既有行为）。

---

## 3. 术语与角色

| 术语 | 含义 |
|---|---|
| 配置项 | 一条可独立读写的键值配置（键、类型、值、默认、范围、是否危险、生效方式） |
| 快照 | 进程内缓存的一份配置视图；读配置不查库 |
| 版本号 | 配置全集自增版本；用于多实例间判断"我这份快照是否过期" |
| 草稿 / 发布 | 提示词的状态：草稿可编辑、发布才参与运行；已发布版本不可改（只能新建版本） |
| 回滚 | 把生效版本指回某个历史版本（产生一次新审计，不修改历史版本内容） |
| 能力开关 | 与权限码正交的系统级开关（工具组启停、写能力总开关、预算参数） |
| 危险配置 | 改变模型、替换提示词、关闭审计/写能力相关开关等（页面二次确认 + 提案确认卡双重提示） |
| 收口 | 既有交付（② ③）中未闭环的两处缺口（指纹比对、归档执行体）的补齐 |

| 角色 | 关注点 |
|---|---|
| 超级管理员 | 改配置、看变更历史、回滚 |
| 运营负责人 | 是否需要开写能力、提示词措辞怎么改 |
| 开发 | 发布节奏是否变快；出问题能否立刻回到上一版 |
| 审计 | 谁在什么时候把哪一项从什么改成了什么；回答用的是哪一版提示词 |

---

## 4. 用户故事

| # | 角色 | 故事 | 现状 | 本阶段 |
|---|---|---|---|---|
| US-1 | 管理员 | 我想把温度从 0.2 调到 0.4，不想重启服务 | ❌ 字面量在 yml 里 | REQ-CFG-01 |
| US-2 | 运营 | 我想改一句提示词的措辞，并且能先看 diff、不满意就回滚 | 🟡 改文件不用重启，但要重新打包、无版本 | REQ-CFG-02 |
| US-3 | 管理员 | 演示环境我想整体关掉"让 AI 改数据"的能力，但不动任何人的权限 | ❌ 只能回收权限码 + 重启 | REQ-CFG-03 |
| US-4 | 审计 | 我要能查到"谁把模型换成了什么、谁删了提示词哪一条" | ❌ 这类变更是代码提交，不进统一审计 | REQ-CFG-04 / 05 |
| US-5 | 管理员 | 我改错了配置，想 1 分钟内回到上一版 | ❌ 无版本概念 | REQ-CFG-06 |
| US-6 | 开发 | 配置非法时我希望**明确报错**，而不是静默用错值跑 | 🟡 未配 Key 时已明确报错，其余无校验 | REQ-CFG-07 |
| US-7 | 审计 | 我要知道这次回答是基于哪一版提示词 | ❌ 未记录 | REQ-CFG-02 / 05 |
| US-8 | 审计 | 确认卡确认的前提是"我看的那个对象没被人改过" | 🟡 指纹写了但从不比对（T-09 未闭环） | REQ-CFG-09 |

---

## 5. 功能需求

### 5.1 配置底座

#### 5.1.1 REQ-CFG-01 配置项与运行期读取（P0）

| 项 | 内容 |
|---|---|
| 配置项清单（第一版） | 见 §6.2；覆盖模型（`base-url` / `model` / `temperature` / `max-tokens` / `timeout` / `max-retries`）、能力开关（工具组 ×5、写能力总开关）、预算（历史条数 / 轮次上限 / 单轮调用上限 / 软超时 / 工具结果字节上限）、知识开关（第三阶段的 `guarantee.ai.knowledge.*`） |
| 类型与校验 | `STRING` / `INT` / `DECIMAL` / `BOOLEAN` / `ENUM`；每项声明**取值范围与默认值**，写入前校验，越界给可读错误 |
| 读取方式 | `AiConfigService` 维护**内存快照**；`AiChatService` / `AiToolRegistry` / `BoundedToolCallback` 从快照取参数，不再从常量取 |
| 生效方式 | 快照带 `version`；每个请求开始前比对 DB 版本号（或 ≤30s 轮询），不一致即刷新（见 REQ-CFG-06） |
| 迁移要求 | **默认值必须与当前行为逐一对齐**（`temperature=0.2`、`MAX_TOOL_ROUNDS=4`、`MAX_TOOL_CALLS_PER_ROUND=12`、`SOFT_TIMEOUT_MS=60000`、`HISTORY_LIMIT=20`）。配置缺省时行为与改造前**完全一致** |
| 现状改造点 | `application.yml:57-65` 的 4 个键改为"默认值来源"；`AiChatService.java:89/92/116/126` 的常量改为读配置；`BoundedToolCallback` 的超时改为读配置 |

#### 5.1.2 REQ-CFG-02 提示词版本化（P0）

| 项 | 内容 |
|---|---|
| 版本模型 | 每次发布产生一个不可变版本（`version_no`、内容哈希、作者、时间、发布说明、状态）；**已发布版本只读** |
| 状态 | `DRAFT` → `PUBLISHED` → `ARCHIVED`；同一时刻只有一个 `PUBLISHED` |
| 编辑 | 页面在线编辑草稿；支持从当前发布版"另存为草稿"；保存时给出**与发布版的 diff** |
| 发布门禁 | 发布前必须过评测门禁（见 REQ-CFG-11）：黄金问题集（阶段二 15 条 + 阶段三知识类）在**确定性集**上必须全绿；真机集可作为"建议门禁"（缺 Key 时如实说明未跑） |
| 运行期读取 | `BusinessAssistantPrompt` 改为从配置取"当前发布版内容"；**保留 classpath 文件作为兜底**（DB 无发布版时用 jar 内文件，保证冷启动可用） |
| 审计绑定 | 每轮对话记录 `promptVersion`（用于回答级回溯；SSE 协议不变，写入 `ai_conversation` 或审计字段） |
| 回滚 | 回滚 = 把生效版本指回某个历史版本（新建一次审计记录，不修改历史） |
| 安全 | 提示词中的"事实由服务端产出/红线"等条款必须有**保护标记**，页面编辑时对删除保护段落给出强警告（避免误删安全约束） |

#### 5.1.3 REQ-CFG-03 能力开关（P0）

| 开关 | 粒度 | 语义 | 与权限码的关系 |
|---|---|---|---|
| 工具组启停 | `order` / `analysis` / `system` / `audit` / `proposal` 五组 | **组级**关闭后，该组工具一律不注册（对所有人） | **与**：开关开 **且** 权限满足，才注册 |
| 写能力总开关 | 全局 | 关闭后不注册任何 `propose*` | **与** `ai:system:write` |
| 预算参数 | 全局 | 历史条数 / 轮次上限 / 单轮调用上限 / 软超时 / 结果字节上限 | 与权限无关 |
| 知识层开关 | 全局 | 对应第三阶段 `guarantee.ai.knowledge.enabled` | 与权限无关 |

- 开关变更同样走提案 + 审计（REQ-CFG-04）；
- **不做**按用户/角色的开关：那是权限码的职责，两套机制叠在一起会产生"到底谁关的"这类不可判定问题；
- 关闭开关**不影响**已落库的历史数据与审计。

#### 5.1.4 REQ-CFG-04 配置变更走提案与确认（P0）

- 新增 `ConfigProposalExecutor`（`targetType="AI_CONFIG"`），复用既有 `ProposalService`：
  - 变更内容进入 `request_payload`（**不含密钥明文**）；
  - `preview_payload` 给出 before → after 的**逐项 diff**（含提示词的段落级 diff 摘要）；
  - 危险配置在确认卡上二次确认（沿用 `ProposalCard.vue` 的 `DANGEROUS_ACTIONS` 机制，新增 `CONFIG_UPDATE`）；
  - TTL 沿用 15 分钟；重复确认由既有条件 UPDATE 拦截。
- **页面直连也能改**（能力对齐原则）：页面改配置走同一条 `提案 → 确认 → 执行` 逻辑（或"页面表单 + 二次确认 + 直接落库 + 写 WEB 审计"），不得出现"页面能直接改、助手要绕一圈"或反过来的不对称。
- 执行期**必须重新校验**（沿用 `ProposalExecutor` 的 SYS-C-05 要求）：配置项在预览后被改动 → 拒绝执行并说明原因（这是 REQ-CFG-09 指纹闭环的一部分）。

#### 5.1.5 REQ-CFG-05 配置审计（P0）

- 复用 `ai_operation_audit`，新增动作 `CONFIG_UPDATE`（`source=AI|WEB`、`target_type=AI_CONFIG`、`target_id=配置键或提示词版本号`、`before_value`/`after_value`/`changed_fields`）；
- 密钥类配置项：只记录"是否发生变化"（沿用 `SensitiveFieldMasker` 的 `<changed>` 口径），**绝不记录值**；
- 提示词变更：审计记录**版本号 + 内容哈希 + diff 摘要**（正文可能超 8KB，按既有截断规则处理）；
- 回答级回溯：每轮对话可查到使用的 `promptVersion` 与配置 `version`（写入会话记录或审计，见 §6.3）；
- 审计页的"目标类型/动作"字典需同步扩充（`frontend/src/utils/auditDict.ts`），非 ADMIN 的可见性规则沿用既有白名单。

#### 5.1.6 REQ-CFG-06 生效、缓存与回滚（P0）

| 项 | 内容 |
|---|---|
| 刷新策略 | 快照 + `version`；请求开始前比对（或 ≤30s 轮询），不一致则重载。**多实例最终一致**（明确不做分布式强一致） |
| 生效边界 | 模型/温度/预算：**下一个请求**生效；提示词：**下一个请求**生效；能力开关：**下一个请求**生效（注册期裁剪） |
| 回滚 | 提示词：一键回滚到上一发布版；配置项：一键"恢复默认值"或回滚到上一版本；两者都产生审计 |
| 明确不做 | 不做"正在进行的对话中途变更配置"（一轮对话使用同一份快照，保证可解释性） |
| 变更窗口 | 从确认执行到全部实例生效 ≤ 30s（单实例为即时） |

#### 5.1.7 REQ-CFG-07 启动校验与故障降级（P0）

- 启动时读取配置并校验：非法值 → **不启动失败**，而是回落默认值 + **ERROR 级告警日志**（沿用"未配 Key 用占位值、调用时明确报错"的既有取舍）；
- 模型不可用 / Key 未配置：明确报错、不编造（既有行为，纳入回归）；
- DB 不可用：使用上一份快照 / 默认值，并告警；不得因为"读配置失败"导致整个助手不可用；
- 密钥引用缺失：页面显示"未配置"，运行时报明确错误。

#### 5.1.8 REQ-CFG-08 页面（P0）

`系统管理 → AI 配置`（新增路由 + 菜单 + 权限）：

| 页签 | 内容 |
|---|---|
| 模型 | `base-url` / `model` / `temperature` / `max-tokens` / `timeout` / `max-retries`；显示"当前生效值"与"默认值"；密钥只显示"已配置/未配置"与引用名 |
| 提示词 | 当前发布版（只读）+ 草稿编辑 + 与发布版对比 + 发布（带门禁结果）/ 回滚 / 版本历史（谁、何时、说明） |
| 能力开关 | 工具组 ×5、写能力总开关、预算参数；每项给出影响面说明（关闭后将发生什么） |
| 变更历史 | 复用审计页能力，过滤 `target_type=AI_CONFIG`；支持查看 before → after |

#### 5.1.9 REQ-CFG-09 收口：确认链路的指纹闭环（P1，但建议纳入本阶段）

- **问题**：`target_fingerprint` 已写入提案（`ProposalService.java:163-168`、`schema.sql:407`），mapper 也声明了 `updateFingerprint`，但**全仓没有任何读取/比对调用点** → 预览与确认之间目标被改动时，确认仍会执行。
- **要求**：确认执行前重新计算目标指纹并与提案中的比对，不一致 → 拒绝执行、置 `INVALIDATED`、给出可读原因（"目标在确认前已被他人修改，请重新发起"）。
- **范围**：至少覆盖配置类提案（本阶段新增）与既有 5 类业务提案；补单测 + 一条 IT。
- 若决定不纳入本阶段，必须写明理由并登记为独立待办（不得静默消失）。

#### 5.1.10 REQ-CFG-10 权限与菜单（P0）

- 新增权限码 `ai:config:view` / `ai:config:update`；ADMIN 默认拥有，其余角色默认无；
- `ai:config:update` 纳入**危险权限清单**（与 `ai:system:write` 同档：能改变系统行为面）。**该清单有两处定义，必须同步**：`frontend/src/components/PermissionTree.vue:83-89` 与 `docs/REQ-角色管理与权限分配页面.md:323-333`；
- 菜单：`系统管理 → AI 配置`，按 `ai:config:view` 过滤（前端按菜单展示，服务端仍由 `@PreAuthorize` 兜底）；
- 写接口一律 `@PreAuthorize("hasAuthority('ai:config:update')")`，不得只靠前端隐藏。

#### 5.1.11 REQ-CFG-11 评测门禁（P0，与第五阶段共用）

- 提示词发布前的门禁**不另造一套评测**：复用 `docs/TEST-助手黄金问题集.md` + `scripts/ai-golden-questions.mjs`（阶段二）与阶段三新增的知识类断言；
- 门禁分两级：**确定性集**（Stub ChatModel，无需 Key，必须全绿）与**真机集**（需 Key，缺失时如实标注"未跑"）；
- 第五阶段把门禁升级为自动化评测流水线（见 `docs/REQ-第五阶段-MCP评测与可观测.md`），本阶段只要求"发布动作能读到门禁结果"。

#### 5.1.12 REQ-CFG-12 收口：审计归档执行体（P1）

- **问题**：`ai_operation_audit` 已按月分区（`p202601`…`p202812` + `pmax`），策略是"在线 24 个月 + 归档 36 个月、到期 `DROP PARTITION`"，但 `OperationAuditService.purgeBefore` 与 `AiOperationAuditMapper.deleteBefore` **全仓无调用点**，`ProposalMaintenanceJob` 只做容量巡检告警，`scripts/` 下没有归档脚本 → **只有设计与分区，没有执行体**。
- **要求（二选一，必须在文档与 `TASKS.md` 里显式落地）**：
  - A：**运维脚本 + SOP**（导出到期分区 → 校验行数 → `DROP PARTITION`），应用进程不持有 DDL 权限（推荐）；
  - B：应用内定时任务执行 `DROP PARTITION`（需显式授权与失败告警）。
- 无论 A/B，都要有：执行前校验（分区边界与行数）、执行留痕（审计或独立日志）、失败告警；**不得**静默删除未归档数据。
- 验收：AC-CFG-12。

---

## 6. 接口与数据设计汇总

### 6.1 数据变更（本阶段 +2；按阶段顺序累计 20 → 24 张表）

- `ai_config_item`：`config_key`(唯一) / `config_value` / `value_type` / `default_value` / `min_value` / `max_value` / `enum_options` / `category`(`MODEL`/`SWITCH`/`BUDGET`) / `dangerous` / `description` / `version` / `updated_by` / `updated_at` + 逻辑删除三列；
- `ai_prompt_version`：`version_no`(唯一) / `content`(LONGTEXT) / `content_hash` / `status`(`DRAFT`/`PUBLISHED`/`ARCHIVED`) / `note` / `created_by` / `created_at` / `published_by` / `published_at` + 逻辑删除三列；
- （可选）`ai_config_version`：配置全集版本表，或直接用 `MAX(version)` 推导——**建议不建**，用 `ai_config_item.version` 的最大值作为快照版本。

> 迁移脚本沿用仓库惯例：`db/migration/V7__ai_config.sql`（**无 Flyway，手工幂等执行**，与 `V1`~`V6` 同构）。

### 6.2 配置项总表（第一版，默认值必须与当前行为一致）

| 键 | 类型 | 默认 | 范围 | 危险 | 说明 |
|---|---|---|---|---|---|
| `model.base-url` | STRING | `https://api.deepseek.com` | — | 是 | 改错即全站不可用 |
| `model.name` | STRING | `deepseek-chat` | — | 是 | 落库到 `ai_conversation.model` |
| `model.temperature` | DECIMAL | `0.2` | 0 ~ 2 | 是 | 现状是 yml 字面量 |
| `model.max-tokens` | INT | `2048` | 256 ~ 8192 | 是 | 现状未配置（框架默认） |
| `model.timeout` | INT(ms) | 由 starter 默认 | 1s ~ 5min | 是 | 现状未配置 |
| `model.max-retries` | INT | 由 starter 默认 | 0 ~ 5 | 否 | — |
| `model.api-key-ref` | STRING | `DEEPSEEK_API_KEY` | — | 是 | **只存引用名，不存值** |
| `tools.order.enabled` | BOOLEAN | `true` | — | 是 | 关掉后订单类工具全不注册 |
| `tools.analysis.enabled` | BOOLEAN | `true` | — | 否 | — |
| `tools.system.enabled` | BOOLEAN | `true` | — | 否 | 机构/部门/用户/角色/险种查询 |
| `tools.audit.enabled` | BOOLEAN | `true` | — | 否 | 审计查询工具 |
| `tools.proposal.enabled` | BOOLEAN | `true` | — | 是 | 写能力总开关 |
| `budget.history-limit` | INT | `20` | 5 ~ 50 | 否 | 现状 `HISTORY_LIMIT=20` |
| `budget.max-rounds` | INT | `4` | 1 ~ 8 | 否 | 现状 `MAX_TOOL_ROUNDS=4` |
| `budget.max-calls-per-round` | INT | `12` | 1 ~ 40 | 否 | 现状 `MAX_TOOL_CALLS_PER_ROUND=12` |
| `budget.soft-timeout-ms` | INT | `60000` | 10s ~ 5min | 否 | 现状 `SOFT_TIMEOUT_MS=60000` |
| `budget.tool-result-bytes` | INT | `16384` | 4KB ~ 64KB | 否 | 现状 16 KB |
| `knowledge.enabled` | BOOLEAN | `true` | — | 否 | 第三阶段开关 |
| `prompt.active-version` | INT | 由真源首次导入 | — | 是 | 指向 `ai_prompt_version` |

### 6.3 接口清单

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/ai/config` | `ai:config:view` | 返回全部配置项（密钥类只返回"是否已配置"+引用名） |
| POST | `/api/ai/config/change` | `ai:config:update` | 生成配置变更提案（走确认卡） |
| GET | `/api/ai/config/prompts` | `ai:config:view` | 版本历史列表 |
| GET | `/api/ai/config/prompts/{version}` | `ai:config:view` | 单版本内容 + 哈希 |
| POST | `/api/ai/config/prompts/draft` | `ai:config:update` | 保存草稿 |
| POST | `/api/ai/config/prompts/publish` | `ai:config:update` | 发布（带门禁结果校验） |
| POST | `/api/ai/config/prompts/rollback` | `ai:config:update` | 回滚到指定版本 |

**SSE 事件集合不变**：配置页不使用 SSE；确认卡仍走既有 `proposal` / `proposal_result` 事件。

### 6.4 审计字段扩展

- `action` 新增 `CONFIG_UPDATE`；
- `target_type` 新增 `AI_CONFIG`；
- 回答级回溯：`ai_conversation` 增加 `prompt_version`（或写进 `ai_audit_log` 的 `CHAT` 记录）——**二选一，建议加列**，避免审计表口径膨胀。

---

## 7. 非功能需求

| 类别 | 要求 |
|---|---|
| 性能 | 配置读取**不查库**（内存快照）；版本比对 ≤1 ms；不增加首 token 延迟 |
| 生效时延 | 单实例即时；多实例 ≤30 s 最终一致（Q-CFG-02） |
| 可用性 | DB 读配置失败 → 用上一份快照/默认值 + 告警，助手不整体不可用；模型不可用 → 明确报错、不编造 |
| 安全 | 密钥值不落库/不回显/不进日志；`ai:config:update` 属危险权限；配置变更 100% 走确认 + 审计；提示词发布有门禁 |
| 兼容 | 配置缺省时行为与改造前**逐项一致**（含默认值对齐）；既有单测 + IT 全绿；SSE 事件集合不变；不新增工具给模型（除非确实需要"AI 改配置"能力，见 §2.3-3） |
| 可维护 | 每个配置项有类型/范围/默认/说明；非法值给可读错误；配置项清单在本文件与代码常量之间只有一处真源（建议由 `AiConfigCatalog` 统一定义） |
| 可回滚 | 任一配置变更可在 1 分钟内回滚（提示词一键回滚；配置项恢复默认） |

---

## 8. 测试要求

| 编号 | 类型 | 内容 |
|---|---|---|
| TEST-CFG-01 | 单测 | 配置项校验：类型/范围/枚举/默认值；非法值给可读错误；缺省时行为与常量版一致 |
| TEST-CFG-02 | 单测 | 快照与刷新：版本号变化触发重载；请求内使用同一快照；并发读安全 |
| TEST-CFG-03 | 单测 | 工具注册矩阵：开关 × 权限 的 2×2 组合（含开关关闭时 fail-closed） |
| TEST-CFG-04 | 单测 | 提示词版本机：草稿不可运行、发布后生效、已发布版本不可改、回滚产生新审计 |
| TEST-CFG-05 | IT（真实库） | 配置变更提案全链路：创建 → 确认 → 执行 → 审计（before/after 正确、密钥类只记"是否变化"） |
| TEST-CFG-06 | IT | 生效性：改 temperature/模型名 → **不重启**，下一个请求的模型调用参数已变（以 Stub/断言的可观测点为准） |
| TEST-CFG-07 | 单测 + IT | 指纹闭环（收口项）：目标在确认前被改动 → 拒绝执行并置 `INVALIDATED` |
| TEST-CFG-08 | 回归 | `mvn -DskipITs test` 全绿 + `mvn verify` 既有 IT 全绿；阶段二黄金问题集 15/15 不回归；配置缺省时行为与改造前一致 |

---

## 9. 验收标准

| 编号 | 验收标准（可判定） |
|---|---|
| AC-CFG-01 | 在页面上把 `temperature` 从 0.2 改为其他值，**不重启、不重新打包**，下一个请求生效（日志/落库可证） |
| AC-CFG-02 | 提示词可编辑：保存草稿 → 看到与发布版 diff → 发布 → 生效；已发布版本不可修改 |
| AC-CFG-03 | 提示词可回滚：回滚到上一版后，下一个请求使用旧版本内容，且产生一条审计 |
| AC-CFG-04 | 关闭"写能力总开关"后，任何账号（含 ADMIN）的助手都不再注册 `propose*` 工具；重开后恢复；权限码本身未被修改 |
| AC-CFG-05 | 每次配置变更可在审计页查到：谁、何时、哪一项、before → after；密钥类只显示"是否变化" |
| AC-CFG-06 | 页面与助手的配置能力对齐：页面上能改的配置项，助手也能（经确认卡）；反之亦然，无单边能力 |
| AC-CFG-07 | 非法配置（越界 / 类型错误 / 未知键）被拒绝并给出可读错误；DB 中已有非法值时启动回落默认 + ERROR 告警，助手仍可用 |
| AC-CFG-08 | 配置缺省（空表）时，行为与改造前逐项一致（`temperature=0.2`、轮次 4、单轮 12、软超时 60s、历史 20） |
| AC-CFG-09 | 每轮对话可回溯到所使用的 `promptVersion` 与配置版本 |
| AC-CFG-10 | 提示词发布门禁：确定性黄金问题集全绿才允许发布；真机集缺失时页面明确标注"未跑" |
| AC-CFG-11 | （收口项）确认执行前做指纹比对：目标被改动则拒绝执行并置 `INVALIDATED`，给出可读原因 |
| AC-CFG-12 | （收口项）审计归档有可执行体（定时任务或脚本），或在本文件与 `TASKS.md` 中显式登记为运维手工项并给出 SOP |

---

## 10. 实施计划与里程碑

| 里程碑 | 内容 | 依赖 | 工作量 |
|---|---|---|---|
| **M4.1 配置底座（P0）** | REQ-CFG-01 配置项模型 + 快照读取、REQ-CFG-06 刷新、REQ-CFG-07 启动校验与降级；把 `AiChatService` / `BoundedToolCallback` 的常量改为读配置 | 无 | 2 人日 |
| **M4.2 确认与审计（P0）** | REQ-CFG-04 配置提案 + `ConfigProposalExecutor`、REQ-CFG-05 审计扩展、权限码 `ai:config:view/update`、`auditDict` 扩充 | M4.1 | 1.5 人日 |
| **M4.3 提示词版本化与页面（P0）** | REQ-CFG-02 版本机 + 草稿/发布/回滚、REQ-CFG-08 页面四页签、REQ-CFG-10 权限与菜单、REQ-CFG-11 门禁接入 | M4.1、M4.2 | 2.5 人日 |
| **M4.4 收口与回归（P1）** | REQ-CFG-09 指纹闭环、REQ-CFG-12 归档执行体（或 SOP）、全量回归 + 真机走查 | M4.2 | 1.5 人日 |
| **本阶段合计** | | | **≈ 7.5 人日**（P0 约 6 人日 + 收口 1.5 人日） |

> 建议顺序：**M4.1 → M4.2 → M4.3 → M4.4**。
> 与第三阶段的关系：**提示词迁移（第三阶段 M3.3）应在本阶段 M4.3 之前完成**，否则要在"在线可编辑"的新机制上再做一次内容迁移。
> 与第五阶段的关系：M4.3 的发布门禁直接复用第五阶段的评测流水线；第五阶段的成本/轮次指标依赖本阶段把预算参数配置化。

---

## 11. 风险与应对

| 编号 | 风险 | 应对 |
|---|---|---|
| RK-CFG-01 | 在线改提示词引入行为回归（真机已积累 30+ 条事故条款，边界极敏感） | 发布门禁（确定性黄金问题集全绿）+ 段落级 diff + 保护标记 + 一键回滚；**先在测试环境发布验证** |
| RK-CFG-02 | 配置变成"绕过权限的后门"（改开关让无权限的人也能写） | 开关与权限码是**与**关系；开关只能"更严不能更松"（关掉一切，不能开出不存在的权限）；越权测试纳入 AC |
| RK-CFG-03 | 多实例配置不一致导致"同一问题两种行为" | 快照 + 版本号 + ≤30s 轮询；回答级记录配置版本，出现异常可定位到实例 |
| RK-CFG-04 | 密钥泄漏（页面回显 / 日志打印 / 审计落值） | 只存引用名；审计只记"是否变化"；日志过滤器覆盖配置读取路径；AC-CFG-05 验收 |
| RK-CFG-05 | 配置读取失败导致助手整体不可用 | 上一份快照 + 默认值兜底；只有"模型不可用"才明确报错（且不编造） |
| RK-CFG-06 | DB 里配置被**运维直连 SQL** 改掉，绕过确认与审计 | 明确写入规范（沿用逻辑删除方案的"数据库直连操作规范"）：生产只允许经页面/提案；页面提供"配置是否与审计一致"的自查（可选） |
| RK-CFG-07 | 收口项（指纹闭环）触及既有提案链路，可能影响已交付的 5 类提案 | 单独里程碑 + 全量提案 IT 回归；不一致时按"拒绝执行并说明"处理，不静默放行 |
| RK-CFG-08 | `PROJECT.md:57` 的"多模型路由尚未排期"被误读为本需求可做多模型 | 本文件 §2.2 明确排除；模型配置是"换成哪一个"，不是"同时用多个" |

---

## 12. 未决项

| 编号 | 问题 | 建议 | 影响 |
|---|---|---|---|
| Q-CFG-01 | 配置存储形态（DB 表 / 外部文件 / 配置中心） | **DB 表**；配置中心明确不做 | 表数 20 → 22 |
| Q-CFG-02 | 生效方式（版本轮询 vs 配置中心推送） | 版本轮询 ≤30s | 决定"不重启"可否验收 |
| Q-CFG-03 | 提示词是否允许在线编辑 | **允许**，但必须版本化 + 门禁 + 回滚 | 决定 M4.3 工作量 |
| Q-CFG-04 | API Key 是否入库 | **否**，只存引用名 | 安全边界 |
| Q-CFG-05 | 开关粒度（是否按角色/用户） | **否**，只做环境级；按人靠权限码 | 影响注册矩阵 |
| Q-CFG-06 | 配置变更是否走提案确认 | **是**（红线） | 决定是否新增执行器 |
| Q-CFG-07 | 是否让助手也能改配置（新增 `proposeConfigChange` 工具） | **建议第一版不给助手写配置的能力**：页面是主通道，避免"让 AI 改 AI 的行为"放大风险。若给，必须走同一确认链路（Q-CFG-06） | 决定工具数量与风险面 |
| Q-CFG-08 | 审计归档执行体：定时任务 vs 运维脚本 | 建议**脚本 + SOP**（避免应用进程持有 `DROP PARTITION` 权限）；若不落地，必须在 `TASKS.md` 显式登记 | 决定收口范围 |
| Q-CFG-09 | 收口项 REQ-CFG-09（指纹闭环）是否纳入本阶段 | 建议**纳入**（它是"人工确认"语义完整性的必要条件） | ±1 人日 |

---

## 13. 需求追踪矩阵

| 需求 | 用户故事 | 验收标准 | 测试 |
|---|---|---|---|
| REQ-CFG-01 配置项与读取 | US-1 | AC-CFG-01 / 08 | TEST-CFG-01 / 02 / 06 |
| REQ-CFG-02 提示词版本化 | US-2 / US-7 | AC-CFG-02 / 03 / 09 | TEST-CFG-04 |
| REQ-CFG-03 能力开关 | US-3 | AC-CFG-04 | TEST-CFG-03 |
| REQ-CFG-04 变更走确认 | US-4 | AC-CFG-05 / 06 | TEST-CFG-05 |
| REQ-CFG-05 配置审计 | US-4 | AC-CFG-05 / 09 | TEST-CFG-05 |
| REQ-CFG-06 生效与回滚 | US-5 | AC-CFG-01 / 03 / 08 | TEST-CFG-02 / 06 |
| REQ-CFG-07 启动校验与降级 | US-6 | AC-CFG-07 | TEST-CFG-01 |
| REQ-CFG-08 页面 | US-1 / US-2 / US-3 | AC-CFG-01 / 02 / 04 / 06 | 手工验收 + 前端 build |
| REQ-CFG-09 指纹闭环（收口） | US-8 | AC-CFG-11 | TEST-CFG-07 |
| REQ-CFG-10 权限与菜单 | US-1 / US-3 | AC-CFG-04 / 06 | 越权用例（`@PreAuthorize`） |
| REQ-CFG-11 评测门禁 | US-2 | AC-CFG-10 | TEST-CFG-08 |
| REQ-CFG-12 归档执行体（收口） | — | AC-CFG-12 | 手工验收（脚本 + SOP 演练） |

---

## 14. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-30 | v1.0 | 首版：基于当日只读实测（AI 配置仅 4 个 yml 键、temperature 为字面量、无 `*Properties` 类、无配置表、无热刷新基础设施、`@RefreshScope`/Spring Cloud 零命中、actuator 仅 health/info；② ③ 已交付的提案与审计链路可完整复用；发现 `target_fingerprint` 执行期不比对与审计归档无执行体两处缺口），给出第四阶段的范围、需求、验收与 ≈7.5 人日计划；登记 6 项待拍板（Q-CFG-01~06）与补充未决项（Q-CFG-07~09）及 8 项风险 |
