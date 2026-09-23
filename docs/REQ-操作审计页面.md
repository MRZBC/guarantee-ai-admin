# 需求：操作审计页面 + 菜单（P-07 + P-06-审计项）

| 项 | 值 |
| --- | --- |
| 状态 | **已实现，待你验收**（评审结论：**按建议执行 D1~D5 = A、D6 不引入 Vitest**；实施记录与验证证据见 §13） |
| 承接 | `docs/REQ-系统管理手动操作能力补齐方案.md` §10（P-07 操作审计页）、§9（P-06 菜单与路由权限过滤） |
| 本文档职责 | 不重复设计。只做三件事：① 把既有设计对**当前代码**做一次现状核对；② 补齐既有设计**没定的决策项**；③ 给出可直接验收的规格与涉及文件 |
| 现状核对时间 | 2026-09-23（含开发库实测数据） |

---

## 0. 先看结论：需要你拍板的 5 件事

| # | 决策项 | 选项 | 我的建议 |
| --- | --- | --- | --- |
| **D1** | **翻页能力** | A. 本期零后端改动：只显示"最近 N 条"（N 可选 50/100/200）<br>B. 顺带给接口加可选 `pageNum`/`pageSize`，支持真翻页 | **A**。§13 已明确"审计查询接口不增删改"，A 完全零后端风险；靠"时间区间 + 账号 + 目标 + 动作"缩小范围已能覆盖绝大多数查询。B 列为 P2（改动很小且向后兼容，若你更看重翻页，我按 B 做，+约 0.5 人日） |
| **D2** | **菜单权限过滤范围** | A. 顺带完成 P-06（整站菜单按权限过滤 + 路由 `meta.permission` + `fetchMe` 兜底）<br>B. 只对"操作审计"这一项做局部过滤 | **A**。审计菜单天然依赖它：不做过滤，ANALYST/VIEWER 会看到入口、点进去撞 403（正是 §9.2 记录的 V-4）。且 P-06 已在既有方案里定稿，只是还没实现 |
| **D3** | **与系统页的"跳到该对象审计"联动** | A. 本期不做<br>B. 本期做（后端 controller 加 1 个可选 `targetId` 参数；mapper 已支持） | **A**（列为 P2）。当前需求是"把审计变成可用页面"，联动是锦上添花；且加参数又要碰接口契约 |
| **D4** | **导出（CSV/Excel）** | A. 不做<br>B. 做 | **A**。审计是合规证据，导出要考虑水印/审计导出行为自身留痕，属于独立需求，别混进来 |
| **D5** | **是否同时做 P-08「我的工具调用」页** | A. 不做（本期只做审计）<br>B. 一起做 | **A**。你这次只点了审计；P-08 服务的是 ANALYST 的自查诉求，与审计页受众（ADMIN）不同，分开交付更清楚 |

> 若 D1=A、D2=A、D3=A、D4=A、D5=A（即全按建议），本次是**纯前端**交付：新建 1 个页面 + 1 个字典文件 + api/类型各一处 + 路由与菜单改造，预计 **≈2 人日**（含手工验收，明细见 §10）。
>
> 另有 **D6**（是否引入前端测试框架 Vitest 以便自动化 P07 断言）不在上面 5 项里，默认不引入，见 §11。

---

## 1. 背景与目标

### 1.1 问题：审计数据"有人写，没人看"

审计的**写入**链路是完整的，而且刚被真机验证过（2026-09-23）：

| 渠道 | 触发方式 | 实测行数（开发库 925 行） |
| --- | --- | --- |
| `source=AI` | 助手确认写工具生成的提案（创建 + 执行/拒绝/过期） | 549 |
| `source=WEB` | 页面直连的系统管理写操作 | 376 |

但**没有任何页面能看这些数据**：

- 前端 12 条路由（1 条登录 + 11 个业务页：首页 / 投标订单 / 履约订单 / 数据概览 / 项目 / 企业 / 险种配置 / 机构配置 / 部门配置 / 用户配置 / 角色配置）里**没有审计页**；
- 全仓 `audit` 相关只剩一句自述注释（`frontend/src/utils/status.ts` L66）："**当前未被任何页面调用**（2026-09-22 起，5 个系统管理页面不再展示删除审计列）"；
- 唯一入口是助手的 `queryOperationAudit` 工具，而它**仅 ADMIN**（`system:audit:view`）——等于"系统里最像证据的那张表，只能靠问 AI 才能看到"，而且模型不可用时连这也看不了。

这正是既有方案里登记的缺口 **V-3**（"谁是证据的持有者"）。

### 1.2 目标

1. 提供审计数据的**页面入口**：可按时间区间、操作人账号、目标类型、动作、结果、渠道筛选；可看字段级前后值；可回溯 `traceId`。
2. 菜单出现「操作审计」，且**只有持有 `system:audit:view` 的账号能看到入口**（后端权限不变，前端只解决"看到不该看的入口"）。
3. 不改动后端接口契约（见既有方案 §13 撤销 VO 例外的决议），页面所需的中英映射与 JSON 解析全部在前端完成。

### 1.3 非目标

- 不做审计的增/删/改（`ai_operation_audit` 只增不改不删，SYS-A-04；归档只走分区 `DROP PARTITION`）。
- 不把审计列重新嵌回 5 个系统管理页面（2026-09-22 已刻意移除；如需联动走 D3 的 P2 深链）。
- 不做导出（D4）、不做 P-08（D5）。

---

## 2. 既有设计 vs 本文档补充

| 既有方案已定（直接沿用，不重复论证） | 本文档补充 |
| --- | --- |
| 路由 `/system/operation-audits`、菜单「操作审计」、权限 `system:audit:view`（§10.2） | 现状核对 + 菜单过滤方案（§6，依赖 D2） |
| 筛选字段与约束、表格列、详情抽屉、容量提示（§10.2） | 逐字段接口契约（§3.1）、真实值域字典（§3.2）、空态/错误态、`truncated` 与 `PROPOSAL_CREATED` 的展示口径 |
| 涉及文件清单（§10.3）、测试要点 P07-T1~T6（§10.5） | 新增测试要点 P07-T7~T9、验收标准 AC-38~AC-41 |
| **接口不改**、前端自行做映射与 `JSON.parse`（§10.4 / §10.4a、§13 撤销 VO 例外） | 将"无翻页"这一未写明的限制**显式登记**（§7.1）并给出 D1 决策 |

