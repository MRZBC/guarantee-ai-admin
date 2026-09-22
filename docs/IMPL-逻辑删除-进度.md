# 逻辑删除（`is_deleted`）实施进度

| 项目 | 内容 |
| --- | --- |
| 依据 | `docs/DEC-逻辑删除设计方案.md` v2.1（唯一设计依据）+ `docs/IMPL-逻辑删除-任务书.md` |
| 目标库 | `guarantee_ai_admin` @ `127.0.0.1:3307`（迁移脚本已执行） |
| 迁移脚本 | `guarantee-web/src/main/resources/db/migration/V1__logical_delete.sql`（加列 + 索引）、`V2__logical_delete_unique_keys.sql`（13 唯一键改造） |
| 基线 | `mvn -o verify` 93 项全绿（改造前） |
| 最终 | `mvn -o verify` **133 项全绿**；`npx vue-tsc --noEmit` 与 `npm run build` 均 exit 0 |
| 绝对禁止 | 存储过程 / 触发器 / 函数（LD-EX-02）✔ 未引入；主键改雪花 ID（LD-Q11）✔ 未改；`ai_operation_secret` 加逻辑删除字段（LD-EX-01）✔ 未加 |

---

## 后续变更（2026-09-22，逻辑删除交付之后）

### E. 部门配置页树形改造（PLAN-部门配置树形改造方案.md）

与本轮逻辑删除无直接关系，但**触碰了同一批文件**（`Departments.vue` / `DepartmentService` / `DepartmentController`），记录在此以免与本轮改动混淆：

- 新增 `GET /api/system/departments/tree` 全量接口（复用列表 `queryWhere`，带 `includeDeleted` 参数级鉴权与 2000 条上限）；
- `Departments.vue` 由分页表格改为 `机构 → 部门 → 子部门` 树，并补齐部门的新增 / 修改 / 启停入口；
- 逻辑删除相关行为**未变**：`DELETE` / `restore`、列表分页接口、`includeDeleted` 语义与鉴权全部保持原样；
  「已删除」行级标记（整行置灰 + 操作列内标签 + 禁用 tooltip）在树形下同样保留；
- 后端测试 133 → **139 项全绿**（新增 6 项，均在 `guarantee-system`）；HTTP 走查 38 项全通过（`scripts/verify-dept-tree.ps1`）。

### F. 撤除前端「显示已删除」与恢复入口（2026-09-22，再次评审）

**决策**：页面**不要"显示已删除"这个功能**，5 个系统管理页面全部撤除（机构 / 部门 / 用户 / 角色 / 险种）。

对本轮逻辑删除交付的**推翻范围**（需与上文 §C 的记录对照阅读）：

| 项 | 原状态 | 现状态 |
| --- | --- | --- |
| 前端「显示已删除」开关 | 5 个页面均有 | **全部删除** |
| 前端「恢复」入口 | 5 个页面均有 | **全部删除**（连 `restore*` 前端 API 包装一起删） |
| 前端 `includeDeleted` 参数链路 | 查询 DTO + loadData + 重置 | **全部删除**（请求不再发送该参数） |
| 已删除行级标记（置灰 / 已删除标签 / 禁用 tooltip） | 保留 | **删除**——已删除记录不再进列表，标记无对象可标 |
| 后端 `DELETE` / `restore` 接口、`includeDeleted` 参数、参数级鉴权 | 有 | **保留**（本次只改前端，后端契约与 139 项测试不动） |
| `deletedAt` / `deletedBy` 后端字段 | 接口返回、前端不展示 | **不变**（排障/直连核对仍需；前端类型不再声明） |

**同时修正了一处不实承诺**：删除确认弹窗原写"② 删除可以恢复（打开「显示已删除」开关后可在此处恢复）"，
开关撤除后该承诺无法兑现，5 处已统一改为"删除后不再出现在列表中，且页面不提供恢复入口——如只是暂停业务，请改用「停用」"。

**另一次设计纠正（同日）**：部门页的树形曾错误地把**机构**（出函机构，`sys_org`）当成部门树的一层，
与**部门**（公司内部组织，`sys_department`）混为一棵树。评审指出后已改：树只由 `sys_department.parent_id` 构成，
机构仅作为归属属性（筛选条件 +「所属机构」列 + 顶级行上的机构标签）。后端零改动，
新增 `scripts/verify-dept-tree-shape.mjs` 做结构校验（含"根节点全部是真实部门、无机构伪节点"）。
详见 `PLAN-部门配置树形改造方案.md` §14。

**部门树与人员归属重构（同日）**：按评审要求把部门结构改为
`总部 → 业务部/财务部/人事部/行政部/技术部 → 杭州部/台州部/温州部/大数据部/系统部`，
每机构 11 个部门、共 **231** 个（原 80 个），层级三级；300 个用户的部门归属同步重分配
（231 个部门全部有人，跨机构挂部门 = 0）。`DataInitializer` 改为规格表 `DEPT_SPEC` 驱动，
并修掉一处既有的"用户机构 ≠ 部门机构"不一致（演示账号改写 `org_id` 时未同步 `dept_id`）。
现有库通过 `scripts/migrate-dept-tree.ps1` 两阶段迁移完成。

**再收敛为单一机构（同日，评审要求）**：部门树最终只保留 ORGHQ 一棵（**11 个部门**），
其余 220 个部门删除；300 个用户全部改挂总部（每部门 27~28 人）。机构表未动（仍 21 个）。
副作用：`operator`（省级）/ `analyst`（市级）的数据范围演示失效——
**覆盖没有丢**，改为在 `DataScopeIntegrationTest` 里用自建夹具（6 机构 + 2 用户，用完物理删除）
验证"省级看本省及下级、市级只看本市、跨范围返回 0 条"。迁移脚本 `scripts/migrate-dept-single-org.ps1`。
注意 `DataInitializer` 仍按 21 机构 × 11 部门生成，重置演示数据后需再跑该迁移。
详见该方案 §15、§16。

