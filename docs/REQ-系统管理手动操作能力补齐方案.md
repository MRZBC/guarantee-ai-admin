# 补齐方案：系统管理域「手动操作」与 AI 助手能力对齐

| 项目 | 内容 |
| --- | --- |
| 文档名称 | 系统管理域手动操作能力补齐方案 |
| 文档版本 | v1.3（P-09 统一审计已实施完成；来源归属按评审澄清修正为"身份同源、渠道可区分"；补部门停用保护测试） |
| 适用系统 | 智能电子保函运营管理平台（guarantee-ai-admin） |
| 涉及模块 | `frontend`、`guarantee-system`、`guarantee-ai`、`guarantee-web` |
| 依据文档 | `docs/REQ-系统管理助手能力.md`（v1.4） |
| 关联需求 | SYS-P-06（菜单权限过滤）、SYS-C-18（按钮权限联动）、SYS-A-07（页面写操作接入审计）、AC-22（统一审计） |
| 现状结论 | 后端 5 个域的写接口**全部已实现且带 `@PreAuthorize`**；缺口**100% 集中在前端页面层**，另有 2 个页面完全缺失 |

---

## 1. 核心原则：能力理论相等，手动形态是超集

### 1.1 两层关系必须分开

> **理论层（规范）：`C_manual = C_ai`** —— 凡是 AI 助手能做的操作，**都应该**能在系统管理页面上手动完成。这是"应该"，违反即设计缺陷。
>
> **实际层（事实）：`C_manual ⊇ C_ai`** —— 现实中手动能做的**总是更多**，因为有大量能力只有手动形态才有意义，也有大量场景用户就是偏好手动。这不是缺陷，是自然结果。

形式化表达（`C` = 能力集合）：

```
理论层：C_manual  =  C_ai      ← 必须成立的规范（作对账基准）
实际层：C_manual  ⊇  C_ai      ← 必然成立的事实（无需干预）
```

**把两层混为一谈会导致两种相反的误判**：

| 误判 | 后果 |
| --- | --- |
| 只看实际层（"手动反正更多"） | 忽略"某能力只有助手有"这个真缺陷，倒挂长期存在 |
| 只看理论层（"两边必须一样"） | 反过来要求"页面上每个按钮助手都得有"，这是不必要的（见 §1.4） |

因此本方案确立一条**可判定的判定规则**：

| 观察到的现象 | 归属 | 处理 |
| --- | --- | --- |
| 某能力只有助手有，页面无入口 | 违反**理论层** | **必须修复**（本方案 P-01~P-09 就是修这个） |
| 某能力两边都有，但用户偏好手动 | 属于**实际层** | 无需任何动作 |
| 页面上有额外能力（助手没有） | 属于**实际层** | 无需任何动作（不得为此强行给助手加工具） |

### 1.2 为什么手动形态必然是超集（实际层的三个来源）

**来源一：手动操作更精确**

对话里改一个字段，用户需要读完整张确认卡、在脑中比对若干字段的 before/after；页面里改，用户的眼睛就在那一行那一格上。二者的**信息定位成本与密度完全不同**。

| 任务 | 手动 | 助手 |
| --- | --- | --- |
| "把广东省第 2 机构的排序号从 13 改成 15" | 找到那一行、点修改、改一个数字 | 需要描述清楚目标，还要核对确认卡上模型解析出的 `id` 是否真是 13 |
| "核对 21 个机构的层级与上级关系" | 树上一眼看完 | 需要多轮问答或在回答里读表格 |

**来源二：手动操作更让人放心**

自然语言存在"模型有没有理解对"的残余不确定性——这正是需求 RK-01（"把广东省第2机构解析成错误 orgCode"）专门登记的风险。手填表单时，用户填的就是他填的，中间没有解释层。**这个不确定性无法通过提升模型能力消除**，因为它的根源是"用户无法验证中间步骤"。

**来源三：手动是独立的可用性边界**

模型服务不可达、API Key 失效、配额耗尽时，系统管理不能停摆。这不是"兜底"的次要角色，而是**可用性基线**。

> **结论**：手动通道不是助手的降级替代，而是**并行的主通道**；助手是"少点几次"的加速通道。这个定位差异决定了两者会长期共存，而不是"助手成熟后可以下掉页面"。

### 1.3 反向违反的判定标准

出现下列任一情况即视为**违规**，必须修复（不是"可选优化"）：

| 编号 | 违规形态 | 举反例 |
| --- | --- | --- |
| V-1 | 某写接口只有 AI 渠道调用，页面无入口 | 部门停用：助手可 `proposeDepartmentChange(DISABLE)`，页面无按钮 |
| V-2 | 页面有按钮但被禁用/占位，实际不可用 | `Orgs.vue` 的"修改""新增下级"渲染了 `disabled` 按钮 |
| V-3 | 某查询能力只在助手工具中暴露，页面无对应视图 | 操作审计：有 `queryOperationAudit` 与 `GET /api/ai/operation-audits`，但无审计页面 |
| V-4 | 页面入口不受权限控制，助手反而更严格 | 菜单硬编码，VIEWER 能看到"角色配置"；而助手侧 `queryRole` 会返回 `denied` |

> **V-2 尤其危险**：一个渲染出来但点不动的按钮，比"没有按钮"更容易让人误判为"我没有权限"或"系统坏了"。而且它伪装成"已实现"，会被人事后的对账漏掉。

### 1.4 本原则的合法下界：需求决策可以整体排除某能力

理论层的"相等"约束的是**同一能力在两个渠道的可见性**，它不禁止**两个渠道都没有**某能力。

因此：

| 情况 | 是否违规 | 举例 |
| --- | --- | --- |
| 助手有、页面无 | ✅ 违规 | 部门停用（V-1） |
| 页面有、助手无 | ❌ 不违规（实际层的正常表现） | 用户列表按部门筛选 |
| **两边都无** | ❌ 不违规 | **新建用户 / 重置密码**（D-2 / D-2a 已整体移出本期） |

**推论（重要）**：页面上**不得**出现"新建用户""重置密码"按钮。若出现，反而与助手侧 `SYS-N-10` 的话术直接矛盾（助手说"本期不支持"、页面却能做），用户会立刻反问"那助手为什么不行"。此时正确的处理是**两边都不做**，而不是"补页面的同时给助手也加上"。

### 1.5 本方案的执行约束

补齐工作必须遵守下列约束，避免"为了对称而破坏已有设计"：

| 编号 | 约束 |
| --- | --- |
| C-01 | **不新增、不删改后端接口**（除 §13 列出的例外）。写接口已齐备，补齐只做前端接线；审计查询接口保持原样，只做展示。 |
| C-02 | **复用同一份业务校验**：页面写操作必须走 `guarantee-system` 的同一 Service 方法，不得在前端复制校验规则（否则会出现"页面能改、助手不能改"的新倒挂）。 |
| C-03 | **危险动作保护在服务端，页面只负责提示**：如"停用最后一个 ADMIN"由 `UserService.validateStatusChange` 拒绝，页面把错误文案原样展示即可，不在前端实现该规则。 |
| C-04 | **按钮显隐按 `permissions` 渲染**（SYS-C-18），但**不得把前端过滤当安全边界**（SYS-NF-04）。 |
| C-05 | **页面写操作必须接入统一审计**（SYS-A-07 / AC-22）：`source=WEB`，与助手 `source=AI` 写入同一张 `ai_operation_audit`。**注意**：这是**写入侧**要求，与"审计查询侧只做展示、不改接口"并不矛盾（见 §9.4 的说明）。 |
| C-06 | **AI 工具清单不得先于页面能力出现**：新增 AI 写工具的前提是对应页面动作（或明确的人工通道）已存在。 |

---

## 2. 现状对账

### 2.1 能力矩阵（补齐前）

`✅` 已可用 · `❌` 缺失 · `🟡` 占位/不可用

| 域 | 动作 | AI 助手 | 后端接口 | 页面按钮 | 违规 |
| --- | --- | --- | --- | --- | --- |
| 险种 | CREATE | ✅ | ✅ `POST /api/system/insurance-types` | ✅ 新增险种 | — |
| 险种 | UPDATE | ✅ | ✅ `PUT /{id}` | ✅ 编辑 | — |
| 险种 | ENABLE / DISABLE | ✅ | ✅ `PATCH /{id}/status` | ❌ | V-1 |
| 机构 | CREATE | ✅ | ✅ `POST /api/system/orgs` | 🟡 `disabled` 占位 | V-1 + V-2 |
| 机构 | UPDATE | ✅ | ✅ `PUT /{id}` | 🟡 `disabled` 占位 | V-1 + V-2 |
| 机构 | ENABLE / DISABLE | ✅ | ✅ `PATCH /{id}/status` | ✅ 启停 | — |
| 部门 | CREATE | ✅ | ✅ `POST /api/system/departments` | ❌ | V-1 |
| 部门 | UPDATE | ✅ | ✅ `PUT /{id}` | ❌ | V-1 |
| 部门 | ENABLE / DISABLE | ✅ | ✅ `PATCH /{id}/status` | ❌ | V-1 |
| 用户 | UPDATE（资料） | ✅ | ✅ `PUT /api/system/users/{id}` | ❌ | V-1 |
| 用户 | ENABLE / DISABLE | ✅ | ✅ `PATCH /{id}/status` | ❌ | V-1 |
| 用户 | ASSIGN_ROLES | ✅ | ✅ `PUT /{id}/roles` | ❌ | V-1 |
| 角色 | CREATE | ✅ | ✅ `POST /api/system/roles` | ❌ | V-1 |
| 角色 | UPDATE | ✅ | ✅ `PUT /{id}` | ❌ | V-1 |
| 角色 | ASSIGN_PERMISSIONS | ✅ | ✅ `PUT /{roleCode}/permissions` | ❌ | V-1 |
| 操作审计 | 查询 | ✅ `queryOperationAudit` | ✅ `GET /api/ai/operation-audits` | ❌ 无页面 | V-3 |
| 工具调用 | 自查 | ✅ `queryMyToolCalls` | ✅ `GET /api/ai/tool-calls/mine` | ❌ 无页面 | V-3 |
| 菜单 | 权限过滤 | —（不适用） | — | ❌ 硬编码 | V-4 |
| 提案 | 全生命周期 | ✅ 确认/拒绝 | ✅ `/api/ai/proposals*` | ✅ 确认卡 | — |

