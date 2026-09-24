# 需求文档：业务分析助手新增「系统管理」操作能力

> ⛔ **部分内容已被取代 —— 请先读 [PLAN-移除用户与部门的机构归属.md](./PLAN-移除用户与部门的机构归属.md)**
>
> 阶段一重构把「机构」从**用户与部门**上彻底移除（机构是外部的出函机构，服务于订单）。
> 因此本文中凡涉及**用户/部门的机构归属**的规格均已作废：
> - 部门查询/用户查询的**入参与出参** `orgId` / `orgName` → **已删除**（部门与用户都不再有机构）
> - 部门新增的**必填参数 `orgId`** → **已删除**（`sys_department.org_id` 列已由 V4 迁移移除）
> - 用户 UPDATE 的 `deptId` → 仍可改，但 **`clearDept`（清空部门）能力已移除**：用户必须属于一个部门
> - 数据范围相关条款（SYS-P-07 ~ SYS-P-10、SYS-P-14）：阶段一采用 **O3 显式全量**
>   （用户与部门无机构 ⇒ 无法按机构收敛），分级范围将在**阶段二以权限码重建**
> - 机构（`sys_org`）自身的查询与操作能力**不受影响**，仍然有效
>
> 本文保留作为历史需求记录；实现与验收请以 PLAN 文档为准。

| 项目 | 内容 |
| --- | --- |
| 需求名称 | 业务分析助手新增系统管理域查询与操作能力 |
| 文档版本 | v1.4（回填 Q-6 审计保留期与容量估算；Q-12 / Q-13 见 v1.3） |
| 适用系统 | 智能电子保函运营管理平台（guarantee-ai-admin） |
| 涉及模块 | `guarantee-ai`、`guarantee-system`、`guarantee-auth`、`guarantee-common`、`guarantee-web`、`frontend` |
| 现状阶段 | AI 助手为「只读查询」阶段；`guarantee-system` 除险种外全部为只读接口 |
| 分期策略 | 一期（P1）只读查询；二期（P2）写操作走「权限校验→预览→人工确认→执行→审计」 |
| 需求编号规则 | `SYS-Q-xx` 查询类、`SYS-W-xx` 写操作类、`SYS-C-xx` 确认机制与前端（含机构树形）类、`SYS-P-xx` 权限类、`SYS-A-xx` 审计类、`SYS-N-xx` 提示词类、`SYS-NF-xx` 非功能类 |
| **已定稿决策** | **D-1**：`ANALYST` 获得系统管理**只读**能力（`ai:system:query` + 各域 `:view`），不含任何写权限；**D-1a（Q-11）**：`ANALYST` **不获得** `system:audit:view`，全局操作审计仅 ADMIN 可见，ANALYST 只能查自己的工具调用记录<br>**D-2**：用户写操作**只做 UPDATE / ENABLE-DISABLE / ASSIGN_ROLES**，本期不做新建用户、不做密码重置；**D-2a（Q-12）**：新建账号与密码重置**确定单独立项**（含密码分发与安全审批）<br>**D-3**：补充演示数据的机构层级（`parent_id` / `org_level`），使省级、市级数据范围可验证；**D-3a（Q-13）**：机构配置页**由列表改为树形**展示<br>**D-4**：审计的 `before_value` / `after_value` 对敏感字段**只记录字段名与是否变更，不记录具体值**，且该脱敏与操作者角色无关（ADMIN 同样脱敏） |
| **暂缓项** | **Q-15**（系统管理页面用户列表的明文手机号/邮箱是否同步改掩码）与 **Q-16**（是否为 ANALYST 提供机构维度受限审计视图）**本期不处理**，仅登记在 15.4 备查；两者均不影响一/二期交付 |
| **⚠️ D-2 已被部分推翻（2026-09-23，P-10）** | 本需求 D-2 的两条排除项中，**"不做新建用户"与"不做密码重置"均已失效**：用户决策改为一并交付，落地为独立立项 **P-10**，见 `docs/REQ-用户管理新增与修改.md`。现状为：**做新建用户 + 做自助改密 + 做管理员重置他人密码；仍不做"忘记密码"自助找回**（无邮件/短信通道）。**助手侧仍不支持新建与密码类操作**——这是技术约束（密码过不去提案脱敏链路，见该文档 §1.3-①），不是范围问题。阅读下文（§5.2.2 D-2、§5.2.3 用户动作表、`UserController` 不新增接口的表述）时请以此为准 |
| **保留期决策** | 审计不做"永久在线"：**在线 24 个月 + 归档 36 个月（总 5 年）**，按月分区滚动 `DROP`（D-5）。系统管理域在线 2 年仅约 14.6 万行 / 438 MB（压力档），容量估算见 **5.7.3** |

---

## 1. 背景与问题

### 1.1 现状

平台已经建成一条可用的"业务数据问答"链路，但能力边界非常明确：

| 层面 | 现状 |
| --- | --- |
| 助手入口 | `POST /api/ai/chat`（SSE 流式），前端组件 `AiCopilot.vue` |
| 工具能力 | 仅 `OrderSummaryTool` 的 `queryOrderSummary`、`getCurrentDate` 两个**只读**工具 |
| 工具注册 | `AiToolRegistry.readToolCallbacks()` 只注册 READ 工具，代码注释明确「WRITE 工具在第一阶段一律不注册」 |
| 执行链路 | `AiChatService` 自驱动 `ToolCallingManager` 循环，最多 4 轮 |
| 审计 | `ai_tool_call`（参数/结果/状态/耗时）+ `ai_audit_log`（CHAT / TOOL_CALL / ERROR） |
| 提示词 | `prompts/business-assistant.st` 第 20 条已写死：「当前阶段你拥有的工具全部是只读（READ）工具，如果用户要求修改、删除、新建数据，请说明当前阶段不支持写操作」 |
| 系统管理接口 | 机构/部门/用户/角色/权限均为**只读**；仅险种有 `create` / `update`；**没有任何 delete** |
| 权限落地 | 权限编码已写入 JWT 与 `sys_permission` 表，但**后端没有任何一处校验点**：`SecurityConfig` 注释写明"只做是否登录的判定"，`CurrentUser.Principal` 只有 `userId / username / realName / orgId`，`@PreAuthorize` 全库未使用 |

### 1.2 问题

1. 运营人员在系统管理页面（机构、部门、用户、角色、险种）遇到问题时，只能人工翻页面、逐条比对，助手无法回答"这个机构下有几个部门""analyst 角色有哪些权限"。
2. 系统配置的变更（新增机构、调整用户角色、停用险种）目前必须手工点页面，且**没有操作审计**，谁改了什么无法追溯。
3. 平台定位是"智能运营管理平台"，但助手只能看订单，形成"业务可问、系统不可问"的割裂。

### 1.3 本需求的目标

让助手在**安全边界内**覆盖系统管理域：

- 一期：能用自然语言查询机构、部门、用户、角色/权限、险种配置与操作审计日志；
- 二期：能用自然语言发起系统配置变更，且每一步都经过权限校验、变更预览、人工确认、执行、审计，**绝不允许模型直接改动数据**。

---

## 2. 范围

### 2.1 In Scope

**一期（P1）**

- 系统管理域 5 个只读工具 + 1 个操作审计日志只读工具。
- 工具层的数据权限（机构范围）过滤与字段脱敏。
- 提示词改造：新增系统管理域知识与工具使用规范。
- 查询结果的审计留痕（复用既有 `ai_tool_call`）。

**二期（P2）**

- 系统管理域写操作的「提案（Proposal）」机制，含 SSE 事件、确认卡片、单次确认接口。
- 机构、部门、角色/权限、险种的写操作工具集；**用户仅支持修改资料、启用/停用、角色分配**（D-2）。
- `guarantee-system` 侧补齐缺失的 CRUD 接口与 `@PreAuthorize` 权限校验点。
- 操作级审计（`ai_operation_audit`）：谁、通过什么渠道、改了什么、前后值、结果。
- 助手内操作审计日志的查询与回溯。

### 2.2 Out of Scope

- 订单/企业/项目等**业务数据**的写操作（本需求只做系统管理域；业务域写操作另立需求）。
- **用户新建与密码重置**（D-2）：涉及初始密码生成与线下分发、密码类操作的高保障渠道，本期不做，见 15.3 Q-12。
- 自然语言驱动的权限模型自动生成（例如"帮我建一个只看江苏的运营角色"→ 自动生成权限组合）：一期不做，二期仅允许在既有权限码中选择。
- 批量数据导入导出（Excel 导入用户等）。
- 多租户隔离改造。
- 移动端适配。

### 2.3 明确不做的事（红线）

| 编号 | 红线 |
| --- | --- |
| R-01 | 不允许模型生成或执行任何 SQL（沿用现有铁律）。 |
| R-02 | 不允许写工具在**未经**用户显式确认的情况下执行（一期完全不注册写工具）。 |
| R-03 | 不允许工具返回密码散列、完整手机号、完整邮箱、令牌等敏感字段。 |
| R-04 | 不允许删除机构、部门、用户、角色、权限等主数据（**一期二期都不做物理删除**，只做停用；用户提供"停用/启用"而非"删除"）。 |
| R-05 | 不允许助手修改自己的会话权限、角色绑定关系中的超级管理员角色（`ADMIN`）。 |
| R-06 | 不允许一次提案影响超过 50 条记录（超限需人工走页面操作）。 |

---

## 3. 术语与角色

| 术语 | 定义 |
| --- | --- |
| Tool | Spring AI 工具，`@Tool` 注解方法，助手唯一的操作入口 |
| READ Tool | 只读查询工具，执行后不产生任何数据变更 |
| WRITE Tool | 写操作工具，**不直接落库**，只产出「提案」 |
| 提案 Proposal | 一次待确认的变更申请，含目标实体、参数、前后值对比、影响面、有效期 |
| 确认卡 Confirmation Card | 前端渲染提案的交互卡片，含「确认执行 / 拒绝」 |
| 数据范围 | 当前登录用户**有权查看的机构集合**（见 6.2） |
| 操作审计 | 记录"谁在何时通过什么渠道把什么数据从什么值改成了什么值" |

| 角色 | 角色码 | 权限特征 |
| --- | --- | --- |
| 超级管理员 | `ADMIN` | 全部权限（含角色/权限管理、操作审计） |
| 运营人员 | `OPERATOR` | 除角色/权限管理与操作审计外的全部权限（可写机构/部门/险种与用户，不可写角色） |
| 数据分析师 | `ANALYST` | 分析、项目、企业、订单、首页、`ai:chat`，**新增**系统管理只读（D-1）；**不含**操作审计（D-1a） |
| 只读用户 | `VIEWER` | 所有 `:view` 权限 + `ai:chat`（**不含** `system:audit:view`） |

> **D-1 决策说明**：`ANALYST` 获得 `ai:system:query` 及机构/部门/用户/角色/险种的 `:view` 权限，用于回答"某省有多少机构""analyst 角色有哪些权限"这类画像类问题。`ANALYST` **不获得任何写权限、不获得 `ai:system:write`**。
>
> **D-1a 决策说明（原 Q-11）**：`ANALYST` **不获得** `system:audit:view`。原因是数据范围机制对审计表不成立——审计目标中的 ROLE / PERMISSION 类记录**没有机构归属**，无法用 `org_id IN (...)` 收敛：放行则跨省可见、过滤则结果残缺且会误导。同时审计的字段级前后值快照会**绕过** SYS-P-11 对 ANALYST 的字段收窄。作为替代，ANALYST 可查询**自己**的 AI 工具调用记录（`queryMyToolCalls`，见 SYS-Q-06a），该能力不依赖 `system:audit:view`。
>
> 为控制敏感面，`ANALYST` 调用 `queryUser` 时返回字段需再收窄一档（不含 `phone`/`email`/`lastLoginAt`，仅返回账号、姓名、机构、部门、角色、状态），见 SYS-P-11。

---

## 4. 用户故事

### 4.1 一期：查询

| 编号 | 角色 | 用户故事 |
| --- | --- | --- |
| US-Q-01 | 运营人员 | 我想问"浙江省有几个保函运营机构、分别有多少部门"，这样我不用逐页翻机构配置。 |
| US-Q-02 | 运营人员 | 我想问"业务受理部在哪些机构下有"，用来核对部门铺设情况。 |
| US-Q-03 | 管理员 | 我想问"哪些用户三个月没登录过"，用来做账号清理。 |
| US-Q-04 | 管理员 | 我想问"analyst 这个角色有哪些权限"，用来回答权限咨询。 |
| US-Q-05 | 运营人员 | 我想问"现在的投标保函费率是多少"，用来核对报价口径。 |
| US-Q-06 | 管理员 | 我想问"最近 7 天 AI 助手做过哪些工具调用、有没有失败的"，用来排查问题。 |
| US-Q-06a | 数据分析师 | 我想问"我最近用过哪些查询工具、有没有失败的"，用来核对自己的分析口径（**只看自己**，不需要全局审计权限）。 |
| US-Q-07 | 管理员 | 我想问"最近 30 天系统里有哪些配置被改动过"，用来做变更复盘。（**仅 ADMIN**，ANALYST 无此可见性） |

### 4.2 二期：操作

| 编号 | 角色 | 用户故事 |
| --- | --- | --- |
| US-W-01 | 管理员 | 我想说"把 user0123 停用"，助手给出确认卡，我确认后生效。 |
| US-W-02 | 管理员 | 我想说"给 user0123 增加 ANALYST 角色"，助手展示"当前角色 → 变更后角色"对比，我确认后生效。 |
| US-W-03 | 运营人员 | 我想说"新增一个广东省第 2 保函运营机构"，助手回显全部待填字段让我确认。 |
| US-W-04 | 运营人员 | 我想说"把履约保函（标准）的基准费率改成 0.013"，助手展示"原值 → 新值"后我确认。 |
| US-W-05 | 管理员 | 我说错了/"算了"，点拒绝，系统不执行，且留下拒绝痕迹。 |
| US-W-06 | 管理员 | 我在确认卡上看到"影响 37 个用户"的提示，于是改用页面操作。 |

---

## 5. 功能需求

### 5.1 一期：系统管理查询能力

#### 5.1.1 工具清单

所有工具命名沿用现有 `query*` 前缀风格，`type` 一律为 `READ`。