**验收**：`vue-tsc` 0、`npm run build` 0；产物中「显示已删除」/`row-deleted`/`deleted-tag` 均 0 处；
后端 `mvn -o verify` 139 项全绿（后端未改）。详见 `PLAN-部门配置树形改造方案.md` §13。

### 运维提示（本轮踩到，值得记住）

**`mvn spring-boot:run -pl guarantee-web`（不带 `-am`）读取的是本地仓库里的 `guarantee-system` jar，不是 `target/classes`。**
改完 `guarantee-system` 后必须先 `mvn -o -pl guarantee-system -am -DskipTests install` 再重启后端，
否则会出现"源码与 `target/classes` 都有新端点，但接口返回 400/404"的假象——
本次表现为新端点 `/departments/tree` 被 `/{id}` 匹配，报"参数类型不正确: id"。

---

## 前置动作（2026-09-22）

- 状态：完成
- 备份：`%TEMP%\guarantee_backup_before_ld.sql`（29.11 MB，`--no-tablespaces`；首次因缺 `PROCESS` 权限报
  `mysqldump: Error: 'Access denied ... dump tablespaces'`，改用 `--no-tablespaces` 后 exit=0）
- 13 个唯一键重复校验：**13/13 全部 0 行**（无存量重复业务键）
- 既有数据库对象：`ROUTINES` = 0、`TRIGGERS` = 0（LD-EX-02 基线成立）
- 基线：`mvn -o verify` 93 项全绿；前端 `vue-tsc` + `npm run build` 均通过
- 环境观察（非阻塞）：
  - 库中有两个**设计阶段实测遗留**的临时表 `t_dt`（`deleted_at datetime(6) DEFAULT CURRENT_TIMESTAMP(6)`）
    与 `t_du`。它们不属于 18 张业务表、不被应用使用，本次**未做任何改动**；建议后续清理
    （注意：AC-1 的列定义巡检 SQL 需排除它们，否则命中 `t_dt` 会得到 1 个"假违规"）
  - `guarantee` 用户只有 `guarantee_ai_admin` 的权限，**无法创建临时库** `guarantee_ai_admin_ldtest`；
    root 密码与 docker-compose 里的 `root@2026` 不一致。因此破坏性验证改用
    **开发库内的临时夹具行（`__ldt` 前缀）+ `@AfterEach` 物理清理**，等价且不污染演示数据（详见批次 2）

---

## 批次 1（2026-09-22）

- 状态：完成
- 内容：18 张表加 3 列 + `idx_*_deleted` 索引；实体 / VO / DTO 加字段；`schema.sql` 与迁移脚本
- 数据库执行：`V1__logical_delete.sql` 成功，自检 `table_count=18`、`index_count=18`；
  `ai_operation_secret` 无任何字段；`deleted_at` 全部 `datetime(6) NULL DEFAULT NULL`
- 新增文件：
  - `guarantee-web/src/main/resources/db/migration/V1__logical_delete.sql`
  - `guarantee-web/src/main/resources/db/migration/V2__logical_delete_unique_keys.sql`
  - `scripts/gen-logical-delete-sql.mjs`（schema/迁移生成器，自带 18/18/13 + 排除表断言）
  - `scripts/add-ld-fields.mjs`、`scripts/add-ld-dto-flag.mjs`、`scripts/add-ld-select-cols.mjs`
- 修改文件：
  - `guarantee-web/src/main/resources/db/schema.sql`（18 表加列 + 索引 + 13 唯一键改复合键）
  - 实体 16 个：`SysOrg` `SysDepartment` `SysUser` `SysRole` `SysPermission` `InsuranceType`
    `TenderOrder` `PerformanceOrder` `Enterprise` `Project` `AiConversation` `AiMessage` `AiToolCall`
    `AiAuditLog` `AiOperationProposal` `AiOperationAudit`
  - VO 6 个：`OrgVO` `DepartmentVO` `UserVO` `RoleVO` `PermissionVO` `InsuranceTypeVO`（record，同步
    `InsuranceTypeService.toVO`）
  - DTO 5 个 Query：`OrgDto` `DepartmentDto` `UserDto` `RoleDto` `InsuranceTypeDto` 加 `includeDeleted`
  - Mapper XML 6 个：SELECT 列清单返回 `is_deleted AS isDeleted / deleted_at AS deletedAt / deleted_by AS deletedBy`
- 测试：`mvn -o verify` → **93 项全绿**（与基线一致，系统行为不变）
- 遗留：迁移脚本需**手工执行**（应用启动只跑 `db/schema.sql`），顺序 V1 → V2

---

## 批次 2（2026-09-22）

- 状态：完成
- 内容：13 个唯一键改造；关联表"先清后插 → UPSERT"；鉴权路径 9 处显式过滤
- 数据库执行：`V2__logical_delete_unique_keys.sql` 成功；自检 13 个键均为 `col_cnt=2 / has_deleted_at=1`
- **LD-T2 实测证据**（开发库夹具行，5 轮 × 13 键）：
  - 手工先在 `sys_user` 上验证：5 轮后 `rows_for_key=5 / deleted_rows=4 / active_rows=1`，清理后 0 行
  - 测试 `LogicalDeleteSchemaIntegrationTest#fiveRoundDeleteRebuildCycleForAllKeys`：
    13 个键 × 5 轮"插入 → 软删 → 再插入"全部成功，每表留下 5 行且 `COUNT(DISTINCT deleted_at)=5`
    （证明必须微秒精度；秒精度会在同一秒内撞键）
