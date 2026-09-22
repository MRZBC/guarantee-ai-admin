# 移除用户与部门的「机构」概念 实施方案 v3

> 状态：**阶段一实施中**
> v2 变更：按「用户只挂部门、机构是外部出函机构」的领域模型重写；新增 §2 数据范围的关键后果
> 影响范围：`guarantee-system` / `guarantee-auth` / `guarantee-ai` / `guarantee-web` / `frontend` / `scripts` / 测试

---

## 0. 实施进度（阶段一）

| 项 | 状态 |
|---|---|
| 全库备份 | ✅ 已备份到 `%TEMP%\guarantee_before_drop_org_<时间戳>.sql`（约 32.8 MB） |
| `schema.sql` 去 `org_id`、`dept_id` 收紧 | ✅ |
| `V4__drop_org_from_user_and_dept.sql` | ✅ 已编写并**在本地库执行**；**幂等性已验证**（连跑两次 exit 0、结果一致） |
| 库结构实测 | ✅ `sys_user` / `sys_department` 已无任何 `org_id`；`sys_user.dept_id` 为 `NOT NULL` |
| 实体 `SysUser` / `SysDepartment` | ✅ 去 `orgId` |
| VO / DTO（`UserVO`/`CurrentUserVO`/`DepartmentVO`/`DepartmentOptionVO`/`OrgVO`/`UserDto`/`DepartmentDto`） | ✅ 机构相关字段已去 |
| 三个 Mapper XML（user / department / org） | ✅ `org_id` 列、机构 JOIN、`org_id IN` 范围块、`selectOrgCounts`、`clearDept` 均已清除 |
| `SysOrgMapper.java` 四个按机构统计方法 / `OrgCountRef` | ✅ 已删；`OrgCountRef.java` 文件已删除 |
| Service 层（`DataScopeService` O3 / `OrgService` / `UserService` / `DepartmentService`） | ✅ 已改，主代码编译通过 |
| Auth（`Principal` 去 orgId、JWT 去 claim、`CurrentUserVO`）/ 4 个 Controller | ✅ 已改 |
| `DataInitializer`（去 `org_id`、**admin 挂"总部"部门**） | ✅ 已改（按名字定位，不硬编码 id） |
| `guarantee-ai` 模块（提案/查询工具去机构） | ✅ 已改（含 `DepartmentQueryTool` 整套"机构名歧义"出口的结构性删除） |
| 前端 6 个文件 | ✅ 已改，`npm run build` exit 0（已独立复验） |
| `scripts` 历史脚本标注 + 新增结构断言脚本 | ✅ 已改，断言 5/5 通过（已独立复验） |
| **全后端主代码编译** | ✅ `mvn -o -DskipTests compile` 8 模块 **BUILD SUCCESS** |
| **运行期隐患封堵** | ✅ `SysRoleMapper.xml` 的 `u3.org_id` EXISTS 过滤 + `RoleDto.Query.orgId`（编译查不出、运行才报 `Unknown column`）已删除 |
| 测试代码适配（13 个 `src/test/` 文件） | ✅ 已改 |
| **全量 `mvn -o -B -ntp verify`** | ✅ **BUILD SUCCESS**，**139 项全绿**（system 59 / ai 56 / web 24），Failures 0 / Errors 0 / Skipped 0 |
| **前端 `npm run build`** | ✅ exit 0 |
| **结构断言脚本** | ✅ `scripts/verify-no-org-on-user-dept.ps1` 5/5 PASS |

> **⚠️ 测试数量仍是 139，但构成变了**（数字巧合相同，勿误读为"测试没动"）：
> `DataScopeIntegrationTest` 删除了 5 个分级范围用例（`provinceUserSeesOnlyOwnProvince`、
> `cityUserSeesOnlyOwnCity`、`crossScopeQueryReturnsNothing`、`userListIsScoped`、
> `departmentTreeIsScoped`）与整个 `ScopeFixture` 夹具 + `orgIdOf`/`insertOrg`/`insertDept` 等辅助方法，
> 原因是**构造分级范围必须给用户/部门设不同机构，而机构归属已删除**；
> 同时新增了替代覆盖：`nonAdminAlsoSeesEverything`（O3 下非 ADMIN 也看全量）、
> `everyUserBelongsToRealDepartment`、`requireVisibleOrgUsesUnifiedMessage`、
> `orgOwnershipColumnsAreDropped`（断言两列已不存在、`dept_id` 为 NOT NULL）等。
> 删除原因已写入该测试类的类级 javadoc 并引用本文档。

