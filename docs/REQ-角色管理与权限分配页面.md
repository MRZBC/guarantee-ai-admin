# 需求：角色管理与权限分配页面（P-05 施工级规格）

| 项 | 值 |
| --- | --- |
| 状态 | **已实施，待验收**（D1~D6 已拍板；代码变更与验证证据见 §13） |
| 承接 | `docs/REQ-系统管理手动操作能力补齐方案.md` **§8（P-05：角色新增 / 修改 / 授权）** |
| 触发 | 用户反馈：**「我现在无法增加角色，也没办法调整角色的权限」** |
| 本文档职责 | ① 把 P-05 从"设计要点"升级为**可直接施工的规格**（接口契约、字段表、错误文案、边界、验收）；② 补齐 P-05 **没写**的三处风险与两个决策项；③ 修正 P-05 中对当前代码已过时的描述 |
| 现状核对时间 | 2026-09-23（基于当前工作区源码逐文件核对） |
| 后端改动 | 2 处 mapper SQL（D2=B **已实施**）：`SysPermissionMapper.selectAllOrdered` 与 `SysRoleMapper.selectAllPermissionsOrdered` 各加 `WHERE is_deleted = 0`。其余后端零改动 |

---

## 0. 决策结论（2026-09-23 已拍板）

> **全部按建议执行**：`D1=A`、`D2=B`、`D3=A`、`D4=A`、`D5=A`、`D6=A`。
>
> 并追加一条**硬不变式**（用户明确要求）：
> **ADMIN 必须始终持有全部权限，且不可修改、不可删除、不可授权变更、不可停用。**
> 它由**五道后端校验**共同保证，前端只负责"不给出会发生错误的入口"：
>
> | 不变式 | 后端保证 |
> | --- | --- |
> | 始终持有全部权限 | `PermissionCatalog.ADMIN_PERMISSIONS` = 目录中全部权限码；`PermissionSyncInitializer` 幂等补数，新增权限码会自动补进 ADMIN |
> | 不可新建/占用保留码 | `RoleService.validateCreate`：「不允许使用保留角色编码 ADMIN」 |
> | 不可修改 | `RoleService.validateUpdate`：「超级管理员（ADMIN）角色不允许修改」 |
> | 不可变更权限 | `RoleService.validateAssignPermissions`：「超级管理员（ADMIN）角色不允许变更权限」 |
> | 不可停用 | `RoleService.changeStatus`：「超级管理员（ADMIN）角色不允许停用…」 |
> | 不可删除 | `RoleService.deleteBlockers`：「超级管理员（ADMIN）角色不允许删除」 |
>
> **前端永远不是安全边界**（SYS-NF-04）：本节所有"禁用"仅为避免用户点了才报错的体验优化，
> 真正的拒绝一律由上述后端校验完成。

下表保留原决策记录（选项与理由），便于日后回溯：

### 0.1 决策记录

| # | 决策项 | 选项 | 我的建议 |
| --- | --- | --- | --- |
| **D1** | **权限树的权限依赖**：`GET /api/system/permissions` 要求 `system:permission:view`，而授权操作要求 `system:role:assign-permission`——**这两个权限码之间没有任何蕴含关系** | A. 不动后端：把耦合**显式写进文档**，并让对话框加载失败时给出可行动文案<br>B. 后端把 `GET /permissions` 放宽为 `hasAnyAuthority('system:permission:view','system:role:assign-permission')` | **A**。默认权限矩阵下只有 ADMIN 持有 `:assign-permission`，而 ADMIN 持有全部权限，**当前不会触发**；为一个尚未出现的角色改权限门禁不划算。但必须**写下来**，否则将来有人给自定义角色发出授权权、却被 403 挡住，会当成 bug 查半天 |
| **D2** | **已删除权限码（`is_deleted=1`）会混进权限清单**：`SysPermissionMapper.selectAllOrdered` 与 `SysRoleMapper.selectAllPermissionsOrdered` 都**没有** `is_deleted = 0` 过滤，而授权校验 `RoleService.validateAssignPermissions` **有**——树里勾中一个已删除权限码，提交必被拒 | A. 前端按 `isDeleted` 过滤（需给 `PermissionItem` 补 `isDeleted`/`deletedAt` 字段）<br>B. 后端给两处 SQL 各加一行 `WHERE is_deleted = 0` | **B**。一行 SQL 根治，且这两个方法的语义本来就是"**可选**权限清单"——把不可选项列进来是接口自身的缺陷。前端过滤等于每个消费方都要记得滤一遍（AI 的 `queryRole(PERMISSION)` 工具就是漏的那个，见 §1.3-③）。**代价**：`selectAllOrdered` 目前唯一调用方是 `PermissionController.list`，`selectAllPermissionsOrdered` 唯一调用方是 `RoleQueryTool`，均可控 |
| **D3** | **`ADMIN` 行的按钮禁用范围**：P-05 §8.2.1 只要求「修改」「授权」禁用，但**现有的**「停用」「删除」在 `ADMIN` 行同样可点、点了才被后端拒绝 | A. 一并禁用（四个按钮都禁用 + tooltip）<br>B. 只按 P-05 原文，只管新增的两个 | **A**。§8.2.1 的立论是"**禁用的语义必须可见**"，而当前 `ADMIN` 行的停用/删除正是它要消灭的 V-2 形态（点不动的按钮 / 点了报错的按钮无法区分"我没权限""这是保留角色""系统坏了"）。漏掉这两个等于补齐工作只做了一半 |
| **D4** | **是否抽 `PermissionTree.vue` 复用组件** | A. 抽成 `frontend/src/components/PermissionTree.vue`<br>B. 先在 `Roles.vue` 内联，出现第二个消费方再抽 | **A**。P-05 §8.3 已建议抽；且用户角色分配（P-04）迟早要用同一棵树。但要**控制抽象度**：只封装"扁平列表 → 树 + 勾选 + 危险项标红"，不要把对话框也塞进去 |
| **D5** | **是否限制"只能授予自己持有的权限码"**（防自提权） | A. 本期不做<br>B. 本期做（前端置灰 + 后端新增校验） | **A**，但**登记为已知风险**（§11-RK-02）。理由：当前矩阵下只有 ADMIN 能授权，ADMIN 持有全部权限，此限制恒真而无意义；且"只能授予自己有的"是运维语义决策（有的组织要求管理员能授予自己都没有的权限），不该由本次页面补齐顺手定掉 |
| **D6** | **是否恢复「显示已删除」开关与恢复入口** | A. 不恢复，维持 2026-09-22 的撤除决定<br>B. 本次一并恢复 | **A**。`Roles.vue` 现有注释明确记录了撤除决策；后端 `POST /{id}/restore` 仍可用（供工具/后续页面），但**恢复入口不在本次范围**。若恢复，应连其它 4 个页面一起，属独立需求 |

