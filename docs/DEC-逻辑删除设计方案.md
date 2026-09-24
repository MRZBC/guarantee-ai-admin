# 设计方案：全表逻辑删除（`is_deleted`）

| 项目 | 内容 |
| --- | --- |
| 文档名称 | 逻辑删除（Logical Delete）设计方案 |
| 文档版本 | v2.2（**唯一键改为函数索引**，修复 v2.1 裸 `(业务键, deleted_at)` 放行重复有效行的缺陷，见 §2.2c；不使用存储过程与触发器） |
| 决策来源 | 评审结论：**所有表都加 `is_deleted`**；`status` 与 `is_deleted` 是**两个正交维度** |
| 适用范围 | `guarantee_ai_admin` 的 18 张业务表（`ai_operation_secret` 为例外，见 §7.3a） |
| 涉及模块 | `guarantee-web`（schema / DataInitializer）、`guarantee-system`、`guarantee-ai`、`guarantee-order`、`guarantee-analysis`、`frontend` |
| 关联文档 | `docs/REQ-系统管理助手能力.md`（R-04 不做物理删除）、`docs/REQ-系统管理手动操作能力补齐方案.md`（能力对齐原则） |
| 预估工作量 | **11~15 人日**（详见 §12） |

---

## 1. 设计前提：两个维度正交

这是本方案的地基，后续所有设计都从它推出：

| 维度 | 字段 | 取值 | 回答的问题 | 是否可逆 |
| --- | --- | --- | --- | --- |
| **能力状态** | `status` | `1` 启用 / `0` 停用 | 这个主体**还能不能参与业务** | 可逆（启用⇄停用） |
| **存在状态** | `is_deleted` | `0` 正常 / `1` 已删除 | 这条记录**还用不用** | 可逆（删除⇄恢复，见 §2.3） |

**两者独立组合，语义都成立**：

| 组合 | 含义 | 业务例子 |
| --- | --- | --- |
| `status=1, is_deleted=0` | 正常可用 | 在职用户的启用账号 |
| `status=0, is_deleted=0` | 存在但暂停 | 长假期间的账号被停用；某部门暂不承接新业务 |
| `status=0, is_deleted=1` | 已删除（删除时本就停用） | 离职用户被停用后再删除 |
| `status=1, is_deleted=1` | 已删除但仍标记为启用 | **允许**：删除只改 `is_deleted`，不隐式改 `status`。这样"恢复"能完整回到删除前的状态 |

> **设计要点（重要）**：删除**不得**隐式修改 `status`。否则恢复时无法还原原状态，用户会抱怨"恢复回来的和我删之前不一样"。

由此推出三条硬规则：

| 编号 | 规则 |
| --- | --- |
| LD-01 | 所有业务查询默认只返回 `is_deleted = 0` 的行（§5） |
| LD-02 | 删除 = `is_deleted: 0→1` + `deleted_at`（`NOW(6)`）+ `deleted_by`（应用写 `user_id`，直连用默认 `'DB'`）；**不改 `status`** |
| LD-03 | `status` 的停用前置检查（如"部门下有启用用户不能停用"）**只统计 `is_deleted=0` 的行**——已删除的用户不该继续阻塞部门停用 |

---

## 2. 数据模型设计

### 2.1 统一字段定义（最终版）

18 张表**全部**新增这 3 列（命名与注释全库统一）；`ai_operation_secret` 为唯一例外，不加字段、保持物理删除（§7.3a）：

```sql
is_deleted  TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除 0正常 1已删除',
deleted_at  DATETIME(6) NULL     DEFAULT NULL   COMMENT '删除时间（微秒精度，同时是唯一键分量）',
deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB'   COMMENT '删除人标识：应用删除写 sys_user.id，直连删除/未提供时为 DB'
```

**三个字段的分工**：

| 字段 | 写入者 | 值形态 | 回答什么 |
| --- | --- | --- | --- |
| `is_deleted` | 应用 / 直连 | `0` / `1` | **是不是已删除**（所有查询过滤用这个） |
| `deleted_at` | 应用 / 直连 | 微秒时间 / `NULL` | **什么时候删的**；同时是唯一键分量（§2.2） |
| `deleted_by` | 应用写 `user_id`；直连留空取默认 `'DB'` | 数字字符串 或 `'DB'` | **是谁删的**（业务用户 / 数据库直连） |

### 2.1a `deleted_by` 的默认值 `'DB'`：为什么是 `VARCHAR` 而不是 `BIGINT`

原设计用 `BIGINT` 存 `sys_user.id`，但那样**无法用默认值表达"直连删除"**：
`BIGINT` 的默认值只能是数字，而 `DEFAULT 0` 会被误读为"存在 id=0 的用户"，
把"未知"伪装成"已知"，反而降低审计可信度。

改用 `VARCHAR(64) DEFAULT 'DB'` 后，一个字段同时承载两种来源，且**可被准确区分**（已实测）：

```sql
SELECT
    username,
    deleted_by,
    CASE
        WHEN deleted_by = 'DB'                 THEN '数据库直连'
        WHEN deleted_by REGEXP '^[0-9]+$'      THEN '应用（user_id）'
        ELSE '其它'
    END AS 删除来源,
    CASE
        WHEN deleted_by REGEXP '^[0-9]+$'
        THEN (SELECT username FROM sys_user WHERE id = CAST(deleted_by AS UNSIGNED))
        ELSE NULL
    END AS 删除人账号
FROM sys_user WHERE is_deleted = 1;
```

实测结果：

| username | deleted_by | 删除来源 | 删除人账号 |
| --- | --- | --- | --- |
| a（直连删除） | `DB` | 数据库直连 | `NULL` |
| b（应用删除） | `1` | 应用（user_id） | `admin` |

**必须接受的一个副作用**：`deleted_by` 不再是纯数值外键，因此**不能直接 `JOIN sys_user`**：

```sql
-- ❌ 能跑但语义不清：'DB' 走隐式转换后匹配不到，静默返回 NULL（实测无 warning，不会报错）
LEFT JOIN sys_user u ON u.id = t.deleted_by

-- ✅ 推荐：显式判类型
LEFT JOIN sys_user u ON u.id = CAST(t.deleted_by AS UNSIGNED)
                    AND t.deleted_by REGEXP '^[0-9]+$'
```

> 判定：`'DB'` 与数字字符串在同一列里共存是**有意为之**的折中——
> 用一个字段同时满足"应用可追溯到具体用户"与"直连至少有线索"。
> 代价是查询必须多一个类型判断，本方案要求在统一的视图/SQL 片段中封装该判断，禁止各处手写。

### 2.1b `deleted_at` 不能用 `CURRENT_TIMESTAMP(6)` 做默认值（实测）

评审曾提出"给这两个字段加默认值，`deleted_at` 默认当前修改时间"。
**技术上 `DATETIME(6)` 可以带 `CURRENT_TIMESTAMP(6)` 默认值，但加了会摧毁唯一键。** 实测：

| 建表语句 | 结果 |
| --- | --- |
| `deleted_at DATETIME DEFAULT CURRENT_TIMESTAMP(6)` | ❌ `ERROR 1067: Invalid default value for 'deleted_at'` |
| `deleted_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)` | ✅ 允许（但**语义错误**，见下） |

**为什么语义错误**：唯一键 `(业务键, deleted_at)` 成立的前提是**未删除的行 `deleted_at` 必须恰好为 NULL**。
一旦有默认值，任何"没写 `deleted_at`"的 INSERT 都会得到一个**各不相同**的时间戳：

| 行 | username | deleted_at | 唯一键判定 |
| --- | --- | --- | --- |
| 1 | user0123 | `2026-09-22 14:30:00.123456` | `(user0123, 14:30:00.123456)` |
| 2 | user0123 | `2026-09-22 14:31:00.654321` | `(user0123, 14:31:00.654321)` |
| 3 | user0123 | `2026-09-22 14:32:00.111111` | `(user0123, 14:32:00.111111)` |

**三条都是"有效账号"，却全部通过了唯一键** —— 默认值悄悄替每行生成了不同的唯一键分量。
登录时 `selectByUsername` 返回多行，直接失败。这比 §2.2a 的问题更隐蔽：**它是默认值造成的，而不是漏加约束**。

**结论**：

```sql
deleted_at DATETIME(6) NULL DEFAULT NULL   -- 必须 NULL，禁止默认值
```

代价是"漏写 `deleted_at`"会静默破坏唯一性。应对方式是 §2.2b 的统一写入口
（应用只走 `softDelete()`，直连只走存储过程），并在巡检中校验
`(is_deleted = 1) <> (deleted_at IS NOT NULL)` 恒为 0 行。

### 2.2 唯一键必须包含"每次删除都不同"的分量（含实测证据）

唯一键只有业务键（如 `sys_user.username`）时，一条逻辑删除的记录仍占着该业务键，
**再建同名账号会撞唯一键** —— 这是逻辑删除最经典的坑，也是 16 张表都存在的现实约束。

**三种解法实测对比**（在 MySQL 8 上真实执行，非推演）：

| 唯一键 | 第 1 次删+重建 | 第 2 次删 | 第 4 次删 | 结论 |
| --- | --- | --- | --- | --- |
| `(username, is_deleted)` | ✅ 成功 | ❌ `Duplicate entry 'user0123-1'` | — | **只能删 1 次** |
| `(username, deleted_at)` 秒精度 | ✅ 成功 | ✅ | ❌ 同秒内撞键 | 精度不足 |
| `(username, deleted_at)` **微秒精度** | ✅ 成功 | ✅ | ✅ | ✅ **5 轮连续删建全通过** |

实测 SQL 与结果（节选）：