> **已知测试脆弱性（非本次引入）**：`LogicalDeleteSchemaIntegrationTest.directSqlTemplateWorks`
> 在首轮 verify 时曾因共享 MySQL 上并行构建的夹具清理（`LIKE '__ldt%'`）失败一次，单跑/重跑均全绿。
> 该用例只操作 `sys_org`，与本重构无关。

> **运行期隐患封堵（编译期查不出，本次共 3 处）**：
> 1. `SysRoleMapper.xml` 的 `u3.org_id = #{q.orgId}` EXISTS 过滤 + `RoleDto.Query.orgId`
>    → `GET /api/system/roles?orgId=N` 会抛 `Unknown column 'u3.org_id'`。**已删**。
> 2. `OperationAuditService.query()` 非 ADMIN 分支 `restrictByOrg=true` + `visibleOrgIds` 恒 `null`
>    → mapper 走 `<otherwise>` 注入 `AND 1 = 0`，**非 ADMIN 查询操作审计恒为空**。**已改为 `false/null`**。
> 3. `prompts/business-assistant.st` 仍在告诉模型"数据范围按机构收敛：省级用户只见本省"、
>    "部门归属某个机构（`org_id`）"、"用户：所属机构与部门"——**均已成为假话**。**已改写**。

> 迁移前置校验（执行前实测均为 0，故 `ALTER` 得以通过）：
> 无部门用户 0、部门指向不存在 0、`user.org_id <> dept.org_id` 0。

---

---

## 1. 领域模型（本方案的依据）

| | 机构 `sys_org` | 部门 `sys_department` |
|---|---|---|
| 性质 | **外部**：出函机构 | **内部**：组织单元 |
| 服务对象 | **订单**（`tender_order`/`performance_order` 均带 `org_id` + `region_code`） | **人** |
| 携带属性 | `region_code`/`region_name` 行政区划、`org_level`（1总部/2省级/3市级） | 仅编码 / 名称 / `parent_id` |

**结论**：

- `sys_user.org_id` → **删**（用户只挂部门）
- `sys_department.org_id` → **删**（部门服务人，与机构正交）
- `sys_org` 表本身**保留**，但从此**只服务于订单/业务数据**，不再是组织归属维度

**实库已核验**（字段当前已是纯冗余）：

| 校验项 | 结果 |
|---|---|
| `dept_id IS NULL` 的用户 | 0 |
| `user.org_id <> dept.org_id` 的用户 | 0 |
| 部门按 `org_id` 分布 | 11 个部门**全部**在 `org_id = 1` |
| 用户按 `org_id` 分布 | 300 个用户**全部**在 `org_id = 1` |

> 依据 `scripts/migrate-dept-single-org.sql`（已执行）：部门树已收敛为总部单棵树，
> 300 用户全部归 `org_id=1`。该脚本 L10-11 记录了"已知后果（评审已确认接受）：
> operator/analyst/user0004 的数据范围演示随之失效"。

---

## 2. 【拦路】删这两个字段 = 数据范围机制失去全部输入

**这是 v3 最需要你先看的部分。**

### 2.1 机制现状

`DataScopeService.resolve(userId, orgId, roles)` 的输入 `orgId`，此前来自
`sys_user.org_id`，v2 方案里打算改为经 `sys_department.org_id` 派生。
**两个字段都删掉后，这个输入没有任何来源了。**

而 `DataScope.contains()` 的实现是（`DataScope.java` L42）：

```java
return targetOrgId != null && orgIds.contains(targetOrgId);
```