### 2.2 缺口统计

| 分类 | 数量 | 明细 |
| --- | --- | --- |
| **A. 后端已支持、页面零入口** | 12 | 部门 3 + 用户 3 + 角色 3 + 机构 2（占位）+ 险种 1 |
| **B. 有页面但缺按钮/按钮失效** | 3 | 机构新增、机构修改、险种启停 |
| **C. 完全无对应页面** | 2 | 操作审计页、我的工具调用页 |
| **D. 前端权限/体验违规** | 1 | 菜单与路由未按权限过滤（SYS-P-06） |
| **合计** | **18** | — |

> **A 类与 B 类是同一件事的两种表现**，下面按域组织补齐方案（§4 ~ §8），C/D 类单独成节（§6.3、§9）。

### 2.3 关键现状说明

1. **写接口与 API 函数部分已存在**：`frontend/src/api/system.ts` 已导出
   `createOrg` / `updateOrg` / `changeOrgStatus`，但**没有任何页面调用它们**——属于"半成品接线"。
2. **`Orgs.vue` 存在两个 `disabled` 占位按钮**（第 376-377 行），是本次要清除的最直接违规。
3. **部门的 4 个动作在页面上完全没有痕迹**，连列表页都没有操作列。
4. **`guarantee-system` 的写 Service 已包含全部领域校验与危险动作保护**，
   页面补齐只需接线 + 提示，不需要重做校验逻辑（C-02 / C-03）。
5. **两个新的查询接口（审计、自查）已经存在**，只差页面。

---

## 3. 补齐方案总览

| 序号 | 条目 | 域 | 违规 | 工作量 | 依赖 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| P-01 | 机构新增 / 修改 | 机构 | V-1, V-2 | S | — | 待实施 |
| P-02 | 险种启用 / 停用 | 险种 | V-1 | XS | — | 待实施 |
| P-03 | 部门新增 / 修改 / 启停 | 部门 | V-1 | M | — | 待实施 |
| P-04 | 用户改资料 / 启停 / 角色分配 | 用户 | V-1 | L | P-07（角色选项） | 待实施 |
| P-05 | 角色新增 / 修改 / 授权 | 角色 | V-1 | L | — | 待实施 |
| P-06 | 菜单与路由权限过滤 | 全局 | V-4 | S | — | 待实施 |
| P-07 | 操作审计页 | 审计 | V-3 | M | — | 待实施 |
| P-08 | 我的工具调用页 | 审计 | V-3 | S | — | 待实施 |
| **P-09** | **页面写操作接入统一审计** | **审计** | **C-05** | **M** | P-01~P-05 | ✅ **已完成**（§12.7） |

> **P-09 已先行完成**，因为它是**合规缺口**（AC-22 / SYS-A-07），与"补按钮"是两件事：
> 即使页面还没有按钮，页面写路径（以及后续补齐的按钮）也必须留痕。
> 其余条目仍未实施，其中 P-01/P-02/P-06 是清除显性违规的最小集。

工作量口径：`XS` ≤ 0.5 人日 · `S` ≈ 1 人日 · `M` ≈ 2~3 人日 · `L` ≈ 3~5 人日。

**推荐实施顺序**：`P-02` → `P-01` → `P-06` → `P-03` → `P-05` → `P-07` → `P-08` → `P-04` → `P-09`

理由：先做最小改动清除"占位按钮"这类显性违规（P-01/P-02），再补权限过滤（P-06，横切且能立刻消除误导），然后按风险从低到高补写操作（部门 < 角色 < 用户），最后做审计页与统一审计（P-09 依赖前面所有写入口存在才有意义）。

---

## 4. P-01：机构新增 / 修改

### 4.1 目标

- 清除 `Orgs.vue` 中两个 `disabled` 占位按钮（V-2）
- 提供"新增下级机构""修改机构"两个真实可用的对话框

### 4.2 页面设计

**入口**

| 位置 | 按钮 | 权限码 | 行为 |
| --- | --- | --- | --- |
| 卡片工具栏 | 「新增机构」 | `system:org:create` | 打开新增对话框，`parentId` 默认 0（顶级/总部） |
| 树节点操作列 | 「新增下级」 | `system:org:create` | 打开新增对话框，`parentId` 预填该节点 id、`orgLevel` 自动 = 该节点层级 + 1 |
| 树节点操作列 | 「修改」 | `system:org:update` | 打开编辑对话框，回填当前值 |

**表格列增补**：操作列宽度需从 140 调整到 200（容纳 3 个 link 按钮）。

**新增/编辑对话框字段**

| 字段 | 新增 | 修改 | 控件 | 交互规则 |
| --- | --- | --- | --- | --- |
| `orgCode` | 必填 | **只读**（`orgCode` 不可改） | `el-input` | 新增时前端只校验非空与长度，唯一性由后端判定 |
| `orgName` | 必填 | 可改 | `el-input` | — |
| `regionCode` | 必填 | 可改 | `el-input` | 6 位数字，提示"如 330000 浙江省" |
| `regionName` | 选填 | 可改 | `el-input` | 留空时由后端继承上级的 `regionName` |
| `orgLevel` | 必填 | 可改（需谨慎） | `el-select`（1 总部 / 2 省级 / 3 市级） | **联动**：选了 `parentId` 后自动推导为"父层级 + 1"，并置为只读；仅当 `parentId=0` 时允许选 1 |
| `parentId` | 必填 | 可改 | `el-tree-select`（机构树） | 数据源用 `listOrgTree`，**排除自身与自身子树**（防环，前端先拦、后端兜底） |
| `sortNo` | 选填 | 可改 | `el-input-number` | 默认 0 |

**修改对话框的只读字段展示**：`orgCode`、当前层级、上级机构以只读文本呈现，避免用户误以为可改。

### 4.3 交互与错误处理

| 场景 | 前端行为 |
| --- | --- |
| 层级与上级不匹配 | `parentId` 变更时自动重算 `orgLevel`，不给用户"选错"的机会 |
| 上级机构选到自己或自己的下级 | 树选择器中**禁用**这些节点（`el-tree-select` 的 `disabled` 属性） |
| 提交后后端返回"机构编码已存在" | 原样展示后端文案（`BizException` 的 message 已足够可读），不清空表单 |
| 提交成功 | `ElMessage.success` + 关闭对话框 + 刷新树 + **保持原展开状态** |

> **展开状态保持**：树刷新后若重置 `expandedKeys`，用户连续新增多个机构时会反复被"折叠"打断。实现上把 `expandedKeys` 在 `loadData` 前快照、之后回填（`Orgs.vue` 当前已有 `defaultExpanded` 兜底，需改为"优先沿用用户当前展开态"）。

### 4.4 涉及文件

| 文件 | 变更 |
| --- | --- |
| `frontend/src/views/system/Orgs.vue` | 新增对话框、接线 `createOrg`/`updateOrg`、移除 disabled 占位 |
| `frontend/src/api/system.ts` | 已存在 `createOrg` / `updateOrg`，无需改动（但需补 `OrgTreeQuery` 已引用的类型） |
| `frontend/src/types/system.ts` | 已存在 `OrgItem` / `OrgTreeNode`，新增 `OrgCreateParams` / `OrgUpdateParams` 显式类型（替换当前 `Record<string, unknown>`） |

### 4.5 后端

**无需改动**。`OrgController.create/update` + `OrgService.validateCreate/validateUpdate` 已实现：

- `orgCode` 唯一性、`parentId` 层级校验、防环（`selectVisibleOrgIds` 递归检测）
- 数据范围校验（`dataScopeService.requireInScope`）

### 4.6 测试要点

| 编号 | 断言 |
| --- | --- |
| P01-T1 | 新增省级机构（`parentId` = 总部、`orgLevel=2`）：提交成功，树中出现在总部下 |
| P01-T2 | 新增市级机构：`parentId` 选省级后 `orgLevel` 自动为 3 且只读 |
| P01-T3 | 上级选择器不出现"自己"与"自己的下级" |
| P01-T4 | 重复 `orgCode` 提交：提示"机构编码已存在"，表单保留 |
| P01-T5 | 修改机构名称：树中实时更新，展开状态不变 |
| P01-T6 | VIEWER 账号登录：看不到"新增机构/新增下级/修改"按钮（SYS-C-18） |

---

## 5. P-02：险种启用 / 停用

### 5.1 目标

补齐 `PATCH /api/system/insurance-types/{id}/status` 的页面入口。

### 5.2 页面设计

| 位置 | 按钮 | 权限码 | 文案 |
| --- | --- | --- | --- |
| 表格操作列 | 「停用」/「启用」 | `system:insurance:disable` | 按当前状态切换 |

**操作列现状**：`InsuranceTypes.vue` 操作列目前只有"编辑"，需在同列追加启停按钮。

### 5.3 交互细节

| 场景 | 处理 |
| --- | --- |
| 点击停用 | `ElMessageBox.confirm` 二次确认，文案需含**影响面**：停用后该险种不再出现在新订单可选列表中，历史订单不受影响 |
| 点击启用 | 单次确认即可（低风险） |
| 后端返回"已被 N 条订单引用" | 原样展示（`stopImpact` 的结果在助手侧展示，页面侧由后端在拒绝时给出条数） |
| 成功后 | 刷新当前页，保持筛选条件与页码 |

> **口径一致性**：助手侧确认卡的"影响面"文案与页面确认弹窗文案应保持一致（同一句话），避免"同一个动作两种说法"。

### 5.4 涉及文件

| 文件 | 变更 |
| --- | --- |
| `frontend/src/views/system/InsuranceTypes.vue` | 操作列增启停按钮 + 确认弹窗 |
| `frontend/src/api/system.ts` | 新增 `changeInsuranceTypeStatus(id, status)` |

### 5.5 后端

**无需改动**。`InsuranceTypeController.changeStatus` + `InsuranceTypeService.changeStatus` 已实现条件更新（`updateStatus(id, status, expectedStatus)`）。

### 5.6 测试要点

| 编号 | 断言 |
| --- | --- |
| P02-T1 | 停用启用中的险种 → 状态变 0，`ai_operation_audit` 有记录（P-09 完成后） |
| P02-T2 | 重复点击（双击）→ 仅一次成功，第二次提示"已处于目标状态" |
| P02-T3 | VIEWER 看不到启停按钮 |

