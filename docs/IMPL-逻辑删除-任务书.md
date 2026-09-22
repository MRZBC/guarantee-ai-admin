# 实施任务书：逻辑删除（`is_deleted`）落地

| 项目 | 内容 |
| --- | --- |
| 依据文档 | `docs/DEC-逻辑删除设计方案.md`（**v2.1，唯一设计依据**） |
| 交付范围 | 批次 1 ~ 批次 4（**全量**，含删除/恢复能力） |
| 目标数据库 | `guarantee_ai_admin` @ `127.0.0.1:3307`，用户 `guarantee` / 密码 `guarantee@2026` |
| 模块范围 | `guarantee-system`、`guarantee-ai`、`guarantee-web`、`guarantee-order`、`guarantee-analysis`、`frontend` |
| 预估工作量 | 17 人日 |
| 绝对禁止 | 存储过程 / 触发器 / 函数（LD-EX-02）；把主键改成雪花 ID（LD-Q11，单独立项）；对 `ai_operation_secret` 加逻辑删除字段（LD-EX-01） |

---

## 1. 前置动作（**必须最先做，不可跳过**）

### 1.1 数据库备份

```powershell
# 备份当前开发库（唯一键改造不可逆，必须先快照）
& "D:\environment\mysql-8.0.29-winx64\bin\mysqldump.exe" -h 127.0.0.1 -P 3307 `
  -u guarantee "-pguarantee@2026" --single-transaction --routines=false --triggers=false `
  guarantee_ai_admin > "$env:TEMP\guarantee_backup_before_ld.sql"
```

备份文件大小应与数据量匹配（15 万订单，预计 60~80 MB）。**若备份失败或明显过小，停止实施。**

### 1.2 唯一键改造前的数据校验（13 个键逐个执行）

```sql
-- 任一语句返回行 → 存量数据已有重复业务键，必须先清理再改唯一键
SELECT username,    COUNT(*) c FROM sys_user         GROUP BY username    HAVING c > 1;
SELECT org_code,    COUNT(*) c FROM sys_org          GROUP BY org_code    HAVING c > 1;
SELECT dept_code,   COUNT(*) c FROM sys_department   GROUP BY dept_code   HAVING c > 1;
SELECT role_code,   COUNT(*) c FROM sys_role         GROUP BY role_code   HAVING c > 1;
SELECT perm_code,   COUNT(*) c FROM sys_permission   GROUP BY perm_code   HAVING c > 1;
SELECT type_code,   COUNT(*) c FROM insurance_type   GROUP BY type_code   HAVING c > 1;
SELECT ent_code,    COUNT(*) c FROM enterprise       GROUP BY ent_code    HAVING c > 1;
SELECT credit_code, COUNT(*) c FROM enterprise       GROUP BY credit_code HAVING c > 1;
SELECT project_code,COUNT(*) c FROM project          GROUP BY project_code HAVING c > 1;
SELECT order_no,    COUNT(*) c FROM tender_order     GROUP BY order_no    HAVING c > 1;
SELECT order_no,    COUNT(*) c FROM performance_order GROUP BY order_no   HAVING c > 1;
SELECT conversation_no, COUNT(*) c FROM ai_conversation GROUP BY conversation_no HAVING c > 1;
SELECT proposal_no, COUNT(*) c FROM ai_operation_proposal GROUP BY proposal_no HAVING c > 1;
```

预期全部返回 0 行。**若返回行，先停下来报告，不要自行清理数据。**

### 1.3 建立当前行为基线

在改造前先跑一遍，用于改造后对比：

```powershell
mvn -o -q -DskipTests install
mvn -o verify          # 当前基线：93 项测试全绿
cd frontend; npx vue-tsc --noEmit; npm run build
```

**基线必须全绿才开始改造。** 若基线本身有失败，先报告。

---

## 2. 字段定义（18 张表，严格照抄）

```sql
is_deleted  TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除 0正常 1已删除',
deleted_at  DATETIME(6) NULL     DEFAULT NULL   COMMENT '删除时间（微秒精度，唯一键分量）',
deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB'   COMMENT '删除人：应用写 sys_user.id，直连为 DB'
```

**三个必须记住的硬约束**（都是真机实测出来的，不是推理）：