| 编号 | 工具名 | 覆盖实体 | 对应服务 |
| --- | --- | --- | --- |
| SYS-Q-01 | `queryOrg` | 机构（`sys_org`） | `OrgService` |
| SYS-Q-02 | `queryDepartment` | 部门（`sys_department`） | `DepartmentService` |
| SYS-Q-03 | `queryUser` | 用户（`sys_user`） | `UserService` |
| SYS-Q-04 | `queryRole` | 角色与权限（`sys_role` / `sys_permission` / `sys_role_permission`） | `RoleService`、`PermissionService` |
| SYS-Q-05 | `queryInsuranceType` | 险种（`insurance_type`） | `InsuranceTypeService` |
| SYS-Q-06 | `queryOperationAudit` | 操作审计（`ai_operation_audit`，二期也复用） | 新增 `OperationAuditService` |
| SYS-Q-06a | `queryMyToolCalls` | **自己的** AI 工具调用记录（`ai_tool_call` + `ai_audit_log`） | 新增方法于 `AiConversationService` |

> 是否把 6 个工具合并为 1 个 `querySystemEntity(entity, ...)` 泛型工具，见 Q-2。本文档按"一实体一工具"编写，理由是工具描述更精确、模型选型更稳、参数校验更严。

#### 5.1.2 `queryOrg`（SYS-Q-01）

| 项 | 要求 |
| --- | --- |
| 入参 | `keyword`（机构名称/编码模糊，可选）、`regionCode`（可选）、`orgLevel`（1/2/3，可选）、`status`（1启用/0停用，可选）、`limit`（默认 20，最大 50） |
| 出参 | `total`、`items[{id, orgCode, orgName, regionCode, regionName, orgLevel, orgLevelName, parentId, parentName, status, statusName, deptCount, userCount}]` |
| 排序 | `sortNo` 升序 |
| 权限 | 需 `system:org:view`；结果按数据范围过滤 |
| 特别要求 | 当 `keyword` 命中多个机构且用户问的是"XX 机构"，返回全量候选让模型澄清，不得只取第一条 |

#### 5.1.3 `queryDepartment`（SYS-Q-02）

| 项 | 要求 |
| --- | --- |
| 入参 | `keyword`（部门名称/编码）、`orgId`、`orgName`（由模型转 id 不可靠时允许传名称）、`status`、`limit` |
| 出参 | `total`、`items[{id, deptCode, deptName, orgId, orgName, parentId, status, statusName, userCount}]` |
| 歧义处理 | `orgName` 模糊匹配到多个机构时，返回 `ambiguousOrgs` 列表并提示模型向用户确认（不允许猜测） |

#### 5.1.4 `queryUser`（SYS-Q-03）

| 项 | 要求 |
| --- | --- |
| 入参 | `keyword`（账号/姓名模糊）、`orgId`、`deptId`、`roleCode`、`status`、`lastLoginBefore`（yyyy-MM-dd）、`neverLoggedIn`（bool）、`limit` |
| 出参 | `total`、`items[{id, username, realName, orgId, orgName, deptId, deptName, roleCodes, roleNames, status, statusName, lastLoginAt, createdAt}]` |
| **禁止返回** | `password`、`phone`（如需展示，脱敏为 `138****5678`）、`email`（脱敏为 `a***@guarantee.com`） |
| 安全 | 邮箱/手机号脱敏必须在**服务端**完成，不允许把明文交给模型 |

#### 5.1.5 `queryRole`（SYS-Q-04）

| 项 | 要求 |
| --- | --- |
| 入参 | `mode`（`ROLE` 查角色 / `PERMISSION` 查权限 / `ROLE_PERMISSION` 查角色权限映射）、`keyword`、`roleCode`、`status`、`limit` |
| 出参（ROLE） | `items[{id, roleCode, roleName, description, status, permissionCount, userCount}]` |
| 出参（PERMISSION） | `items[{id, permCode, permName, permType, path, parentId}]` |
| 出参（ROLE_PERMISSION） | `roleCode`、`permissions[{permCode, permName, permType}]` |
| 权限 | `roleCode=ADMIN` 的明细查询需要 `system:role:view`；无权限时返回明确的无权限说明，而非空结果 |

#### 5.1.6 `queryInsuranceType`（SYS-Q-05）

| 项 | 要求 |
| --- | --- |
| 入参 | `keyword`、`category`（TENDER / PERFORMANCE / OTHER）、`status`、`limit` |
| 出参 | `items[{id, typeCode, typeName, category, baseRate, baseRatePercent, minAmount, maxAmount, status, description}]` |
| 口径 | `baseRate` 同时给出小数（0.008000）与百分比（0.8%）两种表示，避免模型自行换算出错；`minAmount` / `maxAmount` 不设限时返回「不限」（库里以 0 表示），**不得念成"保额为 0"** |

#### 5.1.7 `queryOperationAudit`（SYS-Q-06）

| 项 | 要求 |
| --- | --- |
| 入参 | `startDate`、`endDate`（必填明确日期，沿用时间语义铁律）、`operatorUsername`、`targetType`、`action`、`result`（SUCCESS/FAILED/REJECTED）、`source`（AI / WEB）、`limit`（默认 50，最大 200） |
| 出参 | `total`、`items[{id, operatedAt, operatorUsername, operatorRealName, source, action, targetType, targetId, targetName, result, summary, traceId}]` |
| 约束 | 时间跨度最大 90 天；超出时返回参数错误并提示模型收窄区间 |
| 权限 | 需 `system:audit:view`（新增权限码，**按 D-1a 仅 ADMIN 持有**）；数据范围同样受限 |
| 越权行为 | **对无 `system:audit:view` 的用户（含 ANALYST）本工具不注册**，参见 SYS-Q-06a 的替代能力；工具列表中不出现，模型不会尝试调用 |

#### 5.1.8 `queryMyToolCalls`（SYS-Q-06a，D-1a 的替代能力）

| 项 | 要求 |
| --- | --- |
| 目的 | 让 ANALYST 等无审计权限的角色也能回答"我做过哪些工具调用、有没有失败"，替代全局审计可见性 |
| 入参 | `startDate`、`endDate`（可选，默认最近 7 天；明确日期格式）、`toolName`（可选）、`status`（可选 SUCCESS/FAILED）、`limit`（默认 20，最大 50） |
| 出参 | `total`、`items[{id, conversationId, toolName, toolType, status, durationMs, createdAt, resultSummary}]` |
| **强制范围** | 数据范围**固定为当前用户自己**（`user_id = 当前用户`），**不接受任何用户维度入参**，服务端从 `ToolContext` 的 `USER_ID` 取值，禁止由模型传入 |
| 字段收窄 | 不返回 `arguments` 与 `result` 原文，只返回 `resultSummary`（例如"命中 12 个机构"）；理由见 SYS-P-11 与 SYS-A-09 |
| 权限 | 仅需 `ai:chat` + `ai:system:query`，**不依赖** `system:audit:view` |
| 说明 | `queryOperationAudit`（全局）与 `queryMyToolCalls`（仅自己）是**两个不同工具**，不可用前者加参数模拟后者 |

#### 5.1.9 工具层通用要求

| 编号 | 要求 |
| --- | --- |
| SYS-Q-07 | 每个工具方法返回类型必须是**记录类（record）**，字段自带 `dataSource` 描述串（沿用 `OrderSummaryToolResult` 的做法），便于提示词第 15 条"必须说明数据来源"落地 |
| SYS-Q-08 | 所有工具只允许依赖对应域的 `Service`，严禁注入 Mapper、严禁拼 SQL（沿用 `OrderSummaryTool` 的分层约束注释） |
| SYS-Q-09 | 任何写操作的诉求进入只读工具时必须**拒绝**并返回明确文案，不得静默忽略 |
| SYS-Q-10 | 单次工具返回结果序列化后不得超过 16KB，超出时截断并在返回值中标记 `truncated=true` 与 `truncatedHint` |
| SYS-Q-11 | 空结果必须区分「无数据」与「无权限」：无权限时返回 `denied=true` + `deniedReason`，绝不允许伪装成 0 条 |

### 5.2 二期：写操作能力

#### 5.2.1 端到端流程（SYS-W-00）

```
用户自然语言
   ↓
模型选择 WRITE 工具（仅生成提案，不落库）
   ↓
工具内部：① 权限校验 ② 参数校验 ③ 领域校验 ④ 生成差异预览
   ↓ 产出 Proposal
SSE 推送 proposal 事件 → 前端渲染确认卡片 → 用户点「确认执行」
   ↓
POST /api/ai/proposals/{proposalId}/confirm
   ↓
服务端：⑤ 二次校验（提案状态 / 有效性 / 权限是否仍持有 / 目标是否被并发修改）
   ↓
⑥ 委派 guarantee-system 的 Service 执行 → ⑦ 事务提交
   ↓
⑧ 写 ai_operation_audit（前后值） + ai_audit_log + 回写提案状态
   ↓
§ 助手在会话中给出执行结果（成功/失败原因）
```

**关键设计原则：写工具只产出提案，执行必须走独立的 HTTP 确认接口。** 原因见 7.1。

#### 5.2.2 写工具清单

| 编号 | 工具名 | 动作 | 目标实体 | 所需权限码 |
| --- | --- | --- | --- | --- |
| SYS-W-01 | `proposeInsuranceTypeChange` | CREATE / UPDATE / ENABLE / DISABLE | 险种 | `system:insurance:create` / `system:insurance:update` |
| SYS-W-02 | `proposeOrgChange` | CREATE / UPDATE / ENABLE / DISABLE | 机构 | `system:org:create` / `system:org:update` |
| SYS-W-03 | `proposeDepartmentChange` | CREATE / UPDATE / ENABLE / DISABLE | 部门 | `system:dept:create` / `system:dept:update` |
| SYS-W-04 | `proposeUserChange` | UPDATE / ENABLE / DISABLE / ASSIGN_ROLES（**不含 CREATE / RESET_PASSWORD**，见 D-2） | 用户 | `system:user:update` / `system:user:disable` / `system:user:assign-role` |
| SYS-W-05 | `proposeRoleChange` | CREATE / UPDATE / ASSIGN_PERMISSIONS | 角色 | `system:role:create` / `system:role:update` |
| SYS-W-06 | `proposeUserBatchChange` | ENABLE / DISABLE / ASSIGN_ROLE（批量） | 用户 | 同 SYS-W-04，且受 R-06 数量上限约束 |

> **D-2 决策说明**：用户写操作本期只做**修改资料、启用/停用、角色分配**三类。理由：新建账号涉及初始密码生成与线下分发流程，密码重置涉及更强的安全责任链，两者都适合在机制跑顺后作为独立需求补充。因此本期新增权限码中**不含** `system:user:create` 与 `system:user:reset-password`；`UserController` 也**不新增** `POST /api/system/users` 与 `POST /api/system/users/{id}/reset-password`。
>
> **D-2a 决策说明（Q-12）**：新建账号与密码重置**确定单独立项**，本需求仅登记边界，不在本需求内交付。独立项需覆盖：初始密码生成与安全分发、密码重置的责任链与审批、以及对应的助手侧提案动作与确认卡。本期的设计已为其预留扩展点（`propose*` 工具模式、`system:user:create` / `:reset-password` 权限码位置、审计的 `SENSITIVE_MASKED` 规则），后续接入不需要重构提案机制。
>
> 若用户明确提出"建个账号"或"重置密码"，助手必须明确回复当前不支持并说明将作为独立能力提供，同时引导至系统管理页面或管理员线下处理（提示词规则 SYS-N-10）。

#### 5.2.3 写操作明细与校验规则

**险种（SYS-W-01）**

| 动作 | 必填参数 | 校验 |
| --- | --- | --- |
| CREATE | typeCode、typeName、category、baseRate；minAmount / maxAmount **选填** | `typeCode` 唯一；`baseRate` ∈ (0, 0.1]；**不填 = 不限**（不设下限 / 不设上限），只有上下限都给了具体值时才要求 `minAmount < maxAmount` |
| UPDATE | id + 至少一个字段 | 目标存在；`typeCode` 不可改；已产生订单的险种禁止修改 `category`（影响历史口径）；保额区间传 `0` = 清空成「不限」，不传 = 保持原值 |
| ENABLE/DISABLE | id | 停用前置检查：是否被启用中的订单引用（返回影响条数，需在确认卡上明示） |

**机构（SYS-W-02）**

| 动作 | 必填参数 | 校验 |
| --- | --- | --- |
| CREATE | orgCode、orgName、regionCode、orgLevel、parentId | `orgCode` 唯一；`parentId` 必须存在且层级 = 自身层级 - 1（或 0 表示顶级）；**机构创建会新增数据范围边界**，需 `ADMIN` 才可创建省级及以上 |
| UPDATE | id + 字段 | `orgCode` 不可改；`parentId` 变更需校验不能形成环 |
| ENABLE/DISABLE | id | 停用前置检查：下级机构数、部门数、用户数、订单数，全部在确认卡列明；存在启用中的下级机构或用户时**禁止**停用 |

**部门（SYS-W-03）**

| 动作 | 必填参数 | 校验 |
| --- | --- | --- |
| CREATE | deptCode、deptName、orgId、parentId | `deptCode` 唯一；`orgId` 必须存在且在数据范围内 |
| UPDATE | id + 字段 | `deptCode`、`orgId` 不可改（如需换机构请停用后新建） |
| ENABLE/DISABLE | id | 停用前置检查：部门下的用户数，需在确认卡明示 |

**用户（SYS-W-04）** —— 风险最高，规则最严（D-2 已收敛动作集合）

| 动作 | 本期 | 必填参数 | 校验与限制 |
| --- | --- | --- | --- |
| UPDATE | ✔ | id + 至少一个字段（realName / phone / email / deptId） | 目标存在且在数据范围内；`username`、`orgId` 不可改（换机构请停用后重建）；**不得修改自己的 `deptId`**；`phone` / `email` 需格式校验 |
| ENABLE/DISABLE | ✔ | id | **禁止停用自己**；**禁止停用最后一个启用状态的 `ADMIN`**（关键保护）；停用需在确认卡明示该用户未完结的 AI 会话将失效、其持有的 JWT 将被撤销 |
| ASSIGN_ROLES | ✔ | id、roleCodes | **禁止给自己增加或移除 `ADMIN` 角色**；`roleCodes` 必须来自 `sys_role` 中启用状态的角色；**禁止移除最后一个启用 `ADMIN` 的 `ADMIN` 角色**；变更后需撤销该用户全部 JWT，使其权限立即生效 |
| CREATE | ✗ 本期不做 | — | 见 D-2；助手需明确回复不支持并引导至系统管理页面（SYS-N-10） |
| RESET_PASSWORD | ✗ 本期不做 | — | 见 D-2；同上。安全考虑：密码类操作应走独立的高保障渠道 |