---

## 6. P-03：部门新增 / 修改 / 启停

### 6.1 目标

`Departments.vue` 当前**只有查询**（186 行，无操作列）。补齐 4 个动作。

> **为什么部门页保持列表而不是树形**：需求 SYS-C-22 明确规定「部门配置页本期不做树形，仅要求列表能正确展示层级字段（`parent_id`）」。本方案遵守该结论，只补操作能力，不改展示形态。

### 6.2 页面设计

**工具栏**：新增「新增部门」按钮（权限 `system:dept:create`）。

**表格增补**：操作列（宽 200）+ 上级部门列（把 `parentId` 翻译成名称，当前只显示 ID，可读性差）。

**新增对话框字段**

| 字段 | 必填 | 控件 | 规则 |
| --- | --- | --- | --- |
| `deptCode` | 是 | `el-input` | 全局唯一（后端判定） |
| `deptName` | 是 | `el-input` | — |
| `orgId` | 是 | `el-select`（机构下拉） | 数据源 `listOrgOptions()`；**必填且必须在数据范围内** |
| `parentId` | 否 | `el-tree-select`（同机构部门树） | 0 = 机构内顶级；**只列出同机构的部门** |
| `sortNo` | 否 | `el-input-number` | 默认 0 |

**修改对话框字段**：`deptName` / `parentId` / `sortNo`。
**不可改字段**（以只读文本展示并给出说明）：`deptCode`、`orgId` —— 需求 SYS-W-03 明确「`deptCode`、`orgId` 不可改（如需换机构请停用后新建）」。

**启停按钮**：权限 `system:dept:disable`；停用时确认弹窗展示影响面（该部门下启用用户数）。

### 6.3 关键交互：同机构约束

`parentId` 的选择器**必须只允许同一机构下的部门**。实现方式：

1. 用户选定 `orgId` 后，调用 `listDepartmentOptions(orgId)` 拉该机构部门；
2. 修改场景下 `orgId` 不可改，因此该列表固定；
3. 若用户在新增时先选了 `parentId` 再改 `orgId`，清空 `parentId` 并提示。

> 后端 `DepartmentService.validateCreate` 已校验"上级部门必须与本部门属于同一机构"，前端做这层限制是为了避免让用户提交必然失败的请求。

### 6.4 涉及文件

| 文件 | 变更 |
| --- | --- |
| `frontend/src/views/system/Departments.vue` | 操作列 + 两个对话框 + 启停 |
| `frontend/src/api/system.ts` | 新增 `createDepartment` / `updateDepartment` / `changeDepartmentStatus` |
| `frontend/src/types/system.ts` | 新增 `DepartmentCreateParams` / `DepartmentUpdateParams` |

### 6.5 后端

**无需改动**。已实现：`create`（编码唯一 + 机构范围 + 上级同机构）、`update`（`deptCode`/`orgId` 不可改由 DTO 层面保证——`UpdateRequest` 不含这两个字段）、`changeStatus`（部门下有启用用户时拒绝）。

### 6.6 测试要点

| 编号 | 断言 |
| --- | --- |
| P03-T1 | 新增部门：出现在列表中，`orgName` 正确 |
| P03-T2 | 上级部门选择器只列出同机构部门 |
| P03-T3 | 新增时 `orgId` 选 A、`parentId` 选了 A 下部门，再改 `orgId` 为 B → `parentId` 被清空并提示 |
| P03-T4 | 修改对话框不出现 `deptCode` / `orgId` 输入框（只读展示） |
| P03-T5 | 停用有启用用户的部门 → 后端拒绝，页面展示原因 |
| P03-T6 | VIEWER 无任何操作按钮 |

---

## 7. P-04：用户改资料 / 启停 / 角色分配

### 7.1 目标

`Users.vue` 当前**只有查询**（217 行）。补齐 3 个动作，**严格限定在 D-2 收敛后的范围内**：

| 动作 | 本期 | 说明 |
| --- | --- | --- |
| UPDATE（资料） | ✅ 做 | `realName` / `phone` / `email` / `deptId` |
| ENABLE / DISABLE | ✅ 做 | 停用需二次确认 + 撤销令牌提示 |
| ASSIGN_ROLES | ✅ 做 | 角色多选 |
| **CREATE** | ❌ **不做** | D-2 / D-2a：新建账号单独立项（含初始密码分发） |
| **RESET_PASSWORD** | ❌ **不做** | D-2 / D-2a：密码操作走独立高保障渠道 |

> **重要**：页面上**不得**出现"新增用户""重置密码"按钮。若出现，反而构成新的倒挂（助手明确不支持、页面却能做，会与 `SYS-N-10` 的话术矛盾，用户会问"为什么助手说不能做"）。这正是"手动 ⊇ 助手"原则的**下界**：可以更大，但不能大到与需求决策冲突——D-2 已经把"新建/重置"整体移出本期，所以两个渠道都没有，不属于违规。

### 7.2 页面设计

**工具栏**：仅「查询」「重置」（无新增按钮，符合 D-2）。

**表格增补**：操作列（宽 220）——「改资料」「停用/启用」「角色」。

**改资料对话框**

| 字段 | 控件 | 规则 |
| --- | --- | --- |
| `username` | 只读文本 | 不可改（SYS-W-04） |
| `orgName` | 只读文本 | **不可改**（换机构请停用后重建） |
| `realName` | `el-input` | 可改 |
| `phone` | `el-input` | 11 位手机号；**掩码展示规则见 7.3** |
| `email` | `el-input` | 邮箱格式 |
| `deptId` | `el-select`（该机构部门） | **禁止修改自己的部门**——对自己时该控件置灰并给出提示 |

**启停确认弹窗**（停用时）

必须包含三段信息，与助手确认卡口径一致：

1. **影响面**：该用户持有的角色列表
2. **影响**：该用户未完结的 AI 会话将失效；其持有的 JWT 将被撤销，需重新登录
3. **二次确认**：危险动作，需再次点击确认

**角色分配对话框**

| 元素 | 说明 |
| --- | --- |
| 当前角色 | 只读展示 |
| 变更后角色 | `el-select multiple`，选项来自 `pageRoles`（仅启用角色） |
| 变更对比 | 提交前展示「当前角色 → 变更后角色」diff（与助手确认卡一致） |
| 提示 | 变更后该用户全部 JWT 被撤销，权限立即生效，需重新登录 |

**对自己操作时的特殊限制**（后端已实现，前端应禁用相应控件以避免必然失败）：

| 场景 | 前端处理 |
| --- | --- |
| 修改自己的部门 | `deptId` 置灰 + tooltip「不允许修改自己的所属部门」 |
| 停用自己 | 「停用」按钮置灰 + tooltip「不允许停用自己的账号」 |
| 给自己增删 ADMIN | 角色选项中 `ADMIN` 置灰（当目标是自己时） |
| 移除最后一个 ADMIN 的 ADMIN 角色 | 允许提交，由后端拒绝并展示原因（前端无法可靠判断"是否最后一个"） |

### 7.3 手机号 / 邮箱的展示口径（关键决策点）

当前页面 `Users.vue` 直接展示 `phone` / `email` 明文（Q-15 暂缓口径，RK-11 已登记为"有意接受的不一致"）。

**本方案的处理**：**保持明文不变**（不动 Q-15 结论）。理由：

1. Q-15 明确"本期不改页面，避免引入预期外的页面变更"，本方案是"补操作能力"，不应顺手改展示口径；
2. 若在本次顺手改成掩码，会改变既有用户（运营人员）的使用习惯，且需要同步更新文档与测试；
3. 但**改资料对话框中的"当前手机号"回显必须特殊处理**：由于提交的是**新值**，对话框应显示"当前：138****5678 → 新值：（输入框）"，其中"当前"用掩码展示——理由是这里展示的是**变更对比**，与列表的"浏览"语义不同，掩码更符合"审计只记是否变更"的 D-4 精神。

> 这一取舍需要在评审时确认。若评审要求"列表也掩码"，则应作为独立需求（沿用 Q-15 的收敛路径），不并入本方案。

### 7.4 涉及文件

| 文件 | 变更 |
| --- | --- |
| `frontend/src/views/system/Users.vue` | 操作列 + 改资料/角色对话框 + 启停确认 |
| `frontend/src/api/system.ts` | 新增 `updateUser` / `changeUserStatus` / `assignUserRoles` |
| `frontend/src/types/system.ts` | 新增 `UserUpdateParams` / `UserAssignRolesParams` |

### 7.5 后端

**无需改动**。已实现全部分级校验与危险动作保护：

- `UserService.validateUpdateProfile`：手机号/邮箱格式、不得改自己部门、至少一个字段
- `UserService.validateStatusChange`：禁止停用自己、禁止停用最后一个启用 ADMIN
- `UserService.validateAssignRoles`：角色必须存在且启用、禁止给自己增删 ADMIN、禁止移除最后一个 ADMIN 的 ADMIN
- `UserService.changeStatus` / `assignRoles`：撤销该用户全部令牌（`UserTokenRevoker`）

### 7.6 测试要点

| 编号 | 断言 |
| --- | --- |
| P04-T1 | 改资料：`realName` 变更成功并刷新 |
| P04-T2 | 改手机号为非法格式 → 后端拒绝，页面展示原因 |
| P04-T3 | 对自己：`deptId` 置灰、"停用"按钮置灰 |
| P04-T4 | 停用最后一个 ADMIN → 后端拒绝并展示"最后一个启用状态的超级管理员" |
| P04-T5 | 角色分配：提交后该用户旧 token 立即 401（AC-21） |
| P04-T6 | 页面上不存在"新增用户""重置密码"按钮（D-2 下界） |
| P04-T7 | VIEWER / OPERATOR 看不到用户写按钮（OPERATOR 无 `system:user:update`，见权限矩阵） |

---

## 8. P-05：角色新增 / 修改 / 授权

### 8.1 目标

`Roles.vue` 当前**只有查询**（163 行）。补齐 3 个动作。

### 8.2 页面设计

**工具栏**：新增「新增角色」按钮（`system:role:create`）。

**表格增补**：
- 操作列（宽 200）：「修改」「授权」，`ADMIN` 行的这两个按钮需**禁用**（`ADMIN` 不可改、不可授权）
- 权限列当前展示 `permissionNames`（320px 宽），建议改为「前 3 个 + `共 N 项`」的折叠展示，避免长列表撑破表格