| # | 约束 | 违反后果 |
| --- | --- | --- |
| 1 | `deleted_at` **必须** `DATETIME(6)` **且 `DEFAULT NULL`** | 秒精度 → 同一秒内重复删建撞唯一键；加 `DEFAULT CURRENT_TIMESTAMP(6)` → **有效行 `deleted_at` 非 NULL，绕过唯一键，多个同名有效账号共存** |
| 2 | `deleted_by` **必须** `VARCHAR(64) NOT NULL DEFAULT 'DB'` | 用 `BIGINT` 无法表达"直连删除"，`DEFAULT 0` 会把"未知"伪装成"已知用户" |
| 3 | 删除时**三列必须在一条语句里写全** | 只写一半 → 唯一键判定错乱，且现象与原因看起来无关，极难排查 |

**不加字段的表**：`ai_operation_secret`（LD-EX-01，保持物理删除）。

---

## 3. 唯一键改造（13 个键，逐一执行）

**改造前必须先加列**（`deleted_at` 是唯一键的分量）。模板：

```sql
ALTER TABLE sys_user
    ADD COLUMN is_deleted TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除' AFTER status,
    ADD COLUMN deleted_at DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    ADD INDEX idx_sys_user_deleted (is_deleted);

ALTER TABLE sys_user DROP INDEX uk_sys_user_username;
ALTER TABLE sys_user ADD UNIQUE KEY uk_sys_user_username (username, deleted_at);
```

| # | 表 | 新唯一键 |
| --- | --- | --- |
| 1 | `sys_user` | `uk_sys_user_username (username, deleted_at)` |
| 2 | `sys_org` | `uk_sys_org_code (org_code, deleted_at)` |
| 3 | `sys_department` | `uk_sys_dept_code (dept_code, deleted_at)` |
| 4 | `sys_role` | `uk_sys_role_code (role_code, deleted_at)` |
| 5 | `sys_permission` | `uk_sys_perm_code (perm_code, deleted_at)` |
| 6 | `insurance_type` | `uk_insurance_type_code (type_code, deleted_at)` |
| 7 | `enterprise` | `uk_enterprise_code (ent_code, deleted_at)` |
| 8 | `enterprise` | `uk_enterprise_credit (credit_code, deleted_at)` |
| 9 | `project` | `uk_project_code (project_code, deleted_at)` |
| 10 | `tender_order` | `uk_tender_order_no (order_no, deleted_at)` |
| 11 | `performance_order` | `uk_perf_order_no (order_no, deleted_at)` |
| 12 | `ai_conversation` | `uk_ai_conv_no (conversation_no, deleted_at)` |
| 13 | `ai_operation_proposal` | `uk_proposal_no (proposal_no, deleted_at)` |

**不改造**：`sys_user_role(user_id, role_id)`、`sys_role_permission(role_id, permission_id)`（改用 UPSERT，见 §5）、`ai_operation_secret`、`ai_operation_audit`（其唯一键是复合主键，非业务键）。

### 3.1 迁移脚本的落地方式（重要工程约束）

`guarantee-web/src/main/resources/db/schema.sql` **全是 `CREATE TABLE IF NOT EXISTS`**，
对**存量表不会生效**。因此：

| 产物 | 要求 |
| --- | --- |
| `schema.sql` | 同步更新 `CREATE TABLE` 定义（供**空库**自举） |
| **新增** `guarantee-web/src/main/resources/db/migration/V1__logical_delete.sql` | **幂等** `ALTER`：先查 `information_schema.COLUMNS` / `STATISTICS` 判断列与索引是否已存在，再执行。供**存量库**升级 |
| 执行一次迁移脚本 | 手工执行（`mysql < V1__logical_delete.sql`），并记录执行结果 |

---

## 4. 查询过滤（§5 of 设计文档）

### 4.1 MyBatis 拦截器（首选）

新增 `LogicalDeleteInnerInterceptor`：

| 项 | 要求 |
| --- | --- |
| 拦截点 | `StatementHandler#prepare`，只处理 `SELECT` |
| 行为 | 解析 `FROM` / `JOIN`，为受管表注入 `别名.is_deleted = 0` |
| 受管表 | 18 张表，集中在 `LogicalDeleteTables` 常量类 |
| 豁免 | Mapper 方法名以 `IncludingDeleted` 结尾时不注入 |
| 已含条件 | SQL 中已显式出现 `is_deleted` 时跳过，避免重复条件 |
| **解析失败** | **必须抛异常，绝不静默放行**——静默放行会让已删除数据泄漏 |
| 开关 | 配置项 `guarantee.logical-delete.enabled`（默认 true），便于批次 3 出问题时快速关闭 |
| 性能 | 用 `ConcurrentHashMap` 缓存已解析的 SQL 模板 |