---

## 3. 现状核对（实测，2026-09-23）

### 3.1 接口契约（已存在，本期不改）

```
GET /api/ai/operation-audits
@PreAuthorize("hasAuthority('system:audit:view')")
```

**请求参数**（`AiController#operationAudits`，全部 Query String）：

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `startDate` | `yyyy-MM-dd` | ✅ | — | 起始日（含当天 00:00:00） |
| `endDate` | `yyyy-MM-dd` | ✅ | — | 结束日（含当天 23:59:59） |
| `operatorUsername` | string | ❌ | — | **按账号**模糊匹配（`LIKE %…%`，**不是姓名**） |
| `targetType` | enum | ❌ | — | `USER/ORG/DEPT/ROLE/PERMISSION/INSURANCE_TYPE`，**精确匹配** |
| `action` | enum | ❌ | — | 见 §3.2，**精确匹配** |
| `result` | enum | ❌ | — | `SUCCESS/FAILED/REJECTED/EXPIRED/PARTIAL`，精确匹配 |
| `source` | enum | ❌ | — | `AI` / `WEB`，精确匹配 |
| `limit` | int | ❌ | `50` | Service 收敛为 `min(limit,200)`；≤0 视同 50 |

**响应**（`Result<AuditPage>`；前端 `http` 已解包 `data`，直接拿到下面这个对象）：

```jsonc
{
  "total": 925,          // 命中总数（countByQuery）
  "items": [ { /* AiOperationAudit 实体，字段见下表 */ } ]
}
```

**`items[]` 字段**（`com.guarantee.ai.entity.AiOperationAudit` 直接序列化；`spring.jackson.default-property-inclusion: non_null` → **null 字段会被省略**，前端类型必须标可选）：

| 字段 | 类型 | 说明 | 页面用途 |
| --- | --- | --- | --- |
| `id` | number | 主键 | — |
| `operatedAt` | string | 操作时间 | 列 + 排序 |
| `operatorUserId` | number? | 操作人 id | 抽屉 |
| `operatorUsername` | string? | 操作人账号 | 列（次要）+ 筛选 |
| `operatorRealName` | string? | 操作人姓名 | 列（主要） |
| `source` | string | `AI` / `WEB` | 列（tag）+ 筛选 |
| `action` | string | 动作码 | 列（中文）+ 筛选 |
| `targetType` | string | 目标类型码 | 列（中文）+ 筛选 |
| `targetId` | number? | 目标 id | 列（`名称(id)`） |
| `targetName` | string? | 目标展示名 | 列 |
| `beforeValue` | string? | 变更前快照，**JSON 字符串** | 抽屉 diff |
| `afterValue` | string? | 变更后快照，**JSON 字符串** | 抽屉 diff |
| `changedFields` | string? | 变更字段，**英文逗号分隔** | 列（tag 列表） |
| `truncated` | number? | `1` = 快照超 8KB 未保留 | 抽屉提示（§5.4） |
| `result` | string | 结果码 | 列（tag）+ 筛选 |
| `errorMessage` | string? | 失败/拒绝/过期原因 | 列（截断）+ 抽屉全文 |
| `proposalId` | number? | 来源提案 id（不是 `proposalNo`） | 抽屉（可复制） |
| `conversationId` | number? | 来源会话 id | 抽屉（可复制） |
| `traceId` | string? | 链路 id（AC-23 回溯） | 抽屉 + 复制按钮 |
| `isDeleted` / `deletedAt` / `deletedBy` | — | 逻辑删除列（LD-01） | **不展示**（审计不提供删除入口） |

**两条必须由前端遵守的硬约束**：

1. **时间区间必填且跨度 ≤ 90 天**（SYS-A-17）。后端会拒绝（`BizException`），前端必须先用 `disabledDate` 挡住，否则用户会看到一句报错。
2. **非 ADMIN 的可见性收窄**：Service 层对 `admin=false` 的调用强制 `allowedTargetTypes = USER/ORG/DEPT`；若显式传 `targetType=ROLE|PERMISSION`，直接 **403**，文案："你没有查看 ROLE 类操作审计的权限（该类记录仅超级管理员可见）"。→ 前端**必须**按账号是否 ADMIN 提供两套 `targetType` 选项（§5.2）。

**当前账号是否 ADMIN 怎么判断**：`useUserStore()` 已提供 `roles` / `permissions` 两个 computed（`stores/user.ts` L27-28），用 `userStore.roles.includes('ADMIN')` 判断（**不要**用"是否持有 `system:audit:view`"代替——自定义角色可能被授予该权限但仍不是 ADMIN）。

### 3.2 真实值域（开发库 925 行实测分布，这决定了字典必须覆盖什么）

| source | action | target_type | result | 行数 |
| --- | --- | --- | --- | --- |
| AI | **PROPOSAL_CREATED** | INSURANCE_TYPE | SUCCESS | **295** |
| AI | DISABLE | INSURANCE_TYPE | SUCCESS | 125 |
| WEB | DISABLE | INSURANCE_TYPE | SUCCESS | 82 |
| WEB | ASSIGN_PERMISSIONS | ROLE | SUCCESS | 80 |
| WEB | UPDATE | USER | SUCCESS | 80 |
| AI | DISABLE | INSURANCE_TYPE | **EXPIRED** | 72 |
| AI | DISABLE | INSURANCE_TYPE | **REJECTED** | 51 |
| WEB | UPDATE | ROLE | SUCCESS | 40 |
| WEB | DISABLE | USER | SUCCESS | 39 |
| WEB | DISABLE | DEPT | SUCCESS | 38 |
| WEB | UPDATE/ENABLE/DELETE/RESTORE | DEPT | SUCCESS | 11 |
| WEB | CREATE | DEPT / ROLE | SUCCESS | 4 |
| WEB | ENABLE | INSURANCE_TYPE | SUCCESS | 2 |

**由此得到 4 条必须落进规格的结论**（既有方案的字典表没覆盖）：