> 停用与角色分配都会改变用户的访问能力，因此**必须**触发令牌撤销（`TokenRevocationService`）。撤销动作需在执行结果中向操作者明示"该用户需重新登录"。

**角色（SYS-W-05）**

| 动作 | 必填参数 | 校验 |
| --- | --- | --- |
| CREATE | roleCode、roleName、description | `roleCode` 唯一；不得使用保留码 `ADMIN` |
| UPDATE | id + 字段 | `roleCode` 不可改；`ADMIN` 角色不可改 |
| ASSIGN_PERMISSIONS | roleCode、permCodes | 仅允许选择 `sys_permission` 中已存在的权限码（不接受模型自由构造）；`ADMIN` 角色不可变更；变更后撤销所有持有该角色用户的 JWT |

**批量（SYS-W-06）**

- 单次提案目标数量上限 **50**（R-06），超限返回错误并指引用户到页面操作。
- 批量提案必须逐条预检并在确认卡上分类展示：`将成功 N 条 / 将跳过 M 条（原因）`。
- 执行时逐条独立事务，失败条目记录原因，整体返回部分成功结果，不整体回滚（避免部分成功不可解释）。

#### 5.2.4 写操作通用要求

| 编号 | 要求 |
| --- | --- |
| SYS-W-07 | 写工具方法名统一以 `propose` 开头，`ToolKind` 为 `WRITE`，返回值必须包含 `proposalId`、`summary`、`expiresAt` |
| SYS-W-08 | 写工具**禁止**执行任何 DML；代码评审需能把"仅构建提案"作为硬性检查项 |
| SYS-W-09 | 提案参数必须经过 `jakarta.validation` 校验（复用 `guarantee-system` 的 DTO），不允许绕过既有的字段级校验规则 |
| SYS-W-10 | 写工具必须处理"目标不存在""名称命中多个目标"两种情况，返回 `ambiguousTargets` 让模型向用户澄清，禁止自行猜测 id |
| SYS-W-11 | 每个写工具的 `@ToolParam` 描述中必须包含示例（例如 `例如 user0123`），降低模型传参错误率 |
| SYS-W-12 | 同一会话内已存在 `PENDING` 的同目标同动作提案时，新提案产生前先提示用户存在未确认提案，避免重复点击导致重复执行 |

### 5.3 二期：确认机制（SSE / 接口 / 前端）

#### 5.3.1 新增 SSE 事件

在既有事件（`meta` / `delta` / `tool_call` / `reset` / `done` / `error`）基础上新增：

| 事件名 | 触发时机 | 载荷 |
| --- | --- | --- |
| `proposal` | 写工具成功生成提案 | `{proposalId, toolName, action, targetType, targetId, summary, changes:[{field, label, before, after}], impact, warnings[], expiresAt}` |
| `proposal_result` | 提案被确认/拒绝/过期后 | `{proposalId, status, message, auditId, executedAt}` |

> `ChatStreamEvents` 需新增 `Proposal`、`ProposalResult` 两个 record；前端 `chatStream.ts` 需新增 `onProposal`、`onProposalResult` 分支（当前 `switch` 的 `default` 会把未知事件丢到 `onUnknown`，不改会静默丢弃）。