**新增角色对话框**

| 字段 | 必填 | 规则 |
| --- | --- | --- |
| `roleCode` | 是 | 不得使用保留码 `ADMIN`（前端先拦，后端兜底） |
| `roleName` | 是 | — |
| `description` | 否 | ≤ 255 字符 |

**修改角色对话框**：`roleName` / `description` / `status`；`roleCode` 只读展示。

**授权对话框（工作量最大）**

| 元素 | 说明 |
| --- | --- |
| 权限树 | 数据源 `listPermissions()`（扁平列表，前端按 `parentId` 组树） |
| 分组 | 建议按权限码前缀分组：`dashboard` / `order` / `analysis` / `project` / `enterprise` / `system:insurance` / `system:org` / `system:dept` / `system:user` / `system:role` / `system:permission` / `audit` / `ai` |
| 勾选 | `el-tree` 的 `show-checkbox`，`node-key="permCode"`（**用 permCode 而不是 id**：后端接口 `PUT /{roleCode}/permissions` 收 `permCodes`） |
| 危险权限提示 | 勾选 `system:audit:view` 或 `system:user:assign-role` 时给出 warning 提示（这两个是最高敏感项） |
| 变更对比 | 提交前展示「当前权限 → 变更后权限」的差异（新增/移除分别列出） |
| 变更后影响 | 明确提示：持有该角色的 N 个用户 JWT 将被撤销，需重新登录 |

#### 8.2.1 `ADMIN` 角色按钮：必须"禁用 + tooltip"（已确认的硬要求）

| 要求 | 说明 |
| --- | --- |
| **禁用** | `ADMIN` 行的「修改」「授权」按钮为 `disabled`：`ADMIN` 不可改、不可授权（SYS-W-05 / `RoleService.validateUpdate` / `validateAssignPermissions` 会拒绝） |
| **必须有 tooltip** | 禁用态必须挂 tooltip 说明原因：**「超级管理员（ADMIN）角色不允许修改」/「超级管理员（ADMIN）角色不允许变更权限」** |
| **为什么强制** | 只有禁用而无说明，就又退回了 V-2——用户看到一个点不动的按钮，无法区分"我没权限""这是保留角色""系统坏了"三种可能。**禁用的语义必须可见**，否则补齐工作等于白做 |
| 通用化 | 本要求适用于所有"因业务规则而禁用"的按钮（如 §7.2 的"停用自己"置灰、"修改自己部门"置灰），统一约定：**`disabled` 必须与 `el-tooltip` 成对出现** |

> 建议抽一个极小的约定而非组件：`<el-tooltip :disabled="!reason" :content="reason"><span><el-button :disabled="!!reason">…</el-button></span></el-tooltip>`。
> 注意 `el-tooltip` 对 `disabled` 的 `el-button` 不生效（禁用元素不触发鼠标事件），因此**必须用 `<span>` 包裹**——这是 Element Plus 的已知行为，容易踩坑。


### 8.3 涉及文件

| 文件 | 变更 |
| --- | --- |
| `frontend/src/views/system/Roles.vue` | 工具栏 + 操作列 + 3 个对话框 + 权限树组件 |
| `frontend/src/api/system.ts` | 新增 `createRole` / `updateRole` / `assignRolePermissions`；`pageRoles` 已存在 |
| `frontend/src/types/system.ts` | 新增 `RoleCreateParams` / `RoleUpdateParams` / `RoleAssignPermissionsParams` |
| 建议新增 | `frontend/src/components/PermissionTree.vue`（可复用的权限树，供角色授权与其他场景使用） |

### 8.4 后端

**无需改动**。已实现：

- `RoleService.create`：`ADMIN` 保留码拒绝、编码唯一
- `RoleService.update`：`ADMIN` 不可改
- `RoleService.assignPermissions`：`ADMIN` 不可授权、权限码必须已存在、变更后撤销持有该角色用户的令牌

### 8.5 测试要点

| 编号 | 断言 |
| --- | --- |
| P05-T1 | 新增角色 `REGION_OPS`：列表出现，初始权限数为 0 |
| P05-T2 | 角色编码填 `ADMIN` → 前端拦截（或后端拒绝），提示保留码 |
| P05-T3 | `ADMIN` 行的"修改""授权"按钮为禁用态且有 tooltip |
| P05-T4 | 授权：勾选 `system:org:view` 提交 → 权限列更新，`permissionCount` 正确 |
| P05-T5 | 授权变更后，持有该角色的在线用户下次请求 401（AC-21） |
| P05-T6 | 权限树不提供"新建权限码"入口（R-04：权限主数据只读） |

---

## 9. P-06：菜单与路由权限过滤（SYS-P-06）

### 9.1 目标

消除 V-4：当前 `AppLayout.vue` 的 `menuGroups` 是硬编码常量，所有登录用户看到相同菜单；路由守卫只校验登录态。

### 9.2 现状问题

| 问题 | 影响 |
| --- | --- |
| 菜单不过滤 | VIEWER 看到"角色配置"入口，点进去要么空列表要么 403，体验像"系统坏了" |
| 路由守卫不校验 | 用户可直接输 URL 进入任意页面；后端会拦数据，但页面会渲染出空壳并弹出 403 提示 |
| 不一致 | 助手侧 `queryRole` 会明确回 `denied=true` 并给话术；页面侧却是"空白 + 报错" |

### 9.3 方案

**菜单数据源改造**：`menuGroups` 从常量改为 `computed`，按 `userStore.permissions` 过滤。

| 菜单项 | 所需权限码 |
| --- | --- |
| 首页 | `dashboard:view` |
| 投标订单 | `order:tender:view` |
| 履约订单 | `order:performance:view` |
| 数据概览 | `analysis:overview:view` |
| 项目管理 | `project:view` |
| 企业管理 | `enterprise:view` |
| 险种配置 | `system:insurance:view` |
| 机构配置 | `system:org:view` |
| 部门配置 | `system:dept:view` |
| 用户配置 | `system:user:view` |
| 角色配置 | `system:role:view` |
| 操作审计（P-07 新增） | `system:audit:view` |
| 我的工具调用（P-08 新增） | `ai:chat` |

**空分组处理**：若某分组（如"系统配置"）下所有子项都被过滤掉，则该分组整体不渲染。

**路由守卫增补**：在 `router/index.ts` 的 `beforeEach` 中增加 `meta.permission` 校验：

```ts
// 路由 meta 上声明所需权限码
{ path: 'system/roles', name: 'SystemRoles', meta: { title: '角色配置', permission: 'system:role:view' } }

// 守卫中：登录已通过后，若 meta.permission 不在 userStore.permissions 中 → 跳首页并提示
if (permission && !userStore.permissions.includes(permission)) {
  ElMessage.warning('你当前没有访问该页面的权限')
  return { path: '/dashboard' }
}
```

**注意**：`permissions` 依赖 `userStore` 已加载。若刷新页面时 `user` 为空（仅有 token），守卫需先 `await userStore.fetchMe()` 再判断——否则会出现"刷新后被误判无权限"。

### 9.4 明确不做的事

- **不做按钮级的统一权限指令**：本次各页面已按 `permissions.includes(...)` 显式控制（SYS-C-18），暂不引入 `v-permission` 自定义指令，避免一次性大范围改动。可作为后续优化。
- **前端过滤不作为安全边界**（SYS-NF-04）：后端 `@PreAuthorize` 与数据范围保持不变，前端过滤只解决"看到不该看的入口"。

### 9.5 涉及文件

| 文件 | 变更 |
| --- | --- |
| `frontend/src/layout/AppLayout.vue` | `menuGroups` 改为 `computed` + 权限过滤 + 空分组剔除 |
| `frontend/src/router/index.ts` | 路由 `meta` 增加 `permission`；守卫增加权限校验与 `fetchMe` 兜底 |

### 9.6 测试要点

| 编号 | 断言 |
| --- | --- |
| P06-T1 | VIEWER 登录：菜单出现"机构配置/部门配置/用户配置/角色配置/险种配置"，但**不出现**"操作审计" |
| P06-T2 | ANALYST 登录：菜单与权限矩阵一致（有系统管理只读、无操作审计） |
| P06-T3 | VIEWER 手动输入 `#/system/roles` → 被守卫拦回首页并提示 |
| P06-T4 | 刷新页面后直接访问某页面：不被误判无权限（`fetchMe` 兜底生效） |
| P06-T5 | 某分组全部子项被过滤时不渲染该分组标题 |

---

## 10. P-07：操作审计页

### 10.1 目标

消除 V-3：提供 `GET /api/ai/operation-audits` 的页面入口，使"谁改了什么"不再只能靠助手回答。

### 10.2 页面设计

**路由**：`/system/operation-audits`，菜单「操作审计」（`parentTitle: 系统配置`，权限 `system:audit:view`）。

**筛选区**

| 字段 | 控件 | 约束 |
| --- | --- | --- |
| 时间区间 | `el-date-picker`（type=daterange） | **必填**，默认最近 7 天；最大跨度 90 天（前端限制 `disabledDate`，后端二次校验） |
| 操作人账号 | `el-input` | 模糊 |
| 目标类型 | `el-select` | USER / ORG / DEPT / ROLE / PERMISSION / INSURANCE_TYPE |
| 动作 | `el-select` | CREATE / UPDATE / ENABLE / DISABLE / ASSIGN_ROLES / ASSIGN_PERMISSIONS |
| 结果 | `el-select` | SUCCESS / FAILED / REJECTED / EXPIRED / PARTIAL |
| 渠道 | `el-select` | AI（助手确认）/ WEB（页面直连） |

**表格列**

| 列 | 说明 |
| --- | --- |
| 操作时间 | `operatedAt` |
| 操作人 | `operatorRealName`（`operatorUsername`） |
| 渠道 | `source` → tag（`AI` = warning / `WEB` = primary） |
| 动作 | `action` 中文名 |
| 目标 | `targetType` 中文 + `targetName`(`targetId`) |
| 变更字段 | `changedFields`（tag 列表） |
| 结果 | `result` → tag（SUCCESS 绿 / FAILED 红 / REJECTED 灰 / EXPIRED 灰） |
| 前后值 | 「查看」链接 → 抽屉展示 `beforeValue` / `afterValue` 的 JSON diff |
| 提案/会话 | `proposalId` / `conversationId` 可点击跳转（P2 增强） |

