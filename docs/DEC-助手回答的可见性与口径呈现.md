# 决策：助手回答的可见性与口径呈现

| 项目 | 内容 |
| --- | --- |
| 文档名称 | 助手回答的可见性与口径呈现 |
| 文档版本 | v1.0 |
| 适用系统 | 智能电子保函运营管理平台（guarantee-ai-admin） |
| 涉及模块 | `frontend`、`guarantee-ai`、`guarantee-common`、`guarantee-web` |
| 依据文档 | `docs/REQ-系统管理助手能力.md`、`docs/REQ-登录安全与令牌生命周期加固方案.md` |
| 触发来源 | 真机截图复核：① 正文里 `**投标保函（TENDER）**口径` 的星号原样外露；② 提问「工具调用明细该不该给业务用户看」 |
| 结论 | 三处独立改动：Markdown 修补（前端）、工具明细服务端按权限下发（新权限码 `ai:debug:view`）、口径串改为业务人话 |

---

## 0. 三个问题的定性（先分清，否则会开错药方）

| # | 现象 | 性质 | 处理 |
| --- | --- | --- | --- |
| 1 | `**投标保函（TENDER）**口径` 星号外露 | **前端渲染缺陷** | 修渲染器（§1） |
| 2 | `READ queryOrderSummary SUCCESS 95ms` 工具卡 | **可见性边界** | 服务端按权限不下发（§2） |
| 3 | `数据来源：queryOrderSummary(orderType=TENDER, …)` | **文案口径** | 改写成业务人话，**不隐藏**（§3） |

**第 3 项必须与第 2 项分开**：口径追溯是用户价值（SYS-N-08 明确要求回显），
砍掉它会让人不敢用助手给出的数字。它的问题只是"用了开发者语法"，不是"不该出现"。

---

## 1. 决策一：Markdown 加粗在中文里闭不上 → 改渲染器

### 1.1 根因（实测，不是推测）

CommonMark 的强调定界符有 flanking 规则：

> 闭合 `**` 若**前一个字符是标点**，还必须**后接空白或标点**才算合法闭合。

中文没有词间空格，于是这个组合整对失效、星号原样显示：

```
以上为**投标保函（TENDER）**口径，即全量。
                       ↑ 前是「）」标点，后是「口」汉字 → 闭不上
```

实测对照（项目所用 markdown-it）：

| 源码 | 结果 |
| --- | --- |
| `**投标保函（TENDER）**口径` | ❌ 原样 |
| `**投标保函(TENDER)**口径`（半角括号同样命中） | ❌ 原样 |
| `**投标保函（TENDER）** 口径`（后接空格） | ✅ |
| `**投标保函（TENDER）**，即全量`（后接标点） | ✅ |
| `**投标保函**口径`（内容不以标点结尾） | ✅ |
| `**年初至今的累计值**，`（截图里那条正常的） | ✅ |

**触发条件 = 加粗内容以标点结尾 + 闭合 `**` 紧跟汉字。** 中文里极其常见，必会复发。

### 1.2 方案选型

| 方案 | 可行性 | 结论 |
| --- | --- | --- |
| 闭合后插普通空格 | ✅ | ❌ 中文里多一个显眼空格 |
| 闭合后插 U+200B / U+FEFF | ❌ | markdown-it 不认作空白，补了无效 |
| 闭合后插 U+200A 发丝空格 | ✅ | ❌ 往正文塞了不可见字符，复制会带走 |
| **渲染后修补文本节点** | ✅ | ✅ **采用**：一个字符都不动正文 |
| 改提示词让模型换写法 | ⚠️ | ❌ 模型不保证遵守，且会让中文多出空格 |
| 自定义 markdown-it inline 规则 | ✅ | ❌ 需重写强调解析（~60 行解析代码），收益不匹配 |

### 1.3 落地

`frontend/src/utils/markdown.ts` 新增 `repairUnclosedEmphasis(html)`，在 `md.render()` 之后调用。

**为什么在渲染结果上修补而不是改源码**：markdown-it 已经消费掉所有**语法合法**的强调，
剩下的字面量 `**` 必定是它放弃处理的那些，补成 `<strong>` 恰好是作者本意。

**两条必须守住的约束**：

