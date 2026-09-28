---
type: technology
title: 数据模型与逻辑删除
status: active
related:
  - 决策 - 全表逻辑删除设计
  - 概念 - 保函业务领域模型
tags: [database, mysql, logical-delete, schema]
---

> `schema.sql` 是表结构的权威来源。这一页记两件容易搞错的事：
> **实际有多少张表**（与 README 不一致），以及**逻辑删除的唯一键为什么必须用函数索引**。

## 是什么

`schema.sql` 位于 `guarantee-web/src/main/resources/db/schema.sql`，
全部 `CREATE TABLE IF NOT EXISTS`，可重复执行。

**实际 20 张表**（README §五 写的是 16 张，已滞后）：

| 域 | 表 |
| --- | --- |
| 系统配置 | `sys_org`、`sys_department`、`sys_user`、`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission` |
| 地区 | `sys_region` |
| 险种 | `insurance_type` |
| 业务 | `enterprise`、`project` |
| 订单 | `tender_order`、`performance_order` |
| AI 会话与审计 | `ai_conversation`、`ai_message`、`ai_tool_call`、`ai_audit_log` |
| AI 写操作提案 | `ai_operation_proposal`、`ai_operation_audit`、`ai_operation_secret` |

## 逻辑删除：两个正交维度

这是整套设计的**地基**，后续规则都从它推出：

| 维度 | 字段 | 取值 | 回答什么 | 可逆 |
| --- | --- | --- | --- | --- |
| 能力状态 | `status` | `1` 启用 / `0` 停用 | 这个主体还能不能参与业务 | 可逆 |
| 存在状态 | `is_deleted` | `0` 正常 / `1` 已删除 | 这条记录还用不用 | 可逆 |

**`status=1, is_deleted=1` 是允许的**：删除只改 `is_deleted`，**不隐式改 `status`**。
否则「恢复」无法还原删除前的状态，用户会觉得「恢复回来的和删之前不一样」。

三个字段的分工：

```sql
is_deleted  TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除 0正常 1已删除',
deleted_at  DATETIME(6) NULL     DEFAULT NULL   COMMENT '删除时间（微秒精度，同时是唯一键分量）',
deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB'   COMMENT '删除人标识：应用写 sys_user.id，直连删除为 DB'
```

`deleted_by` 用 `VARCHAR` 而不是 `BIGINT`，是为了能用默认值 `'DB'` 表达「数据库直连删除」。
用 `BIGINT DEFAULT 0` 会被误读为「存在 id=0 的用户」，把「未知」伪装成「已知」，
反而降低审计可信度。

## 唯一键：为什么必须是函数索引

唯一键统一改为：

```sql
UNIQUE KEY uk_tender_order_no (order_no, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')))
```

**v2.1 用裸 `(业务键, deleted_at)`，存在缺陷**：`deleted_at` 可为 `NULL`，
而在 MySQL 里 `NULL != NULL`，唯一索引**不约束 NULL** —— 于是可以插入任意多条
`deleted_at IS NULL` 的同业务键记录，等于「同一业务键有多条有效行」的漏洞没被堵住。

改成函数索引 `IFNULL(deleted_at, 宏) ` 后：

- 未删除的行（`deleted_at IS NULL`）→ 索引值是固定哨兵 `'1970-01-01 00:00:00.000000'`
  → 同一业务键只允许一条有效行 ✔
- 已删除的行 → 索引值是各自的删除时间 → 可以有多条历史删除记录 ✔
- 删除后业务键**可被复用**（新记录 `deleted_at` 又是 NULL 哨兵，与已删除行的值不同）✔

13 个唯一键按此改造，迁移脚本：
`V1__logical_delete.sql`（加列 + 索引）、`V2__logical_delete_unique_keys.sql`。

## 三条硬规则

| 编号 | 规则 |
| --- | --- |
| LD-01 | 所有业务查询默认只返回 `is_deleted = 0` 的行 |
| LD-02 | 删除 = `is_deleted: 0→1` + `deleted_at`（`NOW(6)`）+ `deleted_by`；**不改 `status`** |
| LD-03 | 停用前置检查（如「部门下有启用用户不能停用」）**只统计 `is_deleted=0` 的行** —— 已删除的用户不该继续阻塞部门停用 |

## 坑 / 限制

- **`ai_operation_secret` 是唯一不加逻辑删除字段的表**（保存提案中的敏感信息如初始密码）。
  它有独立的清理路径，不要照着别的表给它加 `is_deleted`。
- **绝对禁止**存储过程 / 触发器 / 函数（LD-EX-02），也**没有**把主键改成雪花 ID（LD-Q11）。
  这是评审结论，不要在后续改造中引入。
- **前端已撤除「显示已删除」与「恢复」入口**（机构/部门/用户/角色/险种 5 个页面），
  但**后端接口与契约保留**（`DELETE` / `restore` / `includeDeleted` 参数级鉴权仍在）。
  改动前端时不要以为后端也没了。
- 订单表在 `apply_date`、`region_code`、`org_id`、`insurance_type_id`、
  `(org_id, apply_date)` 上建了索引，为 15 万行规模的分析查询服务。