`resolve()` 在 `orgId == null` 时走 L70-74：

```java
// 无机构归属：按最小可见范围处理，只可见自己（避免误放开）
return DataScope.singleOrg(null, null, "无机构归属（仅可见自己）");
```

此时 `orgIds` 为空 → **`contains()` 恒为 `false`**。

> **注意：已确认的两条约束解决不了这个问题。**
> 「用户必须属于一个部门」只保证 `dept_id` 非空；而 `sys_department.org_id` 本次**也要删**，
> 所以「有部门」**依然推不出机构**——部门本身不再携带机构信息，`resolve()` 的输入仍然是空的。

### 2.2 后果（必须知道）

| 账号 | 现在 | 两字段删除后（若不改授权逻辑） |
|---|---|---|
| `admin` | `DataScope.all()` 全量 | 仍全量（`roles.contains(ADMIN)` 短路在前） |
| `operator` / `analyst` / `viewer` / 其余 296 人 | **全量**（都挂在总部 `org_level=1`） | **什么都看不见** —— `requireInScope` / `requireVisibleOrg` 全拒，scoped 查询 `AND ... org_id IN ()` 还可能直接 SQL 报错 |

**即：删字段本身不改行为（今天已因单机构迁移而全量），但一旦删掉输入源，非 admin 账号会集体失效。**

> 换句话说：**这两个字段现在是"数据范围"机制唯一还站着的输入。删掉它们，等于宣告放弃分级数据范围。**

### 2.3 替代方案

> **✅ 已决策：阶段一采用 O3（显式全量），阶段二另行决定 O1 / O2。**
> 理由：O3 与当前**实际行为等价**（今天所有人挂总部 = 全量），
> 因此阶段一可以在**不改变任何人可见数据**的前提下完成模型清理，回归可验证。

| 选项 | 做法 | 代价 | 评价 |
|---|---|---|---|
| **O1 权限码取代数据范围** | 删掉 `DataScopeService` 的机构输入与 SQL 里的 `org_id IN` 过滤，改为**纯权限码**（`@PreAuthorize` / `PermissionGuard`）控制"能做什么" | 需逐接口梳理权限码；`sys_permission` 目录与 JWT claims **已存在**，但 REQ 文档记录"后端**没有任何一处校验点**"，等于要补齐这套 | 与"机构是外部的"模型最自洽（机构不该决定内部人的可见范围）。**留待阶段二** |
| **O2 保留范围但换锚点** | 例如按**部门子树**授权、或新增"角色 → 可见范围"映射表 | 需要新表 + 新判定逻辑 + 迁移 | 若业务上确实需要"某科室只看某类数据"才选。**留待阶段二** |
| **O3 显式全量 ✅ 选定** | 承认所有登录用户可见全部数据，行级过滤只靠权限码做端级拦截 | 最小改动；等于把 `orgId == null` 分支的语义从"仅可见自己"改成"全量" | 与当前**实际行为等价**（今天就是全量），作为阶段一过渡态 |

---

## 3. 两阶段实施（✅ 已决策：拆分）

**把"数据模型清理"和"授权模型重构"分开做**，否则一次改动同时动数据模型与安全逻辑，出问题无法定位。

### 阶段一：概念清理 + O3 行为等价（本次范围）

1. 删 `sys_user.org_id`、`sys_department.org_id`
2. 清掉所有对外暴露的机构字段（VO / DTO / 前端 / AI 出参）
3. **把 `DataScopeService` 中 `orgId == null` 的语义由「仅可见自己」改为「全量」**（O3）
   - 这一步是关键：它让删除前后**行为完全一致**（今天所有人都是总部 → 全量）
   - 保留 `DataScope` 的 API 形状，12 处 `resolve()` 调用点**零改动**
4. `OrgService` 里 `deptCount` / "机构下有部门不能删"等**依赖 `dept.org_id` 的语义一并移除**

> 阶段一完成后：模型干净了，行为没变，测试基线可比对。

### 阶段二：授权模型（O1 或 O2，另行排期）

