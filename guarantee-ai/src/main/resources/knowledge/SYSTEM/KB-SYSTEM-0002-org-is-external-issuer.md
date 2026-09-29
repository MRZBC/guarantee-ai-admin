---
knowledge_no: KB-SYSTEM-0002
domain: SYSTEM
title: 机构的业务定位：出函机构，不是人的归属
keywords: 机构,出函机构,订单归属,人的归属,用户归属,部门归属
version: 1
status: PUBLISHED
source_ref: prompts/business-assistant.st L71-L72（阶段三迁出）；PLAN-移除用户与部门的机构归属
---

机构是**外部的出函机构，服务于订单**，不是"人的归属维度"：

- 订单自带机构信息，机构是订单的一个维度；
- **用户与部门都不归属任何机构**；
- 因此"某个机构下有哪些人""某机构有哪些部门"在当前模型里没有答案。

把机构理解成"部门的上层"或"人的归属"会得出错误结论。
