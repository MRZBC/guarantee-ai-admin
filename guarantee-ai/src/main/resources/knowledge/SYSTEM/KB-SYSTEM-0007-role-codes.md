---
knowledge_no: KB-SYSTEM-0007
domain: SYSTEM
title: 内置角色及其编码
keywords: 角色,角色编码,超级管理员,运营人员,数据分析师,只读用户,ADMIN,OPERATOR,ANALYST,VIEWER
version: 1
status: PUBLISHED
source_ref: prompts/business-assistant.st L81-L82（阶段三迁出）；PermissionCatalog
---

平台内置四类角色及其编码：

- ADMIN = 超级管理员（系统配置与全局操作审计）；
- OPERATOR = 运营人员；
- ANALYST = 数据分析师；
- VIEWER = 只读用户。

角色可以自定义新增；权限只能在**已有权限码**范围内授予，不存在"新增一个权限码"的能力。
角色都有启用/停用状态，停用与删除是两件不同的事（见"停用与删除的区别"）。