| 约束 | 原因 |
| --- | --- |
| 跳过 `<code>` / `<pre>` 内部 | 代码块里的 `**` 必须保持字面量，否则会把示例代码改坏 |
| 只处理两个标签之间的文本节点 | 不碰标签与属性，避免改坏链接 `href` 里的 `**` |

**安全性**：入参是 `html:false` 下 markdown-it 已转义的 HTML（模型输出的 `<script>`
早已变成 `&lt;script&gt;`），这里只额外插入自己构造的 `<strong>`，不引入新的注入面。

**验收**：`node scripts/verify-markdown-emphasis.mts`，11 项全过（含代码块、`href` 属性、
奇数个 `**`、XSS 四条反向断言）。

> 用独立脚本而非单元测试：本项目前端未引入测试运行器，与 `scripts/verify-dept-tree-shape.mjs`
> 等既有校验脚本保持一致。

---

## 2. 决策二：工具调用明细 → 服务端按权限下发（新增 `ai:debug:view`）

### 2.1 先纠正一个前提：前端隐藏 ≠ 用户看不到

`tool_call` 事件按契约携带**完整的入参与返回 JSON**：

```json
{"id":1,"toolName":"queryOrderSummary","toolType":"READ",
 "arguments":"{...}","result":"{...}","status":"SUCCESS","durationMs":12}
```

前端 `v-if` 只能让界面不显示，**数据仍在 SSE 响应体里**，浏览器开发者工具一开就能看到。
所以"隐藏"要分清是哪种：

| 目标 | 手段 |
| --- | --- |
| 界面上不显示 | 前端 `v-if` |
| **用户拿不到这些数据** | **服务端不下发** ← 本次采用 |

### 2.2 为什么不采用「全局开发者模式」

| 问题 | 说明 |
| --- | --- |
| 与角色脱钩 | 把"谁能看调试信息"变成运维配置，同一个 URL 在环境 A 能看、环境 B 不能看，容易扯皮 |
| 全站横切 | 以后每个功能都要回答"开发者模式下要不要变样" |
| 不可审计 | 谁在开发者模式下看了什么，无从追溯 |

改为**复用本系统已有的权限机制**：新增权限码 `ai:debug:view`，与另外 60 余处
`@PreAuthorize`、`PermissionCatalog` 同一套概念，不引入新名词。

### 2.3 落地

| 位置 | 改动 |
| --- | --- |
| `guarantee-common` `Permissions` | +`AI_DEBUG_VIEW = "ai:debug:view"` |
| `guarantee-web` `PermissionCatalog` | +`{"ai:debug:view", "AI 调试信息", null}`；**只给 ADMIN** |
| `guarantee-ai` `AiChatService.stream` | `sink.asFlux().filter(toolCall -> toolDetailVisible).map(...)` |

**三个实现要点**：

1. **用 `filter` 而不是"不并入流"**：sink 是 `unicast + onBackpressureBuffer`，
   无人订阅会一直缓冲。`filter` 保证事件始终被消费掉，只是不下发。
2. **判定失败时收敛为不可见（fail-closed）**：拿不到权限快照时不给调试信息，而不是默认放开。
3. **工具执行与 `ai_tool_call` 落库完全不受影响**：隐藏的只是推给浏览器的过程事件，
   审计照旧完整。这一点由 `AiToolChainIT#toolCallEventsAreNotSentWithoutDebugPermission`
   显式断言（既断言"没有 `tool_call` 事件"，也断言"工具仍被执行 + 仍落库 1 条"）。

### 2.4 「谁是开发者」怎么配

- 默认只授予 ADMIN（`PermissionCatalog.ADMIN_PERMISSIONS` 是全量，自动包含）。
- 非 ADMIN 的开发者账号：在「系统管理 → 角色配置」里给其角色勾上「AI 调试信息」即可，
  无需改代码。**唯一需要改代码的场景**是希望它默认属于某个内置角色。
- `PermissionSyncInitializer` 会在启动时幂等补入该权限码并绑定到 ADMIN，
  **存量库不需要迁移脚本**（RK-03 关心的正是这件事）。

---

## 3. 决策三：口径串从「函数调用样式」改为「业务人话」

### 3.1 问题

各工具原先各自拼 `"queryOrderSummary(orderType=TENDER, startDate=…)"`。
这个串会被模型**逐字抄进正文**（提示词第 39 条要求回显来源），所以它是**给业务用户看的正文**，
不是日志。而它的形态是函数调用：工具名对用户无意义、参数名是英文、值还是枚举码。