补齐或替换授权判定。**不在本次范围内。**

---

## 4. 影响面清单

### 4.1 数据库

| 位置 | 改动 |
|---|---|
| `schema.sql` `sys_user` | 删 `org_id`、删 `KEY idx_sys_user_org`、`dept_id` 改 `NOT NULL` |
| `schema.sql` `sys_department` | 删 `org_id`、删 `KEY idx_sys_dept_org (org_id)` |
| **新增** `db/migration/V4__drop_org_from_user_and_dept.sql` | 见 §5.1 |
| `LogicalDeleteTables.java` | 无需改（只登记表名） |

> **本仓库无 Flyway**。`db/migration/V1~V3` 是**手工执行**的幂等脚本（V1 头部原文：
> "执行方式（手工执行，应用启动不会自动跑本目录）"）。且 `schema.sql` 全是
> `CREATE TABLE IF NOT EXISTS`，**对已存在的库不改结构** → 必须另给手工 ALTER 脚本。

### 4.2 Mapper XML

**`SysUserMapper.xml`**

| 行 | 改动 |
|---|---|
| L14 `voCols` | 删 `u.org_id AS orgId` |
| L30 `fromJoin` | 删 `LEFT JOIN sys_org o ON o.id = u.org_id`；部门 JOIN 保留 |
| L59 `queryWhere` | 删 `AND u.org_id = #{q.orgId}`（筛选改 `deptId`） |
| L89 | 删范围过滤 `AND u.org_id IN (...)` |
| L146/153/206/357 | 列清单删 `org_id` |
| L263 注释 | 删 `org_id` 表述 |
| L279 `clearDept` | **删除** |

**`SysDepartmentMapper.xml`（16 处，改动最密集）**

| 行 | 改动 |
|---|---|
| L11 `voCols` | 删 `d.org_id AS orgId` |
| L24 `fromJoin` | 删 `LEFT JOIN sys_org o ON o.id = d.org_id` |
| L41 `queryWhere` | 删 `AND d.org_id = #{q.orgId}` |
| L72 / L120 | 删范围过滤 `AND d.org_id IN` |
| L94/100/105/228 | 实体列清单删 `org_id`（4 处） |
| L115 `selectCandidates` | 删 `AND d.org_id = #{orgId}` 参数 |
| L131 / L187 | `ORDER BY d.org_id ASC, ...` → 去掉 `org_id` 排序键 |
| L143 `insert` | 列清单删 `org_id` |
| L194 `selectEnabledOptions` | 删 `d.org_id AS orgId` 与 L198 的 `AND d.org_id = #{orgId}` |
| L180 注释 | "排序按 org_id 在前：前端以机构为顶级分组节点" → 失效，删 |

**`SysOrgMapper.xml`（3 处按机构统计，本次唯一影响写操作安全的地方）**

| 行 | 改动 |
|---|---|
| L212 `countEnabledUserByOrg` | 语义失效 → **删除**（用户不再属机构） |
| L232 `deptCount` 子查询 | 语义失效 → **删除**（部门不再属机构） |
| L234 `userCount` 子查询 | 语义失效 → **删除** |
| L299 `countUserByOrg` / L215 `countDepartmentByOrg` | **删除** |

> 这些是**机构删除/停用守卫**（`OrgService` L399/L411/L467/L471/L486/L487）的数据来源。
> 删除后 `OrgService` 的守卫逻辑必须一并改写，否则会出现
> **"仍有引用却允许删除机构"** 或编译失败。

### 4.3 Java