**详情抽屉**

- 结构化展示 `beforeValue` → `afterValue` 的字段级 diff（表格：字段 / 原值 / 新值）
- 敏感字段展示 `changed` 占位符时的说明文案：**"该字段为敏感字段，审计只记录是否变更，不记录具体值（D-4）"**——必须在界面上解释，否则用户会以为"数据丢了"
- `traceId` 展示并支持复制（AC-23 可回溯）

**容量提示**：页面顶部显示"在线保留 24 个月"的说明（D-5），并在查询跨度接近 90 天时提示"已接近查询上限"。

### 10.3 涉及文件

| 文件 | 变更 |
| --- | --- |
| `frontend/src/views/system/OperationAudits.vue` | **新建** |
| `frontend/src/api/ai.ts` | 新增 `pageOperationAudits(params)` |
| `frontend/src/types/ai.ts` | 新增 `OperationAuditItem` / `OperationAuditQuery` |
| `frontend/src/router/index.ts` | 新增路由 |
| `frontend/src/layout/AppLayout.vue` | 新增菜单项 |

### 10.4 后端

**接口已存在且不改动**：`GET /api/ai/operation-audits`（`@PreAuthorize("hasAuthority('system:audit:view')")`，仅 ADMIN）。

**结论：审计查询接口不增、不删、不改。** 页面直接消费现有的 `AiOperationAudit` 字段，中英映射（`action` → "修改"、`targetType` → "用户" 等）放在前端维护。理由：

1. 为了单个页面的可读性去动接口契约，收益配不上风险（会牵动既有的工具返回值 `OperationAuditQueryToolResult` 与契约测试）；
2. 中英映射本身是**展示层职责**，放在前端更符合分层；
3. 现有字段已经"正确且完整"：`before_value` / `after_value` 是结构化 JSON，`changed_fields` 已能体现"哪些字段变了"，`trace_id` 已可回溯——**信息本身不缺，缺的只是页面**。

> 原方案曾提出把响应改为专用 VO（含 `actionName` 与结构化 `before`/`after`），**该例外已撤销**（见 §13）。前端如需对象化的 diff，用 `JSON.parse(beforeValue)` 自行处理即可。

### 10.4a 前端需要自行处理的两件事

| 事项 | 处理方式 |
| --- | --- |
| `beforeValue` / `afterValue` 是 JSON 字符串 | 前端 `JSON.parse`，解析失败时降级为原样文本展示（不抛异常打断渲染） |
| 中英文映射 | 在 `frontend/src/utils/status.ts` 旁新增 `auditDict.ts`，集中维护 `action` / `targetType` / `result` / `source` 四组映射，供审计页与其它页面共用 |

### 10.5 测试要点

| 编号 | 断言 |
| --- | --- |
| P07-T1 | 不选时间区间点查询 → 前端拦截（或后端拒绝并展示提示） |
| P07-T2 | 选 100 天区间 → 被拒绝并提示最大 90 天 |
| P07-T3 | ADMIN 查询看到 `source=WEB` 与 `source=AI` 两种记录混排（AC-22） |
| P07-T4 | ANALYST 访问该页面 → 菜单不显示、URL 被守卫拦住 |
| P07-T5 | 敏感字段记录在详情抽屉中显示 `changed` 且有 D-4 说明文案 |
| P07-T6 | `traceId` 可复制；同一 `traceId` 的会话/工具/提案/审计记录都能在助手侧找到（AC-23） |

---

## 11. P-08：我的工具调用页

### 11.1 目标

消除 V-3：为 `GET /api/ai/tool-calls/mine` 提供页面入口，使 ANALYST 的"自查"能力不依赖助手对话。

**这条对"手动 ⊇ 助手"原则尤其重要**：`queryMyToolCalls` 是 D-1a 回收 ANALYST 审计权限后的**补偿能力**。如果只有助手能做，那么 ANALYST 在模型不可用时连自己的调用记录都查不到——补偿能力形同虚设。

### 11.2 页面设计

**路由**：`/ai/my-tool-calls`，菜单「我的工具调用」（权限 `ai:chat`）。

**筛选区**：时间区间（默认最近 7 天，可选）、工具名（模糊）、状态（SUCCESS / FAILED）。

**表格列**：调用时间 / 工具名 / 类型（READ/WRITE tag）/ 状态 / 耗时(ms) / 结果摘要 / 所属会话（可点击跳转到助手会话）。

**关键说明文案**（页面顶部 info alert）：

> 这里只展示**你自己**的工具调用记录，且不含工具入参原文，只有结果摘要。这是为了保护敏感字段（手机号、邮箱等）不被旁路泄漏。如需查看全局操作审计，请联系超级管理员。

### 11.3 涉及文件

| 文件 | 变更 |
| --- | --- |
| `frontend/src/views/ai/MyToolCalls.vue` | **新建** |
| `frontend/src/api/ai.ts` | 新增 `pageMyToolCalls(params)` |
| `frontend/src/types/ai.ts` | 新增 `MyToolCallItem` / `MyToolCallQuery`（`resultSummary` 字段已由后端返回） |
| `frontend/src/router/index.ts` + `AppLayout.vue` | 路由与菜单 |

### 11.4 后端

**接口已存在**：`GET /api/ai/tool-calls/mine`（`@PreAuthorize("hasAuthority('ai:chat')")`，范围固定为当前用户，不接受用户维度参数）。

### 11.5 测试要点

| 编号 | 断言 |
| --- | --- |
| P08-T1 | ANALYST 能看到自己的记录，且**看不到**他人记录（TEST-17 的页面等价验证） |
| P08-T2 | 记录中 `resultSummary` 不含 `orderType`/`startDate` 等入参原文 |
| P08-T3 | 篡改请求参数（若前端曾拼过 userId）→ 后端忽略，仍只返回本人 |
| P08-T4 | 状态筛 FAILED 只返回失败记录 |

---

## 12. P-09：页面写操作接入统一审计（SYS-A-07 / AC-22）

### 12.1 目标

补齐助手与页面**两条渠道写入同一张审计表**的要求。

### 12.2 现状

| 渠道 | 是否写 `ai_operation_audit` | 说明 |
| --- | --- | --- |
| 助手确认（`source=AI`） | ✅ | `ProposalService.confirm` → `OperationAuditService.record` |
| 页面直连（`source=WEB`） | ❌ | 页面写操作**完全没有审计** |

**这是当前最实质的合规缺口**：AC-22 要求"页面与助手两条渠道的写操作在同一张审计表中可查，可按 `source` 区分"。目前页面改了数据，审计里什么都没有——**表结构、`source` 字段、查询接口都已就绪，只是页面这条写入路径没有接上**。

### 12.2a 与"审计不增删改"的关系（已界定，按下列口径执行）

评审结论「统一审计确实不缺接口，只要正确展示信息即可，不用增删改」与「页面直连也需要写入审计」结合后，本方案按下列口径执行：

| 侧 | 结论 | 本方案动作 | 状态 |
| --- | --- | --- | --- |
| **查询侧**（读审计） | 不缺接口、不改接口，只做正确展示 | 只在**前端**做页面与中英映射（§10.4a）。**不动任何后端接口** | ✅ 已按此执行（原 E-01 已撤销） |
| **写入侧**（写审计） | 表结构、`source` 字段、`OperationAuditService.record` 均已存在，但**页面的写操作没有调用它** | **必须接线**：在 `guarantee-system` 的写 Service 中调用既有能力，`source` 传 `WEB` | ✅ **已实施**（见 §12.7） |

**关键点**：P-09 **不是"新增审计接口"，而是"把已有的审计能力接到页面的写路径上"**。
不新增表、不新增字段、不新增查询接口，只新增一个跨模块端口（E-01）并补调用点。

### 12.6a 来源归属：身份相同，但渠道必须可区分（AC-22 的核心）

> **评审澄清后的结论**：两条渠道的操作者**都是同一个当前用户**——页面是他自己点的，
> 助手是他自己说话触发的。因此"身份"不需要区分，**需要区分的是渠道**。
> 这一条把问题一的性质说准了，同时也说明识别"非页面直连"不需要额外机制（见下）。

#### 身份：两条渠道同源，都取自 `CurrentUser`

`AiController#confirmProposal` 是一个**带 JWT 的普通 HTTP 请求**：

```java
public Result<ProposalPayload> confirmProposal(@PathVariable Long id) {
    return Result.ok(proposalService.confirm(id, executionContext()));
}
private ProposalExecutionContext executionContext() {
    CurrentUser.Principal principal = requirePrincipal();   // ← 身份就在这里
    ...
}
```

所以助手确认路径上 `CurrentUser` **是有效的**。身份的唯一来源就是它，
适配器不需要（也不应该）接受调用方传入的身份。

> **纠错记录**：本方案 v1.2 曾把"助手路径没有 `CurrentUser`"登记为第二个设计问题，
> **该结论是错的**。真实原因是：编写 `WebAuditIT` 时我**直接调用 Service** 而没有建立
> 身份上下文（`CurrentUser.set(...)`），于是观察到了"缺少身份"的现象，并误判为产品行为。
> 记录在此以说明：**用不真实的测试脚手架去验证行为，会把脚手架的限制误读成产品的限制。**

#### 渠道：必须显式标记，否则出现两个错误

虽然身份相同，但把两条渠道都记成 `WEB` 会产生两个实际后果：

| 错误 | 后果 |
| --- | --- |
| **渠道不可区分** | 事后复盘"这次变更是谁通过什么渠道做的"没有答案（AC-22 的全部意义就在这里） |
| **重复记账** | `ProposalService` 已写一条 `source=AI`，写 Service 无法判断自己是被谁调用的，会再写一条，同一次变更两条记录 |

**解法**：`AuditSourceContext`（`guarantee-common`，`ThreadLocal`）——
它**只携带渠道，不携带身份**：

```java
// ProposalService.confirm：执行 executor 之前标记渠道，finally 清理
AuditSourceContext.markAiDriven();
try {
    result = executor.execute(proposal, request, context);
} finally {
    AuditSourceContext.clear();
}

// WebAuditor：助手渠道直接跳过，由 ProposalService 统一记录（带提案号/会话号）
if (AuditSourceContext.isAiDriven()) {
    return;   // 一次变更恰好一条审计，且渠道正确
}
```