### 3.2 落地：把渲染收口到一处

新增 `guarantee-ai/.../tool/DataSourceText.java`，各工具改为
`DataSourceText.of("<业务域名>", parts)`。渲染规则：

| 规则 | 理由 |
| --- | --- |
| 跳过"未生效"的条件（`不限` / `null` / `false`） | 这是把"参数转储"变成"口径说明"的关键；列一堆「不限」只是噪音 |
| 全部条件都未生效 → 显式写「未加过滤（全量）」 | 「什么都没过滤」本身就是重要口径，显示成空白会让人误以为已按他的范围过滤 |
| `true` 的布尔量只输出标签（`含已删除`） | 不写 `includeDeleted=true` |
| `status` 的 1/0 译成 `启用`/`停用` | — |
| `limit` 一律不展示 | 分页细节；截断另由 `ToolResultMeta.truncated` 表达 |
| **未登记的键按原样输出** | 宁可偶尔露出一个英文键，也不静默吞掉一个查询条件——那会让回显口径与实际执行的查询不一致，比不好看严重得多 |

改动覆盖 10 处产出点：机构 / 部门 / 用户 / 角色与权限 / 险种 / 订单统计 /
我的待确认提案 / 我的工具调用记录 / 操作审计 / 变更提案。

**效果对照**（即截图那一行）：

```
改前：数据来源：queryOrderSummary (orderType=TENDER, startDate=2026-01-01, endDate=2026-12-31)
改后：口径：订单统计 · 险种：投标保函 · 时间区间：2026-01-01 ~ 2026-12-31
```

### 3.3 提示词同步

`business-assistant.st` 第 39 条由"给出工具名与查询条件"改为：

> 在回答末尾单独一行说明数据口径；内容必须**逐字引用**工具返回值的 `dataSource`，
> 整行格式为 `口径：<dataSource>`；禁止自行改写、补充、压缩或编造，也不要把它"还原"成
> `queryOrderSummary(orderType=TENDER, …)` 这类样式。

**为什么强调"逐字引用"**：模型复述本身就是编造风险面（提示词第 32 条正在防"编造提案编号"，
是同一类风险）。让模型抄一个已经写好的串，比让它自己组织一段口径更可靠。

> 更彻底的形态是**前端用结构化字段渲染口径条，完全不让模型复述**（可从根上消除编造）。
> 本期未做：它要改工具返回结构与前端渲染，而当前"逐字引用"已经能覆盖主要风险，
> 留作后续可选项。

### 3.4 连带确认

- `ProposalClaimGuard` **不解析「数据来源」字样**（它按"提案 + 声称动词"判定，
  见 `CLAIM_CUES`），因此改格式不影响该兜底逻辑；`ProposalClaimGuardTest` 里出现该字样
  只是"无关业务文本"的夹具内容。
- `RoleProposalTool` 的工具说明里保留 `queryRole(mode=…)`：那是**给模型看的调用指引**，
  模型必须知道工具名才能调用，与"给用户看的正文"是两回事。

---

## 4. 权限契约变更登记

`Permissions` 的既有约定是「权限码一旦下发即视为契约，只能新增、不可改名」。本次新增：

| 权限码 | 名称 | 默认持有者 | 作用 |
| --- | --- | --- | --- |
| `ai:debug:view` | AI 调试信息 | ADMIN | 是否向浏览器下发 AI 工具调用明细（`tool_call` SSE 事件） |

`PermissionSyncInitializer` 会幂等补入权限码并绑定 ADMIN，存量库无需迁移脚本。

---

## 5. 决策四：确认卡里的角色显示中文名而不是编码

### 5.1 现象与根因

真机确认卡的「原值 / 新值」显示 `ADMIN` → `ANALYST`，而同一屏的正文却写「该用户将从超级管理员降为数据分析师」——
**同一件事，两种说法**。

根因是角色**编码**被直接拼进了给用户看的文本：

| 位置 | 原实现 |
| --- | --- |
| `UserProposalTool`（ASSIGN_ROLES 的变更明细） | `String.join(", ", currentRoles)` / `targetRoles` |
| `UserService.assignRolesImpact`（影响面） | `impact.put("当前角色", listRoleCodesByUserId(...))` |
| `UserService.stopImpact` / `deleteImpact`（持有角色） | 同上 |