```sql
-- 方案 A：(username, is_deleted) —— 第 2 次删除即失败
CREATE TABLE ld_demo (id BIGINT AUTO_INCREMENT, username VARCHAR(64), is_deleted TINYINT DEFAULT 0,
                      PRIMARY KEY(id), UNIQUE KEY uk_username (username, is_deleted));
INSERT INTO ld_demo (username, is_deleted) VALUES ('user0123', 0);   -- OK
UPDATE ld_demo SET is_deleted=1 WHERE username='user0123' AND is_deleted=0;  -- OK
INSERT INTO ld_demo (username, is_deleted) VALUES ('user0123', 0);   -- OK（重建）
UPDATE ld_demo SET is_deleted=1 WHERE username='user0123' AND is_deleted=0;
-- ERROR 1062 (23000): Duplicate entry 'user0123-1' for key 'ld_demo.uk_username'
```

**根因**：`is_deleted` 只有 `0/1` 两个取值，无法区分"第一条已删除记录"与"第二条已删除记录"；
而 `(username, deleted_at)` 中每条已删除记录的 `deleted_at` 都不同，因此支持**无限次**删建循环。

**为什么必须用微秒精度 `DATETIME(6)`**：秒精度下，同一秒内发生"删除→重建→再删除"会撞键。
实测中同一秒内连续 3 轮操作，第 3 步即报
`Duplicate entry 'user0123-2026-09-22 14:09:37'`。改用 `DATETIME(6)` 后 5 轮全通过：

```
id  username  is_deleted  deleted_at
1   user0123  1           2026-09-22 14:09:49.293339
2   user0123  1           2026-09-22 14:09:49.295151
3   user0123  1           2026-09-22 14:09:49.296952
4   user0123  1           2026-09-22 14:09:49.298570
5   user0123  0           NULL
```

**采用 `(业务键, IFNULL(deleted_at, 哨兵))` 函数索引 + `DATETIME(6)`**。附带说明四条：

1. 未删除行的 `deleted_at` 为 NULL，经 `IFNULL` 归一化为固定哨兵值，因此**同一业务键只能有一条有效行**；
2. `deleted_at` **不是为唯一键额外付出的成本**：它本身是展示与审计需要的字段（"什么时候删的"）；
3. 业务键**保持原值不变**，按名称的查询、日志、导出都不受影响（对比"删除时改写业务键"的方案 C）；
4. `deleted_at IS NULL` 与 `is_deleted = 0` 严格等价。§5 的过滤条件统一写 `is_deleted = 0`
   （更贴近业务语言），`deleted_at` 只承担"唯一键分量 + 时间记录"两个职责。

### 2.2c 必须用函数索引：裸 `(业务键, deleted_at)` 有一个致命漏洞（v2.2 修订）

> **这是本设计早期版本的真实缺陷，由实施阶段发现并用实测确认。**

§2.2 最初选定的唯一键是 `UNIQUE (业务键, deleted_at)`，理由是"MySQL 的 UNIQUE 允许多个 NULL，
所以未删除行之间不冲突"。但这个理由**同时意味着它拦不住重复的有效行**：

| 语句 | 裸 `(业务键, deleted_at)` | 函数索引 `(业务键, IFNULL(deleted_at, 哨兵))` |
| --- | --- | --- |
| `INSERT ... VALUES ('u1')`（第一条有效行） | ✅ 成功 | ✅ 成功 |
| `INSERT ... VALUES ('u1')`（第二条有效行） | ❌ **也被放行**（两条 NULL 不冲突） | ✅ **`ERROR 1062 Duplicate entry 'u1-1970-01-01…'`** |
| 软删除后可重建同业务键 | ✅ | ✅ |
| 同业务键多条已删除行共存 | ✅ | ✅ |
| 连续 5 轮删建 | ✅ | ✅ |

**后果（两个层面）**：

| 层面 | 后果 |
| --- | --- |
| 业务 | 两条同名有效账号可共存 → `selectByUsername` 返回多行 → MyBatis `TooManyResultsException` → **登录失败** |
| 运维 | §9.4.1 的批量软删除模板 `UPDATE ... SET deleted_at = NOW(6) WHERE 业务键 = ? AND is_deleted = 0` 命中多行时，`NOW(6)` 是**语句级常量** → 所有行拿到相同 `deleted_at` → **直接撞唯一键，删除失败** |

也就是说：裸复合键既没能在写入时拦住重复有效行，又会在删除时因此失败。**这是一个自相矛盾的设计**，
而 LD-T8a（"数据库必须拒绝两条同名有效行"）在裸复合键下根本无法成立——两者不可兼得。

**修复：把 NULL 归一化为哨兵值后再进唯一索引**

```sql
UNIQUE KEY uk_sys_user_username (username, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')))
```

| 行类型 | 索引里的第二个分量 | 效果 |
| --- | --- | --- |
| 有效行（`deleted_at IS NULL`） | 固定哨兵值 | 同一业务键只能有一条 → **DB 层互斥** |
| 已删除行 | 各自的微秒时间戳 | 可多条共存、支持无限次删建 |

这正是 PostgreSQL `CREATE UNIQUE INDEX ... WHERE is_deleted = 0`（partial unique index）的等价实现。

**合规性**：函数索引是 MySQL 8.0.13+ 的**索引表达式**，不是触发器 / 存储过程 / 函数对象，
**不违反 LD-EX-02**；LD-T22（库中无 `TRIGGERS` / `ROUTINES`）仍然通过。

**落地**：`V3__logical_delete_functional_unique_keys.sql`（幂等，可自愈 V2 遗留的裸复合键形态）。
判据是 `information_schema.STATISTICS.EXPRESSION` 是否非空。

> **教训**：§2.2 的论证只验证了"删除后能否重建"（正向），没有验证"能否拦住重复有效行"（反向）。
> 一个约束只有在**两个方向都验证过**才算成立——LD-T8a 原本就是为反向验证写的，
> 但设计阶段没有把它与 §2.2 的方案放在一起对照，导致矛盾直到实施期才暴露。

### 2.2a 明确不采用的方案：把唯一键改到 `id` 上

评审中曾提出"给每张表的每条数据都加自己的唯一键（即 `id`），这样就不会撞唯一键"。
**该方案不可行**，原因是它丢掉了**业务唯一性约束**：

```sql
-- 若不存在 uk_username(username) 这一约束
INSERT INTO sys_user (username) VALUES ('user0123');   -- 允许
INSERT INTO sys_user (username) VALUES ('user0123');   -- 也允许 → 两个同名账号
```

后果：

| 后果 | 说明 |
| --- | --- |
| **登录直接失败** | `selectByUsername` 返回 2 行 → MyBatis `TooManyResultsException`；或取到密码不匹配的那一行 |
| **账号枚举风险** | 同名账号多份、密码不同，认证行为不可预期 |
| **问题不可见** | 管理员在列表里看到两行同名，没有任何提示 |
| **并未解决原问题** | `id` 已经是主键、天然唯一；两个同名账号的 `id` 不同，**唯一键根本不会拦**，所以"不撞键"是因为**不校验**，而不是因为校验通过 |

**关键区分**：

| 概念 | 作用 | 逻辑删除后是否需要调整 |
| --- | --- | --- |
| **主键唯一性**（`id`） | 标识"这一行" | 不需要，永远唯一 |
| **业务唯一性**（`username` 等） | 保证"这个业务标识只有一条有效记录" | **必须调整**：改为 `(业务键, deleted_at)` |

结论：唯一键的正确构造是 **`业务键 + 每次删除都不同的分量`**——业务键保证业务唯一性，
时间戳分量保证逻辑删除后可重建。

### 2.2b 字段冗余：`is_deleted` 与 `deleted_at IS NOT NULL` 表达同一事实

这是本方案的**已知代价**，必须显式接受：

```sql
is_deleted = 1          -- 删除状态
deleted_at IS NOT NULL  -- 同一个事实的另一种表达
```

两者永远应该一致，但**数据库层面无法约束**（MySQL 的 `CHECK` 不允许使用 `NOW()` 这类非确定性函数）。
一旦只写了一半：

| 漏写 | 后果 | 可发现性 |
| --- | --- | --- |
| 只写 `deleted_at`，漏写 `is_deleted` | 行**看起来是有效的**（列表照常显示），但唯一键已占用"已删除"槽位 → 再删同业务键会撞键 | **极难发现**：现象与原因看起来无关 |
| 只写 `is_deleted`，漏写 `deleted_at` | `deleted_at` 为 NULL → 唯一键把多条已删除行判为同一条 → 第二次删除撞键 | 较易发现（报唯一键冲突） |

**应对：统一写入口 + 巡检（本方案采用）**

> **LD-EX-02（评审决策）**：**不使用存储过程与触发器**——数据库对象不便维护、升级与回滚，
> 且容易与代码逻辑形成"两套真相"。因此一致性只能靠"**统一写入口 + 人工纪律 + 巡检**"保证，
> 这是一个**已知并接受的薄弱环节**（风险 LD-R10），用 §9.4.1 的标准 SQL 模板与 §9.4.5 的每日巡检兜住。

| 渠道 | 统一入口 |
| --- | --- |
| 应用 | 各域只调用 `softDelete(id, operatorId)` 一处，内部固定写 `is_deleted` + `deleted_at` + `deleted_by` |
| 数据库直连 | 按 §9.4.1 的标准 SQL 模板执行（**三列必须一条语句写全**），并按 §9.4.3 手工补审计 |
| 校验 | 每日巡检：`SELECT COUNT(*) FROM {table} WHERE (is_deleted = 1) <> (deleted_at IS NOT NULL);` 必须恒为 0 行 |

**明确不采用的三个替代方案**：

| 方案 | 做法 | 不采用的理由 |
| --- | --- | --- |
| `is_deleted` 改生成列 `GENERATED ALWAYS AS (deleted_at IS NOT NULL) STORED` | 数据库层面不可能不一致 | `is_deleted` 变成只读，直连写 `SET is_deleted = 1` 会直接报错，与"直连改 is_deleted"的操作习惯冲突；且生成列不能有 `DEFAULT` |
| 触发器自动补齐另一个字段 | 18 张表 × 触发器 | **评审明确不用触发器**（LD-EX-02）；且 18 个对象需维护与版本管理 |
| 存储过程 `sp_logical_delete` 统一直连入口 | 一个过程覆盖删除+审计 | **评审明确不用存储过程**（LD-EX-02）；且存储过程同样会与代码逻辑形成两套真相 |

