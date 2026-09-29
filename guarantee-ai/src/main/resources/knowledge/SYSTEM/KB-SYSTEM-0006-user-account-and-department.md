---
knowledge_no: KB-SYSTEM-0006
domain: SYSTEM
title: 用户账号与部门归属规则
keywords: 用户,账号,登录名,所属部门,部门归属,状态,最近登录时间
version: 1
status: PUBLISHED
source_ref: prompts/business-assistant.st L79-L82（阶段三迁出）；sys_user DDL：db/schema.sql
---

用户的核心属性与规则：

- 用户**必须属于一个部门**，部门是唯一保留的归属维度（机构归属已取消）；
- 账号（登录名）**创建后不可修改**，没有"改账号"这个能力；
- 用户还有姓名、状态（启用/停用）、最近登录时间等属性；
- 停用与删除是两个正交状态，"已停用"和"已删除"可以同时存在。