### 5.2 落地

`UserService` 新增 `roleDisplayNames(Collection<String>)`：批量按编码查 `sys_role.role_name`，
**查不到的编码原样返回**（宁可偶尔露出一个编码，也不能吞掉一个角色——那会让影响面与实际变更不一致）。
上述三处影响面 + `UserProposalTool` 的变更明细全部改用它；分隔符由半角 `", "` 改为全角 `"，"`。

**一条容易写错的边界**：`Roles.ADMIN.equals(...)`、`roles.contains(Roles.ADMIN)` 这类**逻辑判定**
必须继续用编码。`stopImpact` / `validateAssignRoles` 里因此同时存在"编码版"与"展示版"两个列表，
不能图省事合并——用展示名做逻辑判断会在角色改名后静默失效。

### 5.3 已知边界：已存的待确认提案不会被改写

`ai_operation_proposal.preview_payload` 是**生成时的快照**，`getProposal` / `listProposals`
读的就是它。因此本修复只对**新生成**的提案生效；此前已 PENDING 的卡片仍显示编码。
这是有意的——快照代表"当时给用户看过并据以确认的内容"，事后重算会让它与用户实际看到的
不一致。要看到中文名，拒绝旧提案后重新发起即可。

---

## 6. 决策五：复用既有待确认提案时，必须照样推送确认卡

### 6.1 现象

用户第二次说「**再**把它改回数据分析师」，助手正文写了完整的影响面并说
「请在确认卡上点击「确认执行」以生效」，**但卡片没有出现**；用户追问后才拿到卡片。

### 6.2 根因（确凿缺陷）

`ProposalService.create` 有两条返回路径，但**只有新建路径推送 SSE**：

```java
if (!existing.isEmpty()) {
    // 同会话同目标同动作已有 PENDING：复用，避免重复生成
    return toPayload(pending, draft.preview(), draft.userText());   // ← 没有 publishProposal
}
...
eventPublisher.publishProposal(...);   // ← 只有新建路径才有
```

原注释写的意图是「让前端复用同一张确认卡」——**前提假设是"那张卡还挂在屏上"**。
而卡不在屏上的情形很常见：刷新过页面、切过会话、前端刚做过一次待确认列表刷新（`refreshProposals`
会**整体覆盖** `proposals.value`），或者在另一台设备 / 另一个标签页发起。此时模型正文照样说
"请点击确认卡"，用户就只看到"说生成了提案、却没有卡片"。

### 6.3 落地

复用分支同样调用 `eventPublisher.publishProposal(...)`。**重复推送是安全的**：前端
`upsertProposal` 按 `proposalId` 去重（`AiCopilot.vue` 的注释已经写明"SSE 可能重复推送同一提案，
后端会复用既有提案"）——也就是说前端的去重逻辑本来就是为这个场景准备的，只是后端漏了推送这一步。

### 6.4 现场确认方法

后端日志里有一条**专为这个故障写的** INFO（`ProposalEventPublisher` 的 WARN 是另一种形态）：

```
会话 {id} 已存在同目标同动作的待确认提案 {proposalNo}，本次不重复生成
```

若故障时刻附近出现该行，即确认命中了本缺陷。另有配套 WARN：

```
会话 {id} 没有活跃的提案通道，提案 {id} 仅落库，本轮前端不会出现确认卡
```

---

## 6.5 排查决策五时顺带修正：`ProposalFlowIT` 数了软删除的历史行

`ProposalFlowIT` 有一条前置断言：

```sql
SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
WHERE r.role_code = 'ADMIN'          -- ← 缺 is_deleted = 0
```

关联表用 **UPSERT** 语义（见 `DEC-逻辑删除设计方案` §4）：取消一个角色分配只把旧行置为
`is_deleted = 1`，历史行仍留在表里。本开发库就有 `sys_user_role.id = 806`
（`user0292`，ADMIN，`is_deleted = 1`）这样的历史行——于是这条断言在**任何一次真实角色变更之后**
必然失败。已补上 `AND ur.is_deleted = 0`。

`LogicalDeleteServiceIntegrationTest` 早就示范了正确写法（同一张表分别按"带过滤 / 不带过滤"
各查一次），此处是漏网的一处。