**风险提示**：`guarantee-analysis` 的聚合 SQL（`OverviewMapper`、`OrderAnalysisMapper` 等）
含 `GROUP BY` + 多表 `JOIN`，拦截器注入位置易错。**建议分析模块不依赖拦截器，改为手工显式加过滤**，
并逐个测试。

### 4.2 必须显式加过滤的场景（拦截器盖不住）

| 场景 | 条件 |
| --- | --- |
| 停用前置检查（部门下有启用用户） | `status = 1 AND is_deleted = 0`（LD-03） |
| 危险动作保护（最后一个 ADMIN） | 排除已删除的用户与**已删除的**角色绑定 |
| 唯一性校验（新增时查重） | `AND is_deleted = 0`，否则会被已删除的同名记录误报"已存在" |
| **数据范围递归 CTE**（`selectVisibleOrgIds`） | 起手与递归都要带 `is_deleted = 0`。**漏加 = 越权**（范围会穿过已删除机构继续展开） |
| 脏数据巡检 | 显式 `is_deleted = 1` |

### 4.3 鉴权路径（**最高风险，漏加 = 提权**）

以下 9 处必须加 `is_deleted = 0`：

| Mapper 方法 | 影响 |
| --- | --- |
| `SysUserMapper.listPermissionCodesByUserId` | **决定用户权限集合；漏加 → 被删除的角色权限仍签发进 JWT** |
| `SysUserMapper.listRoleCodesByUserId` | 角色判定 |
| `SysUserMapper.selectRoleRefsByUserIds` | 角色回填 |
| `SysUserMapper.countOtherEnabledAdmins` | 危险动作保护 |
| `SysUserMapper.countEnabledUsersByRoleCode` | 统计 |
| `SysUserMapper.listRoleIdsByUserId` | 角色 id 列表 |
| `SysRoleMapper.selectPermissionRefsByRoleIds` | 权限回填 |
| `SysRoleMapper.countUsersByRoleIds` | 用户数统计 |
| `SysRoleMapper.selectUserIdsByRoleCode` | 令牌撤销范围 |

---

## 5. 关联表改造：先清后插 → UPSERT（§4 of 设计文档）

**漏了会在"重新分配同一角色"时直接失败**（唯一键拒绝 INSERT）：

```xml
<!-- 把不在目标集合中的绑定置为已删除 -->
<update id="softDeleteUserRolesNotIn">
    UPDATE sys_user_role
    SET is_deleted = 1, deleted_at = NOW(6), deleted_by = #{operatorId}
    WHERE user_id = #{userId} AND is_deleted = 0
    <if test="roleIds != null and roleIds.size() > 0">
      AND role_id NOT IN <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">#{roleId}</foreach>
    </if>
</update>

<!-- 命中唯一键则恢复，否则新建 -->
<insert id="upsertUserRoles">
    INSERT INTO sys_user_role (user_id, role_id, is_deleted) VALUES
    <foreach collection="roleIds" item="roleId" separator=",">(#{userId}, #{roleId}, 0)</foreach>
    ON DUPLICATE KEY UPDATE is_deleted = 0, deleted_at = NULL, deleted_by = 'DB'
</insert>
```

`sys_role_permission` 同构。两条语句**顺序无关**，比原来的"先清后插"更稳健。

---

## 6. 运行期语义（§6 of 设计文档）

| 编号 | 要求 |
| --- | --- |
| LD-02 | 删除**不隐式修改 `status`**（否则恢复时无法还原原状态） |
| LD-03 | 停用前置检查只统计 `is_deleted = 0` 的行 |
| LD-04 | 删除可恢复，二者成对提供 |
| LD-04a | 恢复必须重做删除时的全部前置校验（父未恢复 → 拒绝子恢复） |
| LD-04b | 列表提供「显示已删除」开关，否则用户会认为数据丢了。**（2026-09-22 后续评审撤销）** 页面不要该功能：5 个系统管理页的开关与「恢复」入口**全部撤除**，后端 `restore` 接口与 `includeDeleted` 参数保留、仅页面不暴露。删除确认弹窗中原有的"可恢复"承诺同步删除，改为引导到「停用」 |
| LD-05 | 已删除用户登录返回**与密码错误完全相同**的提示（`LOGIN_FAILED`，防账号枚举）。**注意 `selectByUsername` 必须加 `is_deleted = 0`**——复合唯一键后同一 username 可能有多行，不能再依赖"用户名唯一所以只返回一行"的原假设 |
| LD-05a | 已停用用户仍返回 `ACCOUNT_DISABLED`（不变） |
| §6.2 | 删除的领域校验**比停用更严格**：被引用即拒绝（险种/企业/项目） |
| §6.3 | 删除用户 / 变更角色权限 → 撤销相关令牌（`UserTokenRevoker`），否则旧 JWT 可用最长 12 小时 |
| §6.4 | 删除/恢复都落 `ai_operation_audit`：`action=DELETE`/`RESTORE`，`source=WEB`/`AI`，敏感字段仍经 `SensitiveFieldMasker` |
| §6.5 | `sys_permission` 删除实质是发版动作，需同步 `PermissionCatalog`；**建议页面上不提供权限删除入口** |