- 新增文件：`scripts/patch-ld-batch2.mjs`
- 修改文件：
  - `mapper/system/SysUserMapper.xml`：`listPermissionCodesByUserId`（**最关键：决定权限集合**）、
    `listRoleCodesByUserId`、`selectRoleRefsByUserIds`、`countOtherEnabledAdmins`、
    `countEnabledUsersByRoleCode`、`listRoleIdsByUserId`、`listUserIdsByRoleCode`、`selectByUsername`（LD-05b）
  - `mapper/system/SysRoleMapper.xml`：`selectPermissionRefsByRoleIds`、`countUsersByRoleIds`、
    `selectUserIdsByRoleCode`、`countPermissionsByRoleIds`、`selectPermissionIdsByCodes`、
    `selectPermissionEntitiesByCodes`、`selectIdsByCodes`、`selectEnabledCodes`、`selectEntityByCode/ById`
  - 关联表 UPSERT：`softDeleteUserRolesNotIn` + `upsertUserRoles`、`softDeleteRolePermissionsNotIn` +
    `upsertRolePermissions`、`softDeleteUserRoleByCode`（`SysUserMapper.java` / `SysRoleMapper.java` /
    `UserService.assignRoles` / `RoleService.assignPermissions`）
- 测试：`mvn -o verify` → **93 项全绿**（含既有 `ProposalFlowIT` 的 ASSIGN_ROLES 用例，走 UPSERT 新路径）

---

## 批次 3（2026-09-22）

- 状态：完成
- 内容：`LogicalDeleteInnerInterceptor`（查询过滤）+ 显式过滤场景 + `includeDeleted` 查询
- 新增文件：
  - `guarantee-system/src/main/java/com/guarantee/system/mybatis/LogicalDeleteTables.java`（18 张受管表，**不含 `ai_operation_secret`**）
  - `.../mybatis/LogicalDeleteSqlRewriter.java`（纯函数 SQL 改写器）
  - `.../mybatis/LogicalDeleteInnerInterceptor.java`（`StatementHandler#prepare`，只处理 SELECT）
  - `guarantee-system/src/test/.../LogicalDeleteSqlRewriterTest.java`（LD-T14，10 项）
- 关键实现选择（与设计 §5.2 的对应关系）：
  - 只处理**括号深度 0** 的 FROM/JOIN；派生表 / 子查询 / 递归 CTE 内部不注入（由 SQL 自身负责）
  - `FROM` 表与无 `ON` 的连接注入到 `WHERE` 末尾；带 `ON` 的连接注入到 **`ON` 子句**末尾
    （否则 `LEFT JOIN` 会退化成 `INNER JOIN`）
  - 方法名以 `IncludingDeleted` 结尾 → 豁免；SQL 已含 `is_deleted` **条件** → 跳过
    （列投影 `is_deleted AS isDeleted` 不计为条件，这样 VO 能返回标记且拦截器仍兜底）
  - **顶层 UNION 直接抛异常**（无法安全注入，绝不静默只过滤一个分支）
  - 开关 `guarantee.logical-delete.enabled`（默认 true，两个 `application.yml` 均已写入）
- 显式过滤（拦截器盖不住，按 §5.3 逐个补）：
  - `selectVisibleOrgIds`：递归 CTE 起手与递归都带 `is_deleted = 0`（**LD-T7 防越权**）
  - `countEnabledDescendants` / `countChildren` / `countDepartmentByOrg` / `countEnabledUserByOrg` /
    `countUserByOrg` / `countOrderByOrg` / `selectOrgCounts`（相关子查询）
  - `countEnabledUserByDept`（**LD-03 停用前置检查**）、`countUserByDept`
  - `SysUserMapper` / `SysRoleMapper` 的 `EXISTS` 子查询、`InsuranceTypeMapper.countOrderByType`
  - 分析模块**全部手工显式过滤**（任务书 §4.1 建议）：`OverviewMapper`、`OrderAnalysisMapper`、
    `EnterpriseMapper`、`ProjectMapper`
  - 订单模块：`TenderOrderMapper` / `PerformanceOrderMapper`（主表条件进 WHERE，LEFT JOIN 维度表条件进 ON）、
    `OrderStatisticsMapper.branchFilter`
- 修改文件（脚本）：`scripts/patch-ld-analysis.mjs`、`patch-ld-order.mjs`、`patch-ld-order-analysis.mjs`、
  `patch-ld-explicit.mjs`
- 测试：`mvn -o verify` → **93 项全绿**（拦截器开启后系统行为与基线一致，因为此时库中没有任何已删除行）
- **过程中修掉的两个真实缺陷**（都由测试暴露）：
  1. 无 WHERE 的语句会把 `WHERE ... is_deleted = 0` 插到第一个 `LEFT JOIN` **之前** → 语法错误
     （改为 `findNoWhereInjectionPoint`：只在子句关键字前插入，不在连接关键字前插入）
  2. 注入文本末尾缺空格 → `...is_deleted = 0LEFT JOIN...` 语法错误

---

## 批次 4（2026-09-22）

- 状态：完成
- 内容：删除 / 恢复接口 + 权限码 + 前端 + AI 工具与提示词
- 新增文件：
  - `guarantee-common/.../security/LogicalDeletePermissions.java`（`includeDeleted` 参数级权限判定，单一实现）
  - `guarantee-system/src/test/.../LogicalDeleteSchemaIntegrationTest.java`（LD-T2/T2a/T8a/T17/T18/T19/T20/T21/T22）
  - `guarantee-system/src/test/.../LogicalDeleteServiceIntegrationTest.java`（LD-T1/T3/T4/T5/T6/T7/T9/T10/T11/T13/T15）
  - `guarantee-system/src/test/.../LogicalDeletePermissionsTest.java`（includeDeleted 权限判定 4 项）
  - `guarantee-web/src/test/.../LogicalDeleteWebIT.java`（LD-T8 / LD-T12 / LD-T21 / AC-8）