### 2.3 删除可恢复（LD-04）

既然付出了 `is_deleted` 的成本，就要拿到它的收益。**删除必须可恢复**，否则不如直接物理隐藏。

| 编号 | 要求 |
| --- | --- |
| LD-04 | 删除可恢复：`is_deleted: 1→0` + `deleted_at = NULL`。恢复是删除的逆操作，二者成对提供 |
| LD-04a | 恢复时**必须重做删除时的全部前置校验**（例如恢复机构要检查上级机构未被删除、恢复用户要检查机构与部门未被删除）。否则会出现"机构已删、用户被恢复"的悬挂引用 |
| LD-04b | "已删除"记录默认不在列表出现，但页面提供**「显示已删除」开关**（权限同上），由管理员查看并恢复。开关必须显式可见——否则用户会认为数据丢了 |

> **顺序约束**：恢复顺序与删除顺序相反（先恢复父、再恢复子）。页面在恢复子记录失败时应提示"请先恢复其所属机构"。

### 2.4 各表字段落地清单

| # | 表 | 删除语义 | 备注 |
| --- | --- | --- | --- |
| 1 | `sys_org` | 有 | 唯一键改造（§3） |
| 2 | `sys_department` | 有 | 唯一键改造 |
| 3 | `sys_user` | 有 | 唯一键改造；影响登录链路（§6） |
| 4 | `sys_role` | 有 | 唯一键改造 |
| 5 | `sys_permission` | 有 | 唯一键改造；**必须同步 `PermissionCatalog`**（§6.5） |
| 6 | `sys_user_role` | 有 | **关联表，需 UPSERT 改造**（§4） |
| 7 | `sys_role_permission` | 有 | 关联表，需 UPSERT 改造 |
| 8 | `insurance_type` | 有 | 唯一键改造 |
| 9 | `enterprise` | 有 | 两个唯一键改造 |
| 10 | `project` | 有 | 唯一键改造 |
| 11 | `tender_order` | 有 | 唯一键改造；10 万行全表 DDL |
| 12 | `performance_order` | 有 | 唯一键改造；5 万行全表 DDL |
| 13 | `ai_conversation` | 有 | 与 `status=ARCHIVED` 并存，语义不同（归档≠删除） |
| 14 | `ai_message` | 有 | 大表 `MEDIUMTEXT`，`ALTER` 需注意执行时间 |
| 15 | `ai_tool_call` | 有 | 审计性质；**删除入口不开放**（§7.3） |
| 16 | `ai_audit_log` | 有 | 同上 |
| 17 | `ai_operation_proposal` | 有 | 与状态机并存 |
| 18 | ~~`ai_operation_secret`~~ | **不加**（例外） | **保持物理删除**：唯一目的是缩短敏感数据窗口，逻辑删除会让密文永久滞留（§7.3a / LD-EX-01） |
| 19 | `ai_operation_audit` | 有 | **分区表**，`ALTER` 需 `ALGORITHM=INPLACE` 并逐分区执行（§9.3） |

---

## 3. 唯一键改造（13 个键）

**这是全量方案最主要的成本**。当前业务唯一键共 16 个，其中 13 个与"删除后重建"直接冲突，必须改成复合唯一键 `(业务键, deleted_at)`。

| # | 表 | 现唯一键 | 新唯一键 | 冲突场景（不改造会怎样） |
| --- | --- | --- | --- | --- |
| 1 | `sys_user` | `uk_sys_user_username(username)` | `uk_sys_user_username(username, deleted_at)` | 删掉 `user0123` 后重建同名账号 → 500 撞键 |
| 2 | `sys_org` | `uk_sys_org_code(org_code)` | `uk_sys_org_code(org_code, deleted_at)` | 删掉机构后重建同编码 |
| 3 | `sys_department` | `uk_sys_dept_code(dept_code)` | `uk_sys_dept_code(dept_code, deleted_at)` | 同上 |
| 4 | `sys_role` | `uk_sys_role_code(role_code)` | `uk_sys_role_code(role_code, deleted_at)` | 删掉自定义角色后重建同编码 |
| 5 | `sys_permission` | `uk_sys_perm_code(perm_code)` | `uk_sys_perm_code(perm_code, deleted_at)` | 权限码删除后重新启用同码 |
| 6 | `insurance_type` | `uk_insurance_type_code(type_code)` | `uk_insurance_type_code(type_code, deleted_at)` | 删掉险种后重建同编码 |
| 7 | `enterprise` | `uk_enterprise_code(ent_code)` | `uk_enterprise_code(ent_code, deleted_at)` | 删掉企业后重建同编码 |
| 8 | `enterprise` | `uk_enterprise_credit(credit_code)` | `uk_enterprise_credit(credit_code, deleted_at)` | 同一企业删后重新录入（信用代码不变） |
| 9 | `project` | `uk_project_code(project_code)` | `uk_project_code(project_code, deleted_at)` | 删掉项目后重建同编码 |
| 10 | `tender_order` | `uk_tender_order_no(order_no)` | `uk_tender_order_no(order_no, deleted_at)` | 订单号重用（罕见但必须允许） |
| 11 | `performance_order` | `uk_perf_order_no(order_no)` | `uk_perf_order_no(order_no, deleted_at)` | 同上 |
| 12 | `ai_conversation` | `uk_ai_conv_no(conversation_no)` | `uk_ai_conv_no(conversation_no, deleted_at)` | 会话编号重用 |
| 13 | `ai_operation_proposal` | `uk_proposal_no(proposal_no)` | `uk_proposal_no(proposal_no, deleted_at)` | 提案编号重用 |

**有 3 个唯一键不需改造**（因为删除后重建的碰撞概率为零，且改造需要额外设计）：

| 表 | 唯一键 | 不改造的理由 |
| --- | --- | --- |
| `sys_user_role` | `(user_id, role_id)` | 关联表；改 `is_deleted` 后用 **UPSERT** 处理（§4），无需放宽唯一键 |
| `sys_role_permission` | `(role_id, permission_id)` | 同上 |
| `ai_operation_secret` | `(proposal_id)` | 一提案一条，生命周期跟随提案；提案删除时密文应随删 |

### 3.1 迁移 DDL 模板（以 `sys_user` 为例）

```sql
-- 步骤 1：加删除标记列（18 张表统一执行，可先只加列、不动唯一键，分两次上线）
ALTER TABLE sys_user
    ADD COLUMN is_deleted TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除' AFTER status,
    ADD COLUMN deleted_at DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    ADD INDEX idx_sys_user_deleted (is_deleted);

-- ⚠️ deleted_at 必须保持 DEFAULT NULL，不可改成 CURRENT_TIMESTAMP(6)。
--    默认值会让"有效行"的 deleted_at 非 NULL，从而绕过 (业务键, deleted_at) 唯一键，
--    使多个同名有效账号同时存在 —— 详见 §2.1b。

-- 步骤 2：唯一键改造（必须在加列之后，且需先确认无重复数据）
ALTER TABLE sys_user DROP INDEX uk_sys_user_username;
ALTER TABLE sys_user ADD UNIQUE KEY uk_sys_user_username (username, deleted_at);
```

**执行前必须做的数据校验**（对 13 个键逐个执行）：

```sql
-- 若返回任何行，说明存量数据里已有重复业务键，必须先清理再改唯一键
SELECT username, COUNT(*) c FROM sys_user GROUP BY username HAVING c > 1;
```

> **分两次上线的理由**：加列是低风险操作（`ALTER` 秒级，可先完成并让代码开始写入默认值）；
> 唯一键改造会短暂锁表并校验唯一性，且一旦失败需要回滚索引。分两次可以把风险隔离开。

### 3.2 大表改造注意事项

| 表 | 行数 | 注意 |
| --- | --- | --- |
| `tender_order` | 10 万 | `ADD COLUMN` 在 MySQL 8 是 `INSTANT`/`INPLACE`，秒级；但改唯一键需重建索引，建议在低峰执行并观察锁等待 |
| `performance_order` | 5 万 | 同上 |
| `ai_message` | 视会话量 | 含 `MEDIUMTEXT`，`ALTER` 为 `INPLACE` 不复制数据；仍建议低峰 |
| `ai_operation_audit` | 视审计量 | **分区表**：`ALTER` 需对所有分区生效；`ADD COLUMN` 为 `INPLACE`；该表唯一键是复合主键 `(id, operated_at)` 且**不是业务键**，所以本表**无需改唯一键** |

---

## 4. 关联表改造：从"先清后插"改为 UPSERT

**这一条最容易被漏掉，漏了会在"重新分配同一角色"时直接失败。**

### 4.1 现状与问题

当前角色分配的逻辑是"先物理删除、再插入"：

```java
sysUserMapper.deleteUserRoles(id);
sysUserMapper.insertUserRoles(id, roleIds);
```

改成逻辑删除后，被删的行仍在表中，**(user_id, role_id) 唯一键会拒绝新的 INSERT**：

```sql
-- 删除后：行还在，只是 is_deleted=1
-- 重新分配同一角色时：
INSERT INTO sys_user_role (user_id, role_id) VALUES (7, 3);
-- ERROR 1062: Duplicate entry '7-3' for key 'uk_sys_user_role'
```

### 4.2 改造方案：把"删除"统一为 UPSERT

不再区分"先删后插"，而是**一次 UPSERT 表达最终状态**：

```xml
<!-- 标记：把该用户当前未在目标集合中的绑定置为已删除 -->
<update id="softDeleteUserRolesNotIn">
    UPDATE sys_user_role
    SET is_deleted = 1, deleted_at = NOW(6)
    WHERE user_id = #{userId}
      AND is_deleted = 0
      <if test="roleIds != null and roleIds.size() > 0">
        AND role_id NOT IN
        <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">
            #{roleId}
        </foreach>
      </if>
</update>

<!-- 恢复或新建：命中唯一键时把 is_deleted 归零并清空删除信息 -->
<insert id="upsertUserRoles">
    INSERT INTO sys_user_role (user_id, role_id, is_deleted) VALUES
    <foreach collection="roleIds" item="roleId" separator=",">
        (#{userId}, #{roleId}, 0)
    </foreach>
    ON DUPLICATE KEY UPDATE is_deleted = 0, deleted_at = NULL
</insert>
```