> **若 D1=A、D2=B、D3=A、D4=A、D5=A、D6=A**（即除 D2 外全按建议）：本次为 **纯前端 + 2 行 SQL** 交付，预计 **≈3 人日**（明细见 §10）。

---

## 1. 背景与现状核对

### 1.1 现象

`/system/roles` 页面（菜单「角色配置」）**只能看和停用/删除**：

- 找不到任何「新增角色」入口；
- 权限列是**纯只读标签**（`permissionNames`），没有任何修改入口；
- 因此"建一个新角色""给已有角色加/减权限"这两件最基本的运维动作**在页面上无法完成**。

### 1.2 能力对账（实测 2026-09-23）

| 动作 | 后端接口 | 后端校验 | 前端 API 函数 | 页面入口 | 结论 |
| --- | --- | --- | --- | --- | --- |
| CREATE | ✅ `POST /api/system/roles` | ✅ `validateCreate` | ❌ **不存在** | ❌ 无 | **缺失** |
| UPDATE | ✅ `PUT /api/system/roles/{id}` | ✅ `validateUpdate` | ❌ **不存在** | ❌ 无 | **缺失** |
| ASSIGN_PERMISSIONS | ✅ `PUT /api/system/roles/{roleCode}/permissions` | ✅ `validateAssignPermissions` | ❌ **不存在** | ❌ 无 | **缺失** |
| ENABLE / DISABLE | ✅ `PATCH /{id}/status` | ✅ `changeStatus` | ✅ `changeRoleStatus` | ✅ 停用/启用 | 可用 |
| DELETE | ✅ `DELETE /{id}` | ✅ `deleteBlockers` | ✅ `deleteRole` | ✅ 删除 | 可用 |
| RESTORE | ✅ `POST /{id}/restore` | ✅ `restore` | ❌ 不存在 | ❌ 无 | 按 D6 不做 |
| 权限主数据 | ✅ `GET /api/system/permissions` | ✅ `system:permission:view` | ✅ `listPermissions()` | ❌ 无 | **API 已就绪，页面未接** |

> **`listPermissions()` 是全项目死代码**：`frontend/src/api/system.ts:190` 定义了它，但全仓 `frontend/src` 内**没有任何一处调用**。权限树的管道其实已经铺好，只差页面。

### 1.3 三条关键结论

**① 这是"需求未落地"，不是"需求缺失"。**
P-05 早已写明页面设计与交互，且总览表（原文档 §3）状态为 **「待实施」**；§8.4 也已明确「**后端无需改动**」。本次不需要重新论证方案，只需要把它变成可施工的规格并补上缺口。

> 顺带修正：原文档 §3 的状态列已**过时**（P-02/P-06/P-07/P-09 实际已完成，`OperationAudits.vue` 与路由 `meta.permission`、`AppLayout` 的菜单过滤都在位）。P-05 是 `P-01~P-05` 中**唯一 100% 未动**的一项（P-01 机构新增/修改仍缺、P-04 的角色分配仍缺）。

**② 施工面比 P-05 预估的小。**
`pageRoles` / `changeRoleStatus` / `deleteRole` / `listPermissions` / `useUserStore().permissions` / 分页 / 筛选 / `@/utils/status` 工具**都已存在**，`Roles.vue` 已有完整的查询+分页+启停+删除骨架。新增的是：**1 个工具栏按钮 + 1 个操作列 + 3 个对话框 + 3 个 API 函数 + 3 个类型**。

**③ 发现两处既有缺陷（P-05 未覆盖），建议本次一并修掉。**

| # | 缺陷 | 证据 | 影响 |
| --- | --- | --- | --- |
| a | **"有授权权但看不到权限清单"** | `PermissionController` 要求 `system:permission:view`；`RoleController.assignPermissions` 要求 `system:role:assign-permission`。`PermissionCatalog` 中 ANALYST 有 `system:role:view` 但**无** `system:permission:view`；OPERATOR **两者都没有** | 默认矩阵下不触发（只有 ADMIN 能授权，ADMIN 全权）。但权限可被自定义授予 → 一旦出现"只能授权、不能看清单"的角色，授权对话框必然 403 且用户无从理解。见 D1 |
| b | **已删除权限码混入权限清单** | `SysPermissionMapper.xml:7-21`（`selectAllOrdered`）与 `SysRoleMapper.xml:223-227`（`selectAllPermissionsOrdered`）**均无 `is_deleted = 0`**；而 `SysRoleMapper.xml:202-211`（`selectPermissionEntitiesByCodes`，授权校验用）**有** | 树里存在"勾了必被拒"的权限码，报错文案是「以下权限码不存在，不能授权」——用户会认为是前端 bug。见 D2 |
| c | **AI 侧同样受影响** | `RoleQueryTool.java:132-143` 的 `queryPermissions` 遍历 `listPermissionEntities()`（即缺陷 b 的第二个出口），**未过滤 `isDeleted`** | 模型会被告知存在一个实际不可授权的权限码，进而生成注定失败的授权提案。修 b（选 B）可一并消除 |

---

## 2. 既有设计沿用什么 / 本文档补充什么

| 既有方案 P-05 已定（**直接沿用，不重复论证**） | 本文档补充 |
| --- | --- |
| 工具栏「新增角色」、操作列「修改」「授权」、权限列折叠展示（§8.2） | 逐接口**请求/响应字段表**（§3）、真实**错误文案目录**（§3.7）、权限分组的**真实码值**（§4.2） |
| 新增/修改对话框字段与规则（§8.2） | 与 `InsuranceTypes.vue` 对话框模式的**逐项对齐**（§5.3/§5.4），含编码只读实现方式 |
| 权限树：`node-key="permCode"`、按前缀分组、危险权限提示、变更对比、影响提示（§8.2） | 树数据源的**已知边界**（D1/D2）、`RoleItem` 类型**缺字段**清单（§3.6）、自提权风险（D5） |
| `ADMIN` 按钮「禁用 + tooltip」硬要求 + `<span>` 包裹的坑（§8.2.1） | 把该要求**扩展到 `ADMIN` 行现有的停用/删除**（D3），并给出可直接复制的落地形态（§5.6） |
| 涉及文件清单（§8.3）、测试要点 P05-T1~T6（§8.5） | 新增测试要点 **P05-T7~T12**（§8）、验收标准 **AC-57~AC-61**（§9） |
| 「后端无需改动」（§8.4） | **修正**：若 D2 选 B 则需改 2 处 SQL；否则确实为零（§1.3-③） |

---

## 3. 接口契约（现状核对，本期不改）

统一响应包装 `Result<T>`：`{ code, message, data, traceId }`，`code === 0` 为成功；前端 `request.ts` 拦截器**已解包 `data`**，业务代码直接拿到 `data`，非 0 会 `ElMessage.error(message)` 并 reject。

### 3.1 角色列表

```
GET /api/system/roles
权限：system:role:view
```