| 文件 | 改动 |
|---|---|
| `SysUser.java` | 删 `orgId` |
| `SysDepartment.java` | 删 `orgId` |
| `JwtTokenProvider.java` L58/L68 | `.claim(CLAIM_ORG_ID, user.getOrgId())` **编译不过** → 按阶段一方案，claim 可整个移除 |
| `CurrentUser.java` | `Principal.orgId` → 阶段一可置为常量/移除（视 O3 实现） |
| `UserService.java` | L396/L527 的机构取用；L181-202/L428-433 清 `clearDept`；补 `deptId` 必填校验 |
| `DepartmentService.java` | L215/L229 的 `parent.orgId == request.orgId` 强制**删除**（同机构约束消失）；L211 `requireVisibleOrg`；L246 scope 校验；L321 删除守卫里的机构引用 |
| `OrgService.java` | L399/L411/L467/L471/L486/L487 守卫与 impact 文案；L175 `orgId -> [deptCount, userCount]` 整体失效 |
| `DepartmentDto.java` | 删 `orgId`（L4 处） |
| `DepartmentVO.java` / `DepartmentOptionVO.java` | 删 `orgId/orgName` |
| `OrgVO.java` L24 | 删 `deptCount` |
| `OrgCountRef.java` | 删 `deptCount` |
| `DataScopeService.java` | 按 §3 阶段一改 `orgId == null` 语义；阶段二整体重构 |
| `DataInitializer.java` | L416-418 admin 的 `deptId = null` **必须改**（见 §5.5）；L457/L464 去 `org_id`；L333 部门 insert 去 `org_id` |
| `AuthService.java` L92-95 | `CurrentUserVO` 去 `orgId/orgName` |

### 4.4 前端

| 文件 | 引用数 | 改动 |
|---|---|---|
| `views/system/Departments.vue` | **30** | **最大单点**：机构筛选（L522-527）、机构列（L610-611）、根节点机构标签（L596-603）、新建部门必填 `orgId`（L321/L680-697）、`selectEnabledOptions(orgId)`（L389/L397/L422）、L84 的设计注释。**部门树从"机构根节点 + 部门子树"变成一棵纯部门树** |
| `layout/AppLayout.vue` L151 | 1 | 顶栏 `orgName \|\| '未分配机构'` → 改为显示部门 |
| `views/system/Users.vue` L142-147 / L191-192 | 4 | 删机构筛选 + 列 |
| `views/system/Orgs.vue` | 若干 | 机构列表的「部门数」列失效（`deptCount`） |
| `types/auth.ts` L11 / `types/system.ts` L203 等 | 6+ | 删 `orgName/orgId/deptCount` |

> `OrderTable.vue` / `Orgs.vue` / `Departments.vue` 里机构作为**它们自身业务对象**
> 的部分（订单的机构、机构管理页）**保留**，只删"作为用户/部门归属属性"的那些。

### 4.5 AI 工具

| 文件 | 改动 |
|---|---|
| `DepartmentQueryTool.java` / `DepartmentQueryToolResult.java` | 出参、入参去 `orgId/orgName`（12 处） |
| `write/DepartmentProposalTool.java` | CREATE 部门的必填参数去 `orgId`（10 处） |
| `service/executor/DepartmentProposalExecutor.java` | 去 `orgId` |
| `UserQueryTool.java` | 出参去 `orgId/orgName` |
| `OrgQueryTool.java` / `OrgQueryToolResult.java` L31 | 出参去 `deptCount` |
| `AiDataScopeResolver.java` / `BaseProposalTool.java` | 随 `DataScopeService` 调整 |

### 4.6 脚本（引用已删列，删列后会直接报错）

| 文件 | 引用点 |
|---|---|
| `scripts/migrate-dept-single-org.sql` | L24-26、L36、L64、L66-68（含 `UPDATE sys_user SET org_id`） |
| `scripts/migrate-dept-tree-apply.sql` | L14、L31-33、L39-55、L65-71 |
| `scripts/migrate-dept-single-org.ps1` / `migrate-dept-tree.ps1` | 运行器 |
| `scripts/verify-dept-tree.ps1` / `verify-dept-tree-shape.mjs` | 机构一致性断言全部失效 |

**建议**：文件头加"**已执行完毕，历史留存，勿再执行**"，另建结构断言脚本校验
"`sys_user` 无 `org_id`、`sys_department` 无 `org_id`"。

### 4.7 测试