用 `ThreadLocal` 的依据：`confirm` 对 executor 的调用是**同步方法调用**（非 Reactor 线程切换），
线程亲和性成立；`try/finally` 保证清理，避免线程复用导致渠道串台。

#### 静默性：这类错误不会以"报错"形式暴露

如果漏掉 `AuditSourceContext`，系统**照常工作、没有任何异常**，只表现为审计表里
莫名多出一批 `source=WEB` 的助手操作（或反过来，助手操作少了一批渠道标记）。
属于典型的"静默错误"，只能靠测试断言锁住——见 §12.7 的 `aiConfirmShouldProduceExactlyOneAiAudit`。

#### 另一条独立防线：缺少身份时拒绝写入

适配器在 `CurrentUser` 为空时**主动失败**，而不是写一条匿名审计。这条防护的作用对象是
"绕过 Controller 直接调用写 Service"的调用方（测试脚手架、未来的内部批处理任务）：

- 理由：审计的可信度来自"每条记录都能定位到人"，写匿名记录等于污染整张表；
- 而且它**在真实链路上不会触发**（页面直连必经 `JwtAuthenticationFilter`，
  `requirePrincipal()` 已在 Controller 层拦过一道）；
- 印证：本次编写 `departmentWithEnabledUsersCannotBeDisabled` 测试时，
  我一开始忘了 `CurrentUser.set(...)`，正是这条防护把问题报了出来——
  **它抓到了第二次同类脚手架疏漏**，说明这条防线是有效的。

### 12.7 P-09 实施清单（已完成）

| 产物 | 位置 | 说明 |
| --- | --- | --- |
| `OperationAuditPort` + `WebAuditEntry` | `guarantee-common` | 端口与审计内容 DTO |
| `AuditSourceContext` | `guarantee-common` | 来源标记（AI / WEB），解决上述问题一 |
| `OperationAuditPortAdapter` | `guarantee-ai` | 实现端口：`source=WEB`、操作者取自 `CurrentUser`、traceId 取自 MDC |
| `WebAuditor` | `guarantee-system` | 写 Service 的统一入口；助手路径自动跳过 |
| 5 个 Service 接线 | `OrgService` / `DepartmentService` / `UserService` / `RoleService` / `InsuranceTypeService` | 共 13 个写方法全部接入 |
| `WebAuditIT` | `guarantee-web` | 6 个集成测试（含"5 个域各写一次都要有审计"的覆盖检查） |

**已通过的断言**（`WebAuditIT`）：

| 测试 | 断言的实质 |
| --- | --- |
| 改用户资料 | `source=WEB`；审计中**无手机号明文**（新旧值都没有），但 `changed_fields` 含 `phone` |
| 启停险种 | `source=WEB`；结构化前后值 `{"status":1}` → `{"status":0}` |
| 角色授权 | `source=WEB`；`after_value` 含新增权限码 |
| 两渠道共存 | `ai_operation_audit` 中 `AI` 与 `WEB` 记录同表可区分 |
| 审计失败回滚 | 缺少身份时抛异常且**业务回滚、不留审计**（SYS-A-03） |
| 5 域覆盖检查 | 5 个域各写一次 → 至少 5 条 `WEB` 审计（**防漏接**） |
| **助手渠道不重复记账** | 助手确认同一次变更**恰好两条**审计（`PROPOSAL_CREATED` + 执行结果），**全部为 `source=AI`，零条 `WEB`**；身份为当前用户（`ProposalFlowIT#aiConfirmShouldProduceExactlyOneAiAudit`） |
| **同人两渠道可区分** | 同一 ADMIN 分别通过页面与助手各改一次 → 两条记录渠道不同、都能定位到同一人（`ProposalFlowIT#sameUserTwoChannelsAreDistinguishable`） |

### 12.7a 部门停用保护：部门下有正常状态用户时禁止停用

**评审要求**：「部门删除或者禁用时，部门下面不能有状态正常的用户」。

**结论：该规则在 `guarantee-system` 中已实现**，本次补齐的是**测试覆盖**与**前端提示**：

| 项 | 状态 | 位置 |
| --- | --- | --- |
| 后端规则 | ✅ 已实现 | `DepartmentService.changeStatus`：`targetStatus == 0` 时查 `countEnabledUserByDept`，> 0 则拒绝并给出条数 |
| 统计口径 | ✅ 正确 | `SELECT COUNT(*) FROM sys_user WHERE dept_id = ? AND status = 1`，正是"状态正常"的用户 |
| 影响面数据 | ✅ 已提供 | `DepartmentService.stopImpact` → `部门下启用用户数`，供确认卡/页面弹窗展示 |
| 助手侧 | ✅ 已覆盖 | `DepartmentProposalExecutor` 执行期重新判定（SYS-C-05）；`proposeDepartmentChange` 预检同样拒绝 |
| **测试断言** | ✅ **本次补充** | `ProposalFlowIT#departmentWithEnabledUsersCannotBeDisabled`：有启用用户 → 拒绝且文案含条数；用户全部停用后 → 允许停用并落 `WEB` 审计 |
| 前端提示 | ⏳ 待实施 | 按 §8.2.1 的"禁用必须带 tooltip"约定，部门页"停用"按钮应挂 tooltip 展示影响面，避免用户点了才被打回 |

#### 关于"删除"的语义（重要）

本系统**没有物理删除**：需求 R-04 明确规定「不允许删除机构、部门、用户、角色、权限等主数据
（一期二期都不做物理删除，只做停用）」。因此：

| 术语 | 在本系统中的实现 |
| --- | --- |
| "删除部门" | = `DISABLE`（置 `status=0`），数据仍在，可被历史记录引用 |
| 物理 `DELETE` | **两个渠道都不提供** |

这正好落在 §1.4 的"合法下界"上：**删除能力 AI 没有、页面也没有**，
属于"两边都没有"的合规状态，不需要也无法通过补齐来"对齐"。
若将来确需删除能力，必须**先决策是否放开 R-04**，再同时给两个渠道加上——
顺序不能颠倒（否则又造出一次倒挂）。

> **2026-09-22 更新（本段结论已被后续变更覆盖）**：逻辑删除已按 `DEC-逻辑删除设计方案.md` 落地——
> 5 个域都提供 `action=DELETE` 的**逻辑删除**（`is_deleted=1`，不做物理删除，仍符合 R-04 的"不做物理删除"），
> 助手 `propose*` 与页面**两个渠道都有**，因此不再是"合法下界"状态，而是**已对齐**。
> 随后评审又决定**撤除页面的「显示已删除」开关与「恢复」入口**（5 个页面），
> 即：**删除两个渠道都有；恢复只有助手/接口有、页面没有**——这属于 §1.4 描述的能力不对称，
> 已作为有意识的决策记录在 `IMPL-逻辑删除-任务书.md`（LD-04b）与 `PLAN-部门配置树形改造方案.md` §13。
> 上表的"删除部门 = DISABLE"仅适用于逻辑删除落地之前的语境。


### 12.3 方案

**不改前端**，在 `guarantee-system` 的写 Service 上接入审计。

**为什么不放在 Controller**：审计必须与业务执行**同事务**（SYS-A-03：审计写入失败必须导致业务回滚）。放在 Controller 层会产生"业务提交了、审计没写"的窗口；放在 Service 的 `@Transactional` 方法内才能保证原子性。

**实现骨架**

```java
// guarantee-system 新增一个薄封装，避免每个 Service 重复拼装
@Component
public class WebOperationAuditor {
    // 注意：OperationAuditService 在 guarantee-ai 模块，guarantee-system 不能依赖它
    // → 通过 guarantee-common 定义端口（与 UserTokenRevoker 同一手法）
}
```

**必须先解决的模块依赖问题**：`OperationAuditService` 位于 `guarantee-ai`，而写 Service 位于 `guarantee-system`，方向相反。参照 `UserTokenRevoker` 的既有做法，在 `guarantee-common` 定义端口：

| 新增（`guarantee-common`） | 内容 |
| --- | --- |
| `OperationAuditPort` | `Long record(WebAuditEntry entry)` 接口 |
| `WebAuditEntry` | source/action/targetType/targetId/targetName/before/after/result/changedFields |

| 新增（`guarantee-ai`） | 内容 |
| --- | --- |
| `OperationAuditPortAdapter` | 实现端口，内部委托 `OperationAuditService`，`source` 固定为 `WEB`，操作者取自 `CurrentUser` |

**接入点**（每个写方法内，业务变更之后、方法返回之前）：

| Service | 方法 | action | before/after |
| --- | --- | --- | --- |
| `OrgService` | `create` / `update` / `changeStatus` | CREATE/UPDATE/DISABLE/ENABLE | 关键字段快照 |
| `DepartmentService` | `create` / `update` / `changeStatus` | 同上 | 同上 |
| `UserService` | `updateProfile` / `changeStatus` / `assignRoles` | UPDATE / DISABLE / ENABLE / ASSIGN_ROLES | **`phone`/`email` 必须传原值，由 `SensitiveFieldMasker` 在写入前脱敏** |
| `RoleService` | `create` / `update` / `assignPermissions` | CREATE / UPDATE / ASSIGN_PERMISSIONS | 权限码列表 |
| `InsuranceTypeService` | `create` / `update` / `changeStatus` | CREATE / UPDATE / DISABLE / ENABLE | 含费率 |

### 12.4 关键约束

| 编号 | 约束 |
| --- | --- |
| P09-1 | **脱敏在写入前完成**（D-4 / SYS-A-02b）：`UserService` 传 `phone`/`email` 原值给端口，由 `OperationAuditService` 统一调用 `maskSnapshotForAudit`，**Service 层不得自己拼掩码**（RK-12：三处各写一套必然出现旁路） |
| P09-2 | **审计与业务同事务**（SYS-A-03）：审计失败 → 业务回滚 |
| P09-3 | **不允许页面写操作绕过审计**：接入后写一个测试断言"每个写 Service 方法都会产出一条 `source=WEB` 的审计" |
| P09-4 | 操作者信息取自 `CurrentUser.Principal`（Web 线程可用），不得从请求参数取 |

### 12.5 涉及文件