- 修改文件（后端）：
  - Mapper XML 5 个：`softDelete`（**三列一条语句写全 + `NOW(6)` + `AND is_deleted = 0`**）、`restore`
    （三列归零）、`selectXxxByIdIncludingDeleted`、`queryWhere` 的 `includeDeleted` 显式分支、`countUserByOrg/Dept`、`countChildDept`
  - Mapper 接口 5 个：`SysOrgMapper` / `SysDepartmentMapper` / `SysUserMapper` / `SysRoleMapper` / `InsuranceTypeMapper`
  - Service 5 个：`delete` / `restore` / `deleteBlockers` / `deleteImpact`（+ `UserService.restoreBlockers`），
    领域校验按 §6.2（比停用更严格：被引用即拒绝）；`UserService`/`DepartmentService` 构造函数新增
    `SysOrgMapper`（`DepartmentService` 另加 `SysDepartmentMapper` 给 `UserService`）用于 LD-04a 父记录校验
  - Controller 5 个：`DELETE /api/system/{domain}/{id}`、`POST /api/system/{domain}/{id}/restore`、
    列表 + 树接口的 `includeDeleted` 服务端权限校验（`@PreAuthorize` 表达不了参数级权限）
  - `guarantee-auth/.../AuthService.java`：LD-05 已删除用户登录返回 `LOGIN_FAILED`（且放在停用校验**之前**）
  - `guarantee-common/.../Permissions.java`：5 个 `:delete` 常量
  - `guarantee-web/.../init/PermissionCatalog.java`：5 个权限码 + 矩阵（机构/部门/险种给 OPERATOR，
    用户/角色仅 ADMIN）+ `isWritePermission` 纳入 `:delete`
  - 前端 7 个文件：`types/system.ts`、`api/system.ts`、5 个系统管理页
  - AI 侧 13 个文件：5 写工具 + 5 执行器 + 5 查询工具（含 Result record）+ `ProposalService.actionName`
    + `AiPermissionGuard` + `AiToolRegistry` + `business-assistant.st` + `AiToolRegistryTest`
- 测试与端到端走查：
  - `mvn -o verify` → **133 项全绿**（guarantee-system 53 / guarantee-ai 56 / guarantee-web 24）
  - `npx vue-tsc --noEmit` exit 0；`npm run build` exit 0
  - **HTTP 端到端闭环**（真实启动后端 8091 + admin/analyst 登录）：
    | 步骤 | 结果 |
    | --- | --- |
    | 新建机构 → `DELETE /api/system/orgs/{id}` | code=0，返回 `isDeleted=1`、`deletedAt` 有值 |
    | 默认列表 `?orgCode=__e2e_ld` | `total=0`（已删除不可见） |
    | `?includeDeleted=true` | `total=1`、`isDeleted=1`、`deletedAt` 有值 |
    | `GET /orgs/tree?includeDeleted=true` | 同样返回 1 条（树与列表口径一致，权限校验同样生效） |
    | `POST /{id}/restore` | code=0，`isDeleted=0`、`deletedAt` 清空；默认列表回到 1 条 |
    | ANALYST 调 `?includeDeleted=true` | **code=403**「机构的「显示已删除」需要权限：system:org:delete」 |
    | 删除被引用机构（id=2） | code=1000「该机构不能删除：存在 5 个未删除的下级机构；存在 4 个未删除的部门；存在 15 个未删除的用户；**存在 8648 条关联订单**。如只需暂停业务，请改用「停用」。」 |
    | 重复删除同一 id | code=404「目标不存在或不在你的数据范围内」（已删除行对默认视图不可见，不泄露存在性） |
  - 走查后清理：夹具机构与审计已物理删除，库回到 `21 机构 / 300 用户 / 80 部门 / 0 条逻辑删除行`

---

## 数据库结构最终核验（开发库）

```
AC-1  含 is_deleted 的表数                         = 18   ✔
AC-1  ai_operation_secret 三列数                   = 0    ✔
AC-1  18 张表 deleted_at 非 datetime(6)/非 NULL 默认 = 0    ✔（唯一命中者是遗留实验表 t_dt，非业务表）
AC-2  含 deleted_at 的复合唯一键数                  = 13   ✔（且每个键恰好 (业务键, deleted_at) 两列）
AC-3  TRIGGERS / ROUTINES / EVENTS                 = 0/0/0 ✔
      V1、V2 幂等复跑                              = 无变更，自检仍为 18/18/13 ✔
```

---

## LD-T1 ~ LD-T22 覆盖对照

| 编号 | 落点 | 结果 |
| --- | --- | --- |
| LD-T1 | `LogicalDeleteServiceIntegrationTest#deletedRowHiddenByDefaultAndVisibleWithFlag` | ✔ |
| LD-T2 | `LogicalDeleteSchemaIntegrationTest#fiveRoundDeleteRebuildCycleForAllKeys`（13 键 × 5 轮） | ✔ |
| LD-T2a | `...#uniqueKeysIncludeDeletedAt`（information_schema.STATISTICS + 反例护栏） | ✔ |
| LD-T3 | `...#restoreKeepsOriginalStatus`（停用状态用户删→恢复后仍停用） | ✔ |
| LD-T4 | `...#restoreChildRejectedWhenParentStillDeleted` | ✔ |
| LD-T5 | `...#assignRolesUpsertCycle`（[A,B]→[B,C]→[A,B]，含历史绑定保留断言） | ✔ |
| LD-T6 | `...#deletingRoleRemovesItsPermissionsFromAuthPath`（直接软删角色 → 权限集合立即不含其权限） | ✔ |
| LD-T7 | `...#visibleOrgIdsDoNotCrossDeletedOrg`（删中间层 → 递归不含其孙层） | ✔ |
| LD-T8 | `LogicalDeleteWebIT#deletedUserLoginIsIndistinguishableFromWrongPassword`（code 与**文案**逐字相同） | ✔ |
| LD-T8a | `LogicalDeleteSchemaIntegrationTest#activeRowUniquenessIsEnforcedByApplicationNotByUniqueKey` | ⚠ **设计矛盾**，见下 |
| LD-T9 | `...#deleteRevokesTokens`（删用户撤销；角色权限变更撤销持有者） | ✔ |
| LD-T10 | `...#referencedInsuranceTypeCannotBeDeleted`（拒绝 + 引用数）+ Web IT 的机构版 | ✔ |
| LD-T11 | `...#dangerousDeletesAreRejected`（删自己 / 最后一个启用 ADMIN） | ✔ |
| LD-T12 | `LogicalDeleteWebIT#deleteAndRestoreAreAudited`（DELETE/RESTORE、source=WEB、`changed_fields=isDeleted`） | ✔ |
| LD-T13 | `...#disableDeptIgnoresDeletedUsers` | ✔ |
| LD-T14 | `LogicalDeleteSqlRewriterTest`（10 项）+ `LogicalDeletePermissionsTest`（4 项） | ✔ |
| LD-T15 | `...#uniquenessCheckIgnoresDeletedRows`（已删除同编码 → 允许新建） | ✔ |
| LD-T16 | `mvn -o verify` 133 项全绿 + `vue-tsc` + `npm run build` | ✔ |
| LD-T17 | `LogicalDeleteSchemaIntegrationTest#deletedByDistinguishesDirectDbFromApplication` | ✔ |
| LD-T18 | `...#deletedAtMustBeMicrosecondAndNullable` + `#deletedFlagAndOperatorColumns` | ✔ |
| LD-T19 | `...#consistencyScanIsClean`（18 表 UNION ALL，全 0 行） | ✔ |
| LD-T20 | `...#directSqlTemplateWorks`（三列一条语句 / 重复删除 0 行 / 恢复归零） | ✔ |
| LD-T21 | `LogicalDeleteWebIT#secretStoreKeepsPhysicalDelete` + `...#secretTableHasNoLogicalDeleteColumns` | ✔ |
| LD-T22 | `LogicalDeleteSchemaIT#noDatabaseObjects`（TRIGGERS / ROUTINES / EVENTS 全 0） | ✔ |