| 参数 | 类型 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- | --- |
| `pageNum` | int | 否 | ≥1，默认 1 | `PageQuery` |
| `pageSize` | int | 否 | 1~200，默认 10 | 超出报「每页条数不能超过 200」 |
| `roleCode` | string | 否 | ≤32 | 模糊 |
| `roleName` | string | 否 | ≤64 | 模糊 |
| `keyword` | string | 否 | ≤64 | 编码或名称统一模糊词（页面可不暴露） |
| `status` | int | 否 | 0/1 | |
| `includeDeleted` | bool | 否 | 默认 false | **传 true 需 `system:role:delete`**，否则 403。按 D6 页面不传 |

响应 `data`：`PageResult<RoleVO>` = `{ pageNum, pageSize, total, list: RoleVO[] }`。

**`RoleVO` 字段（`RoleService.fillPermissions` / `fillCounts` 批量回填，无 N+1）：**

| 字段 | 类型 | 页面用途 |
| --- | --- | --- |
| `id` | Long | 行 key、修改/删除目标 |
| `roleCode` | String | 列展示；**授权接口的路径参数** |
| `roleName` | String | 列展示 |
| `description` | String | 列展示，空显示 `--` |
| `status` | Integer | 1 启用 / 0 停用 |
| `createdAt` | LocalDateTime | 列展示 |
| `permissionIds` | List\<Long\> | 备用 |
| `permissionNames` | List\<String\> | 权限列展示（**中文名**） |
| `permissionCodes` | List\<String\> | **授权树回显的权威依据**（见 §3.6） |
| `permissionCount` | Integer | 权限数（P05-T4 断言它） |
| `userCount` | Integer | 持该角色的用户数（授权影响提示用） |
| `isDeleted` / `deletedAt` / `deletedBy` | — | 按 D6 页面不展示 |

### 3.2 角色详情

```
GET /api/system/roles/{id}   权限：system:role:view
```
返回 `Result<RoleVO>`（含 `permissionIds`/`permissionNames`/`permissionCount`/`userCount`）。
**设计取舍**：列表已返回全部所需字段，授权对话框直接用 `row.permissionCodes` 回显即可，**不必再调详情**（少一次请求，也避免列表与详情不一致）。

### 3.3 新增角色

```
POST /api/system/roles
权限：system:role:create
```

| 字段 | 必填 | 约束 | 后端错误文案（原文） |
| --- | --- | --- | --- |
| `roleCode` | 是 | `@NotBlank`、≤32 | 「角色编码不能为空」/「角色编码长度不能超过 32」 |
| `roleName` | 是 | `@NotBlank`、≤64 | 「角色名称不能为空」/「角色名称长度不能超过 64」 |
| `description` | 否 | ≤255 | 「描述长度不能超过 255」 |

**业务规则（`RoleService.validateCreate`）：**

| 规则 | 错误文案 |
| --- | --- |
| 保留码 | 「不允许使用保留角色编码 ADMIN」 |
| 编码唯一 | 「角色编码已存在: {code}」 |

响应 `Result<RoleVO>`（`getById` 结果）。
**副作用**：写 `ai_operation_audit`（`source=WEB`、`action=CREATE`、`targetType=ROLE`）。

### 3.4 修改角色

```
PUT /api/system/roles/{id}
权限：system:role:update
```

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `roleName` | 否（建议必填） | ≤64 |
| `description` | 否 | ≤255 |
| `status` | 否 | 0/1 |

**`roleCode` 不在请求体中**——`RoleDto.UpdateRequest` 刻意不提供该字段，编码不可改。

| 业务规则 | 错误文案 |
| --- | --- |
| 目标存在 | 「角色不存在: {id}」（404） |
| ADMIN 保护 | 「超级管理员（ADMIN）角色不允许修改」 |

> **`status` 双通道**：`PUT`（本接口）与 `PATCH /{id}/status` 都能改状态，但**只有 `PATCH` 会做"停用前置检查 + 撤销持有者令牌"**（`changeStatus`）。`update()` 直接 `updateById`，**不检查使用者、不撤令牌**。
> **施工要求**：修改对话框内的「状态」字段若允许改动为 0，会产生**绕过停用保护**的路径。**建议修改对话框不提供状态字段**，状态一律走列表的「停用/启用」按钮（该按钮已具备完整保护与文案）。此项列为 P05-T10 断言。

### 3.5 角色授权

```
PUT /api/system/roles/{roleCode}/permissions
权限：system:role:assign-permission
注意：路径参数是 roleCode（字符串），不是 id
```

请求体：

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `permCodes` | 是 | `@NotNull`；元素 `@NotBlank` |

**这是全量替换语义**（不是增量）：`softDeleteRolePermissionsNotIn`（把不在集合内的置删）+ `upsertRolePermissions`（UPSERT 目标集合）。**因此必须提交"变更后完整权限集"，漏传即等于删除。**

| 业务规则（`validateAssignPermissions`） | 错误文案 |
| --- | --- |
| 角色存在 | 「角色不存在: {roleCode}」（404） |
| ADMIN 保护 | 「超级管理员（ADMIN）角色不允许变更权限」 |
| 非空 | 「权限列表不能为空」 |
| 只接受既有码 | 「以下权限码不存在，不能授权（不支持自由构造权限码）: [codes]」 |
| 解析一致 | 「权限解析失败，请检查权限码: [codes]」 |

**副作用（必须在 UI 明示）**：
1. 写入 `ai_operation_audit`（`action=ASSIGN_PERMISSIONS`，`before/after` 均为 `permissionCodes` 数组）；
2. **撤销所有持有该角色用户的令牌**（`revokeTokens`）→ 这些人**被强制下线，需重新登录**。影响人数用 `RoleVO.userCount` 展示。

> **`before` 取值顺序**：`assignPermissions` 刻意在写入前调 `getById` 取 `beforePermCodes`，否则审计的 before 会等于 after。页面**不要**试图自己算 before 传给后端——没有这个入参。

### 3.6 权限主数据（权限树数据源）

```
GET /api/system/permissions
权限：system:permission:view     ← 见 D1
```
返回 `Result<PermissionVO[]>`，**扁平列表**（`ORDER BY sort_no ASC, id ASC`），由前端按 `parentId` 组树。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | Long | |
| `permCode` | String | **树的 `node-key`**（授权接口收 `permCodes`，不用 id） |
| `permName` | String | 中文名 |
| `permType` | String | `MENU` / `BUTTON` / `API` |
| `parentId` | Long | 组树依据 |
| `path` / `component` / `icon` / `sortNo` | — | 菜单类权限有值 |
| `isDeleted` / `deletedAt` | Integer / LocalDateTime | **后端返回但当前 TS 类型未声明**；按 D2 决定是否使用 |

**已知且必须处理的两件事：**