| 文件 | 变更 |
| --- | --- |
| `guarantee-common/.../security/OperationAuditPort.java` | **新建**（端口 + `WebAuditEntry`） |
| `guarantee-ai/.../service/OperationAuditPortAdapter.java` | **新建**（适配器） |
| `guarantee-system/.../service/OrgService.java` 等 5 个 Service | 注入端口并接入审计 |
| `guarantee-web/src/test/.../WebAuditIT.java` | **新建**测试 |

### 12.6 测试要点

| 编号 | 断言 |
| --- | --- |
| P09-T1 | 页面新增机构 → `ai_operation_audit` 出现 `source=WEB`、`action=CREATE`、`result=SUCCESS` 的记录 |
| P09-T2 | 页面改用户手机号 → 审计 `before_value` 中**无明文手机号**，`changed_fields` 含 `phone`（TEST-15 的 WEB 渠道等价验证） |
| P09-T3 | 审计写入失败 → 业务回滚（用 mock 让 `record` 抛异常验证） |
| P09-T4 | 助手与页面各改一次同一实体 → 两条记录可按 `source` 区分（AC-22） |
| P09-T5 | 5 个域各抽 1 个写方法，确认都产出审计（防止漏接） |

---

## 13. 对既有约束的例外清单

本方案总体遵守"不新增、不删改后端接口"（C-01），以下是**全部例外**：

| 编号 | 例外 | 理由 | 影响面 |
| --- | --- | --- | --- |
| E-01 | 新增 `OperationAuditPort`（`guarantee-common`）+ `OperationAuditPortAdapter`（`guarantee-ai`） | 解决 `guarantee-system` → `guarantee-ai` 的反向依赖（P-09 必需） | 纯新增端口与适配器，**不改任何既有接口** |
| E-02 | `UserDto.Query` 可能需要暴露 `deptId` 供页面按部门筛选 | 用户列表当前不支持按部门筛选，而部门页与用户页联动时有用 | 可选；若评审认为不必要可去掉 |

> **已撤销的例外（原 E-01）**：曾提出把 `GET /api/ai/operation-audits` 的响应从实体改为专用 VO（含中文名与结构化 before/after）。**评审结论：审计查询接口不增删改，只做正确展示**，因此该例外作废，页面在前端完成映射与 JSON 解析（见 §10.4 / §10.4a）。

**明确不加的接口**：

- `POST /api/system/users`（新建用户）——D-2 明确不做
- `POST /api/system/users/{id}/reset-password`——D-2 明确不做
- 任何权限主数据的写接口——R-04 只读
- 任何审计记录的增/删/改接口——SYS-A-04「只增不改不删」，审计表只通过归档任务 `DROP PARTITION` 收缩，不提供业务写入口

---

## 14. 需求文档同步建议

本方案落地后，建议对 `docs/REQ-系统管理助手能力.md` 做以下回填（保持文档与代码一致）：

| 编号 | 回填内容 |
| --- | --- |
| 1 | 新增一节「**能力理论相等，手动形态是超集**」（即本文档 §1），作为跨需求的产品公理。核心是两层关系：理论层 `C_manual = C_ai`（必须成立的规范，用于对账）、实际层 `C_manual ⊇ C_ai`（必然成立的事实，无需干预） |
| 2 | 5.4 节（机构树形）补记：`Orgs.vue` 的写操作按钮在本次补齐中落地（原 SYS-C-18 只要求"按权限渲染按钮"，未要求按钮可用——**该措辞漏洞是本次倒挂得以存在的需求层面原因**） |
| 3 | 6.1 接口清单无需变更（无新增业务接口） |
| 4 | 第 9 章测试要求补：TEST-24（页面写操作审计一致性）、TEST-25（菜单/路由权限过滤）、TEST-26（手动能力 = 助手能力的对账测试，即 L2 护栏） |
| 5 | 第 10 章验收标准补：AC-35（页面可完成全部助手支持的系统管理写操作）、AC-36（页面写操作落 `source=WEB` 审计）、AC-37（禁用态必须带 tooltip 说明） |
| 6 | 14 章需求追踪矩阵补 P-01~P-09 与 AC-35/36/37 的对应 |
| 7 | 15.4 暂缓项补记：**Q-17「审计查询接口是否需要为页面做响应结构调整」→ 结论：不改接口，由前端展示层完成映射与解析** |
| 8 | 在文档开头「已定稿决策」表新增 **D-6：能力对齐原则**——理论相等、实际超集；且**新增 AI 能力时必须以页面（或明确人工通道）已存在为前提** |

**特别建议新增 TEST-26（对账测试，即 L2 护栏）**：把 §2.1 的能力矩阵变成可执行断言，防止未来再次出现倒挂。
详见本文档 §18.1（含清单格式与 4 条断言）。


---

## 15. 工作量与里程碑

| 阶段 | 内容 | 前端 | 后端 | 测试 | 合计 |
| --- | --- | --- | --- | --- | --- |
| W1 | P-02 险种启停 + P-01 机构新增/修改 + P-06 权限过滤 | 3 | 0 | 1 | **4** |
| W2 | P-03 部门 4 动作（含"禁用 + tooltip"约定） | 2.5 | 0 | 1 | **3.5** |
| W3 | P-05 角色 3 动作（含权限树 + ADMIN 禁用/tooltip） | 4 | 0 | 1.5 | **5.5** |
| W4 | P-04 用户 3 动作（含对自己操作的置灰/tooltip） | 3.5 | 0 | 1.5 | **5** |
| W5 | P-07 审计页 + P-08 自查页（**纯前端，不改后端接口**） | 3.5 | 0 | 1.5 | **5** |
| W6 | P-09 统一审计接入（端口 + 适配器 + 5 个域 13 个写方法接线） | 0 | 3（E-01） | 2 | **5** ✅ **已完成** |
| W7 | L2 护栏（能力对账测试）+ 联调 | 0.5 | 0.5 | 2（含 L2-1/2/4） | **3** |
| **合计** | — | **17** | **3.5** | **10.5** | **≈31 人日** |

> 相比初稿减少 1 人日：撤销了原 E-01（审计响应改 VO），W5 的后端工作量归零。

**可独立交付的最小闭环**：W1 + W2 + W4（机构、部门、用户的手动操作）≈ 12.5 人日，
即可消除 §2.2 中 A/B 两类共 15 项中的 9 项，且覆盖最高频的运营动作。

**推荐分批上线**（按"能否独立产生价值"切分，而非按人日平均）：

| 批次 | 内容 | 可交付价值 |
| --- | --- | --- |
| 第 1 批 | W1 + W2 | 机构、部门、险种的手动操作可用，`disabled` 占位清除，菜单权限正确 |
| 第 2 批 | W4 + W5 | 用户与角色手动操作可用；审计页与自查页可用（此时审计页已能看到已有的 `source=AI` 记录） |
| 第 3 批 | W6 + W7 | 页面写操作落 `source=WEB` 审计（AC-22 闭环）；L2 护栏上线防倒挂 |

> **第 3 批不能提前到第 2 批之前**：L2 护栏要断言"每个 propose* 工具都有对应页面能力"，
> 若页面能力还没补齐，护栏一上线就是红的。**先补齐，再上护栏**。

---

## 16. 风险与应对

| 编号 | 风险 | 影响 | 应对 |
| --- | --- | --- | --- |
| RK-W-01 | 页面写操作与助手写操作的**校验口径漂移** | 同一动作两个渠道行为不一致，用户困惑 | C-02 强制复用同一 Service 方法；P09-T4 断言两条路径结果一致 |
| RK-W-02 | 前端复制了危险动作保护规则，后端改动后前端失效 | 前端放行、后端拒绝，体验断裂 | C-03：规则只在服务端；前端只做"避免必然失败"的置灰 |
| RK-W-03 | 权限树组件与后端 `permCodes` 契约不一致（用 id 还是 code） | 授权静默失败或授权错误权限 | 接口契约固定为 `permCodes`；P05-T4 断言授权结果 |
| RK-W-04 | 统一审计接入遗漏某个写方法 | 又出现"改了没痕迹"的盲区 | P09-T5 逐域抽样 + 建议加"写方法必须产出审计"的架构测试 |
| RK-W-05 | 菜单过滤后 `permissions` 未加载就判断 | 刷新页面被误判无权限 | P-06 的 `fetchMe` 兜底 + P06-T4 |
| RK-W-06 | 本次顺手改了 Q-15 的明文展示口径 | 引入预期外变更，打乱既有用户习惯 | §7.3 明确保持明文；如需收敛另立需求 |
| RK-W-07 | 补齐后 AI 与页面出现**新的**倒挂（例如页面新增了助手没有的能力） | 反向不一致 | 这不是问题——原则允许 `C_manual ⊋ C_ai`；但**新增 AI 能力时**必须触发 C-06 检查 |

---

## 17. 验收标准

| 编号 | 验收项 | 通过标准 |
| --- | --- | --- |
| AC-W-01 | 无失效按钮 | 全站不存在渲染出来但不可用的写操作按钮（清除所有 `disabled` 占位） |
| AC-W-02 | 无能力倒挂 | 逐一验证 §2.1 矩阵中 A/B 两类 15 项：AI 能做的，页面都能做 |
| AC-W-03 | 按钮权限正确 | 4 个角色登录后，各页面可见按钮与 `docs/REQ-系统管理助手能力.md` 5.5.3 权限矩阵完全一致 |
| AC-W-04 | 危险动作保护一致 | 助手侧会拒绝的危险操作（停用最后一个 ADMIN、停用自己、给自己加 ADMIN），页面侧同样被拒绝且文案一致 |
| AC-W-05 | 审计统一 | 页面与助手各执行一次同类变更，`ai_operation_audit` 中出现两条可区分 `source` 的记录（AC-22） |
| AC-W-06 | 脱敏无旁路 | 页面改手机号后，审计中无明文（TEST-15 的 WEB 渠道等价验证） |
| AC-W-07 | 菜单权限过滤 | VIEWER / ANALYST / OPERATOR / ADMIN 四种登录态下，菜单项与权限矩阵一致；越权 URL 被守卫拦回 |
| AC-W-08 | 审计页可用 | ADMIN 能在页面上按时间/操作人/目标/结果/渠道筛选，能查看前后值 diff 与 `traceId`；**后端接口零改动** |
| AC-W-09 | 自查页严格 | 任意角色在页面上只能看到自己的工具调用记录，且不含入参原文 |
| AC-W-10 | **禁用态语义可见** | 全站每一个 `disabled` 的写入按钮都配有 tooltip 说明原因（含 `ADMIN` 角色按钮、对自己操作的置灰项）；不存在"点不动且不知道为什么"的按钮 |
| AC-W-11 | **L2 护栏生效** | 新增一个 `propose*` 工具但不填能力对账清单 → `ManualCapabilityParityTest` 失败；清单里的权限码写错 → 测试失败 |
| AC-W-12 | 回归 | 助手侧全部既有验收项（AC-01~AC-34）与本轮 `mvn verify`、前端构建全绿 |