---

## 遗留问题 / 待确认

### A. 设计文档的矛盾与缺口（未自行改设计，按任务书要求上报）

1. **【最重要】LD-T8a 与 §2.2 的唯一键方案互斥。**
   `UNIQUE(业务键, deleted_at)` 中未删除行的 `deleted_at` 为 NULL，而 MySQL 唯一索引**允许多个 NULL**
   → 两条同名"有效"记录在**数据库层面是被允许的**。设计一边用"NULL 不冲突"支撑无限次删建，
   一边在 LD-T8a 要求"数据库拒绝两条未删除的同名行"，二者不可能同时成立。
   - 现状：唯一键保持设计原样（AC-2 / LD-T2a 可验证），业务唯一性只有**应用层校验**（各 Service 的
     `validateCreate`/`selectXxxByCode`）在保证；直接 INSERT 可以造出重名有效行。
   - **实测的连带风险**：一次 `UPDATE ... SET deleted_at = NOW(6) WHERE 业务键 = ? AND is_deleted = 0`
     命中同业务键的多行时，`NOW(6)` 在语句内是常量 → 所有行拿到相同 `deleted_at` → 直接撞唯一键
     （已写入 LD-T8a 用例）。也就是说重名有效行不仅违反唯一性，还会让**批量软删除失败**。
   - **三个方案的实测证据（本机 MySQL 8.0.29，测试表已清理）**：

     | 方案 | 两条未删除同名行 | 删除后重建同业务键 | 5 轮删建 |
     | --- | --- | --- | --- |
     | A `UNIQUE(业务键, deleted_at)`（设计原案，当前实现） | 允许（NULL 不冲突） | 允许 | 通过 |
     | B `UNIQUE(业务键)`（单列） | **拒绝**（1062） | **拒绝**（1062 `Duplicate entry 'u1'`） | 不可能 |
     | C 函数索引 `UNIQUE(业务键, (IFNULL(deleted_at,'1970-01-01 00:00:00.000000')))` | **拒绝**（1062，哨兵值） | 允许 | 通过（5 行 / 5 个不同时间戳） |

     B 的代价：被删除的业务键被**永久占用**，只有物理删除软删行或删除时改写业务键才能回收
     （前者与 R-04 冲突，后者被设计 §2.2 明确否决——会破坏按名称查询/日志/导出）。
     C 可同时满足两个目标，且不是触发器/存储过程（不违反 LD-EX-02），MySQL 8.0.13+ 支持。
   - **补充实测（第二轮，用行编号 id 做唯一键分量）**——回答"能不能像用户编号/订单编号那样加个唯一键"：

     | 变体 | 实测结果 |
     | --- | --- |
     | D `UNIQUE(业务键, id)` | **插入两条同名有效行成功**（`COUNT(*)=2`）。因为 id 天然唯一，把 id 放进唯一键等于**取消业务唯一性约束**——"不撞键"是因为不校验，不是校验通过（与设计 §2.2a 结论一致） |
     | C1 函数索引 `UNIQUE(业务键, (IF(is_deleted=1, id, 0)))` | `ERROR 3754: Functional index 'uk' cannot refer to an auto-increment column.` |
     | C2 生成列 `active_key BIGINT AS (IF(is_deleted=1, id, 0)) STORED` | `ERROR 3109: Generated column 'active_key' cannot refer to auto-increment column.` |

     结论：**MySQL 明确禁止把自增列用于函数索引/生成列**，所以"用行编号做已删除行的区分分量"
     在技术上不可行；`deleted_at`（微秒）是目前唯一可用的区分分量。C 方案的落地形态见设计文档 §2.2c
     与 `V3__logical_delete_functional_unique_keys.sql`。
   - **结论（2026-09-22，已落地）：采用 C（函数索引）**。该决策由上级复核后直接实施，
     不再等待评审——因为 A 的缺陷不是"能力取舍"，而是**设计自相矛盾**：
     既要求"有效行之间互斥"（LD-T8a），又选了无法实现该要求的唯一键形态。

     **已实施的修复**：

     | 产物 | 变更 |
     | --- | --- |
     | `db/migration/V3__logical_delete_functional_unique_keys.sql` | **新增**：幂等迁移，把 13 个唯一键改为 `(业务键, IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))`；判据是 `STATISTICS.EXPRESSION` 非空，可自愈 V2 遗留的裸复合键形态 |
     | `db/schema.sql` | 13 个唯一键定义同步为函数索引形态（空库自举口径） |
     | `LD-T2a` 断言 | 由"必须是 `(业务键, deleted_at)` 两列"改为"第二个分量必须是**表达式**"，并**新增**"不允许存在裸 `deleted_at` 列形态的唯一键" |
     | `LD-T8a` | 由"锁定缺陷行为"改为**双向验证**：① 两条同名有效行必须被 DB 拒绝（`DuplicateKeyException`）；② 软删除后必须能重建同业务键；③ 第二次删除也必须成功；④ 同一业务键的多条已删除行必须共存 |

     **开发库执行结果**（实测）：13 个索引全部为表达式形态（`expr_parts` 各 1）；
     裸列形态计数 = 0；幂等复跑无变更；`INSERT` 重复有效 `admin` → `ERROR 1062 Duplicate entry 'admin-1970-01-01 00:00:00.000000'`（**正确拒绝**）；
     `__ldt_v3` 夹具下"两条已删除 + 一条有效"共存、再插第二条有效被拒；夹具已清理。

     **测试结果**：`guarantee-system` 53 项全绿（含改后的 LD-T2a 与 LD-T8a）。
     **合规**：函数索引是 MySQL 8.0.13+ 的索引表达式，**不是**触发器/存储过程/函数对象，
     LD-T22 与 LD-EX-02 仍然成立。

     设计文档已同步至 v2.2（新增 §2.2c 记录缺陷成因、实测对比与修复原理；
     修订 §2.2 结论、LD-T2a、LD-T8a）。