| 文件 | 说明 |
|---|---|
| `DataScopeIntegrationTest.java`（**53** 处 orgId） | **核心用例（省级/市级范围）将无法成立**，因为构造这些场景必须给用户/部门设不同机构。必须删除或改写为"全量"断言 |
| `LogicalDeleteServiceIntegrationTest.java`（15） | 实体字段引用 |
| `ProposalFlowIT.java`（11）/ `WebAuditIT.java`（10）/ `LogicalDeleteWebIT.java`（4） | fixture 与断言 |
| `LogicalDeleteSchemaIntegrationTest.java`（5） | 列存在性断言 |
| `DepartmentControllerTreeAuthTest.java` | 部门树 + 机构归属断言 |
| `LogicalDeletePermissionsTest.java` | `Principal(..., orgId, ...)` 构造 |

> 测试改动量是**最大**的一块，且 `DataScopeIntegrationTest` 的失效本身就是一个信号：
> **删这两个字段等价于放弃"分级数据范围"**。

### 4.8 文档

`docs/REQ-系统管理助手能力.md`（L180-189/L308-316 部门与用户的 `orgId` 出参入参、L485 SYS-P-10）、
`docs/PLAN-部门配置树形改造方案.md`（整篇以"机构 + 部门双树"为前提，§99 的"方案 A"选型随之作废）、
`docs/DEC-逻辑删除设计方案.md`（如涉及）、`README.md`。

---

## 5. 分步实施（阶段一）

### 5.1 第一步：迁移脚本（先跑，不改代码）

```sql
-- V4__drop_org_from_user_and_dept.sql（幂等，手工执行）
-- 前置校验，任一非 0 则中止：
--   (a) sys_user.dept_id IS NULL 的用户数                 应 = 0
--   (b) 部门指向不存在/已删部门的用户数                    应 = 0
--   (c) sys_user.org_id <> sys_department.org_id 的用户数  应 = 0
-- 通过后：
--   ALTER TABLE sys_user     DROP INDEX idx_sys_user_org;
--   ALTER TABLE sys_user     MODIFY dept_id BIGINT NOT NULL COMMENT '所属部门';
--   ALTER TABLE sys_user     DROP COLUMN org_id;
--   ALTER TABLE sys_department DROP INDEX idx_sys_dept_org;
--   ALTER TABLE sys_department DROP COLUMN org_id;
```

### 5.2 第二步：实体 + Mapper（编译通过）

§4.2 / §4.3；`mvn -o -q -DskipTests compile` 必须通过。

### 5.3 第三步：`DataScopeService` 语义对齐（**保持行为等价的关键步**）

把 `orgId == null` 的"仅可见自己"改为全量（O3），
使删除前后所有人行为一致（今天本就是全量）。12 处 `resolve()` 调用点不动。

### 5.4 第四步：机构维度语义清理

`OrgService` 的 `deptCount` / 用户数守卫、`OrgVO`、AI `OrgQueryTool` 一并处理。

### 5.5 第五步：种子数据

`DataInitializer` L416-418 **必须改**：现在写的是

```java
// SYS-P-24：admin → 总部（全量数据范围），不挂部门
deptId = null;
```

而实库 admin 的 `dept_id = 1001`、无部门用户 = 0（`migrate-dept-single-org.sql` L38-55
给所有用户含 admin 补了部门，种子代码未同步）。删列后 `dept_id` 为 `NOT NULL`，
**必须让 admin 也挂总部部门**，否则全库重建会失败。

> **已确认**：admin 的部门就是**总部**（实库 `dept_id = 1001`，`dept_name = 总部`），
> 把 L418 的 `deptId = null` 改为指向总部部门即可。
> **已确认**：「用户必须属于一个部门」→ `dept_id` 为 `NOT NULL`，
> 「无部门用户」不再是合法状态，`clearDept` 能力整体移除。

### 5.6 第六步：前端 + AI + 测试 + 文档

§4.4 / §4.5 / §4.7 / §4.8。`npm run build` 必须 exit 0。

---

## 6. 验收清单（阶段一）