---

## 18. 附录：能力矩阵维护护栏（L2 已确认纳入范围）

§1.1 的"理论层相等"要长期成立，靠"每次人工对账"不可持续。建议建立三层护栏，其中 **L2 已确认纳入本方案范围**：

| 层次 | 手段 | 成本 | 效果 | 状态 |
| --- | --- | --- | --- | --- |
| L1 | 本文档 §2.1 矩阵作为评审清单：**新增 AI 写工具时必须同步填写矩阵** | 0 | 靠流程约束 | 保留（辅助） |
| **L2** | 维护一份"能力对账清单"（YAML / Java 常量），`TEST-26` 断言每个 `propose*` 工具都有对应的页面权限码 | 1 人日 | **测试期拦截倒挂**：把产品原则变成"测试失败"，而测试失败无法被忽略 | ✅ **已确认实施** |
| L3 | 页面按钮显隐统一走 `permissions` 驱动 + 一份"页面动作 → 权限码"注册表，与该清单做双向校验 | 3~5 人日 | 双向防漂移 | 后续评估 |

### 18.1 L2 的实现形态

**清单内容**（建议放 `guarantee-ai/src/test/resources/manual-capability-matrix.yaml` 或 Java 常量类）：

```yaml
# 每个 AI 写工具 → 必须存在的页面能力
- tool: proposeOrgChange
  actions:
    CREATE:  { page: /system/orgs, permission: system:org:create }
    UPDATE:  { page: /system/orgs, permission: system:org:update }
    DISABLE: { page: /system/orgs, permission: system:org:disable }
    ENABLE:  { page: /system/orgs, permission: system:org:disable }
- tool: proposeUserChange
  actions:
    UPDATE:       { page: /system/users, permission: system:user:update }
    DISABLE:      { page: /system/users, permission: system:user:disable }
    ENABLE:       { page: /system/users, permission: system:user:disable }
    ASSIGN_ROLES: { page: /system/users, permission: system:user:assign-role }
# ... 其余 3 个工具
```

**断言内容**（`ManualCapabilityParityTest`，纯单元测试，不需要数据库）：

| 编号 | 断言 |
| --- | --- |
| L2-1 | 清单覆盖 `AiToolRegistry` 中**每一个** `propose*` 工具（新增工具没填清单 → 测试失败） |
| L2-2 | 清单中声明的每个 `permission` 都存在于 `PermissionCatalog`（防止写错权限码） |
| L2-3 | 清单中声明的每个 `page` 都存在于前端路由表（通过解析 `frontend/src/router/index.ts` 或维护第二份路由清单） |
| L2-4 | 每个 `propose*` 工具声明的每个 action 都有对应条目（防止"工具支持 DISABLE 但清单只填了 UPDATE"） |

> **L2-3 的取舍**：解析前端 TS 文件比较脆（格式一变就失败）。更稳的做法是让前端在 `router/index.ts` 旁维护一份 `ROUTE_PERMISSIONS` 常量，测试读取该常量的**一份 JSON 快照**（由构建脚本生成）。若评审认为成本过高，可先只做 L2-1 / L2-2 / L2-4（纯后端，零前端依赖），L2-3 后置。

---

## 19. 评审待确认事项

| 编号 | 待确认问题 | 建议 | 影响 |
| --- | --- | --- | --- |
| Q-W-1 | 用户列表的明文手机号/邮箱是否在本次顺手改成掩码？ | **不改**（保持 Q-15 结论），另立需求 | 影响 §7.3 与工作量（约 +1 人日） |
| Q-W-2 | 部门配置页是否借本次改为树形？ | **不改**（SYS-C-22 已决策），只补操作 | 影响 P-03（约 +3 人日） |
| Q-W-3 | 角色授权是否需要"权限模板"（预置几套常用组合）？ | 本期**不做**，先收集使用反馈 | 影响 P-05 |
| Q-W-4 | 是否引入 `v-permission` 自定义指令统一按钮权限？ | 本期**不引入**（见 §9.4），后续优化 | 影响 P-06 范围 |
| Q-W-5 | E-02（用户列表按部门筛选）是否保留？ | 倾向**保留**（部门与用户联动时有实际价值） | 约 +0.5 人日 |
| Q-W-6 | 审计页是否需要导出（Excel/CSV）？ | 本期**不做**（需求 2.2 明确排除批量导入导出） | — |
| **Q-W-7** | ~~页面写操作是否确认当前没有落审计？~~ | ✅ **已确认并实施**：评审明确"页面直连也需要写入审计"，P-09 已完成（见 §12.7）。实施中发现并修正了"来源归属"与"助手路径无 CurrentUser"两个设计问题（§12.6a） | 已关闭（5 人日已投入） |
| Q-W-8 | L2 护栏的 L2-3（校验页面路由存在）是否本期做？ | 建议**先只做 L2-1/L2-2/L2-4**（纯后端零依赖），L2-3 后置 | 约 ±0.5 人日 |
| Q-W-9 | "禁用必须带 tooltip"是否作为全站约定写入前端规范（含 `<span>` 包裹的坑）？ | 建议**写成前端约定**（§8.2.1），避免各页面各自处理 | 影响 P-01/P-04/P-05 的细节实现 |

---

## 20. 变更记录

| 日期 | 版本 | 变更 | 说明 |
| --- | --- | --- | --- |
| 2026-09-22 | v1.0 | 初稿 | 基于《REQ-系统管理助手能力》v1.4 落地后的实际代码盘点，形成 9 个补齐条目（P-01~P-09）与 18 项能力倒挂清单 |
| 2026-09-22 | v1.1 | 按评审结论修订 3 处 | ① **§1 重写**：把原则从"手动 ⊇ 助手"改为**两层关系**——理论层 `C_manual = C_ai`（规范，用于对账）+ 实际层 `C_manual ⊇ C_ai`（事实，无需干预），并补"手动更精确/更放心/独立可用性边界"三个来源与"合法下界"说明；② **§10.4 修订 + §13 撤销原 E-01**：审计查询接口**不增删改**，页面在前端完成中英映射与 JSON 解析（新增 §10.4a、§12.2a 明确"查询侧不改接口"与"写入侧需接线"的区别）；③ **§8.2.1 新增**：`ADMIN` 角色按钮"禁用 + tooltip"确立为硬要求并推广为全站约定（含 `el-tooltip` 需 `<span>` 包裹的坑）；④ §18 **L2 护栏确认为实施范围**（含清单格式与 4 条断言）；⑤ 工作量 32 → 31 人日，并新增分批上线建议；⑥ §19 新增 Q-W-7/8/9 |
| 2026-09-22 | v1.3 | **按评审澄清修正来源归属的结论 + 补部门停用保护** | ① **纠错**：v1.2 把"助手路径没有 `CurrentUser`"登记为设计问题是**错的**——`AiController#confirmProposal` 是带 JWT 的普通 HTTP 请求，`CurrentUser` 有效。真实原因是编写测试时**直接调用 Service 而未建立身份上下文**，把脚手架的限制误读成了产品限制。§12.6a 已重写为「**身份同源（都取自 `CurrentUser`）、渠道必须可区分**」，并记录该纠错过程；② 据此收窄 `AuditSourceContext` 的职责为**纯渠道标记**，不再承载身份；适配器改为"缺少身份即拒绝"，并注明其作用是拦截绕过 Controller 的调用（本次又抓到一次同类脚手架疏漏，印证有效）；③ **新增 §12.7a**：确认「部门下有正常状态用户时禁止停用」后端规则**已实现**，本次补齐测试断言（`departmentWithEnabledUsersCannotBeDisabled`）与前端 tooltip 提示；并明确"删除"在本系统中等于 `DISABLE`（R-04 不做物理删除，属 §1.4 的合法下界）；④ 新增两条渠道归属断言（助手零 `WEB` 记录、同人两渠道可区分），web 集成测试 17 → 20 项 |

---

## 12.8 顺带修正：演示数据的 `dept_id` 完整性缺陷

实施 `WebAuditIT` 时，测试断言"新建部门下不应有启用用户"连续失败，追查后发现是
`DataInitializer` 的一个**存量缺陷**（非本次补齐引入）：

| 项 | 内容 |
| --- | --- |
| 现象 | 用户表里有用户的 `dept_id` 指向**并不存在**的部门（例如 id 84），页面上"所属部门"永远为空 |
| 根因 | 早期实现用公式推导部门：`deptId = orgIndex + 1 + ORG_COUNT * slot`。而 `DEPT_COUNT=80` 不能被 `ORG_COUNT=21` 整除，最后 1 个机构只拿到 3 个部门，当它取到 `slot=3` 时就推导出 `21+1+21*3=85` 这类不存在的 id |
| 影响 | ① 该批用户列表"所属部门"为空；② 按部门统计用户数时**静默少算**（这些用户不属于任何部门）；③ 新建部门的自增 id 可能与"幽灵 dept_id"重合，造成误判 |
| 修正 | `seedDepartments` 记录**每个机构实际创建的部门 id**（`orgDeptIds`），`seedUsers` 改为从该表取——彻底消除"推导出不存在的 id"这一可能 |
| 验证 | 重新初始化后 `SELECT COUNT(*) FROM sys_user u WHERE u.dept_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_department d WHERE d.id=u.dept_id)` = **0** |

> **为什么这个缺陷此前没被发现**：它不产生报错、不影响登录、也不影响订单统计，
> 只表现为"部分用户的部门字段是空的"，很容易被当成"数据没填"而不是"程序算错了"。
> 这也说明 §12.7 那条"覆盖检查"测试（5 个域各写一次、断言审计条数）的价值——
> 它顺带把演示数据的完整性也压出来了。