1. **接口不过滤已删除权限码**（`selectAllOrdered` 无 `is_deleted = 0`）→ 按 D2 选 B 由后端修，选 A 则前端必须 `filter(p => !p.isDeleted)`，并给 `PermissionItem` 补字段。
2. **不存在"新建权限码"入口**（R-04：权限主数据只读）→ 树**只可勾选，不可增删权限本身**。P05-T6 断言这一点。

### 3.7 错误文案目录（前端文案须与此一致，勿自造）

| 触发 | 文案 |
| --- | --- |
| 权限不足（任意写接口） | 「你当前没有该操作的权限，请联系管理员」（403，`GlobalExceptionHandler.ACCESS_DENIED_MESSAGE`） |
| 编码为空 / 超长 | 「角色编码不能为空」/「角色编码长度不能超过 32」 |
| 名称为空 / 超长 | 「角色名称不能为空」/「角色名称长度不能超过 64」 |
| 描述超长 | 「描述长度不能超过 255」 |
| 保留码 | 「不允许使用保留角色编码 ADMIN」 |
| 编码重复 | 「角色编码已存在: {code}」 |
| 改 ADMIN | 「超级管理员（ADMIN）角色不允许修改」 |
| 授权 ADMIN | 「超级管理员（ADMIN）角色不允许变更权限」 |
| 停用 ADMIN | 「超级管理员（ADMIN）角色不允许停用：停用会使全体管理员立即失去权限」 |
| 删 ADMIN | 「该角色不能删除：超级管理员（ADMIN）角色不允许删除」 |
| 停用有持有人 | 「仍有 {N} 个启用中的用户持有该角色，不能停用；请先解除绑定」 |
| 删除有持有人 | 「该角色不能删除：…仍有 {N} 个未删除的用户持有该角色，请先解除绑定」 |
| 并发冲突 | 「角色状态已被他人修改，请刷新后重试」/「角色已被他人删除，请刷新后重试」 |

> **一律不在页面里 `try/catch` 后吞掉错误**：`request.ts` 拦截器已统一 `ElMessage.error`，页面只需 `catch {}` 留空并保留弹窗（既有页面均如此）。

---

## 4. 权限点定义与真实矩阵

### 4.1 角色-权限矩阵（`PermissionCatalog`，权限码的**唯一权威定义**）

| 权限码 | 名称 | ADMIN | OPERATOR | ANALYST | VIEWER |
| --- | --- | :---: | :---: | :---: | :---: |
| `system:role:view` | 角色配置 | ✅ | ❌ | ✅ | ✅ |
| `system:role:create` | 角色新增 | ✅ | ❌ | ❌ | ❌ |
| `system:role:update` | 角色修改 | ✅ | ❌ | ❌ | ❌ |
| `system:role:assign-permission` | 角色授权 | ✅ | ❌ | ❌ | ❌ |
| `system:role:disable` | 角色启停 | ✅ | ❌ | ❌ | ❌ |
| `system:role:delete` | 角色删除 | ✅ | ❌ | ❌ | ❌ |
| `system:permission:view` | 权限配置 | ✅ | ❌ | **❌** | ✅ |

**由此推出三条页面行为：**

1. **默认矩阵下只有 ADMIN 能新增/修改/授权**（OPERATOR/ANALYST/VIEWER 均无写权限）→ 按钮权限过滤 `canCreate/canUpdate/canAssign` 在默认数据下等价于"仅 ADMIN 可见"。
2. `system:role:view` 与写权限**彼此独立**：ANALYST/VIEWER 能进页面但**看不到任何写按钮**。因此**工具栏与操作列必须按权限渲染**，否则他们看到的是"点了就 403"的死按钮（V-2）。
3. **`system:permission:view` 的持有者与授权权限持有者不重合**（VIEWER 有前者无后者；ADMIN 两者都有）→ D1 的来源。

### 4.2 权限树分组（真实码值，按 `permCode` 前缀）

`PermissionCatalog.PERMISSIONS` 共 **44** 项，实测分组如下（组名建议直接用「权限名」列的模块名）：

| 组 | 权限码 |
| --- | --- |
| 首页 | `dashboard:view` |
| 订单管理 | `order:tender:view`、`order:performance:view` |
| 数据概览 | `analysis:overview:view` |
| 项目管理 | `project:view` |
| 企业管理 | `enterprise:view` |
| 险种配置 | `system:insurance:view` / `:create` / `:update` / `:disable` / `:delete` |
| 机构配置 | `system:org:view` / `:create` / `:update` / `:disable` / `:delete` |
| 部门配置 | `system:dept:view` / `:create` / `:update` / `:disable` / `:delete` |
| 用户配置 | `system:user:view` / `:update` / `:disable` / `:assign-role` / `:delete` |
| 角色配置 | `system:role:view` / `:create` / `:update` / `:assign-permission` / `:disable` / `:delete` |
| 权限与审计 | `system:permission:view`、`system:audit:view` |
| 在线会话 | `system:session:view`、`system:session:kick` |
| AI 助手 | `ai:chat`、`ai:system:query`、`ai:system:write`、`ai:debug:view`、`ai:config:view`、`ai:config:update`、`ai:mcp:read`、`ai:mcp:manage` |

> **实测结论：只能用上表的前缀分组。** `sys_permission.parent_id` 由 `PermissionSyncInitializer` 恒定写入 **0**（其 INSERT 语句里 `parent_id` 是字面量 `0`），`perm_type` 也只是 `path != null ? "MENU" : "BUTTON"` 的粗分类——**两者都不具备树形语义**。因此分组来源唯一：`permCode` 模块前缀。
>
> **这带来一个额外收益**：分组节点成了**合成节点**（key 前缀 `group::`），不是真实权限码，于是"半选父节点是否要提交"这个分叉（§5.5 末、RK-03）**自动消失**——提交前按 `permCode` 白名单过滤即可。若将来改用真实父级权限码组树，必须重新审视这一点。
>
> **界面上的例外（2026-09-23 验收反馈）**：上表是**匹配口径**，不等于最终展示结构。**仅含单个权限的分组会被提升为顶层叶子**——`首页` / `数据概览` / `项目管理` / `企业管理` 四组各只有 1 项，保留分组只会得到同名两级。实际保持分组的只有 9 组：订单管理(2)、险种配置(5)、机构配置(5)、部门配置(5)、用户配置(5)、角色配置(6)、权限与审计(2)、在线会话(2)、AI 助手(8)。详见 §13.5。

### 4.3 危险权限清单（勾选时给出 warning）

P-05 只列了 2 项，实测应扩到 **8** 项——判据是"**能改变他人权限或扩大自身权限面**"：