---

## 7. 接口与权限（§7 of 设计文档）

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| `DELETE` | `/api/system/{domain}/{id}` | `system:{domain}:delete` |
| `POST` | `/api/system/{domain}/{id}/restore` | 同上 |
| `GET` | `/api/system/{domain}?includeDeleted=true` | 同上 |

新增 5 个权限码（写入 `PermissionCatalog` 并纳入矩阵）：

| 权限码 | ADMIN | OPERATOR | ANALYST | VIEWER |
| --- | --- | --- | --- | --- |
| `system:org:delete` | ✔ | ✔ | ✘ | ✘ |
| `system:dept:delete` | ✔ | ✔ | ✘ | ✘ |
| `system:user:delete` | ✔ | ✘ | ✘ | ✘ |
| `system:role:delete` | ✔ | ✘ | ✘ | ✘ |
| `system:insurance:delete` | ✔ | ✔ | ✘ | ✘ |

**不提供删除入口**：`ai_audit_log` / `ai_operation_audit` / `ai_tool_call`（SYS-A-04 只增不改不删）、
`sys_permission`（R-04）、`ai_operation_secret`（LD-EX-01）。

### 7.1 AI 助手侧（§7.4）

| 改动 | 要求 |
| --- | --- |
| 5 个 `propose*` 写工具 | `action` 增加 `DELETE`；**必须与 `DISABLE` 区分**，确认卡明确显示"删除后默认不可见，可恢复" |
| 5 个 `ProposalExecutor` | 新增 `DELETE` 分支，**执行期重做校验**（SYS-C-05） |
| `ProposalService.actionName()` | 增加 `DELETE` → "删除"、`RESTORE` → "恢复" |
| `prompts/business-assistant.st` | 补"停用 vs 删除"的区分话术；删除属危险动作需二次确认；被引用时删除会被拒并说明原因 |
| 查询工具 | 新增 `includeDeleted` 参数，默认 false，仅持有 `:delete` 权限时才允许传 true |

---

## 8. 前端（§8 of 设计文档）

| 改动 | 要求 |
| --- | --- |
| 5 个系统管理页 | 操作列新增「删除」（`system:*:delete`）。~~与「恢复」（仅 `includeDeleted` 视图下）~~ → **恢复入口已撤除（2026-09-22 后续评审）** |
| 筛选区 | ~~「显示已删除」开关~~ → **已撤除（2026-09-22 后续评审）**：页面不提供该功能，请求也不再发送 `includeDeleted` |
| 已删除行样式 | ~~整行置灰 + 操作列内 `已删除` 标签~~ → **已撤除（2026-09-22 后续评审）**：已删除记录不再进列表，标记无对象可标。另注：删除时间与删除人**不展示在页面**（回到设计文档 §10.3 口径）；接口仍返回 `isDeleted` / `deletedAt` / `deletedBy`，用于排障与直连核对 |
| 删除确认弹窗 | 必须说明：① 不再出现在默认列表；② ~~可恢复~~ **页面不提供恢复入口，如只是暂停业务请改用「停用」**；③ 被引用时会被拒绝（附具体引用数） |
| 恢复失败 | ~~提示前置条件（如"请先恢复其所属机构"）~~ → **页面已无恢复入口**（后端 `restore` 保留，接口层仍会返回该提示） |
| 危险动作 | 二次确认弹窗（与停用一致） |
| **禁用即带 tooltip** | 沿用 `REQ-系统管理手动操作能力补齐方案.md` §8.2.1 的约定：`disabled` 必须配 `el-tooltip` 说明原因，并注意 `el-tooltip` 对 disabled 按钮不生效、需用 `<span>` 包裹 |

---

## 9. 上线批次与验证（每批独立验证，不可合并）