1. **`PROPOSAL_CREATED` 占 32%，必须进字典**（显示为"提案创建"）。它不是"一次变更"，而是提案生命周期的起点（SYS-A-01 要求可追溯），`before/after` 都为空。
2. **`DELETE` / `RESTORE` 必须进字典**（审计里确实有，而助手的 `actionName()` 目前缺这两个，落到英文码——**这是助手侧的一个已知小差异，不在本页范围内，仅登记**）。
3. **`result` 不只有 SUCCESS**：`EXPIRED`(72) / `REJECTED`(51) 也常见，且这些行**没有前后值**（未执行）。表格的"前后值"列对它们显示"无（未执行）"，而不是"查看"。
4. **约 46% 的行没有快照**（`before_value` 为空 422 行 / `after_value` 为空 418 行）→ 前后值列与抽屉必须优雅处理空值。当前 `truncated=1` 为 **0 行**（8KB 截断暂未发生，但代码路径真实存在，必须实现提示）。

### 3.3 前端现状（改造点的真实位置）

| 现状 | 位置 | 结论 |
| --- | --- | --- |
| 菜单是**硬编码常量**，所有登录用户看到同一份，无任何权限过滤 | `frontend/src/layout/AppLayout.vue` `menuGroups`（L20-40） | 新增审计项的同时需按 D2 决定是否做过滤 |
| 路由守卫**只校验登录态**，无 `meta.permission` | `frontend/src/router/index.ts` L101-110 | 同上 |
| 页面权限判断已有既定写法 | 各系统页 `userStore.permissions.includes('code')`（如 `Departments.vue` L58-61） | 沿用，不引入 `v-permission`（§9.4 已明确不做） |
| 可复用的工具函数 | `formatDateTime`（兼容 ISO 串与 `yyyy-MM-dd HH:mm:ss`）、`prettyJson`（解析失败原样返回）、`status.ts` 的 `dictLabel` | 直接复用，不新写解析/格式化逻辑 |
| `/api` 已由 Vite 代理到后端 | `frontend/vite.config.ts` L37-39 | **无需改代理** |
| 尚无审计相关 api / 类型 | `frontend/src/api/ai.ts`、`frontend/src/types/ai.ts` | 各加一处（§9） |

### 3.4 数据与容量

- 保留期（D-5）：**在线 24 个月 + 归档 36 个月**，按月 RANGE 分区；实测分区已覆盖至 `p202812`，并有 `pmax`（MAXVALUE）兜底 → 不存在"插入落到无分区"的风险。
- 审计表**只增不改不删**：页面不提供任何删除/编辑入口，也不要设计"清理按钮"。
- 敏感字段在**写入前**已脱敏为 `<changed>`（`SensitiveFieldMasker.CHANGED_PLACEHOLDER = "<changed>"`），与操作者角色无关；单行快照上限 8KB（`SNAPSHOT_MAX_BYTES = 8*1024`），超限则该行快照置空并 `truncated=1`。

### 3.5 与本次 V5 迁移的关系

本页**只读现有列**，不涉及 `operator_org_id`（该列已于 2026-09-23 按 PLAN §8 Q3 删除）。页面**不设任何"机构"列或筛选项**——机构是服务订单的外部出函机构，不是人的归属维度，审计里从来没有、今后也不会有人所属机构。凡看到"操作人机构"字样的设计稿，一律不采纳。

---

## 4. 范围

**做**：审计页面（筛选/表格/详情抽屉/容量提示）、菜单项、菜单与路由的权限过滤（按 D2）、字典文件、api 与类型、测试。

**不做**：真翻页（D1 默认不选）、对象深链（D3）、导出（D4）、P-08（D5）、审计的写/删接口、把审计列嵌回系统页、按钮级权限指令（`v-permission`）。

---

## 5. 页面规格

### 5.1 路由与菜单

| 项 | 值 |
| --- | --- |
| 路由 | `/system/operation-audits`，name `SystemOperationAudits`，`meta: { title: '操作审计', parentTitle: '系统配置', permission: 'system:audit:view' }` |
| 菜单 | 「系统配置」分组下新增一项「操作审计」，icon 建议 `List`（`main.ts` L15-16 已全局注册全部 Element Plus 图标，任意合法图标名即可用），所需权限 `system:audit:view` |
| 页面标题栏 | **不放常驻说明文字**（评审意见：这类"只增不改不删 / 在线保留 24 个月"的科普属于非必要提示，占地方且每次都要读一遍）。保留期由"默认最近 7 天 + 跨度 ≤90 天"的操作约束体现即可 |

### 5.2 筛选区（`el-form` inline）

| 字段 | 控件 | 默认 | 约束与说明 |
| --- | --- | --- | --- |
| 时间区间 | `el-date-picker` `type=daterange` | **最近 7 天** | 必填；`disabledDate` 限制：不晚于今天、且跨度 ≤ 90 天；清空后点查询 → 前端拦截并提示"请先选择时间区间" |
| 操作人账号 | `el-input` | 空 | 占位符写明"按账号模糊匹配，不是姓名" |
| 目标类型 | `el-select` | 空（全部） | **ADMIN**：6 项；**非 ADMIN**：仅 `USER/ORG/DEPT`（避免必然 403） |
| 动作 | `el-select` | 空（全部） | 字典含 `PROPOSAL_CREATED/DELETE/RESTORE`（§3.2） |
| 结果 | `el-select` | 空（全部） | `SUCCESS/FAILED/REJECTED/EXPIRED/PARTIAL` |
| 渠道 | `el-select` | 空（全部） | `AI`=助手确认 / `WEB`=页面直连 |
| 条数上限 | `el-select` | `50` | `50 / 100 / 200 / **全部**`（评审追加：最高档必须是「全部」）。前三个是"最多取多少条"（后端 `clampLimit`，上限 200）；**「全部」走独立参数 `all=true`，后端不设上限**（唯一护栏仍是 ≤90 天区间）。**不能**用"传一个很大的 limit"冒充全部：后端会把 >200 收敛回 200，界面写着"全部"却只拿到 200 条 |
| 按钮 | 查询 / 重置 | — | 重置 = 回到默认（最近 7 天 + 全空 + 50） |

### 5.3 表格列