| 权限码 | 为什么危险 |
| --- | --- |
| `system:role:assign-permission` | **自提权**：授予它 = 授予"给自己加任何权限"的能力（D5 登记的风险） |
| `system:user:assign-role` | 可给任意用户换任意角色，等价于间接提权 |
| `system:audit:view` | 全站操作审计（含他人敏感操作的字段级前后值） |
| `system:session:kick` | 可强制他人下线 |
| `ai:system:write` | 打开 AI 助手的全部写能力（提案通道），是 `propose*` 工具的总开关 |
| `ai:config:update` | 可改全站 AI 助手的模型/温度/提示词与能力开关（含一键关闭写能力、改密钥引用名），影响所有用户（第四阶段 REQ-CFG-10） |
| `ai:mcp:read` | 受控取数面的**机器入口**：持有它的 MCP Token 可从平台外部读取订单/机构/用户/审计等只读数据（数据范围仍由 `DataScopeService` 约束）。只应授予**服务账号**（`sys_user.account_type=SERVICE`），人类角色拿到它等于开了一条绕过页面审计习惯的外部取数通道（第五阶段 REQ-MCP-02 / AC-MCP-03） |
| `ai:mcp:manage` | 可**签发/撤销 MCP 机器凭据**：等于把"铸造对外取数身份"的能力交出去。它与 `ai:mcp:read` 是两种风险面（read 管"Token 能调什么"，manage 管"谁能造 Token"），刻意不复用 `ai:config:update` —— 复用会让"只看配置"的人顺手拿到对外取数通道（第五阶段 REQ-MCP-02 / AC-MCP-02） |

---

## 5. 页面设计（施工级）

### 5.1 工具栏（`table-toolbar`）

```html
<div class="table-toolbar">
  <span class="table-toolbar__title">角色列表 · 共 {{ total }} 条</span>
  <el-button v-if="canCreate" type="primary" icon="Plus" @click="openCreate">新增角色</el-button>
</div>
```

> **不要照抄 `InsuranceTypes.vue:368`**：那儿的「新增险种」/「编辑」**没有做权限过滤**（只有启停/删除做了），无权限用户看到的是死按钮。`Roles.vue` 要按 `canCreate/canUpdate/canAssign` 渲染。若要顺手修 `InsuranceTypes.vue`，另开条目，不混进本次。

### 5.2 表格增补

| 列 | 变更 |
| --- | --- |
| 权限 | 由"全量标签"改为**折叠展示**：前 3 个 `el-tag` + `共 {{ permissionCount }} 项`（有第 4 项起才显示计数）。理由：`permissionNames` 最长可达 40 项，会把表格撑破 |
| 操作 | 宽 **200**（现为 130，容不下 4 个按钮）；按钮：**修改**（`canUpdate`）、**授权**（`canAssign`）、**停用/启用**（`canDisable`）、**删除**（`canDelete`） |
| 操作列 `v-if` | **必须改为 `canUpdate \|\| canAssign \|\| canDisable \|\| canDelete`**。现状是 `canDisable \|\| canDelete`——只持有新增/修改/授权的用户**整列都不渲染**，等于什么都点不到 |

### 5.3 新增角色对话框

对齐 `InsuranceTypes.vue:443-518` 的既有模式（`form` reactive + `dialogVisible` + `submitting` + `formRef` + `isEdit` + `dialogTitle` + `rules` computed + `@closed="resetForm"`）：

| 字段 | 控件 | 规则 | 说明 |
| --- | --- | --- | --- |
| `roleCode` | `el-input` | 必填；`/^[A-Za-z0-9_-]{2,32}$/`；**前端拦 `ADMIN`** | 提示"如 REGION_OPS"；保留码前端先拦、后端兜底 |
| `roleName` | `el-input` | 必填；≤64 | |
| `description` | `el-input type=textarea` | 选填；`maxlength=255 show-word-limit`；`:rows=3` | |

提交：`createRole(payload)` → `ElMessage.success('角色新增成功')` → 关弹窗 → `loadData()`。
**新增后初始权限数为 0**（`create` 不写 `sys_role_permission`）→ 列表权限列应显示 `--`，`permissionCount=0`（P05-T1 断言）。

### 5.4 修改角色对话框

| 字段 | 控件 | 规则 |
| --- | --- | --- |
| `roleCode` | `el-input` **`:disabled="true"` 或纯文本** | 只读展示 + `form-hint` 提示「角色编码创建后不可修改」 |
| `roleName` | `el-input` | 必填；≤64 |
| `description` | textarea | 选填；≤255 |
| `status` | — | **不提供**（见 §3.4 的绕过风险），状态改动一律走列表按钮 |

提交：`updateRole(form.id, payload)`，payload **不含 `roleCode`**（后端 DTO 无此字段）。

### 5.5 授权对话框（工作量主体）

**布局**：宽 `720px`，上半权限树（限高滚动），下半变更对比 + 影响提示。

| 元素 | 规格 |
| --- | --- |
| 标题 | `授权角色「{roleName}」` |
| 数据源 | `listPermissions()`（挂载时加载一次，`loading` 态，失败按 D1 处理） |
| 组树 | `el-tree`，`:data` 为按 `parentId`（或 §4.2 前缀）构造的树 |
| 勾选 | `show-checkbox`，**`node-key="permCode"`**，`:default-checked-keys="row.permissionCodes"` |
| 回显 | 用 `row.permissionCodes`（**不是 `permissionNames`，也不是 `permissionIds`**）——`permissionCodes` 与 `selectPermissionEntitiesByCodes` 同源同过滤，是唯一不会"回显了却被判不存在"的来源 |
| 危险提示 | 勾选 §4.3 中任一项时，行内 `${permName}` 后追加红色 ⚠ 图标 + tooltip 说明原因 |
| 提交前对比 | 展示「当前权限 → 变更后权限」：**新增**（绿）/ **移除**（红）两组列表；**无差异时禁用保存按钮**并提示「权限未发生变化」 |
| 影响提示 | 常量文案 + 真实数字：`{roleName}` 当前 **{userCount}** 个用户持有，保存后**这些用户会被立即强制下线，需重新登录** |
| 提交 | `assignRolePermissions(roleCode, codes)`，**codes = 勾选后的完整集合**（`getCheckedKeys()` + 半选父节点是否需要入集见下） |
| 成功 | `ElMessage.success('角色授权已更新')` → 关弹窗 → `loadData()` |

> **半选父节点要不要提交？——已确认为"不需要"，不再是分叉点。**
> 原先的担忧是：若树里父节点是**真实的 MENU 权限码**，`getCheckedKeys()` 不含半选父节点会让菜单权限被静默撤销。
> 实测 `parent_id` 恒为 0（§4.2），**分组节点是合成节点、不是权限码**，因此该风险不存在。
> 实现采用"`check` 事件回调 + 按 `permCode` 白名单过滤"（`PermissionTree.vue` 的 `toPermCodes`）：
> 既无需持有 `el-tree` 实例去调 `setCheckedKeys` 同步初始勾选态，也天然把合成 key 滤掉。

### 5.6 「禁用必须带 tooltip」的落地形态（P-05 §8.2.1 硬要求）