- [ ] 迁移脚本**可重复执行**，且对 (a)(b)(c) 非 0 情况**拒绝执行**
- [ ] `mvn -o verify` 全绿（基线将因删除 `DataScopeIntegrationTest` 分级用例而变化，需记录新基线）
- [ ] **行为等价验证**：删字段前后，每个角色的可见数据量一致（今天应均为全量）
- [ ] **非 admin 账号可用性**：`operator`/`analyst`/`viewer` 登录后各列表页**不能出现空白或 500**
      （这是 §2.2 风险的核心回归项）
- [ ] 机构删除/停用守卫改写后，不出现"允许删除仍被引用的机构"
- [ ] 用户/部门相关接口与页面**不再出现"所属机构"**
- [ ] 部门树页面正常（从"机构根节点"变为纯部门树）
- [ ] `scripts/` 历史脚本已标注"勿再执行"
- [ ] `npm run build` exit 0
- [ ] 全库重建跑通，admin 有部门

---

## 7. 风险

| 级别 | 风险 | 说明 |
|---|---|---|
| **最高** | 非 admin 账号集体失效 | §2.2。`resolve(orgId=null)` → `contains()` 恒 false。**必须在同一批改动里改掉"仅可见自己"分支**，否则系统对非 admin 不可用 |
| **高** | 机构删除守卫被删后失去保护 | §4.2 三处统计的语义失效，`OrgService` 守卫需一并改写 |
| **高** | scoped SQL 空列表 | `AND ... org_id IN ()` 若未加 `restricted` 守卫会直接 SQL 报错，需逐片段确认 |
| **中** | `DepartmentService` 同机构约束消失 | L215/L229 删除后，部门树不再有机构边界；单机构现状下无影响，但语义变了 |
| **中** | 前端 `Departments.vue` 30 处 | 单文件改动最大，且涉及树结构（机构根节点 → 纯部门树） |
| **中** | 历史脚本引用已删列 | §4.6；漏改会在下次有人执行时炸 |
| **低** | 逻辑删除拦截器污染 JOIN | 部门不再 JOIN 机构后，此风险随之消失（v2 中的该风险项**本次被消除**） |
| **低** | 审计快照 | `ai_operation_audit.operator_org_id` 需定去留（§8 Q3） |

---

## 8. 已确认 / 待确认

**已确认（无需再议）**

| # | 结论 |
|---|---|
| C1 | `sys_user.org_id` 删除；用户只挂部门 |
| C2 | `sys_department.org_id` 删除；部门不再有机构归属 |
| C3 | 用户**必须**属于一个部门 → `sys_user.dept_id` 为 `NOT NULL`，「无部门用户」不合法 |
| C4 | admin 的部门 = **总部**（实库 `dept_id = 1001`）；`DataInitializer` L418 的 `deptId = null` 改为总部部门 |
| C5 | `clearDept` / AI 的「（清空）部门」能力整体移除 |
| C6 | 数据范围采用 **O3（显式全量）**：`DataScopeService` 中 `orgId == null` 的语义由「仅可见自己」改为「全量」。理由：与当前实际行为等价（今天所有人挂总部 = 全量），阶段一不改变任何人的可见数据 |
| C7 | 实施**拆两阶段**：阶段一 = 概念清理 + O3 行为等价（本次范围）；阶段二 = 授权模型（O1 权限码 / O2 换锚点），另行排期 |

**待确认**

| # | 问题 | 我的建议 |
|---|---|---|
| **Q3** | `ai_operation_audit.operator_org_id` 去留？ | 这是审计快照。若审计里也不该出现机构，删列；否则保留（写死 `null` 或去掉赋值） |
| **Q4** | `Orgs.vue` 的「部门数」「用户数」列怎么处理？ | 语义失效，建议直接去掉这两列（机构页只保留机构自身属性 + 订单关联） |
| **Q5** | 历史上"分级数据范围"的测试用例是否直接删除？ | `DataScopeIntegrationTest` 的分级用例已无法成立，建议删除并在文档里记录"分级范围已废弃"的原因 |