**这两条语句的顺序无关**（先 upsert 再 soft-delete、或反之都得到同一结果），这比原来的"先清后插"更稳健——原来如果插入失败，用户的角色会被清空且无法恢复。

### 4.3 关联表的读取过滤

所有关联查询都要加 `is_deleted = 0`。当前受影响的 SQL：

| Mapper 方法 | 影响 |
| --- | --- |
| `SysUserMapper.selectRoleRefsByUserIds` | 回填用户角色，加 `ur.is_deleted = 0` |
| `SysUserMapper.listRoleCodesByUserId` | **鉴权核心路径**，加 `ur.is_deleted = 0` |
| `SysUserMapper.listPermissionCodesByUserId` | **鉴权核心路径**，加 `ur.is_deleted = 0` 与 `rp.is_deleted = 0` |
| `SysRoleMapper.selectPermissionRefsByRoleIds` | 回填角色权限 |
| `SysRoleMapper.countUsersByRoleIds` | 用户数统计 |
| `SysRoleMapper.selectUserIdsByRoleCode` | 令牌撤销范围 |
| `SysUserMapper.countOtherEnabledAdmins` | **危险动作保护**，必须排除已删除的绑定 |
| `SysUserMapper.countEnabledUsersByRoleCode` | 统计 |
| `SysUserMapper.listRoleIdsByUserId` | 角色 id 列表 |

> ⚠️ **`listPermissionCodesByUserId` 是最关键的一处**：它决定用户的权限集合。
> 如果漏加 `is_deleted = 0`，被删除的角色权限仍会通过 JWT 签发出去 —— 这是**提权漏洞**。
> 本方案要求对鉴权路径的改动**必须有专门测试**（§11 的 LD-T6）。

---

## 5. 查询过滤机制

### 5.1 为什么不能靠手工改

18 张表、**约 60+ 处 select 语句**需要加 `is_deleted = 0`。手工改的问题：

- 漏一处 → 已删除数据"复活"（列表里出现、统计偏大）；
- 漏在多表 JOIN 处 → 更难发现；
- 新增查询时没人提醒要加 → **持续复发**。

因此**手工改 + 机制兜底，两层都要**。

### 5.2 方案：MyBatis 拦截器自动注入（推荐）

新增 `LogicalDeleteInnerInterceptor`：

| 项 | 设计 |
| --- | --- |
| 拦截点 | `StatementHandler#prepare`（只处理 `SELECT`） |
| 行为 | 解析 SQL 中的 `FROM` / `JOIN` 子句，为**受管表**注入 `别名.is_deleted = 0` |
| 受管表 | 18 张表（配置化，集中在 `LogicalDeleteTables` 常量类；不含 `ai_operation_secret`） |
| 豁免 | 通过 Mapper 方法名后缀约定豁免，例如 `...IncludingDeleted` 结尾的方法不注入（用于"显示已删除"列表与恢复操作） |
| 别名处理 | 从 SQL 文本提取别名；无别名时用表名做限定 |
| 已有同名条件 | 若 SQL 中已显式出现 `is_deleted`，则**跳过注入**（避免重复条件） |

**实现要点与风险**：

| 风险 | 应对 |
| --- | --- |
| SQL 已有关联子查询 / `EXISTS` | 只处理最外层 `FROM`/`JOIN`；子查询内的表由其自身条件负责。需对现有 SQL 逐条回归 |
| 注入位置错误 → SQL 语法错误 | 在拦截器内做 `try/catch`：解析失败时**抛出明确异常**而不是静默放行。静默放行会让已删除数据泄漏，比报错严重 |
| 性能 | 每次查询做一次轻量 SQL 文本解析；对有 `default-statement-timeout: 30` 的现状无实质影响。用 `ConcurrentHashMap` 缓存已解析的 SQL 模板 |

> **替代方案（若不引入拦截器）**：在 XML 里定义统一的 `<sql id="notDeleted">AND ${alias}.is_deleted = 0</sql>` 片段，
> 由每个查询显式 `<include>`。这个方案更简单、无解析风险，但**完全依赖开发者自觉**。
> 本方案**推荐拦截器**，理由是"漏加 = 数据泄漏"这类错误的代价远高于解析复杂度。

### 5.3 显式过滤场景（拦截器不覆盖的）

拦截器只处理"查询默认可见性"。以下场景需要**显式**写条件：

| 场景 | 条件 |
| --- | --- |
| 停用前置检查（部门下有启用用户） | `WHERE dept_id = ? AND status = 1 AND is_deleted = 0`（LD-03） |
| 危险动作保护（最后一个 ADMIN） | 排除已删除的用户与**已删除的**角色绑定 |
| 唯一性校验（新增时查重） | `WHERE org_code = ? AND is_deleted = 0`（否则会因已删除的同名记录误报"编码已存在"） |
| 数据范围递归 CTE | `WHERE id = ? AND is_deleted = 0` 起手，递归时也要带（否则会穿过已删除的机构） |
| 脏数据巡检 | 显式 `is_deleted = 1` |

> **`selectVisibleOrgIds` 的递归 CTE 要特别注意**：如果递归过程中不带 `is_deleted = 0`，
> 数据范围会**穿过已删除的机构继续向下展开**，把不该可见的机构纳入范围——**越权**。
> 这是本方案里唯一一处"漏加会导致安全问题（而非显示问题）"的地方，必须专项回归。

---

## 6. 运行期语义

### 6.1 登录：已删除用户不可登录

`AuthService.login` 现流程：查账号 → 校验密码 → 校验 `status`。**新增删除校验**：

```java
if (user.getIsDeleted() != null && user.getIsDeleted() == 1) {
    // 注意：与"账号不存在"返回同一提示，避免账号枚举
    throw new BizException(ResultCode.LOGIN_FAILED);
}
```

| 要求 | 说明 |
| --- | --- |
| LD-05 | 已删除用户登录返回**与密码错误完全相同的提示**（`LOGIN_FAILED`），不得返回"账号已删除"——否则可被用来探测账号是否存在 |
| LD-05a | 已停用用户仍返回 `ACCOUNT_DISABLED`（现状不变）：停用是给用户的明确信号，删除是不想让对方知道 |
| LD-05b | `selectByUsername` 必须只查 `is_deleted = 0`。**注意**：改为复合唯一键后，同一 username 可能有多行（含已删除），因此该语句必须显式 `AND is_deleted = 0`，不能依赖"用户名唯一所以只返回一行"的原假设 |

### 6.2 删除的领域校验（每个域的前置检查）

删除比停用更"重"，校验必须**不弱于停用**：

| 实体 | 删除前置检查 |
| --- | --- |
| 机构 | ① 无 `is_deleted=0` 的下级机构；② ~~无 `is_deleted=0` 的部门~~（O3 已移除）；③ ~~无 `is_deleted=0` 的用户~~（O3 已移除）；④ ~~无关联订单~~（**已取消**，见下） |
| 部门 | 无 `is_deleted=0` 的用户 |
| 用户 | ① 不能删除自己；② 不能删除最后一个启用状态且未删除的 ADMIN |
| 角色 | ① `ADMIN` 不可删除；② 无 `is_deleted=0` 的用户持有该角色（否则先解绑） |
| 权限 | 无角色持有该权限（或先自动解绑，见 §6.5） |
| 险种 | ~~被订单引用时禁止删除~~（**已取消**，见下） |
| 企业 / 项目 | 被订单/项目引用时禁止删除（尚未落地：这两个域没有删除入口） |
| 订单 | **建议禁止后台删除**（监管留痕）；若确需，必须留审计并限制为 ADMIN |

> **阶段一后续调整：业务数据（订单）的引用不再拦删除。**
> 原口径是"删除后被引用的历史会指向一条'不存在'的记录，因此被引用即拒绝"。
> 现改为：**删除 = 从配置列表移除、不再用于新业务**，历史订单照常展示该对象的名称、
> 也仍能按它筛选（订单列表与分布图的维度 join 不带 `is_deleted` 条件；筛选下拉按
> "启用未删除 ∪ 被订单引用"的口径收录）。因此：
>
> - 机构：删除只剩"无未删除的下级机构"这一道层级守卫；
> - 险种：没有任何删除前置检查，被订单引用也可删；
> - 恢复（RESTORE）仍然可用，删除是可逆的，因此"删错了"不会丢数据。
>
> 决策与影响面见 `docs/DEC-订单筛选下拉的选项口径.md`。历史进度文档
> （`docs/IMPL-逻辑删除-进度.md`）里"存在 N 条关联订单 → 拒绝"的走查记录保留为**当时的**事实，不再回改。
>
> 仍然成立的差异：**层级的引用要拦**（删父留子会产生悬挂层级），
> 而**业务数据的引用不拦**（历史记录仍然可读、可筛、可恢复）。

### 6.3 令牌撤销

删除用户 / 变更角色权限 → 必须撤销相关令牌，与停用一致：

| 场景 | 动作 |
| --- | --- |
| 删除用户 | `revokeUsers([userId], "用户被删除")` —— 否则该用户持旧 JWT 仍可访问直到过期 |
| 删除角色 / 变更角色权限 | 撤销所有持有该角色的用户（复用 `selectUserIdsByRoleCode`，需加过滤） |

### 6.4 审计

删除与恢复都必须落 `ai_operation_audit`，与现有停用一致：

| 渠道 | `source` | `action` |
| --- | --- | --- |
| 页面删除 | `WEB` | `DELETE` |
| 页面恢复 | `WEB` | `RESTORE` |
| 助手提案删除 | `AI` | `DELETE` |