```html
<!-- el-tooltip 对 disabled 的 el-button 不生效（禁用元素不触发鼠标事件）→ 必须 <span> 包裹 -->
<!-- disabledReason(row, 'update') 对非 ADMIN 返回 ''，此时 el-tooltip 自身也禁用 -->
<el-tooltip :disabled="!disabledReason(row, 'update')" :content="disabledReason(row, 'update')">
  <span>
    <el-button link type="primary" :disabled="!!disabledReason(row, 'update')" @click="openEdit(row)">
      修改
    </el-button>
  </span>
</el-tooltip>
```

> `disabledReason(row, action)` 建议实现为纯函数并**用 `Map` 收敛文案**，避免四个按钮各写一段 `<el-tooltip>` 时文案漂移（P-05 §8.2.1 建议的"极小约定"）：`ADMIN` 行返回对应原因，其余行返回 `''`。

**按 D3 的适用范围**（`ADMIN` 行）：

| 按钮 | tooltip 文案 |
| --- | --- |
| 修改 | 超级管理员（ADMIN）角色不允许修改 |
| 授权 | 超级管理员（ADMIN）角色不允许变更权限 |
| 停用 | 超级管理员（ADMIN）角色不允许停用 |
| 删除 | 超级管理员（ADMIN）角色不允许删除 |

> 该约定**不限于 `ADMIN`**：任何"因业务规则而禁用"的按钮都要 `disabled` + `el-tooltip` + `<span>` 三件套。**只有禁用而无说明 = V-2 未修复**。

---

## 6. 边界与异常

| # | 场景 | 期望行为 |
| --- | --- | --- |
| E-1 | 只持有 `system:role:view`（ANALYST/VIEWER） | 看得到页面与列表；工具栏**无**新增按钮；操作列**整列不渲染**（四个 `can*` 全 false） |
| E-2 | `ADMIN` 行 | 四个按钮 `disabled` + tooltip（D3） |
| E-3 | 新增时填 `ADMIN` | 前端 `rules` 拦截并提示保留码；绕过前端则由后端返回「不允许使用保留角色编码 ADMIN」 |
| E-4 | 新增重复编码 | 后端「角色编码已存在: {code}」，弹窗**保持打开**便于改（不要 `catch` 后关弹窗） |
| E-5 | 授权提交空集 | 后端「权限列表不能为空」；前端应在无勾选时直接禁用保存按钮，不上送 |
| E-6 | 权限树加载 403（D1 场景） | 树区域显示可行动文案（如「缺少权限配置查看权限（system:permission:view），无法加载权限清单」），**不要**显示空树——空树会被误读为"该角色没有权限" |
| E-7 | 树含已删除权限码（D2=A 时） | 前端过滤掉，避免"勾了必被拒" |
| E-8 | 列表为空 | 既有 `el-empty`「暂无角色数据」保持不变 |
| E-9 | 并发：他人已改/已删 | 后端「角色状态已被他人修改，请刷新后重试」/「角色已被他人删除…」→ 提示后 `loadData()` 刷新 |
| E-10 | 停用有启用用户的角色 | 后端「仍有 N 个启用中的用户持有该角色，不能停用；请先解除绑定」；既有确认弹窗文案**已写明**该规则，无需改 |

---

## 7. 涉及文件清单

| 文件 | 变更 | 规模 |
| --- | --- | --- |
| `frontend/src/api/system.ts` | 新增 `createRole` / `updateRole` / `assignRolePermissions`（`listPermissions` 已存在，直接复用） | +3 函数 |
| `frontend/src/types/system.ts` | 新增 `RoleCreateParams` / `RoleUpdateParams` / `RoleAssignPermissionsParams`；**给 `RoleItem` 补 `permissionCodes` / `permissionCount` / `userCount`** | +3 类型、+3 字段 |
| `frontend/src/views/system/Roles.vue` | 工具栏 + 操作列改造 + 3 个对话框 + 权限树接入 + `canCreate/canUpdate/canAssign` | 主体 |
| `frontend/src/components/PermissionTree.vue` | **建议新增**（D4=A）：扁平列表 → 树、勾选、危险项标记、`v-model` 出 `permCode[]` | 新文件 |
| `guarantee-system/.../mapper/system/SysPermissionMapper.xml` | **仅当 D2=B**：`selectAllOrdered` 加 `WHERE is_deleted = 0` | +1 行 |
| `guarantee-system/.../mapper/system/SysRoleMapper.xml` | **仅当 D2=B**：`selectAllPermissionsOrdered` 加 `WHERE is_deleted = 0` | +1 行 |
| `docs/REQ-系统管理手动操作能力补齐方案.md` | P-05 状态列改为「✅ 已完成（见本文档）」并加指针；**顺带修正 §3 已过时的状态列**（P-02/P-06/P-07 已完成） | 文档同步 |

**不需要改**：`RoleController`、`RoleService`、`RoleDto`、`RoleVO`、`PermissionController`、`PermissionService`、`Roles.vue` 的路由与菜单（`router/index.ts:104`、`AppLayout.vue:87` 均已在位且带 `system:role:view`）。

---

## 8. 测试要点

**沿用 P-05 原文（P05-T1~T6）**，本文档新增：

| 编号 | 断言 | 对应决策 |
| --- | --- | --- |
| P05-T1 | 新增角色 `REGION_OPS`：列表出现，初始 `permissionCount = 0`，权限列显示 `--` | — |
| P05-T2 | 角色编码填 `ADMIN` → 前端拦截（或后端拒绝），提示保留码 | — |
| P05-T3 | `ADMIN` 行的「修改」「授权」禁用且有 tooltip | — |
| P05-T4 | 授权勾选 `system:org:view` 提交 → 权限列更新，`permissionCount` 正确 | — |
| P05-T5 | 授权变更后，持有该角色的在线用户下次请求 401（AC-21） | — |
| P05-T6 | 权限树不提供"新建权限码"入口（R-04） | — |
| **P05-T7** | `ADMIN` 行的「停用」「删除」**同样**禁用且有 tooltip | D3 |
| **P05-T8** | 只持有 `system:role:view` 的账号：工具栏无新增按钮，操作列整列不渲染 | §5.2 |
| **P05-T9** | 授权对话框回显使用 `permissionCodes`：打开一个已有 N 项权限的角色，勾选态恰好为 N 项（不靠 `permissionNames` 反查） | §5.5 |
| **P05-T10** | 修改对话框**不提供** status 字段；改状态只能走列表按钮（保护"停用前置检查 + 撤令牌"不被绕过） | §3.4 |
| **P05-T11** | 授权无差异时保存按钮禁用；提交集合为**变更后完整集**（全量替换语义） | §3.5 |
| **P05-T12** | （D2=B 时）树与 `queryRole(PERMISSION)` 工具**均不出现** `is_deleted=1` 的权限码；若 D2=A 则断言前端已过滤 | D2 |

> **半选父节点**（§5.5 末）必须有**人工验收**步骤：给一个角色勾选子权限、父节点呈半选，保存后重新打开，确认勾选态与保存前一致——这是最容易静默改坏数据的点。