> 附带确认：这次失败**不是**本轮改动引起的。查库可见 `admin`（`is_deleted=0`）与
> `user0292`（`is_deleted=1`）两行，正是"先改成 ADMIN、现在再改回"的来龙去脉，
> 属真实业务数据；而本轮改动（文案改写、补一次事件推送）都不写 `sys_user_role`。

---

## 7. 决策六：面向用户的文案里不得出现内部技术术语

### 7.1 现象

二次确认弹窗写着：

> 影响面：当前角色 超级管理员；变更后角色 数据分析师；影响 **该用户持有的 JWT 将被撤销**，
> 其权限变更立即生效，需重新登录。确认执行吗？

角色名已经中文化（决策四），但 **`JWT` 是纯内部术语**——运营与管理人员不知道它是什么，
要么忽略这句话，要么误以为要发生什么危险的事。

### 7.2 这不是孤立的一处

排查后发现同一类问题共 **11 处用户可见文案 + 3 处给模型看的说明**：
`UserService`（停用/角色分配/删除影响面）、`RoleService`（授权/删除影响面）、
`UserProposalTool`、`RoleProposalTool`、`UserProposalExecutor`、`RoleProposalExecutor`、
前端 `Users.vue` 的确认弹窗，以及两个写工具的**工具说明**与提示词第 25 条的示例
（模型会照抄工具说明里的措辞）。

### 7.3 统一话术

| 原来 | 改为 |
| --- | --- |
| 其持有的 JWT 将被撤销，其权限变更立即生效，需重新登录 | **该用户会被立即强制下线，需要重新登录（新权限随即生效）** |
| 其持有的 JWT 已被立即撤销，需重新登录 | **该用户已被立即强制下线，需要重新登录** |
| 持有该角色的用户 JWT 将被撤销…… | **持有该角色的用户会被立即强制下线，需要重新登录……** |
| 该用户将被立即登出（已签发的登录令牌会被撤销）（前端弹窗） | **该用户会被立即强制下线** |

**说"会被强制下线"而不是"登录状态会失效"**：后者仍然要求用户理解"登录状态"是个什么东西，
而"被强制下线、要重新登录"是他实际会经历的事。

### 7.4 顺带修掉的同类问题

`UserProposalExecutor.assignRoles` 的执行结果消息是
`"用户「X」角色已变更为 " + targetRoles` —— **`targetRoles` 是编码**，
执行完弹出来的提示又变成了 `角色已变更为 [ANALYST]`。这正是决策四那类"编码泄漏"在**执行结果**里的
翻版（决策四只修了提案预览，没覆盖执行结果）。同一个改动里一起改为 `roleDisplayNames(...)`。
同处的 `"涉及超级管理员（ADMIN）角色"` 也去掉了编码。

### 7.5 根治：加一条提示词约束

逐处改写治标不治本——模型仍可能自己造出术语。因此提示词新增第 42 条：

> **面向用户的正文里不得出现任何内部技术术语。** 你的读者是运营与管理人员，不是开发：
> - 禁止出现：`JWT`、`token`、`令牌`、`claims`、`接口`、`字段名`、`枚举`、`表名`、`SQL`、
>   工具名（如 `queryOrderSummary`）、参数名（如 `orderType`）、编码值（如 `TENDER`）。
> - 涉及"登录状态失效"时，一律说**「会被立即强制下线，需要重新登录」**，不要解释背后的机制。
> - 涉及角色时写**角色名称**，不要写角色编码；涉及险种类型时写**业务名称**（投标保函 / 履约保函），
>   不要写 `TENDER` / `PERFORMANCE`。
> - 工具返回值里的 `dataSource` 已经按这个口径写好，照抄即可；若返回值里出现了内部术语，
>   要把它翻译成人话再说给用户，而不是原样复述。

最后一条与第 39 条的"逐字引用 `dataSource`"是配套的：口径行走结构化数据（已经写好人话），
其余正文走这条术语约束。

### 7.6 边界：运维/开发侧消息保留术语

以下**有意不改**——它们的目标读者是运维与开发，`JWT` 在这里是精确的：

| 位置 | 为什么保留 |
| --- | --- |
| `JwtTokenProvider` 的密钥守卫消息（`JWT_SECRET` 未设置 / 长度不足 / 内置默认密钥） | 启动期，给部署人员看，且指引里必须出现环境变量名 `JWT_SECRET` |
| `JwtProperties#setExpireMinutes` 的配置改名提示 | 给改配置的运维看 |
| `TokenRevocationService` 的"登记令牌失败"等 WARN/ERROR | 日志 |
| 类名 `JwtTokenProvider` / `JwtAuthenticationFilter` / `JwtProperties` | 代码标识符 |