`before_value` / `after_value` 记录 `{"isDeleted":0}` → `{"isDeleted":1}`；敏感字段仍经 `SensitiveFieldMasker` 脱敏（D-4）。

### 6.5 `sys_permission` 的联动（特殊）

权限码存在**代码常量** `PermissionCatalog`。对它做逻辑删除时：

| 场景 | 处理 |
| --- | --- |
| 删除某个权限码 | ① 先在 `sys_role_permission` 中把引用它的绑定置为已删除；② 再把权限行置为已删除；③ **必须同时从 `PermissionCatalog` 中移除该常量**，否则启动自检会告警"权限码缺失" |
| 恢复 | 反向执行，并把常量加回代码（需发版） |

> **结论**：`sys_permission` 的"删除"实质上是**一次发版动作**，不是纯数据操作。
> 因此**建议页面上不提供权限删除入口**（R-04 本就规定权限主数据只读），
> 只保留 `is_deleted` 字段以维持全表一致性与历史引用完整性。

---

## 7. 接口设计

### 7.1 接口形态

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| `DELETE` | `/api/system/{domain}/{id}` | 逻辑删除 | `system:{domain}:delete` |
| `POST` | `/api/system/{domain}/{id}/restore` | 恢复 | `system:{domain}:delete` |
| `GET` | `/api/system/{domain}?includeDeleted=true` | 列表包含已删除 | `system:{domain}:delete` |

`{domain}` 取值：`orgs` / `departments` / `users` / `roles` / `insurance-types`。

> **为什么恢复用 POST 而不是 PATCH**：恢复是"逆向操作"，语义上更接近"执行一个动作"。
> 但若团队偏好 RESTful 幂等语义，`PATCH /{id}/deleted` + body `{"isDeleted": false}` 也可接受，
> 二者择一即可，**不要两种都提供**。

### 7.2 权限码新增

| 域 | 新增权限码 |
| --- | --- |
| 机构 | `system:org:delete` |
| 部门 | `system:dept:delete` |
| 用户 | `system:user:delete` |
| 角色 | `system:role:delete` |
| 险种 | `system:insurance:delete` |
| 助手写能力 | `ai:system:write` 已存在（删除动作复用） |

权限矩阵（`PermissionCatalog`）：

| 权限 | ADMIN | OPERATOR | ANALYST | VIEWER |
| --- | --- | --- | --- | --- |
| `system:org:delete` | ✔ | ✔ | ✘ | ✘ |
| `system:dept:delete` | ✔ | ✔ | ✘ | ✘ |
| `system:user:delete` | ✔ | ✘ | ✘ | ✘ |
| `system:role:delete` | ✔ | ✘ | ✘ | ✘ |
| `system:insurance:delete` | ✔ | ✔ | ✘ | ✘ |

> 与既有矩阵口径一致：用户与角色的写权限仅 ADMIN（D-2 已收敛），机构/部门/险种 OPERATOR 可写。

### 7.3 不提供删除入口的表

以下表的 `is_deleted` **只用于全表一致性与历史引用**，**不开放任何删除接口**：

| 表 | 理由 |
| --- | --- |
| `ai_audit_log` / `ai_operation_audit` / `ai_tool_call` | 审计**只增不改不删**（SYS-A-04）。提供删除入口等于给出"隐藏痕迹"的能力 |
| `sys_permission` | R-04 权限主数据只读；删除实为发版动作（§6.5） |
| `ai_operation_secret` | 生命周期由过期清理管理（已在提案确认/过期时物理删除） |

> 这三类表加字段是"为一致性付费"，但**不能让这个字段变成可写的**——否则审计表就失去了证据价值。

### 7.3a 例外：`ai_operation_secret` 保持**物理删除**（LD-EX-01）

这是"全表统一"的**唯一明确例外**，评审已确认。

| 项 | 内容 |
| --- | --- |
| 表 | `ai_operation_secret`（提案敏感参数的一次性加密暂存） |
| 字段处理 | **不加** `is_deleted` / `deleted_at` / `deleted_by`，保持现状（`proposal_id` / `cipher_text` / `key_version` / `expires_at`） |
| 删除方式 | 保持**物理删除**：`deleteByProposalId`（提案确认/拒绝后立即清）、`deleteExpired`（定时清理）。**不新增**对应表的逻辑删除能力 |
| 理由 | 该表的唯一存在目的就是"**尽量缩短敏感数据的存储窗口**"——密文只在确认执行的那一刻解密一次，用完即删。<br>若改成逻辑删除，密文会**永久留在库里**（只是 `is_deleted=1`），敏感数据窗口从 15 分钟变成永久，直接违反 D-4 / SYS-A-02b 的设计意图。<br>若为了缓解而再加一个"物理清理已逻辑删除行"的任务，等于**绕一圈又回到物理删除** |
| 权衡结论 | **安全 > 命名一致性**。宁可让"全表统一"有一个例外，也不让手机号密文长期滞留 |
| 附带影响 | 该表不参与 §3 的唯一键改造（它唯一的键 `uk_ai_secret_proposal(proposal_id)` 无需变动） |

> **为什么不做"加字段但永不置 1"**：那会产生一个恒为 0 的误导性字段，
> 让后来者以为"这张表也支持逻辑删除"，进而写出错误的清理逻辑。**明确不加，比加了不用更清晰。**

### 7.4 AI 助手侧

按"手动 ⊇ 助手"原则，删除能力要在两个渠道都提供：

| 工具 | 动作 | 说明 |
| --- | --- | --- |
| `proposeOrgChange` 等 5 个写工具 | **新增 `DELETE` 动作** | 与 `DISABLE` 区分，确认卡须明确显示"删除后默认不可见，可恢复" |
| 新增 `includeDeleted` 参数 | `query*` 工具 | 默认 `false`；仅持有 `:delete` 权限时才允许传 `true` |

**提示词改动**（`business-assistant.st`）：

- 明确区分"停用"与"删除"：停用=暂停业务可随时启用；删除=从列表移除、需显式恢复
- 删除属**危险动作**，确认卡需二次确认
- 被引用时删除会被拒绝，需说明"请先处理引用的数据，或改为停用"

---

## 8. 前端设计

| 页面 | 改动 |
| --- | --- |
| 5 个系统管理页 | 操作列新增「删除」（`system:*:delete`）与「恢复」（仅 `includeDeleted` 视图下） |
| 筛选区 | 新增「显示已删除」开关（默认关；无 `:delete` 权限时不渲染） |
| 已删除行样式 | 整行置灰 + `已删除` 标签 + 显示 `deleted_at`；删除操作人从审计表取（按 `target_type + target_id` 关联） |
| 删除确认弹窗 | 必须说明：① 数据集不再出现在默认列表；② 可恢复；③ 被引用时会被拒绝（附具体引用数） |
| 恢复按钮 | 恢复失败时提示前置条件（如"请先恢复其所属机构"） |
| 危险动作 | 删除需二次确认弹窗（与停用一致，见 `ProposalCard.vue` 的 `dangerous` 机制） |

> **"显示已删除"开关的必要性**（LD-04b）：没有它，删除就是单向不可见的，用户会认为数据丢了。
> 有了它，`is_deleted` 才真正区别于物理删除。

---

## 9. 迁移实施步骤

### 9.1 上线批次（分 4 批，每批可独立回滚）

| 批次 | 内容 | 可交付状态 |
| --- | --- | --- |
| **批次 1** | 18 张表加 3 列（`is_deleted` / `deleted_at DATETIME(6)` / `deleted_by` / 索引）；实体类与 VO 加字段（默认 0） | 系统行为**不变**（无人写 `is_deleted=1`），可安全上线 |
| **批次 2** | 13 个唯一键改造 + 数据校验；关联表 UPSERT 改造；鉴权路径过滤（§4.3） | 仍无删除入口，但底层已就绪。**必须跑完整回归** |
| **批次 3** | 查询过滤拦截器 + 显式过滤场景（§5.3）+ `includeDeleted` 查询 | 过滤生效，行为仍与之前一致（因为无删除数据） |
| **批次 4** | 删除/恢复接口 + 权限码 + 前端 + AI 工具与提示词 | 能力上线 |

**批次 1~3 都是"零行为变更"的准备**，这是本方案最重要的风险控制手段——
把"结构改造"与"能力开放"分开，任何一批出问题都不影响用户可用性。

### 9.2 回滚方案

| 批次 | 回滚方式 |
| --- | --- |
| 批次 1 | `DROP COLUMN`（数据无影响，因为没人写过 1） |
| 批次 2 | 恢复原唯一键（需先删除已逻辑删除的行，或将其业务键改名）；**这是最难回滚的一批**，因此务必先在测试库验证 `ALTER` 与数据校验 |
| 批次 3 | 关闭拦截器开关（配置项 `guarantee.logical-delete.enabled=false`） |
| 批次 4 | 下线接口（前端按钮同步隐藏） |

> 批次 2 的"难以回滚"是客观存在的：唯一键一旦放宽，就可能已经产生"同业务键多行"的数据。
> 因此**批次 2 必须在演练环境完整跑一遍**，包括"删除→重建同名→再删除"的循环。

### 9.3 分区表 `ai_operation_audit` 的执行

```sql
-- 分区表加列：MySQL 8 支持 INPLACE，但需确认分区数（当前 37 个）
ALTER TABLE ai_operation_audit
    ADD COLUMN is_deleted TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    ADD COLUMN deleted_at DATETIME(6) NULL     DEFAULT NULL,
    ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT 'DB',
    ALGORITHM=INPLACE, LOCK=NONE;

-- 执行前记录分区数用于回滚核对
SELECT COUNT(*) FROM information_schema.PARTITIONS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_audit';
```

> 该表虽加字段，但**不提供删除入口**（§7.3），因此 `is_deleted` 会恒为 0。

### 9.4 数据库直连操作的规范做法

评审明确需要考虑"从数据库直接操作"的场景，同时**明确不使用存储过程与触发器**
（数据库对象不便维护、升级与回滚成本高，且容易与代码逻辑形成两套真相）。

