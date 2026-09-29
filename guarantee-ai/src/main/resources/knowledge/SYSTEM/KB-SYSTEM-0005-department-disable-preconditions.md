---
knowledge_no: KB-SYSTEM-0005
domain: SYSTEM
title: 部门的挂载约束与停用前置条件
keywords: 部门停用,下级部门,启用用户,前置条件,挂载关系,停用失败
version: 1
status: PUBLISHED
source_ref: prompts/business-assistant.st L75-L77（阶段三迁出）
---

停用一个部门前，系统会检查两件事：

- 该部门**不能还有下级部门**；
- 该部门**不能还有启用中的用户**。

两条都满足才能停用；不满足时系统会拒绝，并在失败信息里给出具体数量。

这与"删除"的前置检查同源（见"删除的前置检查口径"），但适用对象不同：
停用只针对部门与角色，删除还覆盖机构、用户、险种。