| 列 | 内容 | 备注 |
| --- | --- | --- |
| 操作时间 | `formatDateTime(operatedAt)` | 默认按接口返回顺序（后端已 `ORDER BY operated_at DESC, id DESC`） |
| 操作人 | `operatorRealName`（`operatorUsername`） | 缺姓名时只显示账号 |
| 渠道 | tag：`AI`=warning / `WEB`=primary | 字典 |
| 动作 | 字典中文 | `PROPOSAL_CREATED` → "提案创建" |
| 目标 | `目标类型中文` + `targetName(targetId)` | 缺 `targetName` 时只显示 id |
| 变更字段 | `changedFields.split(',')` → 多个 tag；空则 `--` | **注意**：`PROPOSAL_CREATED` 行此处为空 |
| 结果 | tag：SUCCESS=success / FAILED=danger / REJECTED=info / EXPIRED=info / PARTIAL=warning | 字典 |
| 原因 | `errorMessage` 截断 30 字 | 完整内容在抽屉 |
| 详情 | 「查看」链接 | **无快照且无 `changedFields` 的行也允许打开**（抽屉里有 `traceId` 等元信息） |

**状态**：`loading` 用 `v-loading`；空结果显示"该条件下没有审计记录（可放宽时间区间或清空筛选）"；请求失败由 `request.ts` 统一 `ElMessage` 提示，页面不再重复弹窗。

### 5.4 详情抽屉（`el-drawer`，宽 640px）

按顺序展示四块：

1. **基本信息**：时间、操作人（姓名+账号+id）、渠道、动作、目标（类型+名称+id）、结果、原因（全文）。
2. **字段级 diff**：把 `beforeValue` / `afterValue` 各自 `JSON.parse` 后按字段并集渲染三列表格（字段 / 原值 / 新值），值用 `JSON.stringify` 短展示（对象/数组折叠为一行文本，避免抽屉被撑爆）；**解析失败降级为原样文本**（用 `prettyJson`），绝不抛异常打断渲染。
3. **脱敏说明**（当 diff 中出现 `<changed>` 时**必须显示**）："该字段为敏感字段，审计只记录是否变更，不记录具体值（D-4）。"——否则用户会以为"数据丢了"。
4. **可回溯信息**：`traceId`（+复制按钮）、`proposalId`、`conversationId`、`id`（都提供复制），以及**两条特殊提示**：
   - `truncated === 1` → "本次变更的快照超过 8KB，未保留前后值（SYS-A-16）；只能依据「变更字段」判断哪些字段发生了变化。"
   - `beforeValue`/`afterValue` 均为空且 `changedFields` 非空 → "该记录未保存前后快照（例如提案创建、被拒绝或被拒绝后过期的事件）。"

### 5.5 容量与口径提示

- **不放常驻说明**（评审意见，见 §5.1）。
- 选中跨度 ≥ 80 天时，在时间控件下方提示"已接近查询上限 90 天"（这条是**按需出现**的，不是常驻科普，保留）。
- 表格上方标题为 `操作审计 · 共 N 条`；**仅当没显示完时**（`items.length < total`）追加一句 `· 已显示 M 条`。
  - 这样在两种情况下都如实：选了 50/100/200 的上限时能立刻看出被截断；后端尚未支持「全部」时也不会谎称"全部已显示"。
  - 数字全部展示时不加任何后缀——没有多余的常驻文案。

---

## 6. 菜单与权限过滤（依赖 D2）

### 6.1 方案 A（建议）：顺带完成 P-06

| 改动 | 内容 |
| --- | --- |
| `AppLayout.vue` | `menuGroups` 由常量改为 `computed`，每项带 `permission` 码；按 `userStore.permissions` 过滤；**分组内全部子项被过滤掉时整组不渲染** |
| `router/index.ts` | 各路由 `meta` 增加 `permission`；`beforeEach` 在登录校验之后增加权限校验：不满足 → `ElMessage.warning('你当前没有访问该页面的权限')` + 跳 `/dashboard` |
| 刷新兜底 | `userStore.user` 为空（仅有 token）时先 `await userStore.fetchMe()` 再判断，否则刷新页面会被误判无权限（§9.3 已指出） |

各菜单项所需权限码沿用 §9.3 的表（首页 `dashboard:view`、投标订单 `order:tender:view`、履约订单 `order:performance:view`、数据概览 `analysis:overview:view`、项目 `project:view`、企业 `enterprise:view`、险种 `system:insurance:view`、机构 `system:org:view`、部门 `system:dept:view`、用户 `system:user:view`、角色 `system:role:view`、**操作审计 `system:audit:view`**、我的工具调用 `ai:chat`（本期不做该页，故不加该项））。

### 6.2 方案 B：只做审计项的局部过滤

只在菜单渲染「操作审计」时判断 `userStore.permissions.includes('system:audit:view')`，路由守卫不加 `meta.permission`（即保留了 V-4 的其余部分）。

- 优点：改动最小（1 个文件）。
- 缺点：与 P-06 的最终形态不一致，将来做 P-06 时要返工；其它菜单项仍会"看得到点不进"。
- **我不建议**，除非你希望本次改动面尽量小。

### 6.3 方案 C：不做任何过滤（只加菜单项）

- 后果：ANALYST / VIEWER 侧边栏会多出「操作审计」，点进去页面渲染出来但接口 403，`request.ts` 会弹"没有权限访问该资源"——正是 §9.2 记录的 V-4 体验（"像系统坏了"）。
- **不建议**。仅当你希望本次改动严格限制在"新增一个页面"时选它，且应同时把该菜单项标注为"暂不对非管理员开放"。

### 6.4 前端过滤不是安全边界（沿用 §9.4）

后端 `@PreAuthorize` 与 Service 的数据范围/白名单保持原样；前端过滤只解决"看到不该看的入口"。

---

## 7. 边界与已知限制（先写清楚，避免验收时当"bug"）