因此直连只能靠"**标准 SQL 模板 + 人工纪律 + 巡检兜底**"。这是本方案已知的薄弱环节，
必须用下面的模板与检查把它约束住。

#### 9.4.1 删除：三列必须一次写全（复制即用）

```sql
-- 【机构】逻辑删除；:id 与 :operator 替换为实际值，不填 operator 则落默认值 'DB'
UPDATE sys_org
   SET is_deleted = 1,
       deleted_at = NOW(6),
       deleted_by = '1'          -- sys_user.id 的字符串形式；直连不确定时删掉本行，走默认 'DB'
 WHERE id = :id
   AND is_deleted = 0;          -- 条件更新：防重复删除，且 ROW_COUNT() 可判成败
```

**五个硬要求**：

| # | 要求 | 漏掉的后果 |
| --- | --- | --- |
| 1 | `is_deleted` 与 `deleted_at` **必须同一条语句写** | 只写一个 → 唯一键判定错乱（§2.2b），且现象与原因看起来无关 |
| 2 | `deleted_at` 必须用 **`NOW(6)`**（不是 `NOW()`） | 秒精度下同一秒内重复删建会撞唯一键（§2.2 实测） |
| 3 | 必须带 `AND is_deleted = 0` | 重复删除会被当成成功；`ROW_COUNT()` 失去判成败的能力 |
| 4 | 不要手写 `deleted_by` 为 `0` 或空串 | 会把"未知"伪装成"已知用户"，污染审计 |
| 5 | 删除前先做 §9.4.4 的四项检查 | 绕过领域校验，可能造出孤立数据或缺令牌撤销 |

#### 9.4.2 恢复：三列一起归零

```sql
-- 恢复（不会撞唯一键：有效行用 NULL 槽位，一条记录只能占一个）
UPDATE sys_org
   SET is_deleted = 0,
       deleted_at = NULL,
       deleted_by = 'DB'
 WHERE id = :id
   AND is_deleted = 1;          -- 只允许从"已删除"恢复
```

> **恢复的顺序约束**（LD-04a）：先恢复父、再恢复子。否则会出现"机构仍被删除、部门已恢复"的悬挂引用。

#### 9.4.3 审计留痕：手工补写（没有存储过程，只能手工做）

应用侧删除会自动写 `ai_operation_audit`。直连不会，因此**需要手工补一条**，否则这次变更在审计里完全不可见：

```sql
INSERT INTO ai_operation_audit
    (operated_at, operator_user_id, operator_username, operator_real_name, source,
     action, target_type, target_id, before_value, after_value, changed_fields,
     truncated, result, error_message, trace_id)
VALUES
    (NOW(6),
     NULL,                    -- 直连时无法可靠得知业务操作者，留 NULL 而不是编造
     'admin',                 -- 若明确知道是谁操作，填账号；否则填 MySQL 账号名
     NULL,
     'DB',                    -- 新增来源值：AI / WEB / DB
     'DELETE', 'ORG', :id,
     NULL, '{"isDeleted":1}', 'isDeleted',
     0, 'SUCCESS', NULL, CONCAT('DB-', UUID()));
```

**新增 `source = 'DB'`**：现有 `source` 只有 `AI` / `WEB`（`VARCHAR(16)`，加值不需改表）。
有了它就能一条 SQL 筛出所有绕过应用的操作，用于事后核查：

```sql
SELECT operated_at, operator_username, action, target_type, target_id, result, error_message
FROM ai_operation_audit
WHERE source = 'DB'
ORDER BY operated_at DESC;
```

#### 9.4.4 删除前的四项人工检查（直连绕过的四道防线）

| # | 检查 | SQL 要点 |
| --- | --- | --- |
| ① | **引用检查** | 有未删除的下级/部门/用户/订单则不许删（按域不同，见 §6.2）。例：`SELECT COUNT(*) FROM sys_department WHERE org_id = :id AND is_deleted = 0;` |
| ② | **危险目标** | 不许删自己、不许删最后一个启用 ADMIN：`SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id AND ur.is_deleted=0 JOIN sys_role r ON r.id=ur.role_id WHERE r.role_code='ADMIN' AND u.status=1 AND u.is_deleted=0 AND u.id <> :id;` |
| ③ | **数据范围** | 直连**没有任何范围限制**，必须人工核对目标机构是否在操作者范围内 |
| ④ | **令牌撤销** | ⚠️ **SQL 做不到**：撤销标记在 Redis，删用户/改角色后必须手工执行<br>`redis-cli SET "guarantee:auth:user-revoked:{userId}" <epoch_millis> EX 46800` |

#### 9.4.5 一致性巡检（因为不用触发器，这是唯一的兜底）

**不用触发器 = 数据库层面无法保证 `is_deleted` 与 `deleted_at` 一致**，
因此巡检从"可选"变为**必须**，建议纳入每日巡检作业：

```sql
-- 对 18 张表逐表执行；任何一张返回非 0 行 = 存在"只写了一半"的脏数据
SELECT 'sys_org' AS tbl, COUNT(*) AS bad_rows FROM sys_org
 WHERE (is_deleted = 1) <> (deleted_at IS NOT NULL);
-- ... 其余 17 张表同理，或用一个 UNION ALL 汇总
```

发现脏数据后的修复原则（按哪种漏写决定）：

| 情形 | 修复 |
| --- | --- |
| `is_deleted=1` 但 `deleted_at IS NULL` | `UPDATE ... SET deleted_at = NOW(6) WHERE id = ?` —— 但**必须先确认没有同业务键的有效行**，否则补上后可能撞唯一键 |
| `deleted_at IS NOT NULL` 但 `is_deleted=0` | `UPDATE ... SET is_deleted = 1 WHERE id = ?`（这行本来就该是已删除状态） |

#### 9.4.5a 关于 `CURRENT_USER()`：不能做默认值，也不能靠触发器

评审曾希望用 `CURRENT_USER()` 自动记录"谁执行的 SQL"。实测与结论：

| 途径 | 结果 |
| --- | --- |
| 列默认值 `DEFAULT (CURRENT_USER())` | ❌ `ERROR 3770: Default value expression ... contains a disallowed function` |
| 触发器里 `SET NEW.deleted_by_account = CURRENT_USER()` | ✅ 可用，但**本方案已明确不引入触发器** |
| 因此 | **放弃自动记录数据库账号**；`deleted_by` 只在应用侧写真实 `user_id`，直连侧落默认 `'DB'` |

这带来的能力缺口要显式承认：**只写 `is_deleted=1`、不写 `deleted_by` 时，追溯到的是 `'DB'` 这个标记，而不是具体的人。**
若需要精确到人，直连时必须手工填 `deleted_by = '<user_id>'`。

#### 9.4.6 必须回到应用的场景（直连无法正确完成）

| 场景 | 原因 |
| --- | --- |
| 批量删除（如 1000 条） | 需逐条做领域校验与令牌撤销，SQL 无法覆盖 |
| 删除用户 / 调整角色权限 | 令牌撤销在 Redis，SQL 改不到（§9.4.4 ④） |
| 需要 `source = 'AI'` 的助手渠道操作 | 只能由 `ProposalService` 产生 |
| 迁移 / 批量导入 | 应显式写全三列，或导入后跑 §9.4.5 的巡检修复 |

#### 9.4.7 定级：直连是"应急通道"，不是常规路径

综合上面的约束，本方案对直连的定位是**应急**：

| 操作类型 | 建议路径 | 理由 |
| --- | --- | --- |
| 单实体删除（有明确操作者） | **走应用** | 四道防线齐全，零额外工作 |
| 批量清理 | **走应用**（分批） | 令牌撤销 + 领域校验无法省略 |
| 数据修复 / 误删恢复 | 直连可以 | 恢复只需三列归零，不撞唯一键 |
| 环境初始化 / 运维脚本 | 直连可以，按 §9.4.1~9.4.3 手工补审计 | 无明确业务操作者，`deleted_by` 落 `'DB'` |

---

## 10. 代码改造清单（按模块）

### 10.1 `guarantee-web`

| 文件 | 改动 |
| --- | --- |
| `src/main/resources/db/schema.sql` | 18 张表加 3 列；13 个唯一键改造（幂等写法见下） |
| `src/main/resources/db/migration/` | **新增**：唯一键改造与数据校验脚本（`schema.sql` 用 `CREATE TABLE IF NOT EXISTS`，对存量表不会生效，因此改造必须独立脚本） |
| `init/DataInitializer.java` | 插入语句显式写 `is_deleted = 0`（依赖默认值亦可，但显式更清晰） |
| `init/PermissionCatalog.java` | 新增 5 个 `:delete` 权限码并纳入矩阵 |

> **`schema.sql` 的幂等边界**：本项目 `schema.sql` 全为 `CREATE TABLE IF NOT EXISTS`，
> 因此**存量库不会因它而获得新列**。必须在 `migration/` 下提供幂等 `ALTER`（用
> `information_schema` 判断列是否存在），并在文档中写明执行顺序。这是既有工程习惯的延续
> （`PermissionSyncInitializer` 就是为解决同类问题而引入的）。

### 10.2 `guarantee-common`

| 文件 | 改动 |
| --- | --- |
| `security/Permissions.java` | 新增 5 个删除权限码常量 |

### 10.3 `guarantee-system`

| 文件 | 改动 |
| --- | --- |
| `entity/*.java`（5 个实体） | 加 `isDeleted` / `deletedAt` / `deletedBy` |
| `vo/*.java` | 列表 VO 加 `isDeleted` / `deletedAt`（删除操作人从审计表关联取，不在 VO 上加 `deletedBy`） |
| `dto/*Dto.Query` | 加 `includeDeleted`（Boolean，默认 false） |
| `mapper/*.java` + `resources/mapper/system/*.xml` | 删除/恢复/UPSERT/显式过滤；§4.3 的 9 处鉴权与统计路径 |
| `service/*Service.java` | 新增 `delete` / `restore`；所有停用前置检查加 `is_deleted = 0`；`WebAuditor` 落 `DELETE` / `RESTORE` |
| `controller/*Controller.java` | 新增 `DELETE` 与 `/restore`，各带 `@PreAuthorize` |
| `service/WebAuditor.java` | 加 `DELETE` / `RESTORE` 的便捷方法（复用现有实现） |

