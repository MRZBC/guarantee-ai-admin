---
knowledge_no: KB-SYSTEM-0010
domain: SYSTEM
title: 操作审计记录的内容与渠道
keywords: 操作审计,变更记录,审计渠道,助手确认,页面直连,提案链路,变更前后
version: 1
status: PUBLISHED
permission_code: system:audit:view
source_ref: prompts/business-assistant.st L92-L93（阶段三迁出）；ai_operation_audit DDL：db/schema.sql
---

操作审计记录"**谁、何时、通过什么渠道、把什么对象的哪些字段改成了什么**"：

- 渠道分两种：业务助手确认执行、页面直接操作；
- 每条记录都带变更前后的字段对比；**敏感字段只记录"是否发生变更"，不记录具体值**；
- 变更提案与执行结果可以串成完整链路（提案编号、确认时间、执行结果）；
- 审计记录**只增不改不删**，不存在"删除审计记录"的能力；
- 全局操作审计只有超级管理员可见（它涉及他人与他域的变更）。