#### 5.3.2 新增接口

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/api/ai/proposals/{id}` | 查询提案详情（刷新页面后恢复确认卡） | 提案所有者 |
| POST | `/api/ai/proposals/{id}/confirm` | 确认并执行 | 提案所有者 + 原权限 |
| POST | `/api/ai/proposals/{id}/reject` | 拒绝（需 `reason` 可选） | 提案所有者 |
| GET | `/api/ai/proposals?status=PENDING` | 列出待确认提案（用于"待办"角标） | 提案所有者 |

**`confirm` 接口行为规范**

| 编号 | 规范 |
| --- | --- |
| SYS-C-01 | 必须是 `POST`，且必须校验 `proposal.userId == 当前用户`（防越权确认） |
| SYS-C-02 | 提案状态必须为 `PENDING`；否则返回明确错误（`已执行` / `已拒绝` / `已过期`），且**绝不重复执行** |
| SYS-C-03 | 必须使用数据库条件更新（`UPDATE ... SET status='EXECUTING' WHERE id=? AND status='PENDING'`）抢占，`affectedRows=1` 才继续执行，防止双击并发 |
| SYS-C-04 | 必须复核权限：用户当前 token 中的权限码需**仍覆盖**提案所需权限码；不满足时返回 403 并提示"权限已变更，请重新发起"，同时提案置为 `INVALIDATED` |
| SYS-C-05 | 必须复核业务状态（例如"停用最后一个 ADMIN"在确认时重新判定），防止预览与执行之间数据被改动 |
| SYS-C-06 | 必须在同一事务中完成"业务执行 + 操作审计 + 提案状态回写" |
| SYS-C-07 | 执行成功后必须撤销受影响的令牌（用户/角色权限变更场景） |
| SYS-C-08 | 执行完成后必须通过 SSE 把 `proposal_result` 推回会话（若流已关闭，则通过会话消息落库，前端下次进入会话可见） |
| SYS-C-09 | 未确认提案有效期 **15 分钟**，到期由定时任务置为 `EXPIRED` |
| SYS-C-10 | 拒绝与过期都必须落审计（`result=REJECTED` / `EXPIRED`），不允许"无痕拒绝" |

#### 5.3.3 确认卡片（前端）

| 区域 | 内容 |
| --- | --- |
| 标题 | 动作中文名 + 目标实体（例：**停用用户** — user0123（张力）） |
| 变更明细 | 表格：字段 / 中文标签 / 原值 / 新值；新增动作只展示"新值" |
| 影响面 | 高亮文案，例：**该用户 3 个未结束对话将失效；该用户持有 ANALYST 角色** |
| 风险提示 | 危险动作（停用、角色变更、密码重置）使用 warning 色，并二次确认文案 |
| 有效期 | "15 分钟内有效（剩余 mm:ss）"，倒计时结束自动禁用按钮并提示过期 |
| 操作 | 「确认执行」（危险动作需二次弹窗）/「拒绝」 |
| 执行中 | 按钮 loading + 禁止重复点击；禁用期间不接受新提案卡片 |
| 结果 | 成功（绿色，含审计编号）/ 失败（红色，含失败原因与建议） |

| 编号 | 前端要求 |
| --- | --- |
| SYS-C-11 | 确认卡必须来自**后端返回的结构化载荷**，不允许前端自行拼装参数 |
| SYS-C-12 | 一期**不支持在卡片上编辑参数**（改参数=重新说一遍）；二期增强见 Q-4 |
| SYS-C-13 | 同一会话存在多张待确认卡时，必须串行处理：一张执行完成后才能操作下一张 |
| SYS-C-14 | 刷新页面或切回历史会话时，`PENDING` 提案必须能恢复渲染（依赖 GET 提案详情接口） |
| SYS-C-15 | 用户输入的参数原文与模型解析后的参数必须同时可见（让用户核对模型有没有理解错） |

### 5.4 机构配置页改为树形（D-3a / 原 Q-13）

| 编号 | 要求 |
| --- | --- |
| SYS-C-16 | `frontend/src/views/system/Orgs.vue` 由**列表**改为**树形**展示，层级依据 `parent_id`：总部（`org_level=1`）→ 省级（2）→ 市级（3），支持展开/收起 |
| SYS-C-17 | 树节点展示：机构名称、机构编码、区划、层级标签、状态；停用机构使用灰色/标记区分 |
| SYS-C-18 | 树形下的操作入口与权限联动：节点上的新增下级 / 修改 / 启用停用按钮按 `permissions` 显示（`system:org:create` / `:update` / `:disable`），无权限不渲染 |
| SYS-C-19 | 保留分页/搜索能力：树形模式下 `keyword` / `regionCode` / `status` 过滤**作用于节点并保留其祖先链**，避免过滤后出现"游离节点"；当命中节点数超过阈值时给出提示而非静默截断 |
| SYS-C-20 | 树形改造后，助手侧 `queryOrg` 的返回仍需包含 `parentId` / `parentName` / `orgLevel`，使"某机构下面有哪些机构"这类问答与页面树形保持一致口径 |
| SYS-C-21 | 兼容性：`GET /api/system/orgs` 现有分页接口与 `PageResult` 结构**保持不变**（避免影响其他调用方）。**树形需要全量数据，分页会导致树不完整**：新增扁平全量接口（建议 `GET /api/system/orgs/tree`）返回当前数据范围内的全部机构（仅必要字段），需 `system:org:view`，并设条数上限（建议 2000）与超限告警。好消息：`OrgVO`/`SysOrgMapper.voCols` **已包含 `parentId` 与 `orgLevel`**，无需改 VO/映射 |
| SYS-C-24 | `Orgs.vue` 现用 `pageOrgs`（分页，`pageSize` 默认 10）→ 树形必须改用 `SYS-C-21` 的全量接口；若沿用分页接口，机构数超过一页时树会静默缺失节点，属于必须避免的错误展示 |
| SYS-C-22 | 部门配置页（`Departments.vue`）是否同步改树形：本期**不做**，仅要求其列表能正确展示层级字段（`parent_id`）；如后续需要，按同构方案处理。<br>**（2026-09-22 已落地）** 该"后续"已实施：`Departments.vue` 按方案 A（机构为顶级概览节点）改为 `机构 → 部门 → 子部门` 树，新增 `GET /api/system/departments/tree` 全量接口（复用列表 `queryWhere`、带参数级鉴权与 2000 条上限），并一并补齐部门的新增 / 修改 / 启停入口。方案、落地清单与验收见 `PLAN-部门配置树形改造方案.md` |
| SYS-C-23 | 验收时需覆盖：总部为唯一根节点、省级节点数为 8、市级节点挂载正确（含浙江 5 / 江苏 3 / 广东 2 / 山东 1 / 四川 1，湖北、北京、上海无下级） |

### 5.5 权限体系补齐

#### 5.5.1 现状缺口（必须补齐，否则本需求无法安全落地）

| 编号 | 缺口 | 处理 |
| --- | --- | --- |
| SYS-P-01 | 后端无任何权限校验点：`guarantee-system` 的接口只要登录就能调，`@PreAuthorize` 全库未使用 | 为所有 `system:*` 读写接口加 `@PreAuthorize("hasAuthority('xxx')")`，需在 `SecurityConfig` 开启 `@EnableMethodSecurity` |
| SYS-P-02 | `CurrentUser.Principal` 不含权限码，工具层拿不到 | 扩展为 `Principal(userId, username, realName, orgId, roles, permissions)`，由 `JwtAuthenticationFilter` 从 claims 填充 |
| SYS-P-03 | Tool 执行线程与请求线程不同（`AiChatService` 自驱动循环 + Reactor），`CurrentUser` ThreadLocal **不可用** | 权限码必须通过 `ToolContext` 下传：新增 `AiToolContextKeys.PERMISSIONS`（`List<String>`）、`ROLES`、`ORG_ID`；`AiChatService.buildToolContext` 负责填充 |
| SYS-P-04 | 权限编码缺失：机构/部门/用户/角色只有 `:view` | 按 5.5.2 补齐权限码，并写入 `DataInitializer` 与数据库初始化脚本（注意：`DataInitializer` 只在 `sys_user` 为空时执行，**存量库需提供幂等补数 SQL 或独立的权限同步逻辑**） |
| SYS-P-05 | JWT 中权限是登录时的快照，最长有效期内不会刷新 | 写操作确认时以**当前 token 的 claims** 为准复核（SYS-C-04）；同时对用户/角色/权限变更立即撤销相关用户令牌（`TokenRevocationService`） |
| SYS-P-06 | 前端菜单未按权限过滤（仅登录态判断） | 系统管理菜单与页面按钮按 `userStore.permissions` 过滤；后端仍需独立校验（前端过滤不作为安全边界） |

> 上表 6 项为**一期硬前置**：全部完成前不应上线任何系统管理工具，否则等于在没有权限边界的情况下开放系统数据。

#### 5.5.2 权限码清单（新增）

| 域 | 现有 | 新增 |
| --- | --- | --- |
| 险种 | `system:insurance:view` / `:create` / `:update` | `system:insurance:disable`（停用/启用） |
| 机构 | `system:org:view` | `system:org:create` / `:update` / `:disable` |
| 部门 | `system:dept:view` | `system:dept:create` / `:update` / `:disable` |
| 用户 | `system:user:view` | `system:user:update` / `:disable` / `:assign-role`（**不含** `:create` 与 `:reset-password`，见 D-2） |
| 角色 | `system:role:view` | `system:role:create` / `:update` / `:assign-permission` |
| 权限 | `system:permission:view` | 无（权限主数据不允许改，见 R-04） |
| 审计 | 无 | `system:audit:view` |
| 助手 | `ai:chat` | `ai:system:query`（使用系统管理查询能力，ADMIN/OPERATOR/ANALYST 持有）/ `ai:system:write`（使用系统管理写能力，**仅 ADMIN 持有**） |

> `ai:system:query` 与 `ai:system:write` 是**能力开关**，与具体域的 `system:*` 权限是"与"关系：写操作需同时持有 `ai:system:write` 与对应域权限码。这样管理员可以"允许某角色查系统数据，但不允许通过助手改系统数据"。

#### 5.5.3 权限矩阵（D-1 已定稿）

| 权限 | ADMIN | OPERATOR | ANALYST | VIEWER | 说明 |
| --- | --- | --- | --- | --- | --- |
| `ai:chat` | ✔ | ✔ | ✔ | ✔ | |
| `ai:system:query` | ✔ | ✔ | **✔（D-1）** | ✘ | 助手侧系统管理查询能力开关 |
| `ai:system:write` | ✔ | ✘ | ✘ | ✘ | 助手侧写能力开关，仅 ADMIN |
| `system:org:view` | ✔ | ✔ | **✔** | ✔ | |
| `system:org:create/update/disable` | ✔ | ✔ | ✘ | ✘ | |
| `system:dept:view` | ✔ | ✔ | **✔** | ✔ | |
| `system:dept:create/update/disable` | ✔ | ✔ | ✘ | ✘ | |
| `system:user:view` | ✔ | ✔ | **✔** | ✔ | ANALYST 返回字段收窄，见 SYS-P-11 |
| `system:user:update/disable/assign-role` | ✔ | ✘ | ✘ | ✘ | D-2：无 create / reset-password |
| `system:role:view` | ✔ | ✘ | **✔** | ✔ | |
| `system:role:create/update/assign-permission` | ✔ | ✘ | ✘ | ✘ | |
| `system:insurance:view` | ✔ | ✔ | **✔** | ✔ | |
| `system:insurance:create/update/disable` | ✔ | ✔ | ✘ | ✘ | |
| `system:audit:view` | ✔ | ✘ | ✘（**D-1a**） | ✘ | 全局操作审计仅 ADMIN；ANALYST 改用 `queryMyToolCalls` 自查 |

**关键约束**

| 编号 | 约束 |
| --- | --- |
| SYS-P-12 | ANALYST 的写权限必须为 0：矩阵中"写"列全为 ✘，集成测试需断言 ANALYST 通过助手发起任何 `propose*` 请求时**工具根本未注册**（而非执行后报错） |
| SYS-P-12a | **按权限裁剪工具注册集**：`AiToolRegistry` 需按当前用户权限决定注册哪些工具——无 `system:audit:view` → 不注册 `queryOperationAudit`；无 `ai:system:write` → 不注册全部 `propose*`；无对应域 `:view` → 不注册该域查询工具。**注册裁剪是安全边界的第一道，权限校验是第二道**，两者都要有 |
| SYS-P-13 | 权限矩阵需同时落地两处，且保持一致：`DataInitializer.seedRolesAndPermissions`（空库）与幂等补数脚本（存量库）；不一致时启动自检告警 |

### 5.6 数据权限（机构范围）

| 编号 | 要求 |
| --- | --- |
| SYS-P-07 | 系统管理查询结果必须按"数据范围"过滤，规则：`ADMIN` 全量；`org_level=1` 用户可见全部下级；省级用户仅可见本省机构及其下部门/用户；市级用户仅可见本市 |
| SYS-P-08 | 数据范围**必须由服务端强制**（现有 Mapper 的查询条件里追加 `org_id IN (...)`），不允许只在提示词里要求模型自律 |
| SYS-P-09 | 跨范围目标（例如"停用江苏的一个用户"而当前用户是浙江运营）必须返回"目标不在你的数据范围内"，且**不暴露该目标是否存在** |
| SYS-P-10 | 数据范围判定逻辑放在 `guarantee-system`（新增 `DataScopeService`），AI 层只传 `orgId` + `roles`（+ `orgLevel`），不重复实现 |
| SYS-P-11 | **字段级分级脱敏**：`queryUser` 按角色返回不同字段集——`ADMIN`/`OPERATOR` 可含脱敏后的 `phone`（`138****5678`）、`email`（`a***@guarantee.com`）；`ANALYST`/`VIEWER` 仅返回 `username`、`realName`、`orgName`、`deptName`、`roleNames`、`status`，**不含** `phone`、`email`、`lastLoginAt`。白名单式组装，禁止直接序列化 VO（见 RK-08） |
| SYS-P-14 | 数据范围必须覆盖**列表与明细两类查询**：`queryOrg`/`queryDepartment`/`queryUser` 列表过滤之外，写操作的**目标解析**（按名称找 id）也必须在范围内进行，否则会出现"能停用但看不到"的越权 |

#### 5.6.1 机构层级数据修复（D-3）

| 编号 | 要求 |
| --- | --- |
| SYS-P-15 | 修复 `DataInitializer.seedOrgs`：当前 `parent_id` 固定写 0、`org_level` 固定为 `i == 1 ? 1 : 2`，导致层级树平铺，SYS-P-07 的省市范围无法验证 |
| SYS-P-16 | 目标层级结构（**总部不参与区域分层**）：新增 **1 个总部节点**（`org_level=1`、`parent_id=0`、编码 `ORGHQ`）；**每个区域按原数量保留机构**（浙江 6、江苏 4、广东 3、山东 2、四川 2、湖北 1、北京 1、上海 1，合计 20），其中**每个区域第 1 个为省级**（`org_level=2`、`parent_id=总部ID`），**其余为市级**（`org_level=3`、`parent_id=该区域省级机构ID`）；机构总数 = 1 + 20 = **21** |
| SYS-P-16a | **区域权重不变性（关键）**：区域订单权重由 `REGIONS[r][2] / REGIONS[r][3]` 决定（见 `DataInitializer` 注释），区域机构数与区域权重都保持不变，因此**订单/企业/项目的区域分布与总量不受本次层级修复影响**——只有机构表的 `parent_id`/`org_level` 与新增的总部行发生变化。这一点需在实施时明确保留，避免顺手"统一各区域机构数"而破坏既有的"浙江占比最高"数据规律 |
| SYS-P-17 | 机构总数由 20 变为 21，需同步检查依赖"机构数"的既有代码与测试：**`DaySampler` 的 `orgTotals`/`cumulative` 维度、部门分配 `(id-1) % ORG_COUNT`（应改为 `orgs.size()` 或明确的 21）、用户机构分配 `i % orgs.size()`**，三处逐一复核，否则部门/用户会挂到错误机构 |
| SYS-P-18 | `sys_department.parent_id` 目前也全为 0，建议至少在同一机构内构造一层父子关系（每机构第 1 个部门为顶级，其余挂其下），使部门的层级语义可验证。<br>**（2026-09-22 重构）** 部门树已改为 `总部 → 业务部/财务部/人事部/行政部/技术部 → 杭州部/台州部/温州部/大数据部/系统部`，每机构 11 个部门、共 231 个，层级为三级。见 `PLAN-部门配置树形改造方案.md` §15 |
| SYS-P-19 | 数据修复会改变 `DataInitializer` 的随机数消耗顺序 → **演示数据全量变化**。需评估：既有截图/演示脚本/文档中引用的具体数字是否需要同步更新；`AiToolChainIT` 等断言固定数值的测试需重新校准 |
| SYS-P-20 | 修复后必须补一条针对性验证：以"浙江省省级机构"（`id=2`）用户身份查询机构列表 → 只能看到浙江省内机构；以"浙江省第2保函运营机构"（`id=3`，`org_level=3`）用户身份 → 只能看到本市机构及其部门 |

**目标机构表（D-3 落实口径，id 与现有 `id++` 递增顺序保持一致）**

| id | org_code | org_name | region_code | org_level | parent_id | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `ORGHQ` | 平台总部 | 110000 | 1 | 0 | **新增节点**，`ADMIN` 所属；不承保业务（`region_code` 仅为占位，不参与区域统计） |
| 2 | `ORG3301` | 浙江省第1保函运营机构 | 330000 | 2 | 1 | 浙江省省级（区域第 1 个） |
| 3~7 | `ORG3302`~`ORG3306` | 浙江省第2~6保函运营机构 | 330000 | 3 | 2 | 浙江省其余 5 个均为市级，父级 = 2 |
| 8 | `ORG3201` | 江苏省第1保函运营机构 | 320000 | 2 | 1 | 江苏省省级 |
| 9~11 | `ORG3202`~`ORG3204` | 江苏省第2~4保函运营机构 | 320000 | 3 | 8 | 父级 = 8 |
| 12 | `ORG4401` | 广东省第1保函运营机构 | 440000 | 2 | 1 | 广东省省级 |
| 13~14 | `ORG4402`~`ORG4403` | 广东省第2~3保函运营机构 | 440000 | 3 | 12 | 父级 = 12 |
| 15 | `ORG3701` | 山东省第1保函运营机构 | 370000 | 2 | 1 | 山东省省级 |
| 16 | `ORG3702` | 山东省第2保函运营机构 | 370000 | 3 | 15 | 父级 = 15 |
| 17 | `ORG5101` | 四川省第1保函运营机构 | 510000 | 2 | 1 | 四川省省级 |
| 18 | `ORG5102` | 四川省第2保函运营机构 | 510000 | 3 | 17 | 父级 = 17 |
| 19 | `ORG4201` | 湖北省第1保函运营机构 | 420000 | 2 | 1 | 湖北省省级（该区域仅 1 个机构，无市级） |
| 20 | `ORG1101` | 北京市第1保函运营机构 | 110000 | 2 | 1 | 北京市省级（仅 1 个） |
| 21 | `ORG3101` | 上海市第1保函运营机构 | 310000 | 2 | 1 | 上海市省级（仅 1 个） |

> 注意：湖北/北京/上海这 3 个区域各只有 1 个机构，因此**没有市级节点**——数据范围的三档（总部全量 / 省级本省 / 市级本市）需要靠浙江、江苏、广东、山东、四川这 5 个区域来验证。

**`DataInitializer` 改造要点**

| 编号 | 要点 |
| --- | --- |
| SYS-P-21 | `seedOrgs` 先插入总部节点，再按区域插入：区域内索引 0 → `org_level=2` 且 `parent_id=总部ID`，索引 ≥1 → `org_level=3` 且 `parent_id=该区域省级机构ID` |
| SYS-P-22 | 原 `ORG_COUNT = 20` 的语义需拆开，避免"总数"与"区域内机构数"混用：建议定义 `HEADQUARTERS = 1`、`ORG_COUNT = 21`（实际总数，驱动 `DaySampler` 维度）、`REGION_ORG_COUNT = 20`（仅区域机构，驱动采样范围），区域机构数继续从 `REGIONS[..][3]` 读取。**现有 `ORG_COUNT` 常量在 `seedDepartments` 与断言中都被引用，改名后必须同步全部引用点** |
| SYS-P-23 | 总部节点是否参与订单采样：建议**不参与**（总部不承保业务），即 `DaySampler` 只在 20 个区域机构中采样；需显式排除，而不是依赖"恰好没有订单" |
| SYS-P-24 | **演示账号归属必须显式指定，不能依赖 `i % orgs.size()` 的巧合**：`admin` → 总部（`org_id=1`，全量范围，`dept_id` 可为空）；`operator` → 浙江省省级（`org_id=2`）；`analyst` → **浙江省第2保函运营机构（市级，`org_id=3`）**；`user0004` → 江苏省省级（`org_id=8`）。这四个账号覆盖三档数据范围，是 AC-03、TEST-03/13 的验证载体。实现方式建议：在 `seedUsers` 的公式分配之后对 1~4 号账号做显式覆盖，避免改动整体分配规律 |
| SYS-P-24a | `seedDepartments` 的部门分配同样依赖机构总数，修复层级后需复核"每机构 4 个部门"的构造仍成立（现有实现用 `(i % 4)` 与 `(id-1)` 组合推导 `dept_id`，其中 `% ORG_COUNT` 需同步改为 21）。<br>**（2026-09-22 重构）** 该算术推导已被**规格表 `DEPT_SPEC` 驱动**取代（`DataInitializer`）：父部门按名字解析，不再靠 id 算术；部门数改为 `机构数 × 规格表长度` 并在启动时校验一致 |
| SYS-P-25 | `sys_department.parent_id` 同步修复：每机构第 1 个部门为顶级（`parent_id=0`），其余部门挂该机构第 1 个部门，使部门层级语义可验证（SYS-P-18）。<br>**（2026-09-22 重构）** 层级扩展为三级：`总部` 为顶级，`业务部/财务部/人事部/行政部/技术部` 挂总部，`杭州部/台州部/温州部` 挂业务部、`大数据部/系统部` 挂技术部 |
| SYS-P-26 | **角色权限分配逻辑需显式改写**（现有实现有两条规则会被新权限码意外命中，必须显式排除）：<br>① `VIEWER` 现用 `code.endsWith(":view")` 兜底 → **会误授 `system:audit:view`**，需显式排除；<br>② `ANALYST` 现用前缀白名单（`analysis`/`project`/`enterprise`/`order`/`dashboard:view`/`ai:chat`）→ 不会命中新增的 `system:*:view`、`ai:system:query`、`system:audit:view`，需按 D-1 显式补入。<br>建议改为**显式权限码清单**（每个角色一份常量列表）替代前缀/后缀推导，避免后续新增权限码时出现静默误授或漏授 |

> 现状提示（修复前）：`sys_org.parent_id` 全为 0，SYS-P-07 的层级判定失效，省级/市级范围退化为"本机构"。该问题**必须在 M0/M1 阶段一并修复**，否则数据范围只能验证到"本机构"一档，等于没验证。

### 5.7 审计要求

#### 5.7.1 数据模型

**新增 `ai_operation_proposal`（提案）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT PK | |
| proposal_no | VARCHAR(40) UNIQUE | 提案编号 `OP` + 时间 + 随机 |
| conversation_id | BIGINT | 来源会话 |
| user_id | BIGINT | 提案发起人 |
| tool_name | VARCHAR(64) | |
| action | VARCHAR(32) | CREATE/UPDATE/DISABLE/... |
| target_type | VARCHAR(32) | USER/ORG/DEPT/ROLE/INSURANCE_TYPE |
| target_id | BIGINT NULL | 新建时为空 |
| request_payload | JSON / TEXT | 模型解析后的参数 |
| preview_payload | JSON / TEXT | `changes[]` + `impact` + `warnings[]` |
| required_perms | VARCHAR(512) | 所需权限码（逗号分隔），确认时复核用 |
| status | VARCHAR(16) | PENDING / EXECUTING / EXECUTED / REJECTED / EXPIRED / INVALIDATED / FAILED |
| reject_reason | VARCHAR(255) NULL | |
| confirmed_at / executed_at / expires_at | DATETIME | |
| result_message | VARCHAR(512) NULL | |
| audit_id | BIGINT NULL | 关联操作审计 |
| trace_id | VARCHAR(64) | |
| created_at / updated_at | DATETIME | |

索引：`uk_proposal_no`、`idx_user_status(user_id, status)`、`idx_conversation(conversation_id)`、`idx_expires(status, expires_at)`

**新增 `ai_operation_audit`（操作审计）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT PK | |
| operated_at | DATETIME | 操作时间 |
| operator_user_id / operator_username / operator_real_name | | 操作人 |
| source | VARCHAR(16) | AI（助手确认）/ WEB（页面直连）——二期要求页面写操作也写同一张表，实现统一追溯 |
| action | VARCHAR(32) | |
| target_type / target_id / target_name | | 目标 |
| before_value | JSON / TEXT NULL | 变更前（新增为空） |
| after_value | JSON / TEXT NULL | 变更后（停用为空） |
| changed_fields | VARCHAR(512) NULL | 变更字段列表，便于检索 |
| result | VARCHAR(16) | SUCCESS / FAILED / REJECTED / EXPIRED / PARTIAL |
| error_message | VARCHAR(512) NULL | |
| proposal_id | BIGINT NULL | 关联提案 |
| conversation_id | BIGINT NULL | 关联会话 |
| trace_id | VARCHAR(64) | |

索引：`idx_operated_at`、`idx_operator(operator_user_id, operated_at)`、`idx_target(target_type, target_id)`、`idx_result(result)`

**分区与归档（SYS-A-14）**：按 `operated_at` 做**按月 RANGE 分区**（`PARTITION BY RANGE (TO_DAYS(operated_at))`），到期分区 `DROP` 前导出归档。注意 MySQL 分区表要求**分区键必须包含在每个唯一键中**——本表主键为 `id`，因此需改为复合主键 `(id, operated_at)` 或至少确保无跨分区的唯一约束需求（本表无业务唯一键，采用前者最稳妥）。该约束需在设计阶段确认，否则建表脚本会直接失败。

**扩展 `ai_audit_log.action` 枚举**：新增 `PROPOSAL_CREATED` / `PROPOSAL_CONFIRMED` / `PROPOSAL_REJECTED` / `PROPOSAL_EXPIRED` / `OPERATION_EXECUTED` / `OPERATION_FAILED`

**扩展 `ai_tool_call.tool_type`**：`READ` / `WRITE`（字段已预留 `READ/WRITE` 注释，无需改表）

#### 5.7.2 审计功能要求

| 编号 | 要求 |
| --- | --- |
| SYS-A-01 | 每一次写操作的完整生命周期（提案→确认/拒绝/过期→执行成功/失败）都必须可追溯，且以 `trace_id` 串起会话、工具调用、提案、审计四条记录 |
| SYS-A-02 | `before_value` / `after_value` 必须是**结构化快照**（字段级），不得只存一句描述 |
| SYS-A-02a | **敏感字段脱敏（D-4）**：`before_value` / `after_value` 对敏感字段（`phone`、`email`、`password`、令牌、身份证件类字段）**只记录字段名与"是否发生变更"，不记录具体值**。落库形态示例：`changed_fields="phone,email"`、`before_value={"phone":"<changed>","email":"<changed>"}` 或统一的 `masked_fields` 列表。**该规则与操作者角色无关**——ADMIN 同样脱敏；需要原文时走独立的高权限流程，不通过审计表 |
| SYS-A-02b | 脱敏**必须在写入前完成**，不允许"存原文、返回时按角色裁剪"：审计表本身可能被导出、被 SQL 直查或被备份流转，返回层脱敏无法覆盖这些路径 |
| SYS-A-03 | 审计写入必须与业务执行同事务（SYS-C-06）；审计写入失败必须导致业务回滚 |
| SYS-A-04 | 审计记录**只增不改不删**；不提供任何删除/修改审计的接口 |
| SYS-A-05 | 密码、令牌类字段**绝不入审计**（本期 D-2 已不含新建用户与密码重置，该约束作为长期规则保留） |
| SYS-A-06 | 助手可通过 `queryOperationAudit` 查询审计（SYS-Q-06，仅 ADMIN），但查询本身也要落 `ai_tool_call`；无权限用户的该工具**不注册**（SYS-P-12a） |
| SYS-A-07 | 页面侧的写操作（险种现有 create/update）在二期需接入同一张审计表，`source=WEB` |
| SYS-A-08 | **保留期（原 Q-6，已定稿）**：**在线可查期 24 个月**（滚动窗口）+ **归档后 36 个月**，**总保留 5 年**；**不做"永久在线"**。依据与量级估算见 5.7.3 |
| SYS-A-09 | **其他存储位置的同类脱敏**：`ai_tool_call.arguments` / `ai_tool_call.result`（现有 `AiToolCallRecorder` 原样落库）、`ai_operation_proposal.request_payload` / `preview_payload` 同样禁止出现敏感字段明文；写入前统一经 `SensitiveFieldMasker` 处理。**理由**：ANALYST 对自己的会话有完整查询权，若写工具入参含明文手机号则会从 `ai_tool_call` 泄漏，使 SYS-P-11 与 D-4 双双失效 |
| SYS-A-10 | 审计查询（`queryOperationAudit`）的**数据范围**：对 USER / ORG / DEPT 类目标按机构范围过滤；**ROLE / PERMISSION 类目标无机构归属，仅 `ADMIN` 可见**（这正是 D-1a 回收 ANALYST 权限的根因）。该规则需在 `OperationAuditService` 中显式实现，不能依赖通用 `org_id IN (...)` |
| SYS-A-11 | 审计表需保证**可验证脱敏**：`ai_operation_audit` 表中不得出现任何敏感字段明文，需提供一条校验手段（单元测试断言 + 可选的启动/巡检自检 SQL），便于 NFR 与合规检查取证（D-4） |

#### 5.7.3 容量估算与保留期策略（SYS-A-08 的依据）

**估算前提**：本期审计只覆盖系统管理域 5 个实体（机构 21 / 部门 ~~80~~ **231**（2026-09-22 部门树重构后）/ 用户 300 / 角色 4 / 险种 6），且 D-2 已排除用户新建与密码重置。**审计行数不跟随订单量增长**——演示数据 15 万订单 / 638 天 ≈ 日均 165 笔订单；若把订单类写操作纳入同一张表，量级将高出约 3 个数量级，故本期在 2.2 中明确排除。

**单行开销**：`before_value` + `after_value` 为主要成本（约 0.8~2 KB），加上其余字段与行开销，净数据约 **1.2~2 KB/行**；计入 InnoDB 页填充与二级索引后按 **2.5~3 KB/行** 规划。

| 场景 | 定位 | 日均写操作 | 年行数 | 年存储 | 在线 12 个月 | 在线 24 个月 | 在线 36 个月 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **P0 低频** | 当前演示/试点 | 5 | 1,825 | ~4.6 MB | 0.2 万行 / 5 MB | 0.4 万行 / 9 MB | 0.5 万行 / 14 MB |
| **P1 基准** | 正常运营（21 机构，每周合计约 200 次配置变更） | 30 | 10,950 | ~27 MB | 1.1 万行 / 33 MB | 2.2 万行 / 66 MB | 3.3 万行 / 99 MB |
| **P2 压力** | 上线初期集中批量调整 | 200 | 73,000 | ~183 MB | 7.3 万行 / 219 MB | 14.6 万行 / 438 MB | 21.9 万行 / 657 MB |
| （参考）订单域若纳入 | **本需求不做** | 45,000 | 1,642 万 | ~41 GB | 1,642 万行 / 41 GB | 3,284 万行 / 82 GB | — |

**结论**

| 编号 | 结论 |
| --- | --- |
| SYS-A-12 | 即便按最坏的 P2 档，**在线 3 年也只有 21.9 万行 / 约 0.66 GB**；日增到 500 次（对 300 用户的后台已属异常）时在线 5 年约 183 万行 / 4.6 GB。**因此"永久在线"的容量压力并不成立**，放弃它的真实理由是：① 合规审计通常要求 3~5 年保留与防篡改，靠一张无 WORM 保护的 MySQL 表承担不妥；② 全库备份窗口与恢复时间会随这张永久增长的表一起恶化；③ 不能承诺无限增长，一旦业务域写操作纳入（年 41 GB）再改造分区与归档需停机 |
| SYS-A-13 | **保留期方案**：在线 24 个月（滚动）→ 归档 36 个月，总 5 年。**在线 24 个月**的依据：P2 档也仅 14.6 万行 / 438 MB，查询与备份均在舒适区，同时覆盖"看去年同期怎么改"的跨年复盘需求 |
| SYS-A-14 | **归档实现**：`ai_operation_audit` **按月 RANGE 分区**，到期以 `DROP PARTITION` 清理（元数据操作、秒级、无长事务与锁表），导出为 Parquet/CSV 落对象存储或冷库表并保留 `trace_id`；**不做"3 年后一次性大迁移"** |
| SYS-A-15 | **收缩触发阈值**：当日均写操作持续超过 **500 次/天**（≈18 万行/年），或在线表超过 **100 万行 / 2.5 GB** 时，把在线窗口从 24 个月收缩到 12 个月。两个阈值均可由 `ai_operation_audit` 自身统计得出，无需额外埋点 |
| SYS-A-16 | **单条审计上限**：单行 JSON 快照（`before_value`/`after_value`）设上限 **8 KB**，超限时只记 `changed_fields` 与 `truncated=true` 标记。防止快照膨胀把 2.5 KB/行推高到 6~10 KB/行，使上表估算失效 |
| SYS-A-17 | **查询护栏与分区裁剪配合**：审计查询强制 ≤ 90 天时间窗（`SYS-Q-06`），使其能命中少量分区；禁止无时间条件的全量扫描（接口层拒绝，不依赖调用方自觉） |
| SYS-A-18 | **容量需可观测**：把"在线行数、在线占用、最老在线记录时间、归档成功/失败数"纳入巡检指标；当在线窗口实际超过 24 个月或归档任务连续失败时告警——否则"滚动归档"会静默退化回"永久在线" |

### 5.8 提示词改造（`prompts/business-assistant.st`）

| 编号 | 要求 |
| --- | --- |
| SYS-N-01 | 修改第 20 条：把"当前阶段你拥有的工具全部是只读"改写为分域说明（业务域只读；系统管理域按已注册工具能力），并保留"不可编造已执行"的表述 |
| SYS-N-02 | 新增「系统管理域知识」章节：机构/部门/用户/角色/权限/险种 的实体关系与字段语义（例如 `org_level` 三层、`perm_type` 的 MENU/BUTTON/API、`category` 的 TENDER/PERFORMANCE） |
| SYS-N-03 | 新增「写操作铁律」：写操作**只能**通过 `propose*` 工具生成待确认提案，绝不允许说"我已经帮你改好了"；必须明确告知用户在确认卡上确认后才生效 |
| SYS-N-04 | 新增「参数澄清规则」：机构名/用户名/角色名命中多个目标时必须列出候选让用户选择，禁止猜测 |
| SYS-N-05 | 新增「敏感信息规则」：不得主动查询或输出用户手机号、邮箱、密码；确有必要时说明系统已脱敏 |
| SYS-N-06 | 新增「越权应答模板」：无权限时统一回复"你当前没有 XXX 权限，请联系管理员"，不得暴露数据存在性 |
| SYS-N-07 | 新增「危险操作确认话术」：对停用、角色分配、机构/部门/险种变更，必须在正文中再次提示影响面（密码重置本期不支持，按 SYS-N-10 处理） |
| SYS-N-08 | 保留并强化「时间语义」与「数据来源回显」要求；系统管理查询同样需要回显查询条件 |
| SYS-N-09 | 提示词需控制长度：新增章节后总长度建议 ≤ 4000 字符，避免挤占上下文预算；可通过"工具描述承担细节、提示词只写规则"的方式控制 |
| SYS-N-10 | 新增「本期不支持的动作」清单（D-2）：新建用户、重置密码、删除任何主数据、批量超 50 条。遇到这些诉求必须明确回复"当前不支持"并给出替代路径（系统管理页面 / 联系管理员），**严禁含糊应承或声称已完成** |
| SYS-N-11 | 新增「能力开关说明」：无 `ai:system:write` 的用户，写工具不会出现在工具列表中。提示词需说明"若你没有可用的写工具，说明你的账号未开通该能力，请引导用户联系管理员"，避免模型编造"我尝试了但失败了" |
| SYS-N-12 | 新增「审计可见性说明（D-1a）」：非 ADMIN 角色**没有全局操作审计工具**。当用户问"最近系统里有哪些配置被改动"时，必须回复"你没有查看全局操作审计的权限，请联系管理员；我可以帮你查看你自己的工具调用记录"，并改用 `queryMyToolCalls`，**严禁编造审计内容或用其他数据替代** |
| SYS-N-13 | 新增「敏感字段已脱敏说明（D-4）」：当用户询问某个手机号/邮箱的变更历史时，说明"审计中敏感字段只记录是否变更、不记录具体值"，不得尝试从其他工具绕行拼凑 |

---

## 6. 接口与数据设计汇总

### 6.1 新增/变更接口

| 模块 | 接口 | 类型 | 说明 |
| --- | --- | --- | --- |
| `guarantee-ai` | `POST /api/ai/proposals/{id}/confirm` | 新增 | 确认执行提案 |
| `guarantee-ai` | `POST /api/ai/proposals/{id}/reject` | 新增 | 拒绝提案 |
| `guarantee-ai` | `GET /api/ai/proposals/{id}` | 新增 | 提案详情（恢复确认卡） |
| `guarantee-ai` | `GET /api/ai/proposals` | 新增 | 待确认提案列表 |
| `guarantee-ai` | `GET /api/ai/operation-audits` | 新增 | 操作审计列表（管理页面用，**仅 ADMIN**） |
| `guarantee-ai` | `GET /api/ai/tool-calls/mine` | 新增 | **当前用户自己**的工具调用记录（`queryMyToolCalls` 的页面等价接口，无需 `system:audit:view`） |
| `guarantee-system` | `POST/PUT /api/system/orgs`、`PATCH /{id}/status` | 新增 | 机构写操作 |
| `guarantee-system` | `POST/PUT /api/system/departments`、`PATCH /{id}/status` | 新增 | 部门写操作 |
| `guarantee-system` | `PUT /api/system/users/{id}`、`PATCH /{id}/status`、`PUT /{id}/roles` | 新增 | 用户写操作（D-2：**不新增** `POST /api/system/users` 与 `POST /{id}/reset-password`） |
| `guarantee-system` | `POST/PUT /api/system/roles`、`PUT /{roleCode}/permissions` | 新增 | 角色写操作 |
| `guarantee-system` | `PATCH /api/system/insurance-types/{id}/status` | 新增 | 险种启停 |
| `guarantee-system` | `GET /api/system/orgs/tree` | 新增 | **D-3a 树形数据源**：返回数据范围内全部机构的扁平列表（含 `parentId`/`orgLevel`），供前端组装三级树；分页接口保持不变 |
| `guarantee-common` | `CurrentUser.Principal` 增字段 | 变更 | 增加 roles / permissions |
| `guarantee-auth` | `SecurityConfig` 开启方法级鉴权 | 变更 | `@EnableMethodSecurity` |
| `frontend` | `chatStream.ts` 新增 `onProposal` / `onProposalResult` | 变更 | 否则新事件被 `onUnknown` 静默丢弃 |
| `frontend` | `AiCopilot.vue` 新增确认卡片渲染 | 变更 | 含倒计时、二次确认 |
| `frontend` | `api/ai.ts` 新增提案接口 | 变更 | |
| `frontend` | 系统管理页面新增按钮与权限过滤 | 变更 | 按 `permissions` 控制 |
| `frontend` | **`views/system/Orgs.vue` 改为树形** | 变更 | D-3a：按 `parent_id` 组装三级树（总部→省级→市级），支持展开/收起、过滤保留祖先链；**依赖 `GET /api/system/orgs` 返回 `parentId`/`orgLevel` 字段**（若当前 `OrgVO` 缺失需补齐） |

### 6.2 数据变更汇总

| 对象 | 变更 |
| --- | --- |
| `ai_operation_proposal` | 新建表 |
| `ai_operation_audit` | 新建表 |
| `ai_audit_log` | 扩展 `action` 枚举值（无 DDL 变更，仅注释） |
| `sys_permission` | 新增权限码（D-2 后不含 `system:user:create` / `:reset-password`）：机构 3 + 部门 3 + 用户 3 + 角色 3 + 险种 1 + 审计 1 + 助手 2 = **16 条** |
| 新增 `SensitiveFieldMasker` | 敏感字段统一脱敏工具（`guarantee-common` 或 `guarantee-system`），供审计写入（D-4）、工具返回值组装（SYS-P-11）、`ai_tool_call` 落库（SYS-A-09）三处共用，避免三套实现不一致 |
| 既有数据隐患修复 | `SysUserMapper.xml` 的 `voCols` 与 `UserVO` **当前直接返回明文 `phone`/`email` 且服务端无脱敏**，系统管理页面与工具共用同一 VO。需拆分为「页面 VO」与「工具专用白名单组装」两条路径（SYS-P-11 / RK-08 的具体落点） |
| `sys_role_permission` | 按 5.5.3 矩阵补默认分配（含 ANALYST 的只读系统权限，D-1） |
| `DataInitializer` | 机构层级修复（新增总部节点、`parent_id`/`org_level` 正确化，D-3）；部门层级建议同步修复；角色权限分配逻辑需覆盖 ANALYST 新增权限 |
| `schema.sql` | 追加两张表的幂等 DDL |
| 存量库 | 需提供幂等权限补数脚本（`DataInitializer` 只在空库执行，见 SYS-P-04） |

---

## 7. 技术前提与关键改造点

### 7.1 为什么写工具必须"只产出提案"

| 约束 | 说明 |
| --- | --- |
| 上下文不可用 | 写操作需要权限校验与数据范围判定，而 Tool 执行发生在 `AiChatService` 自驱动的 `ToolCallingManager` 循环中（Reactor 线程），**没有 `HttpServletRequest`、`CurrentUser` ThreadLocal 已失效、也没有 Spring Security 上下文**（`JwtAuthenticationFilter` 的 `finally` 已清理） |
| 流已结束 | 工具在流式过程中执行，此时用户还没有机会看到"要改什么"。若工具直接落库，用户无法预览与撤销 |
| 审计要求 | "权限校验→预览→人工确认→执行→审计"是项目已确立的规范（提示词第 20 条原文），必须遵守 |
| 结论 | 写工具仅构建提案；执行走带 JWT 的独立 HTTP 接口，在 Web 线程上完成鉴权与执行 |

### 7.2 上下文传递改造清单

| 编号 | 改造 |
| --- | --- |
| T-01 | `CurrentUser.Principal` 增加 `roles`、`permissions`（`JwtAuthenticationFilter` 已有 `JwtTokenProvider.permissions(claims)`，直接填充即可） |
| T-02 | `AiToolContextKeys` 新增 `PERMISSIONS`、`ROLES`、`ORG_ID`、`AI_CHAT_PERMISSION_CHECKED`（可选） |
| T-03 | `AiChatService.buildToolContext` 填充上述键（注意 `ToolContext` 不允许 null value，沿用 `putIfNotNull`） |
| T-04 | 新增 `AiPermissionGuard`（AI 层）：提供 `require(ToolContext ctx, String perm)`，抛 `BizException(ResultCode.FORBIDDEN)`；工具统一调用 |
| T-05 | 新增 `AiToolRegistry.writeToolCallbacks()`；`AiChatService.buildToolCallingOptions` 按 `ai:system:write` 权限决定是否注册写工具（**无权限则连工具都不注册**，模型不会尝试去改数据） |
| T-06 | `AiChatService.runToolLoop` 中，写工具轮次结束后不再继续下一轮（或允许继续），需明确：**提案生成后应结束本轮并输出引导语**，避免模型再自作主张继续调用 |
| T-07 | `AI 助手对话 → 提案 → 确认` 三步的 `trace_id` 必须一致，便于串联审计（确认接口需接受/回填 `trace_id`） |

### 7.3 并发与一致性

| 编号 | 要求 |
| --- | --- |
| T-08 | 提案执行用条件更新抢占（SYS-C-03） |
| T-09 | 提案保存目标实体的"版本指纹"（`updated_at` 或字段哈希），执行前比对；不一致则置 `INVALIDATED` 并提示"数据已被他人修改，请重新发起" |
| T-10 | 一人多标签页/重复点击：前端禁用 + 后端条件更新双重保护 |
| T-11 | 定时任务（`@Scheduled`，建议每分钟）清理过期提案，且清理动作写审计 |

---

## 8. 非功能需求

| 编号 | 类别 | 要求 |
| --- | --- | --- |
| SYS-NF-01 | 性能 | 单个查询工具 P95 ≤ 800ms（列表类，`limit≤50`）；`ai_operation_audit` 查询 P95 ≤ 1.5s（时间跨度≤90 天） |
| SYS-NF-02 | 性能 | 提案生成（含预检）P95 ≤ 1s；确认执行 P95 ≤ 2s（不含令牌撤销） |
| SYS-NF-03 | 并发 | 支持同一用户最多 3 个并发会话；同用户并发确认同一提案仅允许一次成功 |
| SYS-NF-04 | 安全 | 后端为唯一安全边界，前端权限过滤仅为体验优化 |
| SYS-NF-05 | 安全 | 敏感字段服务端脱敏，日志与审计均不出现明文密码/令牌 |
| SYS-NF-06 | 安全 | 写操作接口需防重放：提案一次性消费（`PENDING→EXECUTING` 条件更新） |
| SYS-NF-07 | 可用性 | 模型/工具异常不得影响系统本身；确认接口失败必须给出可读原因 |
| SYS-NF-08 | 可观测 | 新增指标：提案数（按状态）、确认率、拒绝率、过期率、执行失败率、平均确认耗时；工具调用成功率沿用既有日志 |
| SYS-NF-09 | 兼容 | 一期上线不改动既有 `queryOrderSummary` 行为；`AiToolChainIT` 等既有测试必须全绿 |
| SYS-NF-10 | 兼容 | 旧版本前端（不认识 `proposal` 事件）不得崩溃：必须走 `onUnknown` 静默忽略路径（已在 `chatStream.ts` 支持） |
| SYS-NF-11 | 可维护 | 工具与 Service 分层约束不变：Tool → Service → Mapper → DB，禁止跨层 |

---

## 9. 测试要求

| 编号 | 层级 | 内容 |
| --- | --- | --- |
| TEST-01 | 单元测试 | 每个工具：正常入参、边界入参（超限 limit、非法日期、越界枚举）、空结果、无权限、歧义目标 |
| TEST-02 | 单元测试 | 权限矩阵逐格验证：4 角色 × 各自有/无权限的域，断言"返回 denied 而非空数据"（SYS-Q-11） |
| TEST-03 | 单元测试 | 数据范围：**省级用户查其他省机构/用户 → 0 结果且不暴露存在性**（SYS-P-09）；市级用户仅见本市（SYS-P-20） |
| TEST-04 | 集成测试 | 沿用 `StubToolCallingChatModel` + `AiToolChainIT` 模式：扩展为"用户问 → 模型返回 tool_call → 工具执行 → 落库 → 审计"全链路，覆盖查询与提案两类 |
| TEST-05 | 集成测试 | 提案确认链路：生成 → 确认 → 执行 → 审计可查；异常分支：过期后确认、重复确认、他人确认、权限变更后确认、目标被并发修改后确认 |
| TEST-06 | 集成测试 | 危险动作保护：停用最后一个 ADMIN → 拒绝；给自己加 ADMIN → 拒绝；停用自己 → 拒绝 |
| TEST-07 | 前端测试 | 确认卡片渲染（新增/修改/停用三类）、倒计时过期、二次确认弹窗、重复点击禁用、页面刷新后恢复 |
| TEST-08 | 端到端 | 一期：US-Q-01~07 逐条走通并核对答案与页面数据一致；二期：US-W-01~06 逐条走通 |
| TEST-09 | 回归 | `queryOrderSummary` 业务问答不退化；SSE 既有 6 种事件行为不变 |
| TEST-10 | 安全测试 | 越权：VIEWER / ANALYST 通过助手尝试写操作 → 工具未注册 + 接口 403（SYS-P-12）；篡改提案 id 确认他人提案 → 403 |
| TEST-11 | 数据一致性 | 险种 create/update 走页面与走助手两条路径，`ai_operation_audit` 记录一致性（SYS-A-07） |
| TEST-12 | 单元测试 | ANALYST 与 VIEWER 调用 `queryUser` → 返回字段集不含 `phone`/`email`/`lastLoginAt`（SYS-P-11） |
| TEST-13 | 集成测试 | 层级数据修复后：`parent_id`/`org_level` 关系正确（每个市级机构的父级是同区域省级机构，省级父级为总部）；机构总数与预期一致（SYS-P-15~17） |
| TEST-14 | 集成测试 | D-2 收敛验证：助手收到"新建用户""重置密码"诉求 → 返回不支持文案，且 `ai_tool_call` 中**不存在**对应工具名 |
| TEST-15 | 单元测试 | **审计脱敏（D-4）**：构造修改 `phone`/`email` 的写操作 → `before_value`/`after_value` 中**查不到明文手机号或邮箱**，但 `changed_fields` 能体现该字段已变更；以 ADMIN 身份查询同样查不到明文 |
| TEST-16 | 集成测试 | **工具注册裁剪（SYS-P-12a）**：ANALYST 的可用工具集中**不含** `queryOperationAudit`，但**含** `queryMyToolCalls`；ADMIN 反之；VIEWER 不含全部 `system:*` 查询工具 |
| TEST-17 | 安全测试 | **`queryMyToolCalls` 越权防护**：无论模型传入任何用户/会话参数，返回结果都**只含当前用户自己**的记录；A 用户无法查到 B 用户的工具调用（SYS-Q-06a 强制范围） |
| TEST-18 | 单元测试 | **存储层脱敏覆盖（SYS-A-09）**：写工具入参含 `phone` 时，`ai_tool_call.arguments`、`ai_operation_proposal.request_payload`、`ai_operation_audit.before_value` 三处均无明文 |
| TEST-19 | 集成测试 | **审计范围规则（SYS-A-10）**：ROLE/PERMISSION 类审计仅 ADMIN 可查；USER/ORG/DEPT 类审计按机构范围过滤 |
| TEST-20 | 单元测试 | **脱敏落点边界（Q-15 暂缓口径）**：`queryUser` 工具返回值中手机号/邮箱按角色脱敏（ADMIN 为 `138****5678`）；同时断言**系统管理页面接口 `GET /api/system/users` 的行为保持不变**（本期不改），确保改造只作用于工具/审计/提案/工具调用四条落库或返回路径 |
| TEST-21 | 前端测试 | **机构树形（D-3a）**：总部为唯一根节点；省级 8 个；市级挂载正确（浙江 5 / 江苏 3 / 广东 2 / 山东 1 / 四川 1）；过滤 `keyword` 后命中的市级节点**保留其祖先链**不游离；无权限账号看不到写操作按钮；机构总数超一页时树不缺节点（验证 `SYS-C-24` 未误用分页接口） |
| TEST-22 | 集成测试 | **分区与归档（D-5）**：`ai_operation_audit` 建表脚本含按月 RANGE 分区且能成功创建（验证分区键约束，见 5.7.1）；插入跨月数据后归档任务仅 `DROP` 到期分区、在线数据不受影响；查询 ≤90 天可命中分区裁剪；快照 >8 KB 时被截断并标记 `truncated=true`（SYS-A-16） |
| TEST-23 | 单元测试 | **审计查询护栏（SYS-A-17）**：不传时间条件或时间窗 >90 天的审计查询被拒绝并给出可读提示，而非执行全量扫描 |

---

## 10. 验收标准

> 编号在 v1.1 中重排为**连续**：v1.0 的 P2 条目顺延（AC-09~20 → AC-14~25），v1.0 的 AC-21~26 归位为 AC-09~13。编号与阶段无关，`阶段` 列标明归属。

| 编号 | 阶段 | 验收项 | 通过标准 |
| --- | --- | --- | --- |
| AC-01 | P1 | 系统管理查询可用 | US-Q-01~06 全部可正确回答，数字与系统管理页面一致（抽样 20 条人工核对） |
| AC-02 | P1 | 权限真实生效 | VIEWER 询问"有哪些用户" → 收到明确无权限提示，**不是** 0 条、**不是**编造数字 |
| AC-03 | P1 | 数据范围生效 | **省级用户询问其他省机构 → 不可见**；**市级用户仅见本市**；服务端日志可证明过滤条件已注入（依赖 D-3 层级修复） |
| AC-04 | P1 | 无敏感信息泄漏 | 全量回归提问"列出用户手机号/密码" → 输出中无明文，字段已脱敏 |
| AC-05 | P1 | 写操作不可用（一期） | 提问"把 user0123 停用" → 明确告知当前不支持，且数据库中无任何变更、无异常堆栈 |
| AC-06 | P1 | 分层约束 | 代码评审确认所有新工具无 Mapper 注入、无 SQL 字符串 |
| AC-07 | P1 | 审计完整 | 每个查询工具调用在 `ai_tool_call` 有记录（含参数/耗时/状态） |
| AC-08 | P1 | 回归 | 既有订单类问答与全部既有测试通过 |
| AC-09 | P1 | **ANALYST 只读可见（D-1）** | ANALYST 询问"浙江省有几个机构""analyst 角色有哪些权限" → 正确回答；询问用户明细 → 返回字段**不含** `phone`/`email`/`lastLoginAt` |
| AC-10 | P1 | **ANALYST 写权限为 0（D-1）** | ANALYST 说"把 user0123 停用" → 明确无权限/不支持；工具列表中不存在任何 `propose*` 工具（SYS-P-12） |
| AC-11 | P1 | **层级数据正确（D-3）** | 数据库校验：总部节点唯一且 `org_level=1`；每个非总部机构的 `parent_id` 指向存在的上级；每个区域恰有 1 个省级机构；机构总数与 `DataInitializer` 常量一致 |
| AC-12 | P1 | **数据修复无回归（D-3）** | 演示数据重建后服务可正常启动，`DataInitializer` 幂等（二次启动跳过）；订单类问答仍可正常返回 |
| AC-13 | P1 | **不支持动作应答（D-2）** | 提问"新建一个用户""重置 user0123 的密码" → 明确回复本期不支持并给出替代路径；`ai_tool_call` 中无对应工具记录 |
| AC-14 | P2 | 提案不落库 | 生成提案后直接查数据库，目标实体**无任何变更**；`ai_operation_proposal` 状态为 `PENDING` |
| AC-15 | P2 | 确认后生效 | 点确认 → 数据变更生效 → 提案 `EXECUTED` → `ai_operation_audit` 有前后值快照 |
| AC-16 | P2 | 拒绝无痕执行 | 点拒绝 → 数据无变更 → 提案 `REJECTED` → 审计 `result=REJECTED` |
| AC-17 | P2 | 过期不可执行 | 15 分钟后确认 → 明确提示已过期并拒绝执行 |
| AC-18 | P2 | 不可重复执行 | 双击确认 / 重放请求 → 仅一次执行，第二次返回明确错误 |
| AC-19 | P2 | 危险保护 | 停用最后一个 ADMIN、给自己加 ADMIN、停用自己 → 均被拒绝并给出原因 |
| AC-20 | P2 | 权限实时性 | 用户被移除权限后，其已发出的提案确认时被拒（`INVALIDATED`） |
| AC-21 | P2 | 令牌撤销 | 角色/权限变更后，被影响用户旧 token 立即失效（下次请求 401） |
| AC-22 | P2 | 统一审计 | 页面与助手两条渠道的写操作在同一张审计表中可查，可按 `source` 区分 |
| AC-23 | P2 | 可回溯 | 给定一次变更，可用 `trace_id` 串起：会话消息 → 工具调用 → 提案 → 审计记录 |
| AC-24 | P2 | 提示词合规 | 助手在任何情况下都不会声称"已直接修改"，均引导到确认卡 |
| AC-25 | P2 | **用户写操作三动作可用（D-2）** | 修改资料、启用/停用、角色分配三类均可通过助手完成并落审计；`UserController` 中不存在 create / reset-password 接口 |
| AC-26 | P2 | 回归 | 一期全部验收项 + 订单类问答依旧通过 |
| AC-27 | P2 | **审计脱敏生效（D-4）** | 修改某用户手机号后，查 `ai_operation_audit` 的 `before_value`/`after_value` → **无明文手机号**，但能看出该字段已变更；ADMIN 查询同样无明文（TEST-15） |
| AC-28 | P2 | **敏感字段无旁路（SYS-A-09）** | 写操作入参含 `phone` 时，`ai_tool_call.arguments`、`ai_operation_proposal.request_payload` 中同样无明文（TEST-18） |
| AC-29 | P2 | **审计可见性符合 D-1a** | ANALYST 询问"最近系统里有哪些配置被改动" → 收到明确的权限说明并改用自查能力，**不返回任何全局审计内容、不编造**；其工具列表中无 `queryOperationAudit`（TEST-16） |
| AC-30 | P2 | **自查范围严格** | 任意角色问"我最近用过哪些工具、有没有失败" → 只返回本人记录；尝试查询他人记录失败（TEST-17） |
| AC-31 | P2 | **机构树形正确（D-3a）** | 机构配置页以三级树呈现：总部为唯一根、省级 8 个、市级挂载正确；展开/收起可用；树中节点总数与数据库一致（不因分页缺节点）（TEST-21） |
| AC-32 | P2 | **树形与权限/过滤联动（D-3a）** | 按 `keyword`/`regionCode`/`status` 过滤后，命中节点保留祖先链、无游离节点；无写权限的账号（如 VIEWER）在节点上看不到新增/修改/启停按钮 |
| AC-33 | P2 | **保留期机制落地（D-5）** | `ai_operation_audit` 为按月分区；到期分区可被归档任务 `DROP` 且在线数据完整；在线记录时间跨度不超过 24 个月（除非已按阈值决策收缩）；归档产物可检索到 `trace_id`（TEST-22） |
| AC-34 | P2 | **查询护栏生效（SYS-A-17）** | 无时间条件或时间窗 >90 天的审计查询被拒绝；≤90 天查询正常且扫描分区数符合预期（TEST-23） |

---

## 11. 实施计划与里程碑

| 阶段 | 内容 | 交付物 | 依赖 |
| --- | --- | --- | --- |
| M0 前置补齐 | `CurrentUser.Principal` 扩展、`@EnableMethodSecurity`、`system:*` 接口补 `@PreAuthorize`、权限码补数（含 ANALYST 只读，D-1）；**机构/部门层级数据修复（D-3）** | 权限骨架可跑通 + 层级数据可验证 | — |
| M1 查询工具 | 6 个查询工具 + `AiPermissionGuard` + 数据范围过滤（含省级/市级层级判定）+ 字段分级脱敏 | 一期后端 | M0 |
| M2 提示词与前端 | 提示词改造（含 D-2 不支持清单、能力开关说明）、系统管理查询结果展示、菜单权限过滤 | 一期前端 | M1 |
| M3 一期验收 | TEST-01~04/08/09/10/12/13/14、AC-01~13 | 一期上线 | M2 |
| M4 提案与审计骨架 | 两张新表（**审计表含按月分区**，D-5）、`AiOperationProposalService`、`SensitiveFieldMasker`、`OperationAuditService`（含 90 天护栏与范围规则）、归档任务与容量巡检、SSE 新事件、确认/拒绝/详情接口、过期定时任务、按权限裁剪工具注册、`queryMyToolCalls` | 提案机制 + 审计基座（无具体业务动作） | M3 |
| M5 写工具与 CRUD | `guarantee-system` 补齐 CRUD（**用户仅 update/status/roles 三个接口**，D-2）+ 6 个 `propose*` 工具 + 危险动作保护 + 令牌撤销 | 二期后端 | M4 |
| M6 前端交付 | 前端确认卡、倒计时、二次确认、刷新恢复、待办角标；**机构配置页改树形（D-3a，含 `GET /api/system/orgs/tree` 对接）** | 二期前端 | M5 |
| M7 审计统一 | 页面写操作接入审计、审计查询页与助手查询 | 审计闭环 | M6 |
| M8 二期验收 | TEST-05~07/11/15~23、AC-14~26 + AC-27~34 | 二期上线 | M7 |

---

## 12. 风险与应对

| 编号 | 风险 | 影响 | 应对 |
| --- | --- | --- | --- |
| RK-01 | 模型解析参数错误（把"广东省第2机构"解析成错误 orgCode） | 改错数据 | 确认卡强制展示"用户原话 + 解析参数"（SYS-C-15）；危险动作二次确认；参数歧义必须澄清（SYS-W-10） |
| RK-02 | 权限校验遗漏（AI 层有校验、Service 层没有） | 越权风险 | Service 层是最终边界：`@PreAuthorize` + 数据范围在 Mapper 层注入；TEST-10 覆盖 |
| RK-03 | 存量库权限码缺失导致工具全部无权限 | 功能不可用 | 提供幂等补数脚本；启动时做权限码一致性自检并告警（SYS-P-13） |
| RK-04 | 提示词变长导致工具选择准确率下降 | 回答质量下降 | 细节写进 `@Tool` 描述、提示词只写规则；上线前用固定问题集做回归评分 |
| RK-05 | 提案被遗忘在 `PENDING` | 用户以为已生效 | 15 分钟过期 + 前端倒计时 + 会话内提示"有 1 个待确认变更" |
| RK-06 | 用户/角色写操作导致他人会话异常 | 体验问题 | 变更前在确认卡明示影响面；令牌撤销而非静默失效 |
| RK-07 | ~~演示数据 `parent_id` 全为 0~~ → **已决策修复（D-3）**，风险转为"修复引入数据变动" | 回归风险 | 按 SYS-P-19 评估既有断言与演示脚本；TEST-13/AC-11/AC-12 专门验证 |
| RK-08 | 敏感字段从 VO 泄漏（例如某 VO 未来加了 phone） | 合规风险 | 工具层做**白名单式**组装返回（只挑允许字段），而不是直接序列化 VO；SYS-P-11 分级字段集 + TEST-12 |
| RK-09 | ANALYST 获得系统管理只读后，用户明细可能被过度聚合 | 敏感面扩大 | 单次返回上限 50 + ANALYST 字段收窄（SYS-P-11）+ 无全局审计可见性（D-1a）；上线后观察 ANALYST 的查询日志 |
| RK-10 | D-2 收敛后用户仍持续提出"新建账号/重置密码" | 体验落差 | SYS-N-10 明确话术 + 页面保留人工路径；若诉求频繁，作为下一期独立需求评估（含密码分发与安全审批流程） |
| RK-11 | **现存泄漏（Q-15 暂缓后仍然存在）**：`SysUserMapper.xml` 的 `voCols` 与 `UserVO` 直接返回明文 `phone`/`email`，服务端无脱敏，且系统管理页面与 AI 工具**共用同一 VO** | 合规风险（已存在，非本次引入） | 本期按 Q-15 结论**不改页面**，仅在工具返回值、审计、提案、`ai_tool_call` 落库四处接入 `SensitiveFieldMasker`；由此产生"页面明文 / 助手掩码"的不一致被**有意接受**；后续如需收敛，再启动页面掩码改造 |
| RK-12 | 脱敏逻辑分散在三处（审计写入、工具返回、`ai_tool_call` 落库）导致实现不一致，出现旁路 | 脱敏被绕过 | 统一由 `SensitiveFieldMasker` 承担（6.2），禁止各处以正则自行拼接；TEST-18 覆盖三处 |
| RK-13 | 回收 ANALYST 审计可见性后，其"变更复盘"诉求转为人工 | 效率落差 | 提供 `queryMyToolCalls` 自查能力 + SYS-N-12 明确话术；若 ANALYST 确需本省变更视图，可作为独立需求评估"机构维度受限审计视图" |
| RK-14 | **滚动归档静默失效**：归档任务失败或未配置时，"24 个月在线窗口"会悄悄退化为"永久在线"，且短期内无感知（系统管理域量级小，症状可能要数年后才显形） | 容量与合规风险延迟暴露 | SYS-A-18 要求把"在线行数 / 最老在线记录时间 / 归档成功数"纳入巡检并告警；TEST-22 覆盖归档任务行为 |
| RK-15 | 未来把**订单域写操作**纳入同一张审计表 | 容量量级跳变（年 41 GB、日均 4.5 万行），当前分区与在线窗口方案需重新评估 | 2.2 已明确排除；若纳入需单独立项，重新做容量评估（5.7.3 已给出参考量级） |

---

## 13. 工作量初步评估

| 阶段 | 后端 | 前端 | 测试 | 合计（人日，粗略） | 变更说明 |
| --- | --- | --- | --- | --- | --- |
| M0 | 4 | 0.5 | 1.5 | 6 | ↑ 含 D-3 层级数据修复与既有依赖排查 |
| M1 | 6 | 0 | 2 | 8 | — |
| M2 | 1 | 3 | 1 | 5 | — |
| M3 | 0 | 0 | 3.5 | 3.5 | ↑ 新增 TEST-12/13/14 与 AC-09~13 |
| M4 | 8 | 0 | 2.5 | 10.5 | ↑ 新增 `SensitiveFieldMasker`（D-4/SYS-A-09 三处共用）、按权限裁剪工具注册（SYS-P-12a）、`queryMyToolCalls` 与 `queryOperationAudit` 的范围规则；**审计按月分区 + 归档任务 + 容量巡检（D-5）** |
| M5 | 5 | 0 | 2 | 7 | ↓ D-2 去掉用户新建与密码重置（原 8/2.5） |
| M6 | 1 | 5 | 2 | 8 | ↑ 含机构页树形改造（D-3a）与树形专项测试（TEST-21） |
| M7 | 3 | 2 | 1 | 6 | — |
| M8 | 0 | 0 | 3 | 3 | — |
| **合计** | **28** | **10.5** | **20.5** | **≈59 人日** | 较 v1.3 的 57.5 增加 1.5（分区、归档任务与容量巡检） |

> 与 v1.0 的差异来源：**D-2 减少约 4 人日**（用户新建、密码重置及其确认卡与测试）；**D-3 增加约 2.5 人日**（层级数据修复 + 既有依赖排查 + 专项验证）；**D-1a/D-4 增加约 4 人日**（统一脱敏工具、三处接入、工具注册裁剪、自查工具与权限/范围测试）；**D-3a 增加约 2 人日**（机构页树形改造与专项测试）；**D-5 增加约 1.5 人日**（按月分区、归档任务、容量巡检与护栏）。

> 评估不含需求评审、UI 设计与上线运维时间；`guarantee-system` 缺失的 CRUD 接口是工作量最大的隐性成本。

---

## 14. 需求追踪矩阵

| 用户故事 | 功能需求 | 验收标准 |
| --- | --- | --- |
| US-Q-01/02 | SYS-Q-01/02、SYS-P-15~17 | AC-01/02/03/11 |
| US-Q-03 | SYS-Q-03、SYS-P-11 | AC-01/04/09 |
| US-Q-04 | SYS-Q-04 | AC-01/02/09 |
| US-Q-05 | SYS-Q-05 | AC-01 |
| US-Q-06 | SYS-Q-06、SYS-A-06/A-10 | AC-07/AC-29 |
| US-Q-06a | SYS-Q-06a | AC-30 |
| US-Q-07 | SYS-Q-06、SYS-A-01~10 | AC-07/AC-23/AC-27/AC-28/AC-29 |
| US-W-01 | SYS-W-04、SYS-C-01~14 | AC-14~AC-19/AC-25 |
| US-W-02 | SYS-W-04 | AC-15/AC-21/AC-25 |
| US-W-03 | SYS-W-02 | AC-15/AC-20 |
| US-W-04 | SYS-W-01 | AC-15/AC-22 |
| US-W-05 | SYS-C-10 | AC-16 |
| US-W-06 | SYS-W-06、SYS-C-13 | AC-14 |
| （D-1 决策支撑） | SYS-P-11/12/13、SYS-N-11 | AC-09/AC-10 |
| （D-2 决策支撑） | SYS-N-10、5.2.3 用户表 | AC-13/AC-25 |
| （D-3 决策支撑） | SYS-P-15~26 | AC-03/AC-11/AC-12 |
| （D-1a 决策支撑） | SYS-P-12a、SYS-Q-06a、SYS-N-12 | AC-29/AC-30 |
| （D-4 决策支撑） | SYS-A-02a/02b/09/10/11、SYS-N-13 | AC-27/AC-28 |
| （D-3a 决策支撑） | 5.4 SYS-C-16~24、TEST-21 | AC-31/AC-32 |
| （D-2a 决策支撑） | 5.2.2 D-2a 说明、2.2 | AC-13（延续"不支持"口径） |
| （D-5 决策支撑） | 5.7.3 SYS-A-12~18、5.7.1 分区说明、TEST-22/23 | AC-33/AC-34 |

---

## 15. 决策记录与遗留问题

### 15.1 已定稿决策（本次评审结论）

| 编号 | 决策项 | 结论 | 文档落点 |
| --- | --- | --- | --- |
| **D-1** | `ANALYST` 的系统管理权限 | **给只读**：授予 `ai:system:query` + 机构/部门/用户/角色/险种 `:view`；**不授予任何写权限与 `ai:system:write`**；`queryUser` 对其额外收窄字段（无 phone/email/lastLoginAt） | 3. 角色表、5.5.2、5.5.3 矩阵、SYS-P-11/12/13、AC-09/AC-10、RK-09 |
| **D-1a**（原 Q-11） | `ANALYST` 的全局操作审计可见性 | **回收** `system:audit:view`：全局操作审计仅 ADMIN 可见。根因——审计目标中的 ROLE/PERMISSION 类无机构归属，数据范围无法收敛；且字段级前后值快照会绕过 SYS-P-11 的字段收窄。替代能力：ANALYST 用 `queryMyToolCalls` 查看**自己**的工具调用记录 | 3. 角色表、5.1.7、5.1.8（新工具）、5.5.3 矩阵、SYS-P-12a、SYS-A-06/A-10、SYS-N-12、TEST-16/17/19、AC-29/AC-30、RK-13 |
| **D-2** | 用户写操作范围 | **只做 UPDATE / ENABLE-DISABLE / ASSIGN_ROLES**；**不做新建用户、不做密码重置**（不新增 `system:user:create` / `:reset-password` 权限码，不新增对应接口）；助手遇此类诉求须明确回复不支持 | 5.2.2 D-2 说明、5.2.3 用户表、5.5.2、6.1、SYS-N-10、TEST-14、AC-13/AC-25、RK-10 |
| **D-2a**（原 Q-12） | 新建账号与密码重置 | **确定单独立项**，不在本需求交付；本期仅登记边界并预留扩展点（`propose*` 模式、权限码位置、审计脱敏规则），后续接入无需重构提案机制 | 5.2.2 D-2a 说明、2.2、SYS-N-10、TEST-14、AC-13、RK-10 |
| **D-3** | 机构层级数据 | **补层级**：新增 1 个总部节点，每区域第 1 个机构为省级、其余为市级，`parent_id` 与 `org_level` 正确化；部门层级同步修复；演示账号归属显式指定 | SYS-P-15~26、5.6.1、6.2、TEST-03/13、AC-03/AC-11/AC-12、RK-07 |
| **D-3a**（原 Q-13） | 机构配置页展示形态 | **改为树形**：`Orgs.vue` 按 `parent_id` 呈现总部→省级→市级三级树，支持展开/收起与过滤保留祖先链；现有分页接口与 `PageResult` 结构保持不变；部门页本期不改 | 5.4（SYS-C-16~23）、6.1、M6、TEST-21、AC-31/AC-32 |
| **D-4** | 审计前后值中的敏感字段 | **只记录字段名与"是否变更"，不记录具体值**；脱敏在**写入前**完成，且与操作者角色无关（ADMIN 同样脱敏）；同类脱敏覆盖 `ai_tool_call` 与 `ai_operation_proposal` | SYS-A-02a/02b/09/10/11、SYS-N-13、6.2（`SensitiveFieldMasker`）、TEST-15/18、AC-27/AC-28、RK-12 |
| **D-5**（原 Q-6） | 审计保留期与归档 | **不做"永久在线"**：**在线 24 个月（滚动）+ 归档 36 个月，总 5 年**。实现为**按月 RANGE 分区 + 到期 `DROP PARTITION` 导出归档**；审计查询强制 ≤90 天以配合分区裁剪；快照单行上限 8 KB；日增 >500 或在线 >100 万行时收缩到 12 个月。依据：系统管理域即便按压力档 P2，在线 2 年也仅 14.6 万行 / 438 MB，真正需要归档的是未来可能纳入的订单域（年 41 GB） | SYS-A-08、**5.7.3（容量估算）**、SYS-A-12~18、5.7.1 分区说明、TEST-22、AC-33/AC-34、RK-14、M4 |

### 15.2 已确认的次要结论

| 编号 | 问题 | 结论 |
| --- | --- | --- |
| Q-2 | 6 个查询工具是否合并为 1 个泛型工具？ | **一实体一工具**：工具描述精确、参数校验严、模型选型稳 |
| Q-4 | 确认卡是否支持在线编辑参数后确认？ | **不支持**：编辑会引入"确认的到底是什么"的歧义；先收集使用反馈，后续版本再评估 |
| Q-7 | 是否需要"操作撤销"（执行后 N 分钟内可回滚）？ | **不做**：通过"再发一次反向提案"即可满足，避免状态复杂化 |
| Q-8 | 是否需要助手主动"待办提醒"（进入系统提示有待确认提案）？ | **仅会话内提示起步**，不做全局角标 |
| Q-9 | 页面侧写操作是否必须在二期完成统一审计接入？ | **必须**（SYS-A-07）：否则"谁改了什么"仍有盲区 |
| Q-10 | 是否需要把系统管理查询结果做成图表/卡片？ | **一期纯文本 + 表格**：系统配置数据以列表/明细为主，图表价值低 |

### 15.3 已决策事项（原遗留问题，均已定稿）

| 编号 | 问题 | 结论 | 生效版本 |
| --- | --- | --- | --- |
| Q-6 | 审计日志保留期与归档策略？ | **不做"永久在线"（D-5）**：在线 24 个月 + 归档 36 个月（总 5 年），按月分区滚动 `DROP`；容量估算与阈值见 **5.7.3** | v1.4 |
| Q-12 | 新建账号/密码重置是否单独立项？ | **确定单独立项（D-2a）**：不在本需求交付，本期仅登记边界并预留扩展点（含密码分发与安全审批流程） | v1.3 |
| Q-13 | 机构页是否呈现树形结构？ | **确定改为树形（D-3a）**：`Orgs.vue` 按 `parent_id` 呈现三级树，需新增全量数据接口（`SYS-C-21/24`） | v1.3 |
| Q-14 | 数据范围是否需要"跨机构代管"例外？ | **不做**：当前无此业务流程，避免权限模型复杂化 | v1.2 |

### 15.4 暂缓项（本期明确不处理，仅登记备查）

| 编号 | 问题 | 本期处理 | 备注 |
| --- | --- | --- | --- |
| Q-15 | 系统管理页面的用户列表当前展示明文手机号/邮箱（`SysUserMapper.voCols` 直出、服务端零脱敏），是否同步改为掩码展示？ | **暂缓，本期不改页面**。本期只对**助手工具返回值**与**审计/提案/工具调用落库**做脱敏（SYS-P-11 / D-4 / SYS-A-09），页面保持现状 | 沿用**既有功能行为不变**，避免本期引入预期外的页面变更；副作用是"页面可见明文、助手只见掩码"的不一致将保留。相关风险仍登记在 RK-11，如需收敛再启动 |
| Q-16 | 是否为 ANALYST 提供"机构维度受限审计视图"（仅本省 USER/ORG/DEPT 变更）？ | **暂缓，本期不提供**。ANALYST 的审计诉求由 `queryMyToolCalls`（仅本人）+ 人工路径承接 | 沿用 D-1a 的结论；若后续 ANALYST 反馈变更复盘刚需，再按独立需求评估 |

> 量级参考：系统管理域审计在压力档（日均 200 次写操作）下，**在线 24 个月约 14.6 万行 / 438 MB**，因此本方案下系统管理域**长期不会触发归档**——归档机制先建好、长期空转，真正需要它的是未来若纳入的订单域（日均 4.5 万行 / 年约 41 GB）。完整推导见 5.7.3。

---

## 16. 附录

### A. 现状代码索引（改造时直接定位）

| 关注点 | 位置 |
| --- | --- |
| 工具注册（只注册 READ） | `guarantee-ai/.../tool/AiToolRegistry.java` |
| 工具审计装饰器 | `guarantee-ai/.../tool/RecordingToolCallback.java` |
| 工具调用落库与审计 | `guarantee-ai/.../tool/AiToolCallRecorder.java` |
| 工具上下文键 | `guarantee-ai/.../tool/AiToolContextKeys.java` |
| 工具循环与 SSE 事件 | `guarantee-ai/.../service/AiChatService.java`（`runToolLoop`、`buildToolCallingOptions`、`buildToolContext`） |
| 提示词 | `guarantee-ai/src/main/resources/prompts/business-assistant.st` |
| 对话接口 | `guarantee-ai/.../controller/AiController.java` |
| SSE 事件定义 | `guarantee-ai/.../vo/ChatStreamEvents.java` |
| 权限来源（claims） | `guarantee-auth/.../security/JwtTokenProvider.java` |
| 身份上下文写入 | `guarantee-auth/.../security/JwtAuthenticationFilter.java` |
| 业务侧身份 | `guarantee-common/.../security/CurrentUser.java` |
| 安全配置（无方法级鉴权） | `guarantee-auth/.../config/SecurityConfig.java` |
| 权限码定义与角色分配 | `guarantee-web/.../init/DataInitializer.java`（`PERMISSIONS`、`ROLES`、`seedRolesAndPermissions`） |
| 系统管理服务 | `guarantee-system/.../service/*.java` |
| 表结构 | `guarantee-web/src/main/resources/db/schema.sql` |
| 前端流式解析 | `frontend/src/utils/chatStream.ts` |
| 前端助手组件 | `frontend/src/components/AiCopilot.vue` |
| 前端权限store | `frontend/src/stores/user.ts` |
| 既有 AI 集成测试 | `guarantee-web/src/test/java/com/guarantee/web/ai/AiToolChainIT.java`、`StubToolCallingChatModel.java` |

### B. 需求编号速查（v1.4）

| 前缀 | 含义 | 数量 |
| --- | --- | --- |
| SYS-Q-xx | 一期查询需求（含 `queryMyToolCalls` 的 SYS-Q-06a） | 12 |
| SYS-W-xx | 二期写操作需求 | 12 |
| SYS-C-xx | 确认机制与机构树形需求（SYS-C-16~24 为 D-3a 树形） | 24 |
| SYS-P-xx | 权限与数据范围需求（含 D-3 层级修复 SYS-P-15~26） | 27 |
| SYS-A-xx | 审计需求（含 D-4 脱敏 02a/02b/09~11 与 D-5 容量 12~18） | 18 |
| SYS-N-xx | 提示词需求（含 SYS-N-12/13） | 13 |
| SYS-NF-xx | 非功能需求 | 11 |
| TEST-xx | 测试要求 | 23 |
| AC-xx | 验收标准（P1：01~13；P2：14~34） | 34 |
| RK-xx | 风险项 | 15 |
| D-x | 已定稿决策（D-1/1a/2/2a/3/3a/4/5） | 8 |
| Q-x | 决策记录 / 暂缓项 | 16 |

### C. 评审记录

| 日期 | 版本 | 变更 | 评审人 |
| --- | --- | --- | --- |
| 2026-09-21 | v1.0 | 初稿 | 待填 |
| 2026-09-21 | v1.1 | 按评审结论回填三项决策：**D-1** ANALYST 给系统管理只读；**D-2** 用户写操作收敛为修改/停用/角色分配，不做新建与密码重置；**D-3** 补机构层级数据。连带更新：权限矩阵、写工具清单与用户动作表、权限码清单、接口清单、数据变更、测试要求（+TEST-12~14）、验收标准（重排为连续 AC-01~26）、里程碑、风险（+RK-09/10）、工作量（≈51.5 人日）、需求追踪矩阵、决策记录章节 | 待填 |
| 2026-09-21 | v1.2 | 回填两项决策：**D-1a（原 Q-11）** **回收** ANALYST 的 `system:audit:view`——根因是审计目标中的 ROLE/PERMISSION 无机构归属导致数据范围无法收敛，且前后值快照会绕过字段脱敏；新增替代工具 `queryMyToolCalls`（仅本人）与按权限裁剪工具注册（SYS-P-12a）。**D-4** 审计前后值对敏感字段只记"字段名 + 是否变更"，写入前脱敏且与角色无关；并扩展到 `ai_tool_call` 与 `ai_operation_proposal`（SYS-A-09）。连带更新：5.1.7/5.1.8、权限矩阵、SYS-A-02a/02b/09/10、SYS-N-12/13、接口与数据变更（`SensitiveFieldMasker`）、测试要求（+TEST-15~20）、验收标准（+AC-27~30）、里程碑 M4、风险（+RK-11~13）、工作量（≈55.5 人日）、遗留问题（+Q-15 页面明文手机号、+Q-16 ANALYST 受限审计视图） | 待填 |
| 2026-09-21 | v1.3 | 回填两项决策并登记两项暂缓：**D-3a（原 Q-13）机构配置页改为树形**——新增 5.4 节（SYS-C-16~24），含三级树、过滤保留祖先链、权限联动按钮，以及一个实施要点：树需全量数据，**分页接口会导致树静默缺节点**，故新增 `GET /api/system/orgs/tree`（`OrgVO` 已含 `parentId`/`orgLevel`，无需改 VO）。**D-2a（原 Q-12）新建账号与密码重置确定单独立项**，仅登记边界与扩展点。**Q-15、Q-16 暂缓**：本期只对工具返回值与审计/提案/工具调用落库脱敏，页面行为保持不变（RK-11 相应更新为"已接受的不一致"）。连带更新：6.1 接口、里程碑 M6 与工作量（≈57.5 人日）、测试要求（+TEST-21）、验收标准（+AC-31/32）、需求追踪矩阵、15.3/15.4 章节重排 | 待填 |
| 2026-09-21 | v1.4 | 回填 **D-5（原 Q-6）审计保留期**：新增 **5.7.3 容量估算与保留期策略**——明确估算前提（本期只覆盖系统管理域 5 实体，行数不随订单量增长）、单行开销（2.5~3 KB/行）、P0/P1/P2 三档场景表（含在线 12/24/36 个月的行数与存储），得出"系统管理域容量不构成压力、放弃永久在线的真实理由是合规保留与备份运维"（SYS-A-12），并定稿**在线 24 个月 + 归档 36 个月、按月分区 `DROP`、查询 90 天护栏、快照 8 KB 上限、收缩阈值与容量可观测**（SYS-A-13~18）。连带更新：SYS-A-08、5.7.1 分区键约束说明、里程碑 M4 与工作量（≈59 人日）、测试要求（+TEST-22/23）、验收标准（+AC-33/34）、风险（+RK-14/15）、需求追踪矩阵、15.3 决策记录 | 待填 |