### 10.4 `guarantee-ai`

| 文件 | 改动 |
| --- | --- |
| `service/executor/*ProposalExecutor.java`（5 个） | 新增 `DELETE` 动作分支，执行期重做校验（SYS-C-05） |
| `tool/write/*ProposalTool.java`（5 个） | `action` 增加 `DELETE`；`@ToolParam` 描述补充删除语义 |
| `tool/*QueryTool.java` | 新增 `includeDeleted` 参数与权限判定 |
| `service/ProposalService.java` | `actionName()` 增加 `DELETE` → "删除"、`RESTORE` → "恢复" |
| `resources/prompts/business-assistant.st` | 补"停用 vs 删除"的区分话术与危险动作提示 |
| `tool/AiToolRegistry.java` | 无需改（写工具已注册），但**权限判定需确认** `:delete` 也在 `anyMatch` 集合内 |

### 10.5 `guarantee-order` / `guarantee-analysis`

| 改动 | 说明 |
| --- | --- |
| 订单/项目/企业相关查询加过滤 | 若引入拦截器则自动生效；但**分析类聚合 SQL**（`OverviewMapper`、`OrderAnalysisMapper`）需专项核对——`GROUP BY` + `JOIN` 场景下拦截器注入位置易错 |
| 建议 | 对分析模块**不依赖拦截器**，手工显式加过滤并逐个测试 |

### 10.6 `frontend`

见 §8，涉及 5 个系统管理页 + `api/system.ts` + `types/system.ts` + `AppLayout.vue`（无需改菜单）。

---

## 11. 测试要求

| 编号 | 层级 | 内容 |
| --- | --- | --- |
| LD-T1 | 集成 | 删除后默认列表不出现；`includeDeleted=true` 时出现且带删除信息 |
| LD-T2 | 集成 | **删除→重建同业务键→再删除→再重建** 全循环不撞唯一键，且**同一业务键连续 5 轮**全部通过（13 个键逐个验证；对应 §2.2 的实测） |
| LD-T2a | 单元 | 唯一键必须为 **`(业务键, IFNULL(deleted_at, 哨兵))` 的函数索引形态**且 `deleted_at` 为 `DATETIME(6)`：用 `information_schema.STATISTICS` 断言第二个分量的 `COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL`。**同时断言不存在"裸 `deleted_at` 列"形态的唯一键**（裸列会放行重复有效行，见 §2.2c） |
| LD-T3 | 集成 | 恢复后记录回到正常状态，且 `status` 与删除前**完全一致**（LD-02 的回归） |
| LD-T4 | 集成 | 恢复子记录时若父记录已删除 → 明确拒绝并提示先恢复父记录（LD-04a） |
| LD-T5 | 集成 | 关联表 UPSERT：把用户角色从 `[A,B]` 改为 `[B,C]` 再改回 `[A,B]`，全程不报错且最终状态正确 |
| LD-T6 | **安全** | **鉴权路径**：删除一个角色后，持有该角色的用户**权限集合立即不含该角色的权限**（防提权，§4.3） |
| LD-T7 | **安全** | **数据范围**：删除中间层机构后，`selectVisibleOrgIds` 递归**不穿过**已删除机构（防越权，§5.3） |
| LD-T8 | 安全 | 已删除用户登录返回与密码错误**相同**的提示（LD-05，防账号枚举） |
| LD-T8a | 安全 | **业务唯一性约束未被削弱**：尝试插入两条同名 `username`（均未删除）→ **数据库必须拒绝**（`DuplicateKeyException`）；且软删除后必须能重建同业务键、第二次删除也必须成功（§2.2c 的双向验证） |
| LD-T9 | 集成 | 删除用户 / 变更角色权限后相关令牌被撤销（§6.3） |
| LD-T10 | 集成 | 删除被引用的险种/企业/项目 → 被拒绝且给出引用数（§6.2） |
| LD-T11 | 集成 | 危险动作：删除自己、删除最后一个 ADMIN → 拒绝 |
| LD-T12 | 集成 | 删除/恢复均落 `ai_operation_audit`，`source` 正确、敏感字段已脱敏（§6.4） |
| LD-T13 | 单元 | 停用前置检查只统计 `is_deleted=0` 的行（LD-03）：部门下有"已删除的启用用户"时**允许**停用 |
| LD-T14 | 单元 | 拦截器：`SELECT` 自动注入、已显式含 `is_deleted` 时不重复注入、`...IncludingDeleted` 方法豁免、解析失败抛异常而非放行 |
| LD-T15 | 集成 | 唯一性校验：存在已删除的同编码记录时，新增同编码**允许**（§5.3） |
| LD-T16 | 回归 | 全量既有测试通过：`mvn verify`（当前 93 项）+ 前端构建 + `vue-tsc` |
| LD-T17 | 单元 | `deleted_by` 三态可区分：`'DB'` → "数据库直连"；数字 → 关联出正确账号；NULL 不出现（列 NOT NULL） |
| LD-T18 | 单元 | **`deleted_at` 列定义断言**：必须是 `DATETIME(6)` 且 `DEFAULT NULL`，且参与唯一键。用 `information_schema.COLUMNS` / `STATISTICS` 校验，**防止实施时误加 `CURRENT_TIMESTAMP` 默认值**（§2.1b） |
| LD-T19 | 集成 | **一致性巡检**：对 18 张表执行 `(is_deleted = 1) <> (deleted_at IS NOT NULL)`，结果必须全为 0 行 |
| LD-T20 | 集成 | **直连 SQL 模板有效性**（不用存储过程，直接执行 §9.4.1 的语句）：三列一次写全、`NOW(6)` 精度正确、`AND is_deleted = 0` 使重复删除的 `ROW_COUNT()` 为 0、`deleted_by` 缺省时落 `'DB'` |
| LD-T21 | 集成 | **`ai_operation_secret` 保持物理删除**：提案确认/拒绝后密文行**物理消失**（`COUNT(*) = 0`），且该表不含 `is_deleted` 列 |
| LD-T22 | 单元 | **禁止数据库对象**：断言 schema 中不存在与本需求相关的 `TRIGGER` / `PROCEDURE` / `FUNCTION`（查询 `information_schema.TRIGGERS` / `ROUTINES`）。这是 LD-EX-02 的回归护栏，防止后续有人"顺手加一个触发器" |

---

## 12. 工作量评估

| 模块 | 内容 | 人日 |
| --- | --- | --- |
| 数据库 | 19 表加列 + 13 唯一键改造 + 迁移与校验脚本 + 演练 | 3 |
| `guarantee-system` | 实体/VO/DTO/Mapper/Service/Controller（5 域 × 4 类改动） | 4 |
| 查询拦截器 | 实现 + 现有 SQL 逐条回归（含分析模块） | 2.5 |
| `guarantee-ai` | 5 个 executor + 5 个写工具 + 查询工具 + 提示词 | 2 |
| 前端 | 5 页 + 开关 + 已删除样式 + 恢复交互 | 2.5 |
| 测试 | LD-T1 ~ LD-T16 | 3 |
| **合计** | — | **17** |

> 若**暂不开放删除能力**（只做批次 1~3：加列、唯一键、过滤），工作量约 **8.5 人日**，
> 且系统行为完全不变——这是一个更保守的中间选项（见 §14 待确认）。

---

## 13. 风险与应对

| 编号 | 风险 | 影响 | 应对 |
| --- | --- | --- | --- |
| LD-R1 | 唯一键改造失败或产生重复数据 | 高：无法回滚 | 批次 2 前做重复校验；在演练库完整跑"删除→重建"循环；大表低峰执行 |
| LD-R2 | 查询过滤漏加 | 高：数据"复活"或统计偏大 | 拦截器兜底 + LD-T14；分析模块手工显式过滤 |
| LD-R3 | 鉴权路径漏加过滤 | **极高：提权** | §4.3 逐条列出；LD-T6 专项断言 |
| LD-R4 | 数据范围递归穿过已删除机构 | **极高：越权** | §5.3；LD-T7 专项断言 |
| LD-R5 | 拦截器解析错误导致 SQL 失效 | 高：全站不可用 | 解析失败抛异常（不静默放行）+ 批次 3 全量回归 + 配置开关可关闭 |
| LD-R6 | 已删除数据仍占用唯一键 | 中：删除功能不可用 | 采用 `(业务键, deleted_at)` 复合唯一键（§2.2，已实测） |
| LD-R6a | **唯一键分量精度不足**：若 `deleted_at` 用秒精度 `DATETIME`，同一秒内"删除→重建→再删除"会撞键（实测第 3 步即失败） | 中：快速连续操作失败 | 必须用 **`DATETIME(6)` 微秒精度**；且该约束需写入迁移脚本评审清单 |
| LD-R6b | 人工/脚本在**同一微秒**内完成同业务键的删除→重建 | 低：概率极低 | 撞键时返回明确提示"操作过快，请重试"，不做额外机制。300 用户规模下可接受 |
| LD-R10 | **`is_deleted` 与 `deleted_at` 不一致**：只写了一个字段 | 中：表现为"看似有效却占着唯一键槽位"，现象与原因无关，极难排查 | **评审明确不用触发器/存储过程（LD-EX-02），因此这是已知并接受的薄弱环节**。应对：§2.2b 统一写入口 + §9.4.1 标准 SQL 模板 + **每日巡检**（§9.4.5，从"可选"升为"必须"） |
| LD-R10a | 巡检发现脏数据后修复时**可能撞唯一键**（补 `deleted_at` 时若已有同业务键的有效行） | 中：修复动作本身失败 | §9.4.5 规定"修复前必须先确认没有同业务键的有效行"；巡检结果需人工确认后再修，不做自动订正 |
| LD-R13 | 不用触发器 → **无法自动记录数据库账号**（`CURRENT_USER()` 只能靠触发器写入） | 低：直连只知是 `'DB'`，不知具体人 | §9.4.5a 显式承认缺口；需精确到人时直连必须手工填 `deleted_by = '<user_id>'` |
| LD-R11 | **`deleted_by` 类型混用**（数字字符串 / `'DB'`）导致查询写错 | 低：`JOIN sys_user ON u.id = t.deleted_by` 会静默返回 NULL（实测无报错） | 封装统一的 SQL 片段（含 `REGEXP '^[0-9]+$'` 判断），禁止各处手写（§2.1a） |
| LD-R12 | 直连删除**未撤销令牌**，被删用户持旧 JWT 继续访问最长 12 小时 | **高：越权** | §9.4 明确列为"直连无法完成、必须回到应用"的场景；巡检 `source = 'DB'` 且 `target_type = 'USER'` 的记录并核对 Redis |
| LD-EX-01 | `ai_operation_secret` 未加逻辑删除字段，与"全表统一"不一致 | 低：命名一致性受损 | **有意为之**：安全优先（§7.3a）。在文档与代码注释中显式说明，避免被当成遗漏
| LD-R7 | 关联表"先清后插"撞唯一键 | 中：角色分配失败 | UPSERT 改造（§4.2）；LD-T5 |
| LD-R8 | 用户误以为删除可永久隐藏 | 中：体验/合规预期错位 | 页面对删除给出明确说明；提供「显示已删除」开关（LD-04b） |
| LD-R9 | 全表加字段被误认为"已支持删除" | 中：预期偏差 | 批次 1~3 上线时明确对外说明"仅结构准备，能力未开放" |