用户可见的那一条（`ResultCode.AUTH_UNAVAILABLE`）本来就是「认证服务暂时不可用，请稍后重试」，
不含术语。

---

## 7.5 决策七：集成测试不得污染共享开发库

### 7.5.1 现象：用户问了一句纯查询，界面却冒出两张「停用险种」卡

使用者问「履约保函怎么样」（一个只读问题），助手面板上突然出现两张**停用险种**确认卡，
他从未提过这个诉求。截图里两张卡的「你的原话」还是「把 履约保函（标准） 停用」
「停用 履约保函（预付款）」——不是他这次说的话。

### 7.5.2 根因（查库确认，不是推测）

| 证据 | 值 |
| --- | --- |
| 提案 `conversation_id` | **NULL** |
| 提案 `user_id` | 1（admin，正是当前登录用户） |
| 提案创建时间 | 18:33:05 |
| IT 报告写入时间 | 18:33:22 ~ 18:33:27（同一次 `mvn verify`） |
| 卡片里的影响面文案 | `影响面：{引用订单数=若干}` —— **测试夹具的字面量** |

链路是：`ProposalFlowIT` 用 `conversationId = null` 直接调 `ProposalService.create(...)`
造提案（测试路径本就无会话），**且没有清理**。而这些提案是 `PENDING` 的，前端的
「待确认提案」列表是**按用户全局**返回的（`GET /ai/proposals?status=PENDING`，它设计上
就是一份跨会话待办清单）。于是下一次任何一轮对话结束时，`refreshProposals()` 就会把它们
渲染出来——用户看到的是「我没说过要停用，卡片却自己弹出来了」。

`ai_operation_proposal` 里这类残留累计 **333 条**。

### 7.5.3 顺带发现：测试把险种状态留在了停用

`WebAuditIT#pageInsuranceStatusChangeShouldWriteWebAudit` 会停用「投标保函（标准）」，
而该类的 `@AfterEach` 只清 `CurrentUser`、**从不还原状态**——它的复位靠的是**下一次运行**
的 `@BeforeEach`。只要不再运行，脏状态就一直留着：开发库里 `投标保函（标准）` 的
`status` 实测为 `0`（其余 5 个都是 1），使用者在页面上看到的就是"演示数据被谁停用了"。

审计里的痕迹也印证了这一点：同一种"AI `trace-it-1` + 紧随其后的 WEB 停用"组合在
11:47、12:07、18:33 三次运行里重复出现，与测试运行时刻逐一对齐。

### 7.5.4 落地

| 改动 | 说明 |
| --- | --- |
| 新增 `support/ProposalFixture` | 测试创建的提案**登记 id**，结束时精确删除（含 `ai_operation_secret`）。刻意不按条件批量删——那会连使用者真正待确认的提案一起删掉 |
| `ProposalFlowIT` / `ProposalClaimGuardIT` | 全部创建点用 `fixture.track(...)` 包起来，补上 `@AfterEach` 清理 |
| `ProposalFlowIT` / `WebAuditIT` | 险种状态改为**快照 + 精确还原**（`@BeforeEach` 先记快照再建立前置状态，`@AfterEach` 按快照还原）。原先只有 `@BeforeEach` 的"一律置为启用"，既会把使用者主动停用的险种重新启用，又会在运行结束后留下脏状态 |

**验证**：修复后跑全量 `mvn verify`，`SELECT MAX(id) FROM ai_operation_proposal` 仍为
619（**零新增**，修复前每次运行新增约 6 条），险种状态保持不变（既未被篡改、也未留下新的改动）。

同时清掉了修复前那一次留下的 333 条测试提案。

### 7.5.5 仍然开放的两件事

1. **`投标保函（标准）` 当前是停用状态**（测试造成，未被还原）。恢复它属于改动业务数据，
   因此没有擅自处理。
2. **审计表里的测试噪声**：`ai_operation_audit` 中按目标聚合可见
   `PROPOSAL_CREATED 履约保函（标准） AI 95 条`、`DISABLE … AI 94 条` 等，累计数百行，
   是历次 IT 运行留下的。审计表按产品设计是"只增不改不删"（SYS-A-04），因此也没有擅自清理。

