---
knowledge_no: KB-SYSTEM-0004
domain: SYSTEM
title: 部门：内部组织单元与层级
keywords: 部门,组织单元,上级部门,顶级部门,下级部门,部门树,部门层级
version: 1
status: PUBLISHED
source_ref: prompts/business-assistant.st L75-L77（阶段三迁出）；db/schema.sql:sys_department
---

部门是**内部组织单元，不归属任何机构**；整棵部门树只由上下级关系构成：

- 没有上级部门的部门即顶级部门；
- 下级部门逐级挂在上级部门之下。

"某部门下有哪些人""某部门有几个下级部门"都是沿这棵部门树看。