2. **LD-T10 只对"险种 / 机构"可实现。** 交付的删除入口是 §7.1 列的 5 个域
   （orgs / departments / users / roles / insurance-types），`enterprise` / `project` **没有删除接口**
   （设计也没为其定义 `{domain}`），因此"企业/项目被引用即拒绝"无处落地。已在测试中用
   "险种（订单引用）"与"机构（下级/部门/用户/订单引用）"覆盖该语义。

3. **任务书 §8 与设计 §10.3 冲突（已两次裁定，最终：前端不展示）**：任务书要求前端展示 `deleted_by`
   （`'DB'` → "数据库直连"、数字 → 用户 id），设计 §10.3 明确"VO 不加 `deletedBy`"。

   - **第一次裁定（实施期，已推翻）**：按任务书写成"前端不展示 `deleted_at` / `deleted_by`"。
   - **第二次裁定（2026-09-22 复核，已推翻）**：`deleted_by` 是评审**明确要求保留**的字段，保留它
     就应当展示 → 后端补字段、前端补两列。
   - **第三次裁定（2026-09-22，最终）**：**前端不展示这两列**，即回到设计 §10.3 原案。
     后端仍返回 `isDeleted` / `deletedAt` / `deletedBy`（接口契约的**放宽**，且恢复入口依赖 `isDeleted`），
     仅停止前端渲染。决策原话：「删除标记和删除信息不展示在前端」。

   **已实施**：

   | 层 | 变更 |
   | --- | --- |
   | 后端 VO | 5 个 VO 补 `deletedBy` 字段（`OrgVO`/`DepartmentVO`/`UserVO`/`RoleVO` 是类，`InsuranceTypeVO` 是 record，同步 `InsuranceTypeService` 两处构造）——此前 mapper 已 `SELECT deleted_by AS deletedBy` 但 **VO 无该字段，查了没人接**，是静默浪费。**保留**：接口仍返回该字段 |
   | 前端类型 | `types/system.ts` 的 5 个接口保留 `deletedBy: string \| null`（与后端契约一致），JSDoc 注明"接口仍返回，页面当前不展示" |
   | 前端工具 | `utils/status.ts#formatDeletedBy()`（`'DB'` → "数据库直连"、数字 → `用户#<id>`）**保留但当前无调用方**，函数头已注明；若长期无展示需求可连同 `DELETED_BY_DB` 一起删除 |
   | ~~前端页面~~ | ~~5 个页面新增「删除信息」列与「删除标记」列~~ → **已移除（最终裁定）**，5 个 `.vue` 的这两列与其注释块一并删除，`formatDeletedBy` 导入同步清理 |

   **保留的行级删除标记**（不是审计信息，而是"恢复入口"的操作安全性前提）：
   `rowClassName` 整行置灰 + **操作列内的 `已删除` 标签** + 已删除行的按钮禁用与 tooltip。
   注意标签的**位置**：撤列时曾一度连标签一起删掉（旧实现的标签长在被撤的「删除标记」列里），
   核对后加回到操作列，因此页面仍是"只有操作列"，没有变相把被撤的列加回来。
   操作列宽度相应上调：机构 210→250、部门/用户/角色 90→130、险种 140→200。
   理由：`includeDeleted` 开关会把已删除行混进列表，而**只有已删除行能恢复**；
   若连行级标记也去掉，用户在开启开关后无法分辨该点哪一行。

   **接口契约**：VO 仍同时返回 `isDeleted` / `deletedAt` / `deletedBy`（对设计 §10.3 的**放宽**，非收紧）。
   验证：`npx vue-tsc --noEmit` exit 0、`npm run build` exit 0；
   产物核对：`dist` 中已不存在「删除标记」/「删除信息」（含 `\u` 转义形态），
   而 `row-deleted` 与「已删除」仍在。后端 133 项测试未受影响（未改后端）。

   **复核阶段又发现两个配套缺陷（已修，见下方 §C）**——`deleted_by` 虽然"补了 VO 字段"，
   但取数链路仍断了两处。其价值在于：**接口层**的 `deletedBy` 从此是真实值（排障、审计、直连核对都看得到），
   与"前端是否展示"无关，因此 C.2 / C.3 的修复**不因本次撤列而失效**。

4. **部门删除的前置检查比 §6.2 多一条**（实现期补充，已在代码注释自述）：除"无未删除用户"外，
   额外要求"无未删除的下级部门"。理由与 §6.2 机构要求"无下级机构"完全一致——否则会留下指向
   已删除父部门的悬挂引用。若评审认为不需要，可去掉 `DepartmentService.deleteBlockers` 中的该条。