---

## 9. 验收标准

| 编号 | 验收标准 |
| --- | --- |
| **AC-57** | 在角色配置页可以**完整创建**一个新角色（编码/名称/描述），创建后能在列表中查到，且初始无任何权限 |
| **AC-58** | 可以**修改**已有角色的名称与描述；**角色编码不可改**；`ADMIN` 角色不可改 |
| **AC-59** | 可以**调整角色权限**：勾选/取消并保存后，列表权限列与 `permissionCount` 同步更新；且保存前能看见「新增/移除」差异与「影响 N 个用户、将被强制下线」的提示 |
| **AC-60** | **所有因业务规则不可用的按钮都是"禁用 + tooltip"**，不存在"能点但报错"或"点了没反应"的按钮（含 `ADMIN` 行四个按钮） |
| **AC-61** | 无相应权限码的账号看不到对应按钮（前端过滤），后端仍独立校验（403 文案统一）——前端过滤仅体验优化，不是安全边界（SYS-NF-04） |

---

## 10. 工作量

| 项 | 人日 |
| --- | --- |
| `api/system.ts` + `types/system.ts` 接线 | 0.25 |
| 新增/修改对话框（对齐 `InsuranceTypes.vue` 模式） | 0.5 |
| `PermissionTree.vue`（组树、勾选、危险标记、半选处理） | 1.0 |
| 授权对话框（差异对比、影响提示、回显、空集禁用） | 0.75 |
| 工具栏/操作列/权限列改造 + `ADMIN` 禁用与 tooltip（D3） | 0.5 |
| D2=B 的两处 SQL + 回归 | 0.25 |
| 手工验收（含 P05-T7~T12 与半选父节点） | 0.75 |
| **合计** | **≈4 人日** |

> 比 P-05 原估的 `L（3~5 人日）` 落在同一区间。若 D2=A、D4=B（前端过滤 + 不抽组件）可压到 ≈3 人日，但会留下 §1.3-③c 的 AI 侧隐患。

---

## 11. 风险

| 编号 | 风险 | 应对 |
| --- | --- | --- |
| **RK-01** | **`PATCH /status` 与 `PUT /{id}` 都能改状态，但只有前者有保护**。若修改对话框提供状态字段，会绕过"停用前置检查 + 撤销持有者令牌" | 修改对话框不提供状态字段（P05-T10） |
| **RK-02** | **自提权**：`system:role:assign-permission` + `system:permission:view` 组合 = 可给自己加任何权限；后端**无**"只能授予自己持有的权限码"校验 | D5 本期不做，但**登记为已知风险**；UI 上把 `system:role:assign-permission` 标为危险项（§4.3） |
| ~~**RK-03**~~ | ~~半选父节点是否提交判断错误 → 菜单权限被静默撤销~~ | ✅ **已消除**：分组节点为合成节点（§4.2），提交前按 `permCode` 白名单过滤（§5.5） |
| **RK-04** | 全量替换语义下**漏传权限即等于删除** | 提交后强制 `loadData()`；P05-T11 断言提交集合为完整集 |
| **RK-05** | D1 场景下授权对话框 403，空树被误读为"该角色没有权限" | E-6 要求显示可行动文案而非空树 |

---

## 12. 变更记录

| 日期 | 版本 | 变更 | 说明 |
| --- | --- | --- | --- |
| 2026-09-23 | v1.0 | 初稿 | 承接 P-05，升级为施工级规格：补全逐接口契约（§3）、真实错误文案目录（§3.7）、真实权限矩阵与分组（§4）、施工级页面规格（§5）、边界（§6）、P05-T7~T12（§8）、AC-57~AC-61（§9）。**新增三处既有缺陷**（§1.3-③）：`system:permission:view` 与授权权限无蕴含关系（D1）、已删除权限码混入权限清单且授权校验会拒绝（D2，波及页面与 AI 的 `queryRole(PERMISSION)` 工具）、`ADMIN` 行现有停用/删除按钮违反 §8.2.1 的"禁用+tooltip"约定（D3）。**修正** P-05 §8.4「后端无需改动」——若 D2=B 需改 2 处 SQL |
| 2026-09-23 | v1.1 | 决策拍板 + 实施完成 | ① §0 记录决策结论（D1=A、D2=B、D3=A、D4=A、D5=A、D6=A）与 **ADMIN 硬不变式**（全权限、不可改/删/授权/停用，及支撑它的五道后端校验）；② §4.2 记录实测结论——`parent_id` 恒为 0，分组只能用 `permCode` 前缀，**并据此消除 RK-03**（分组节点为合成节点，半选父节点不再是分叉点）；③ §5.5 相应改为"已确认不需要提交半选父节点"；④ 新增 §13 实施记录。**代码已全部落地，`vue-tsc --noEmit` 与 `vite build` 均通过（见 §13）** |
| 2026-09-23 | v1.2 | 首轮界面验收反馈 | ① 修复授权弹窗「取消/保存授权」按钮**贴在一起**——根因是本实现为挂 tooltip 用 `<span>` 包裹按钮，破坏了 Element Plus 的相邻兄弟 margin；两个弹窗 footer 统一改为 `.dialog-footer`（flex + gap + 抹除原生 margin）。② 权限树**移除英文权限码**展示（`permCode` 仍在节点数据中，作为 `node-key` 与提交过滤依据）。③ **单权限分组提升为顶层叶子**，消除 `▼ 数据概览 / ☑ 数据概览` 这类同名两级。三项均记入 §13.5，并沉淀两条通用约定（禁用按钮须同时接管间距；单子项分组应提升） |
| 2026-09-30 | v1.3 | 第四阶段 AI 配置权限同步 | 新增权限码 `ai:config:view` / `ai:config:update`（ADMIN 默认拥有、其余角色默认无；初始化矩阵见 `PermissionCatalog`）。§4.2 项数 40 → **42**、AI 助手分组 4 → **6**；§4.3 危险权限 5 → **6**（`ai:config:update`：能改全站助手的模型/提示词/能力开关）。`PermissionTree.vue` 的 `DANGER_REASONS` 与本文档同步修改（两处定义必须一致） |
| 2026-09-30 | v1.4 | 第五阶段业务 MCP 权限同步 | 新增权限码 `ai:mcp:read`（工具调用面）与 `ai:mcp:manage`（凭据管理面）：ADMIN 默认拥有、其余角色默认无；`ai:mcp:read` 只应授予 `sys_user.account_type=SERVICE` 的服务账号。§4.2 项数 42 → **44**、AI 助手分组 6 → **8**；§4.3 危险权限 6 → **8**（`ai:mcp:read`：受控取数面的机器入口；`ai:mcp:manage`：铸造机器凭据，与 `ai:config:update` 同档但**不复用**该码）。`PermissionTree.vue` 的 `DANGER_REASONS` 与本文档同步修改（两处定义必须一致） |

---

## 13. 实施记录（2026-09-23）