| # | 限制 | 原因 | 页面上如何处理 |
| --- | --- | --- | --- |
| 7.1 | **没有真翻页**（`offset` 恒为 0）；条数上限默认 ≤200，**选「全部」时不限** | 接口 `offset` 恒为 0（`OperationAuditService.query` → `selectPage(query, 0, limit)`）；「全部」用 `all=true` 表达，唯一护栏是 ≤90 天区间 | 未显示完时表头显示"共 N 条 · 已显示 M 条"；真翻页仍登记为 P2（D1=B） |
| 7.2 | 无法"排除提案创建"这类事件 | `action` 是精确匹配、无 `excludeActions` 参数 | 引导用「动作」筛选选定具体动作；若要排除，需后端加参数（P2） |
| 7.3 | 非 ADMIN 看不到 `ROLE/PERMISSION` 类记录 | Service 白名单（SYS-A-10），显式点名会 403 | 非 ADMIN 的 `targetType` 下拉仅 3 项 |
| 7.4 | 敏感字段只有 `<changed>` | 写入前脱敏（D-4），与角色无关 | 抽屉固定说明文案 |
| 7.5 | 约 46% 的行没有前后值 | `PROPOSAL_CREATED` / `REJECTED` / `EXPIRED` 等事件本就没有快照 | "前后值"列显示"无"，抽屉给出原因 |
| 7.6 | 时间跨度 > 90 天必被拒绝 | SYS-A-17（分区裁剪护栏）；**这也正是「全部」敢不设条数上限的前提** | `disabledDate` 前端先挡 |
| 7.7 | 只看得到在线 24 个月内的数据 | 归档分区（D-5）已 `DROP`/归档 | 不再用常驻说明文字（评审意见），靠时间筛选操作自然体现 |
| 7.8 | `proposalId` 是数字 id，不是 `PROPOSAL_NO` | 审计行只存 `proposal_id` | 抽屉显示 id + 复制；如需提案号需再查一次（P2） |

---

## 8. 验收标准与测试要点

### 8.1 验收标准（编号顺延既有 AC-35~37）

| 编号 | 验收标准 |
| --- | --- |
| AC-38 | 持有 `system:audit:view` 的账号能看到菜单「操作审计」并进入 `/system/operation-audits`；不持有的账号**菜单不显示**，直接输 URL 也被守卫拦回首页并提示（D2=A 时） |
| AC-39 | 默认区间为最近 7 天；不选区间或跨度 > 90 天时，前端拦截并给出可读提示，不发请求 |
| AC-40 | `AI` 与 `WEB` 两种来源的记录可在同一列表中混排并一眼区分（AC-22 的页面形态）；`PROPOSAL_CREATED`、`DELETE`、`RESTORE` 三种动作显示中文而非英文码 |
| AC-41 | 详情抽屉能展示字段级前后值；敏感字段显示 `<changed>` 且有 D-4 说明；`truncated=1` 与"无快照"两种情形都有明确文案；`traceId` 可复制 |
| **AC-42** | 「条数上限」最高档为「全部」：选中后返回该区间**全部**记录（`items.length == total`，不受 200 限制）；未选时仍按 50/100/200 收敛。页面不显示常驻科普说明；仅在未显示完时补充"已显示 M 条" |

### 8.2 测试要点（P07-T1~T6 沿用既有方案 §10.5，新增 3 条）

| 编号 | 断言 |
| --- | --- |
| P07-T1 | 不选时间区间点查询 → 前端拦截（或后端拒绝并展示提示） |
| P07-T2 | 选 100 天区间 → 被拒绝并提示最大 90 天 |
| P07-T3 | ADMIN 查询看到 `source=WEB` 与 `source=AI` 两种记录混排（AC-22） |
| P07-T4 | ANALYST 访问该页面 → 菜单不显示、URL 被守卫拦住 |
| P07-T5 | 敏感字段记录在详情抽屉中显示 `<changed>` 且有 D-4 说明文案 |
| P07-T6 | `traceId` 可复制；同一 `traceId` 的会话/工具/提案/审计记录都能在助手侧找到（AC-23） |
| **P07-T7**（新） | 结果含 `EXPIRED/REJECTED` 的记录，"前后值"列显示"无（未执行）"，**不出现"查看"空抽屉** |
| **P07-T8**（新） | `truncated=1` 的记录在抽屉中显示 8KB 截断说明；`beforeValue` 为非法 JSON 时不抛异常、降级为原文 |
| **P07-T9**（新） | 条数上限：选 `200` 时返回 200 条且表头出现"已显示 200 条"；选**「全部」**时请求带 `all=true` 且返回数量等于 `total`（后端已支持，见 §13.6） |

**前端测试形态**：本仓库前端暂无测试框架（`npm run build` 是唯一自动化门禁）。因此：
- P07-T1/T7/T8 中**纯函数部分**（字典映射、JSON 解析降级、字段并集 diff）建议抽到 `frontend/src/utils/auditDict.ts` 并**手工验证**为主；
- 若你希望可自动化，需要先引入 Vitest（见 §11 的 D6，默认本次不引入）；
- 其余靠 §8.3 的手工验收清单。

### 8.3 手工验收清单（交付时逐条跑）

1. admin 登录 → 侧边栏「系统配置 → 操作审计」可见 → 进入页面，默认最近 7 天自动查询出数据。
2. 按「渠道=AI」筛选 → 只剩 AI 记录；按「动作=提案创建」筛选 → 只剩 `PROPOSAL_CREATED`。
3. 打开一条 `DISABLE/SUCCESS` 记录 → 抽屉显示 `status 启用 → 停用` 的字段级 diff。
4. 打开一条手机号相关记录 → 出现 `<changed>` 与 D-4 说明。
5. 打开一条 `REJECTED` 记录 → 无前后值 + 原因文案。
6. 时间区间改 100 天 → 被前端挡住。
7. 用非 ADMIN 账号（如 analyst）登录 → 菜单无「操作审计」；手输 `#/system/operation-audits` → 回到首页并有提示。
8. `npm run build` → exit 0。

---

## 9. 涉及文件