---

## 14. 待确认事项

| 编号 | 问题 | 建议 | 影响 |
| --- | --- | --- | --- |
| LD-Q1 | **是否本次就开放删除能力**，还是只做批次 1~3（加列 + 唯一键 + 过滤）？ | 建议**先只做批次 1~3**（8.5 人日，零行为变更），把删除能力作为独立需求评审 | 决定工作量 17 vs 8.5 人日 |
| LD-Q2 | 订单（`tender_order` / `performance_order`）是否允许删除？ | 建议**禁止**（监管留痕），字段只作一致性 | 影响 §6.2 与前端 |
| LD-Q3 | 恢复功能是否本期提供？ | 建议**必须提供**（LD-04），否则 `is_deleted` 不如物理隐藏 | 前端 +2.5 人日 |
| LD-Q4 | 拦截器 vs XML 统一片段？ | 建议**拦截器**（§5.2），漏加代价过高 | 影响 2.5 人日与风险等级 |
| LD-Q5 | `sys_permission` 是否需要页面删除入口？ | 建议**不提供**（删除实为发版动作，§6.5） | 影响前端 |
| LD-Q6 | 已删除记录是否影响"数据范围"可见集合的成员资格？ | 建议**不包括**（已删除机构不该再界定范围） | 影响 LD-T7 |
| LD-Q7 | 是否需要"批量删除"？ | 建议本期**不做**（R-06 的上限约束同样适用） | — |
| ~~LD-Q8~~ | ~~`deleted_by` 是否保留、默认值如何设~~ | ✅ **已定稿**：保留，`VARCHAR(64) NOT NULL DEFAULT 'DB'`（§2.1a） | 已关闭 |
| ~~LD-Q9~~ | ~~`deleted_at` 是否加 `CURRENT_TIMESTAMP` 默认值~~ | ✅ **已定稿**：**禁止**，必须 `DEFAULT NULL`（§2.1b） | 已关闭 |
| ~~LD-Q10~~ | ~~`ai_operation_secret` 是否也加逻辑删除~~ | ✅ **已定稿**：**不加**，保持物理删除（§7.3a / LD-EX-01） | 已关闭 |
| **LD-Q11** | 主键是否改为**雪花 ID**（全局唯一，为将来拆库/迁移预留）？ | 这是**独立于本方案的另一个工程**（改主键要动全部外键引用 + `legacy_id` 对照 + 存量迁移）。建议**单独立项**，不与逻辑删除混做 | 决定是否另出方案文档 |
| **LD-Q12** | ~~是否为"每个管理员独立 MySQL 账号"（让 `CURRENT_USER()` 有区分度）~~ | ✅ **已关闭**：评审决定不用触发器，`CURRENT_USER()` 无法写入（§9.4.5a）。此优化项作废 | 已关闭 |
| **LD-Q13** | 直连时若需精确到人，是否强制手工填 `deleted_by`？ | 建议**在运维手册中要求**：能确定操作者时必须填 `user_id`；确实无法确定时才留默认 `'DB'` | 影响运维纪律，不影响代码 |

---

## 15. 与既有文档的关系

| 既有结论 | 本方案的处理 |
| --- | --- |
| R-04「不做物理删除」 | ✅ **完全兼容**：本方案加的是逻辑删除，物理删除仍禁止 |
| `status` 的停用能力 | ✅ **保留不变**：两个维度正交（§1），停用的前置检查口径微调（LD-03） |
| SYS-A-04「审计只增不改不删」 | ✅ **兼容**：审计表加字段但不开放删除入口（§7.3） |
| `REQ-系统管理手动操作能力补齐方案` §1.4「合法下界」 | ⚠️ **需更新**："删除能力"从"两边都没有"变为"两边都要有"（按能力对齐原则，页面与助手必须同时提供） |
| 该文档 D-7「不引入统一逻辑删除字段」 | ❌ **已被本方案取代**：这是评审的明确决策变更，需回填该文档 |

---

## 16. 变更记录

| 日期 | 版本 | 变更 |
| --- | --- | --- |
| 2026-09-22 | v1.0 | 初稿：按评审决策"全表加 `is_deleted`"设计。含两维度正交模型、复合唯一键方案（13 个键）、关联表 UPSERT 改造、查询过滤拦截器、登录与数据范围的安全专项、4 批次上线与回滚、16 项测试要求 |
| 2026-09-22 | v1.1 | 按评审意见修订 3 处（均基于**真机实测**而非推演）：① **移除 `deleted_by`**——操作人由审计表记录，行上冗余存储会随姓名变更失真；② **`deleted_at` 改为 `DATETIME(6)` 微秒精度**——实测秒精度下"同一秒内删除→重建→再删除"第 3 步即撞键；③ 新增 §2.2a **明确不采用"把唯一键改到 `id` 上"**——该做法丢掉了业务唯一性约束，会导致同名账号并存、登录 `TooManyResultsException`，且"不撞键"是因为不校验而非校验通过。§2.2 补入三方案实测对比表与原始 SQL/报错证据；新增风险 LD-R6a/R6b 与测试 LD-T2a/T8a |
| 2026-09-22 | v2.0 | **字段最终定稿**。按评审确认修订：① **恢复 `deleted_by`，类型改为 `VARCHAR(64) NOT NULL DEFAULT 'DB'`**——原 `BIGINT` 无法用默认值表达"直连删除"（`DEFAULT 0` 会把"未知"伪装成"已知"）；实测确认 `'DB'` 与数字可被准确区分。新增 §2.1a 说明类型变更及其副作用（**不能直接 `JOIN sys_user`**，需带 `REGEXP '^[0-9]+$'` 判断，实测隐式转换会静默返回 NULL）。② 新增 §2.1b **`deleted_at` 禁止默认值**——实测 `DATETIME DEFAULT CURRENT_TIMESTAMP(6)` 报 `ERROR 1067`，而 `DATETIME(6)` 虽允许但会让有效行的 `deleted_at` 非 NULL，**从而绕过唯一键、使多个同名有效账号共存**（比漏加约束更隐蔽）。③ 新增 §2.2b **字段冗余的已知代价**与统一写入口方案。④ 新增 §7.3a **`ai_operation_secret` 保持物理删除**（LD-EX-01）——逻辑删除会让手机号密文从 15 分钟驻留变为永久，违反 D-4，安全优先于命名一致性；表数量由 19 改为 **18 + 1 例外**。⑤ 新增 §9.4 数据库直连操作规范。⑥ 新增风险 LD-R10/R11/R12 与测试 LD-T17~T21 |
| 2026-09-22 | v2.1 | **按评审决策：不使用存储过程与触发器**（LD-EX-02，理由：数据库对象不便维护、升级与回滚，且容易与代码形成"两套真相"）。连带修订：① §2.2b 的一致性保障方案改为"**统一写入口 + 人工纪律 + 每日巡检**"；② **§9.4 整节重写**——改为提供可直接复制执行的**标准 SQL 模板**（删除 / 恢复 / 审计补写三组）与五个硬要求；③ 新增 §9.4.5a **放弃 `CURRENT_USER()` 自动记录**（列默认值报 `ERROR 3770`，触发器又被排除），显式承认"直连只知是 `'DB'`、不知具体人"的缺口；④ §9.4.5 巡检从"可选"升为"**必须**"；⑤ 新增 §9.4.7 将直连**定级为应急通道**；⑥ 新增风险 LD-R10a/R13 与测试 LD-T22 |
| 2026-09-22 | v2.2 | **修复唯一键致命漏洞（实施阶段发现）**。① §2.2 的唯一键由裸 `(业务键, deleted_at)` 改为**函数索引** `(业务键, IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))`；② 新增 §2.2c 记录缺陷成因、实测对比表、两个层面的后果（登录 `TooManyResultsException` + 批量软删除因 `NOW(6)` 语句级常量而撞键）与修复原理（PostgreSQL partial unique index 的等价实现）；③ 修订 LD-T2a（断言函数索引形态，并断言**不存在**裸列形态）与 LD-T8a（改为双向验证：DB 必须拒绝重复有效行 + 删除后必须能重建）；④ 落地脚本 `V3__logical_delete_functional_unique_keys.sql`（幂等、可自愈 V2 遗留形态）。**根因是设计阶段只做了正向验证（删除后能否重建），没做反向验证（能否拦住重复有效行）**——LD-T8a 本就是为反向验证而写，但未与 §2.2 的方案对照 |