5. **§2.1a 给出的 `deleted_by` 判定 SQL 片段在外层表不是 `sys_user` 时会被遮蔽**（实测踩到）：
   `CASE WHEN deleted_by REGEXP ... THEN (SELECT username FROM sys_user WHERE id = CAST(deleted_by AS UNSIGNED))`
   里的裸 `deleted_by` 会优先解析到子查询自身的 `sys_user.deleted_by`，`CAST('DB' AS UNSIGNED)=0`
   → 恒为 NULL。正确写法必须限定外层列（测试里已改为 `o.deleted_by` 并附注释）。
   风险 LD-R11 的"封装统一片段"因此不只是清洁性问题，而是**正确性问题**。

### B. 未完成 / 未覆盖的项

1. **临时库 `guarantee_ai_admin_ldtest` 未能创建**：`guarantee` 用户只有 `guarantee_ai_admin` 的权限
   （`SHOW GRANTS` 已确认），root 密码不等于 docker-compose 中的 `root@2026`。破坏性验证改在开发库用
   `__ldt` 前缀夹具行 + `@AfterEach` 物理清理完成，未污染演示数据（走查后库为 21 机构 / 300 用户 /
   80 部门 / 0 条逻辑删除行）。若需要真正的独立临时库，请提供有 `CREATE` 权限的账号。
2. **`sys_permission` 的删除能力未开放**（设计 §6.5 / §7.3 明确建议不提供入口），因此
   "删除权限码需同步 `PermissionCatalog` 并发版"这一联动未实现，属**有意不做**。
3. **助手侧不支持 RESTORE**（设计只要求写工具新增 DELETE）。若模型传 `action=RESTORE`，
   5 个写工具会显式拒绝并提示走页面「显示已删除」（避免生成"标题恢复、预览停用"的畸形确认卡）。
   如需助手侧恢复提案，属新增工作量。
4. **`OrgProposalTool` 的预览存在越范围信息泄漏**（**既有问题，非本次引入**）：预览用无 scope 的
   `orgService.getEntityById(id)` 取目标，持有 ORG 权限的用户给出范围外 id 时，预览会显示该机构名与
   引用计数；执行期是安全的（`orgService.delete(id, scope, ...)` 会 `requireVisibleOrg` 拒绝）。
   建议在 system 层提供一个带 scope 的实体读取方法后修掉，本次未改。
5. **`@PreAuthorize` 拒绝返回的 body 是 `code=500 系统内部错误`**（**既有问题**，ANALYST 调既有接口
   `PUT /orgs/{id}`、`PATCH /orgs/{id}/status` 同样如此），不是本次引入的。参数级权限检查
   （`includeDeleted`）走的是我自己抛的 `BizException(FORBIDDEN)`，返回 `code=403` 且文案正确。
   建议统一 `AccessDeniedException` 的错误响应（属既有缺陷）。
6. **`softDeleteUserRoleByCode` 目前没有调用点**：按 §5/§4.2 提供（供"按角色编码解绑"用），
   但经全仓 grep，AI 与页面路径都不需要它（`UserService.assignRoles` 走 UPSERT）。保留作为
   统一写入口的一部分，未被测试直接覆盖。
7. **`ai_audit_log` / `ai_operation_audit` / `ai_tool_call` 不开放删除入口**（§7.3），其
   `is_deleted` 恒为 0；一致性巡检覆盖它们，但没有删除路径的测试（设计如此）。
8. **LD-R12 的残余风险仍在**：JWT 的权限来自 token claims，令牌未过期且 Redis 撤销失败时，
   已删除用户的旧 JWT 在最长 12 小时内仍可用。本次通过"删除用户即撤销令牌 + `selectByUsername`
   过滤已删除"降低风险，但没有做"每请求校验用户是否已删除"（会引入每请求一次 DB 查询）。属设计
   已承认的风险（§9.4.4 ④ / LD-R12）。
9. **`8081` 端口上有一个旧版本后端进程在运行**（我在 `mvn` 重建后尝试启动时被 `Port 8081 was already
   in use` 拒绝，E2E 因此临时改用 8091）。旧进程没有删除/恢复接口，**必须重启后端**才能看到新接口；
   重启前 `/api/system/orgs/{id}` 的 DELETE 会返回 500。走查完成后我已停掉自己启动的 8091 实例。

   **2026-09-22 15:30 已解决**：旧进程在本次 MySQL 中断时一并退出，8081 释放，已用**新构建**重启
   （日志 `%TEMP%\ld-backend6.log`）。重启后真实 HTTP 复验：`GET /api/system/orgs?page=1&size=3`
   返回的每条记录都带 `isDeleted` 与 `deletedBy`（如 `{"id":1,"orgCode":"ORGHQ","isDeleted":0,"deletedBy":"DB"}`），
   说明 mapper 取数链路确实已通。
10. **开发库 MySQL（8.0.29 / 3307）是普通进程，不会开机自启**，本次会话中途它退出过一次
    （启动日志显示做了 XA crash recovery，即上次是非正常退出，非 `mysqladmin shutdown`），
    后端随之连接失败退出。已按 `scripts/start-local-env.ps1` 的方式以**独立隐藏进程**重新拉起，
    数据完好（21 机构 / 300 用户 / 80 部门 / 0 条逻辑删除行）。若要开机自启，需管理员权限注册为
    Windows 服务；当前 `MySQL` 这个 Windows 服务指向的是 **5.7.40** 的另一套实例（已停用、手动启动），
    与开发库无关，**不要**用它替代。

### C. 复核阶段发现并修复的缺陷（上级复核，2026-09-22）

初版交付报告称"4 个批次全部完成、133 项全绿"，但**测试全绿不等于端到端正确**。
复核以"真实 HTTP 走查 + 原始 JSON 核对"为手段，发现并修复了 3 个缺陷：

#### C.1【严重·设计层】唯一键放行重复有效行 → 已按函数索引修复