| 文件 | 变更 | 说明 |
| --- | --- | --- |
| `frontend/src/views/system/OperationAudits.vue` | **新建** | 页面主体 |
| `frontend/src/utils/auditDict.ts` | **新建** | `action` / `targetType` / `result` / `source` 四组字典 + tag 类型映射（含 `PROPOSAL_CREATED/DELETE/RESTORE`） |
| `frontend/src/api/ai.ts` | 新增 `pageOperationAudits(params)` | 调用 `GET /ai/operation-audits` |
| `frontend/src/types/ai.ts` | 新增 `OperationAuditItem` / `OperationAuditQuery` / `OperationAuditPage`；`OperationAuditQuery` 增加 `all?: boolean` | 字段按 §3.1，**全部可空字段标可选**（后端省略 null） |
| `frontend/src/router/index.ts` | 新增路由（`meta.permission`） | D2=A 时同时给现有业务路由补 `meta.permission`（登录页除外） |
| `frontend/src/layout/AppLayout.vue` | 新增菜单项 | D2=A 时同时把 `menuGroups` 改为 `computed` + 权限过滤 + 空分组剔除 |
| `frontend/src/stores/user.ts` | **无需改动** | 已有 `roles`（L27）/ `permissions`（L28）computed 与 `fetchMe()`（L51），D2=A 的刷新兜底直接可用 |
| `guarantee-ai/.../mapper/OperationAuditQuery.java` | 新增 `all` 开关 | 「全部」必须用独立开关表达，不能靠"传很大的 limit"（会被 `clampLimit` 收敛回 200） |
| `guarantee-ai/.../service/OperationAuditService.java` | `all=true` 时不设条数上限（`UNLIMITED_LIMIT`） | 唯一护栏仍是强制的 ≤90 天区间；**AI 工具不传 all，SYS-Q-06 的 200 条上限保持不变** |
| `guarantee-ai/.../controller/AiController.java` | 新增可选参数 `all`（默认 false） | 同名接口，向后兼容：不传时行为与从前完全一致 |
| `guarantee-ai/.../OperationAuditServiceTest.java` | +2 条单测 | `all=true` 不收敛 / `all` 默认 false 仍收敛 |
| `guarantee-web/.../OperationAuditAllLimitIT.java` | **新建**（2 条 HTTP 断言） | 线上验证「全部」真的不限量、且默认上限没被改坏 |

> **例外说明**：原方案 §13 曾决定"审计查询接口不增删改"，因此最初的实现是纯前端。
> 评审追加"条数上限最高应为全部"后，`all` 参数属于**必要的契约扩展**（新增可选参数、向后兼容、
> 不改任何既有字段与语义），故作为**新的一条例外**登记：E-03「审计查询接口新增可选参数 `all`」。

---

## 10. 工作量

| 项 | 前端 | 后端 | 测试/验收 | 合计 |
| --- | --- | --- | --- | --- |
| 审计页 + 字典 + api/类型 | 1.0 | 0 | 0.3 | 1.3 |
| 菜单 + 路由权限过滤（D2=A） | 0.5 | 0 | 0.2 | 0.7 |
| **全按建议（D1~D5 = A）** | **1.5** | **0** | **0.5** | **≈2 人日** |
| 若 D1=B（真翻页） | +0.3 | +0.5（含边界/契约测试） | +0.2 | **+1.0** |
| 若 D3=B（targetId 深链） | +0.3 | +0.2 | +0.2 | **+0.7** |

---

## 11. 待确认事项（评审用）

| # | 问题 | 建议 | 你的决定 |
| --- | --- | --- | --- |
| D1 | 本期是否要真翻页？ | 不做（P2） | ✅ 不做（零后端改动） |
| D2 | 菜单权限过滤做全量 P-06 还是只做审计项？ | 全量 P-06 | ✅ 全量 P-06 |
| D3 | 是否做"从系统页跳到该对象审计"？ | 不做（P2） | ✅ 不做 |
| D4 | 是否要导出？ | 不做 | ✅ 不做 |
| D5 | 是否一起做「我的工具调用」页（P-08）？ | 不做 | ✅ 不做 |
| D6 | 是否引入前端测试框架（Vitest）以便自动化 P07 断言？ | 本次不引入，靠手工清单 | ✅ 不引入 |

---

## 12. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-09-23 | 初稿。承接既有 P-07/P-06 设计；补齐现状核对（接口契约、真实值域、前端改造点、容量）、D1~D6 决策项、AC-38~41 与 P07-T7~T9；确认与 V5 删列无关（页面不含任何机构列） |
| 2026-09-23 | 评审通过（D1~D5=A、D6 不引入 Vitest）并**实现完成**：新增审计页 + 字典 + api/类型 + 路由与菜单权限过滤。实施记录、验证证据、2 处实现偏差与 1 个新发现的存量问题见 §13 |
| 2026-09-23 | 第二批：修复 §13.5 的 500 误报（`AccessDeniedException` → 403），新增 3 条单测 + 2 条线上 IT，全量 verify **171 项全绿**；同时如实记录 §13.7 的数据库事故与 binlog 恢复过程 |
| 2026-09-23 | 第三批（§13.6）：按评审去掉页面常驻说明、条数上限新增「全部」（后端新增可选参数 `all`，登记为例外 E-03），新增 2 条单测 + `OperationAuditAllLimitIT`（2 条 HTTP 断言），全量 verify **175 项全绿** |

---

## 13. 实施记录（2026-09-23）

### 13.1 实际改动文件

| 文件 | 变更 |
| --- | --- |
| `frontend/src/views/system/OperationAudits.vue` | **新建**（页面主体：筛选 / 表格 / 详情抽屉 / 容量提示） |
| `frontend/src/utils/auditDict.ts` | **新建**（四组字典 + tag 映射 + 快照解析与 diff + "为什么没有前后值"文案） |
| `frontend/src/api/ai.ts` | 新增 `pageOperationAudits(params)` |
| `frontend/src/types/ai.ts` | 新增 `OperationAuditItem` / `OperationAuditQuery` / `OperationAuditPage` |
| `frontend/src/router/index.ts` | 新增审计路由；`RouteMeta` 增加 `permission` 声明；12 条业务路由补 `meta.permission`；守卫改为异步（`fetchMe` 兜底）并增加权限校验 |
| `frontend/src/layout/AppLayout.vue` | 菜单从硬编码常量改为"定义 + 按权限过滤的 computed"；分组全空时整组不渲染；新增「操作审计」项 |
| **后端** | **零改动**（符合 D1~D5=A） |

### 13.2 验证证据（可复核）

