---
knowledge_no: KB-SYSTEM-0008
domain: SYSTEM
title: 权限编码、权限类型与只读主数据
keywords: 权限,权限编码,权限类型,菜单,按钮,接口,角色授权,只读主数据
version: 1
status: PUBLISHED
source_ref: prompts/business-assistant.st L84-L86（阶段三迁出）；sys_permission DDL：db/schema.sql
---

权限主数据的口径：

- 权限编码形如「域:对象:动作」（例如系统管理域的机构查看、订单域的查看）；
- 权限类型分三类：菜单、按钮、接口；
- 权限主数据**只读**：系统不提供新增或修改权限的能力；
- 角色授权只能在**已有权限码**里选择。

因此"给某个角色新增一项系统里不存在的权限"没有对应的能力。