> **根因层面的建议**：以上两条都是"IT 跑在使用者正在使用的同一个库上"的必然结果。
> 治本做法是给集成测试一个**独立的测试库**（或每个用例一个事务并回滚）。当前没做，
> 是因为 IT 依赖演示数据（admin、演示险种、订单），换库需要先把种子数据与初始化顺序
> 一并解决——属独立立项的工作量，此处只登记。

---

## 8. 验收清单

| # | 验收项 | 证据 |
| --- | --- | --- |
| 1 | 中文里"加粗闭不上"已修好，且不误伤代码块 / 链接属性 / 奇数 `**` | `node scripts/verify-markdown-emphasis.mts`，11 项全过 |
| 2 | 无 `ai:debug:view` 时不发 `tool_call` 事件 | `AiToolChainIT#toolCallEventsAreNotSentWithoutDebugPermission` |
| 3 | 同上场景下工具仍执行、`ai_tool_call` 仍落库 | 同一条用例的两个附加断言 |
| 4 | 有权限时行为不变（既有链路不退化） | `AiToolChainIT#shouldReturnRealDatabaseNumbersThroughToolChain` |
| 5 | 口径串规则（跳过未生效条件 / 全量提示 / 布尔标签 / status 翻译 / 未知键不丢） | `DataSourceTextTest`，10 项 |
| 6 | 口径串不再含工具名与英文参数名 | `MyProposalsQueryToolTest` 新增 `doesNotContain("queryMyProposals")` |
| 7 | 角色编码 → 中文名；未知编码不丢；逻辑判定仍按编码 | `UserServiceRoleDisplayTest`，8 项 |
| 8 | **影响面文案不含 `JWT` / `令牌` / `claims`，且不含角色编码** | `UserServiceRoleDisplayTest#impactTextsAvoidInternalJargon` / `#impactTextsAvoidRoleCodes` |
| 9 | 复用待确认提案时照样推送确认卡；新建分支不退化 | `ProposalServiceReusePublishTest`，2 项 |
| 10 | **集成测试跑完不在共享开发库留下待确认提案，也不残留险种状态** | 跑全量 `mvn verify` 后 `MAX(id)` 不变、险种状态不变（§7.5.4） |
| 11 | 全量回归 | `mvn -B verify` BUILD SUCCESS（8 模块）；`npm run build` 通过 |

---

## 9. 变更记录

| 日期 | 版本 | 变更 |
| --- | --- | --- |
| （待填） | v1.0 | 初稿。三处决策：Markdown 中文强调闭合修补、工具调用明细改为服务端按 `ai:debug:view` 下发（新增权限码）、口径串由函数调用样式改为业务人话（新增 `DataSourceText`，覆盖 10 处产出点），含提示词第 32/39 条同步与权限契约登记 |
| （待填） | v1.1 | 追加两处：**决策四** 确认卡的角色显示中文名（`UserService.roleDisplayNames`，改 3 处影响面 + 1 处变更明细；含"逻辑判定仍按编码"的边界与"已存快照不改写"的说明）；**决策五** 复用既有待确认提案时漏推 SSE 的缺陷（`ProposalService.create` 复用分支补 `publishProposal`）。顺带修正 `ProposalFlowIT` 缺 `is_deleted = 0` 的历史行计数 |
| （待填） | v1.2 | 追加**决策六**：面向用户文案清除内部技术术语（`JWT`/`令牌` 共 11 处用户可见文案 + 3 处工具说明/提示词，统一为「会被立即强制下线，需要重新登录」）；提示词新增第 42 条术语约束；顺带修掉 `UserProposalExecutor` 执行结果消息里的角色编码泄漏（决策四的漏网之处） |
| （待填） | v1.3 | 追加**决策七**：集成测试污染共享开发库——`ProposalFlowIT` 留下的 PENDING 提案（`conversation_id` 为 NULL、归属 admin）会在真实使用者的助手面板里渲染成"没人提过的停用确认卡"；`WebAuditIT` 把「投标保函（标准）」留在停用状态。落地：新增 `ProposalFixture` 精确登记并清理、两个 IT 补 `@AfterEach`、险种状态改为快照精确还原。已清理历史残留 333 条测试提案；业务数据还原与审计噪声清理登记为开放项 |