| # | 命令 / 操作 | 结果 |
| --- | --- | --- |
| 1 | `cd frontend && npm run build`（脚本为 `vue-tsc --noEmit && vite build`） | **exit 0**，`✓ built in 5.86s`；产物含路由级 chunk `OperationAudits-Dnzsegj6.js`（16,239 B）与 `OperationAudits-BSAYxffb.css` |
| 2 | 用演示账号 `admin / Admin@123` 登录取 token（口令为 `DataInitializer` 中的公开常量） | `code=0`，`roles=ADMIN`，37 个权限 |
| 3 | `GET /api/ai/operation-audits?startDate=最近7天&endDate=今天&limit=3` | `code=0`、`total=925`、`items=3`；**字段与 §3.1 逐个吻合**：camelCase、null 字段确被省略（无 `errorMessage`/`isDeleted` 等）、`beforeValue/afterValue` 是 JSON 字符串、`operatedAt=2026-09-23T01:06:17`（`formatDateTime` 兼容 ISO 串） |
| 4 | `limit=500` | `items=200` → 后端 `clampLimit` 生效，证明页面"最多显示最近 N 条（≤200）"的措辞正确 |
| 5 | 时间跨度 100 天 | 后端返回 `code=400`（HTTP 200 + 非 0 code）→ `request.ts` 会弹 message 并 reject，页面的 `catch` 保留上一次结果 |
| 6 | `action=PROPOSAL_CREATED` / `source=WEB` 筛选 | `total=295` / `total=376`，与库里实测分布**完全一致**（见 §3.2） |
| 7 | `targetType=ROLE`（ADMIN） | `code=0, total=121`（ADMIN 可查权限主数据类审计） |

### 13.3 与规格的 2 处实现偏差（都是有意的，供你复核）

1. **守卫加了"兜底页自身无权限时不再跳兜底页"分支**。规格只说"跳首页并提示"，但若某账号连 `dashboard:view` 都没有，就会变成"跳首页 → 首页也没权限 → 再跳首页"，Vue Router 会判定无限重定向并中断导航。现在这种情况放行到首页（由页面内接口的 403 提示暴露真实原因）。四个真实角色都有 `dashboard:view`，所以该分支只防理论情形。
2. **"前后值"列对无快照的行显示文本而非链接**（`无（未执行）` / `无（提案创建）` / `无`），`traceId` 等元信息由「详情」列打开。这样同时满足 §5.3（无快照的行也允许打开抽屉）与 P07-T7（不出现"查看"却只有一个空抽屉）。
3. 另外，渲染路径上**刻意不调用 `JSON.parse`**（`hasSnapshot()` 只判空字符串，diff 解析只在打开抽屉时做一次）：Element Plus 的单元格渲染抛异常会打断整个 tbody，这是本仓库已经踩过的坑（见 `utils/status.ts` 类注释）。

### 13.4 未在浏览器中实测的部分（如实声明）

本仓库无前端测试框架（D6=不引入），也没有可用的浏览器自动化，因此下列**只做了类型检查 + 代码评审 + 后端行为核对**，UI 断言请按 §8.3 手工清单验收：

- 菜单按权限过滤后的实际渲染（含"空分组不渲染"）——类型检查通过、数据驱动，但未在浏览器里看过；
- 守卫"非 ADMIN 直接输 URL 被拦回首页并提示"——逻辑已实现且后端拒绝行为已核实（见 13.5 的复现），但前端跳转未在浏览器中跑过；
- 详情抽屉的视觉呈现（diff 表、脱敏说明、复制按钮）。

### 13.5 顺带发现：`@PreAuthorize` 拒绝被报成 **500「系统内部错误」**（存量问题，未修）

**复现**：用 `analyst / Analyst@123` 取 token 后调 `GET /api/ai/operation-audits?startDate=…&endDate=…`：

```
{"code":500,"message":"系统内部错误","traceId":"e3973edd37544fe8ab2fb7fb59514e8c","success":false}
```

而同一 token 的 `/auth/me` 显示 `roles=ANALYST`、`permissions` 中**确实没有** `system:audit:view`（14 个权限）
→ **拒绝本身是正确的**，问题是错误映射：`GlobalExceptionHandler` 只显式处理了 `BizException` 与几个参数类异常，
`AccessDeniedException` / `AuthorizationDeniedException` 落进了兜底的 `@ExceptionHandler(Exception.class)`
→ 被当成"未处理异常"返回 `code=500`，并且**每次权限拒绝都会打一条 ERROR 堆栈**。
`request.ts` 里那条"没有权限访问该资源"的文案（对应 HTTP 403）因此永远不会命中，用户看到的是"系统内部错误"。

**属性**：存量问题。`GlobalExceptionHandler` 自初始提交 `f76c77c` 起就没有 `AccessDeniedException` 分支，
我这次的提交 `97bd5a2` 未包含该文件。**影响面**：全站所有 `@PreAuthorize` 拒绝路径（不止审计接口）。

**改法**（评审通过后实施）：

| 文件 | 变更 |
| --- | --- |
| `guarantee-common/pom.xml` | 新增 `spring-security-core`（**不是** `spring-boot-starter-security`：只取异常类型，不引入 Security 自动配置与认证过滤器链） |
| `GlobalExceptionHandler` | 新增 `@ExceptionHandler(AccessDeniedException.class)` → `Result.fail(ResultCode.FORBIDDEN, "你当前没有该操作的权限，请联系管理员")`；日志由 ERROR 降为 WARN（权限拒绝是预期结果，不是故障），不再打堆栈 |
| `GlobalExceptionHandlerTest`（新，该模块首个测试） | 3 条：① 403 + 可读话术、且不是 500；② 子类 `AuthorizationDeniedException`（`@PreAuthorize` 实际抛的类型）同样命中；③ 兜底分支仍把未知异常报成 500（修复没有削弱兜底） |
| `PermissionDeniedMappingIT`（新，`guarantee-web`） | 2 条**线上**断言（随机端口真实 Tomcat + 真实 JWT + 真实 `@PreAuthorize`）：ANALYST → `code=403` + 含"权限/联系管理员"且非"系统内部错误"；ADMIN → `code=0`（权限判定没被改坏） |

**证据**：`mvn -o -B -ntp verify`（隔离 schema）→ **BUILD SUCCESS**，
`guarantee-common 3 / guarantee-system 62 / guarantee-auth 2 / guarantee-ai 75 / guarantee-web 29` = **171 项全绿**
（本会话基线 144 → A/B/C 之后 166 → 本次 +5 = 171）。

> **为什么用真实 HTTP 而不是 `MockMvc`**：单测只能证明"调用这个方法会返回 403"，
> 证明不了"Spring MVC 真的把异常派发到了这个方法"，而线上故障恰恰出在派发上。
> 另：Spring Boot 4 已移除 `org.springframework.boot.test.web.client.TestRestTemplate`，
> 本 IT 用框架自带的 `RestTemplate` + `@LocalServerPort` 拼绝对地址。