| 批次 | 内容 | 验证方式 |
| --- | --- | --- |
| **1** | 18 张表加 3 列 + 索引；实体/VO/DTO 加字段；`schema.sql` 与迁移脚本 | 系统行为**必须与基线完全一致**；跑 `mvn verify`（应仍 93 项全绿）+ 前端构建 |
| **2** | 13 唯一键改造；关联表 UPSERT；鉴权路径过滤 | 同上；**额外**在临时库跑"删除→重建同名→再删除→再重建"×5 轮（LD-T2，验证 `DATETIME(6)` 方案） |
| **3** | 查询过滤拦截器 + 显式过滤场景 | 同上；关闭/开启开关各验一次；分析模块聚合 SQL 逐个核对 |
| **4** | 删除/恢复接口 + 权限码 + 前端 + AI 工具与提示词 | 全量测试 + 手工走查删除/恢复闭环 |

**每批结束后必须更新任务进度文件**（见 §11），并报告：新增/修改文件、测试结果、遗留问题。

---

## 10. 测试要求

严格实现 `docs/DEC-逻辑删除设计方案.md` §11 的 **LD-T1 ~ LD-T22**，一条不漏。重点：

| 编号 | 为什么重要 |
| --- | --- |
| LD-T2 / T2a | 验证唯一键方案（微秒精度）与列定义，防止实施偏差 |
| LD-T6 | **鉴权路径**漏加过滤 = 提权漏洞 |
| LD-T7 | **数据范围递归**穿过已删除机构 = 越权 |
| LD-T8 / T8a | 防账号枚举 + 防业务唯一性被削弱 |
| LD-T13 | 停用前置检查口径（LD-03） |
| LD-T18 | **断言 `deleted_at` 必须是 `DATETIME(6)` 且 `DEFAULT NULL`**，防止误加默认值 |
| LD-T19 | 一致性巡检（不用触发器后的唯一兜底） |
| LD-T21 | `ai_operation_secret` 保持物理删除 |
| LD-T22 | **断言库中不存在触发器/存储过程**（LD-EX-02 的回归护栏） |

测试数据库：**新建临时库**（如 `guarantee_ai_admin_ldtest`）做迁移与唯一键循环验证，
避免影响开发库；但迁移脚本本身要在开发库执行（用户已确认）。

---

## 11. 进度与交接

在仓库根目录维护 `docs/IMPL-逻辑删除-进度.md`，每批完成后追加：

```markdown
## 批次 N（日期）
- 状态：完成 / 进行中 / 阻塞
- 新增文件：
- 修改文件：
- 测试结果：（命令 + 通过/失败数）
- 遗留问题 / 待确认：
```

**阻塞时的处理原则**：

| 情况 | 处理 |
| --- | --- |
| 前置校验发现存量重复数据 | **停止，报告**，不自行清理 |
| 唯一键改造失败 | **用 §1.1 的备份恢复**，报告失败语句与报错原文 |
| 拦截器导致 SQL 报错 | 关闭开关 `guarantee.logical-delete.enabled=false`，报告问题 SQL |
| 与设计文档冲突 | **以设计文档为准**；若文档本身有矛盾，报告后再动 |

---

## 12. 交付验收（对齐设计文档 §12 与 AC）

| 编号 | 验收项 |
| --- | --- |
| AC-1 | 18 张表均有 `is_deleted` / `deleted_at(DATETIME(6), DEFAULT NULL)` / `deleted_by(VARCHAR(64), DEFAULT 'DB')`；`ai_operation_secret` 无这三个字段 |
| AC-2 | 13 个唯一键均为 `(业务键, deleted_at)`；`information_schema` 可验证 |
| AC-3 | 库中**不存在**任何触发器 / 存储过程 / 函数 |
| AC-4 | 系统管理 5 个域可删除；列表默认不显示已删除。**（2026-09-22 后续评审修订）** ~~恢复；开关可显示；已删除行有置灰与标签~~ → 页面侧恢复入口、显示已删除开关与已删除行标记均已撤除；后端 `restore` 与 `includeDeleted` 保留并仍受参数级鉴权保护 |
| AC-5 | 助手可通过 `propose*` 发起删除提案，确认卡明确区分"停用"与"删除" |
| AC-6 | 页面与助手的删除都落 `ai_operation_audit`，`source` 分别为 `WEB` / `AI` |
| AC-7 | 已删除用户无法登录，且提示与密码错误一致 |
| AC-8 | 删除被引用的险种/企业/项目被拒绝并给出引用数 |
| AC-9 | 危险动作（删自己、删最后一个 ADMIN）被拒绝 |
| AC-10 | LD-T1 ~ LD-T22 全部通过；`mvn verify` 全绿；`vue-tsc` 与前端构建通过 |
| AC-11 | `docs/IMPL-逻辑删除-进度.md` 完整记录 4 个批次的执行与验证结果 |