### 13.1 实际改动文件

| 文件 | 变更 |
| --- | --- |
| `guarantee-system/.../mapper/system/SysPermissionMapper.xml` | `selectAllOrdered` 加 `WHERE is_deleted = 0`（D2=B），并注明与授权校验同口径的理由 |
| `guarantee-system/.../mapper/system/SysRoleMapper.xml` | `selectAllPermissionsOrdered` 加 `WHERE is_deleted = 0`（D2=B），同上 |
| `frontend/src/types/system.ts` | `RoleItem` 补 `permissionCodes` / `permissionCount` / `userCount`；`PermissionItem` 补 `isDeleted` / `deletedAt`；新增 `RoleCreateParams` / `RoleUpdateParams` / `RoleAssignPermissionsParams` |
| `frontend/src/api/system.ts` | 新增 `createRole` / `updateRole` / `assignRolePermissions`（未新增 `getRole`——列表已含所需字段，避免制造死代码） |
| `frontend/src/components/PermissionTree.vue` | **新增**：按 `permCode` 前缀合成两层树、危险项标记、`check` 事件 + `permCode` 白名单过滤 |
| `frontend/src/views/system/Roles.vue` | 工具栏「新增角色」、操作列改宽 200 并加「修改/授权」、权限列折叠、新增/修改/授权三个对话框、`ADMIN` 行四按钮禁用 + tooltip |
| `docs/REQ-系统管理手动操作能力补齐方案.md` | §3 的 P-05 状态列与 §8 加指针，指向本文档 |

### 13.2 与规格的两处有意偏离

| # | 规格原文 | 实际实现 | 理由 |
| --- | --- | --- | --- |
| 1 | §5.5 未要求额外二次确认 | 提交授权前增加一次 `ElMessageBox.confirm`（复述影响人数与强制下线） | 与本仓库既有破坏性动作模式一致（`toggleStatus` / `handleDelete` 都二次确认）。授权会强制下线 N 个用户，属于同一风险等级 |
| 2 | §5.5 暗示可能需处理半选父节点 | 未处理（不需要） | §4.2 实测 `parent_id` 恒为 0，分组为合成节点；`toPermCodes` 按白名单过滤已足够。详见 §5.5 与 RK-03 |

### 13.3 验证证据

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 前端类型检查 | `frontend> .\node_modules\.bin\vue-tsc.cmd --noEmit` | **exit 0**，无输出 |
| 前端生产构建 | `frontend> npm run build` | **exit 0**，`✓ built in 5.93s`；产物含 `Roles-DZCij4Fd.js` 17.30 kB |
| mapper XML 格式 | `[xml](Get-Content <file> -Raw)` | 两个文件均 **OK**（格式非法会导致应用启动即失败） |
| 改动查询的调用方 | 全仓检索 `selectAllOrdered` / `selectAllPermissionsOrdered` / `listPermissionEntities` | 仅 `PermissionController.list` 与 `RoleQueryTool:133` 两处，**无测试依赖**；加过滤不产生回归 |

### 13.4 未验证项（需你手工验收）

以下**均未实跑**，因为需要启动后端 + 数据库，且本仓库无前端测试框架（`frontend/package.json` 只有 `dev`/`build`/`preview`）：

| 编号 | 待验收 |
| --- | --- |
| P05-T1 / T4 / T9 | 新增角色后列表与 `permissionCount` 正确；授权对话框勾选态按 `permissionCodes` 精确回显 |
| P05-T3 / T7 | `ADMIN` 行四个按钮为禁用态且 tooltip 文案正确（含 `<span>` 包裹是否生效） |
| P05-T8 | 只持有 `system:role:view` 的账号（ANALYST / VIEWER）：无新增按钮，操作列整列不渲染 |
| P05-T11 | 无差异时保存按钮禁用；提交集合为变更后**完整**集 |
| P05-T12 | 权限树与 `queryRole(PERMISSION)` 均不再出现已删除权限码（需库中存在 `sys_permission.is_deleted = 1` 的行才能验证） |
| §5.5 半选 | 勾选分组下部分子项 → 保存 → 重开确认勾选态一致 |
| E-6 / D1 | 用一个**缺 `system:permission:view`** 的账号打开授权对话框，应看到可行动的 `el-alert` 而非空树 |

> **环境前提**：验收需后端重跑以加载改动的 mapper XML（MyBatis 映射在启动时解析），仅重启前端无效。

### 13.5 验收反馈调整（2026-09-23，同批次）

首次界面验收反馈 2 项，均已修（`vue-tsc --noEmit` 与 `npm run build` 复跑仍 exit 0）：

| # | 反馈 | 根因 | 处置 |
| --- | --- | --- | --- |
| 1 | 授权弹窗「取消」「保存授权」两个按钮**贴在一起** | **本实现自己引入的**：为给禁用态保存按钮挂 tooltip，保存按钮被 `<span>` 包裹，导致 Element Plus 的 `.el-button + .el-button { margin-left: 12px }` 相邻兄弟选择器不再匹配 | 两个弹窗的 footer 统一改为 `.dialog-footer`（`display:flex; justify-content:flex-end; gap:12px`），并用 `:deep(.el-button + .el-button){ margin-left:0 }` 抹掉相邻兄弟 margin，避免 gap 与 margin 叠加成 24px。间距此后由**唯一一处**控制 |
| 2 | 权限树展出英文权限码（`order:performance:view` 等），业务用户看不懂 | 实现时把 `permCode` 一并渲染为灰色副文本，属自加的噪音（§5.5 从未要求展示它） | 移除该副文本与 `.permission-tree__code` 样式。`permCode` **仍保留在节点数据中**——它是 `node-key` 与提交前白名单过滤的依据，只是不再上屏。`permName` 缺失时仍以 `permCode` 兜底为标签，避免出现空节点 |
| 3 | 单权限分组渲染成同名两级（`▼ 数据概览` → `☑ 数据概览`） | 分组按 `permCode` 前缀合成，未对"只含一个子项"的分组做处理 | **仅含单个子项的分组自动提升为顶层叶子**（`PermissionTree.vue` 的 `treeData`）。`首页` / `数据概览` / `项目管理` / `企业管理` 四组被提升，其余 9 组保持分组。层级与点击次数均不变，只是少了一层同名包裹 |

> **由此产生的一条通用约定**（值得推广到其它页面）：**凡是"禁用按钮 + tooltip"，就必须同时接管按钮间距**。
> `<span>` 包裹是 tooltip 生效的必要条件，但它会破坏 Element Plus 依赖相邻兄弟选择器的默认间距。
> 只禁用而不处理间距，就会出现本次这种"两个按钮挤在一起"的观感缺陷。

> **另一条可复用的判据**：分组的价值来自"收敛多个同级项"。**只含一项的分组不提供任何收敛价值，
> 只会多一层同名缩进**——凡按前缀/类型自动分组的地方，都应先把单子项分组提升掉。