**仍未验证**：你正在运行的 8081 实例是改动前的进程，**重启后**才会按新映射返回 403（我不允许重启你的实例）。重启后复核：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"analyst","password":"Analyst@123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -s "http://127.0.0.1:8081/api/ai/operation-audits?startDate=2026-09-16&endDate=2026-09-23" \
  -H "Authorization: Bearer $TOKEN"
# 期望：{"code":403,"message":"你当前没有该操作的权限，请联系管理员",...}
```

---

## 13.6 第三批：评审追加项（2026-09-23 收尾）

**评审意见**：① 页面顶部那段"操作审计记录…只增不改不删；在线保留 24 个月…"属于**非必要提示**，不要显示；
② **条数上限最高应该是「全部」**。

**改动**：

| 项 | 做法 |
| --- | --- |
| 去掉常驻提示 | 删除页面顶部 `.audit-hint` 区块及其样式；保留"跨度 ≥80 天"的按需告警（只在接近硬上限时出现） |
| 表头口径 | `操作审计 · 共 N 条`，**仅当 `items.length < total`** 时补 `· 已显示 M 条`（不显示完才提示，全部显示时无任何多余文案） |
| 「全部」 | 前端 `limitMode: 50/100/200/'ALL'`；选 `ALL` 时发 `all=true` 且**不发** `limit`。后端新增可选参数 `all`（`AiController`）→ `OperationAuditQuery.all` → `OperationAuditService` 用 `UNLIMITED_LIMIT` 跳过上限收敛。**AI 工具不传 `all`，SYS-Q-06 的 200 条上限不变** |
| 为什么不"传个大 limit" | `clampLimit` 会把任何 >200 收敛回 200，界面写"全部"却只拿到 200 条 = 静默谎报；也不复用 `limit<=0`（那是"没传，用默认值"，模型传 0 会变成不限量） |

**验证证据**：

| 项 | 结果 |
| --- | --- |
| 后端单测（+2） | `all=true` 不收敛且 `all` 默认 false 仍收敛 → ai 模块 75 → **77** |
| `OperationAuditAllLimitIT`（新，2 条 HTTP 断言） | `all=true` → `items.length == total`（925）且 >200；`limit=200` → 恰好 200 条 |
| `mvn -o -B -ntp verify`（隔离 schema） | **BUILD SUCCESS**：common 3 / system 62 / auth 2 / ai 77 / web 31 = **175 项全绿** |
| `cd frontend && npm run build` | **exit 0**（含 `vue-tsc --noEmit`，`limitMode` 联合类型通过检查） |
| 菜单权限过滤（静态模拟，用库里真实权限） | ADMIN 12 项全可见；OPERATOR 隐藏「角色配置/操作审计」；ANALYST／VIEWER 仅隐藏「操作审计」 → 与 D-1a 矩阵一致 |

> **需要重启**：`all` 参数是后端新增能力，正在运行的实例（改动前的进程）会忽略它——
> 此时页面选「全部」只会拿到默认 50 条，且表头会如实显示"共 925 条 · 已显示 50 条"（不谎报）。
> **重启后端后**「全部」才会真正返回全部。

---

## 13.7 验证过程中的一次数据库事故与恢复（如实记录）

**事故**：为集成测试重建隔离库时，我用一份 `mysqldump --databases` 导出的备份做 `source`。
该产物内部带 `USE \`guarantee_ai_admin\``，于是 `source` **把备份灌回了开发库本身**：
开发库被回滚到 00:54 的备份点，V5 已删除的 `operator_org_id` 列被一起带回；
00:54 之后的写入（那次验收产生的 1 个会话 + 6 条消息 + 4 条工具调用 + 2 条提案 + 6 条审计）被覆盖。

**恢复步骤**（全部完成）：

| 步骤 | 做法 |
| --- | --- |
| 1 | 从 binlog 定位事故时刻：`01:27:56`（thread 899 的 `DROP TABLE`/`CREATE TABLE` 批次） |
| 2 | 立即对"被覆盖后的当前状态"做安全备份（`guarantee-ai-admin-CLOBBERED-*.sql`），保证恢复过程本身可回退 |
| 3 | 重跑 V5 删掉该列，使 `ai_operation_audit` 回到 22 列——与 01:00 之后 row 事件的列结构一致（列数不匹配会导致行事件错位） |
| 4 | `mysqlbinlog --skip-gtids --database=guarantee_ai_admin --start-datetime="2026-09-23 00:54:12" --stop-datetime="2026-09-23 01:27:55"` 提取窗口，剔除 V5 自身的 DDL 后重放（52 个行事件，全部属于 `guarantee_ai_admin`） |
| 5 | 复核：审计 925 行 / `max_id=1419`、提案 295 / `max_id=505`、会话 340 的 6 条消息与 4 条工具调用全部回来，6 条审计行的 `source/action/result` 与原报告逐条一致，应用 `/actuator/health=UP` |

**踩坑记录（写给后来人）**：

- **`mysqldump --databases` 的产物包含 `USE`，绝不能 `source` 进另一个库**——它会静默切库并覆盖目标库。
  正确做法：导出不加 `--databases`（`mysqldump <db> > f.sql`），导入时显式指定目标库。
- 重放 binlog 要加 `--skip-gtids`（否则第二条事务报 `GTID_NEXT cannot be changed by a client that owns a GTID`），
  并用有权限的账号执行（`SET @@SESSION.PSEUDO_SLAVE_MODE` / `SET GTID_NEXT` 需要 `SESSION_VARIABLES_ADMIN`）。
- 行事件重放前必须保证表结构（列数/列序）与事件发生时刻一致，否则会"执行成功但数据错位"。

**保留的备份**：

| 文件 | 内容 |
| --- | --- |
| `guarantee-ai-admin-backup-20260923-005412.sql` | 事故点（00:54、V5 之前）的完整备份 |
| `guarantee-ai-admin-CLOBBERED-20260923-012920.sql` | 事故后、恢复前的状态（用于回退恢复过程本身） |

临时库（`guarantee_ai_admin_it` / `guarantee_ai_admin_v5test`）验证结束后已全部删除，开发库是唯一的库。