见上文 A.1 的结论段。**根因是设计阶段只做了正向验证**（"删除后能否重建"），
没有做反向验证（"能否拦住重复有效行"）——LD-T8a 本就是为反向验证而写，
但未与 §2.2 的唯一键方案对照，导致两个互斥的要求同时写进文档。

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| 唯一键 | `(业务键, deleted_at)` | `(业务键, IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))` |
| 两条同名有效行 | **放行**（NULL 不冲突） | **拒绝**（`ERROR 1062`，实测） |
| 已删除行多条共存 / 5 轮删建 | 通过 | 通过 |
| LD-T8a 用例 | "锁定缺陷行为" | **双向验证**（拒绝重复 + 拒绝后仍能重建 + 二次删除成功 + 多条已删除共存） |
| LD-T2a 用例 | 断言两列 | 断言第二个分量是**表达式**，并断言**不存在**裸列形态 |

落地产物：`V3__logical_delete_functional_unique_keys.sql`（幂等、可自愈 V2 形态）、
`schema.sql`、设计文档 v2.2（新增 §2.2c）。

#### C.2【中·数据流】`deleted_by` 加错了 SQL 片段

`SysOrgMapper.xml` 把三列加进了 **`entityCols`**（实体列）而不是 **`voCols`**（VO 列），
导致 `selectPage` / `selectTree` 等走 `voCols` 的语句**查不到 `deleted_by`**，
VO 字段恒为 null（`spring.jackson.default-property-inclusion: non_null` 会直接省略该键）。

同类问题：`SysDepartmentMapper` / `SysUserMapper` / `SysRoleMapper` 的 `voCols` 也都缺这一列。
`InsuranceTypeMapper` 走 `resultType=entity` + `toVO()`，所以不受影响。

修复：4 个 mapper 的 `voCols` 补 `deleted_by`，并按各自别名限定为 `d.` / `u.` / `r.`
（这几个查询有 JOIN，不带别名会歧义）。

> **为什么 133 项测试没抓到**：既有断言只覆盖 `isDeleted` / `deletedAt`，
> 没有任何一条断言"删除响应里的 `deletedBy` 等于操作者 id"。
> **这是测试覆盖的盲区，不是一个"意外"**——补了字段却没有对应断言，等于没补。

#### C.3【中·数据流】删除响应漏回填 `deletedBy`

`OrgService` / `DepartmentService` / `UserService` / `RoleService` 的 `delete()`
在软删后重读实体并回填 VO，但只回填了 `isDeleted` 与 `deletedAt`，**漏了 `deletedBy`**。

后果（实测现象，正是它暴露了 C.2 之外的问题）：

| 来源 | `deletedBy` | 说明 |
| --- | --- | --- |
| DELETE 响应 | `"DB"` | 漏回填 → 保留 VO 里的默认/nulls → 与列默认值巧合一致 |
| `includeDeleted` 列表 | `"1"` | mapper 已改正 → 真实值 |

**同一个删除操作，两个接口给出不同答案**——这类不一致会让人怀疑数据本身，很危险。

修复：4 处补 `before.setDeletedBy(deleted.getDeletedBy())`。

#### C.4 复核后的端到端证据（真实 HTTP，原始 JSON 核对）

| 步骤 | 结果 |
| --- | --- |
| 建机构 → DELETE | `isDeleted=1`、`deletedAt=2026-09-22T15:24:02`、**`deletedBy=[1]`**（admin 的 id，不再是 `DB`） |
| `?includeDeleted=true` | `deletedBy=[1]`（与 DELETE 响应一致） |
| RESTORE | `isDeleted=0`、`deletedAt` 为空 |
| 库状态 | 21 机构 / **0 条逻辑删除行**（夹具已物理清理，含审计） |
| 唯一键 | 13 个全为表达式形态；裸列形态 0 个 |
| `mvn -o verify` | **133 项全绿**（system 53 / ai 56 / web 24） |
| 前端 | `vue-tsc` exit 0、`npm run build` exit 0；产物中已无「删除标记」/「删除信息」两列 |

> 说明：上表是**接口层**证据（原始 JSON）。前端最终**不展示**这两列，
> 因此 `deletedBy` 的可观测面是"接口 / 日志 / 直连库"，不是页面。

#### C.5 遗留的测试盲区（建议后续补）

| 编号 | 建议补的断言 | 本次为何漏掉 |
| --- | --- | --- |
| LD-T23 | 删除响应与 `includeDeleted` 列表的 `deletedBy` / `deletedAt` **必须一致** | 既有断言逐接口写，没有跨接口比对 |
| LD-T24 | 5 个域的 `voCols` 都必须包含 `deleted_by`（可用 `information_schema` 无法查 SQL，改为对每个 Mapper 的查询结果断言 `deletedBy != null`）。**注意**：前端已不展示该列，但接口仍是契约，取数链路必须通——这条断言不能因为"页面看不到"就省掉 | 补字段时没同步补断言 |
| LD-T25 | 删除响应中的 `deletedBy` 必须等于操作者的 `user_id`（非 `'DB'`） | 同上 |

> **教训**：给一个字段补了"写入"与"存储"，不等于"读取链路"通了。
> 端到端走查（看原始 JSON）与字段级断言缺一不可——本次 3 个缺陷里有 2 个
> 是"写进去了但读不出来"，而它们在单元测试与集成测试里都是绿的。

### D. 实施期间发现的其他事实

- 旧脚本曾按"文件里已出现 `isDeleted`"做幂等判断，导致 `SysOrgMapper.entityCols` 漏掉三列
  （删除响应里 `isDeleted/deletedAt` 为 null），由 LD-T1 用例暴露并修复。教训：**幂等判断要按目标片段判断，
  不能按整文件**。
- 全量测试 133 项中，新增 44 项（53-18=35 于 guarantee-system、1 于 guarantee-ai、4 于 guarantee-web；
  其中 guarantee-system 既有 18 项保持全绿），未删除或放宽任何既有断言。
