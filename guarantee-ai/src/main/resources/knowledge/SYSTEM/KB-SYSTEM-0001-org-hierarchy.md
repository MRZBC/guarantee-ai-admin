---
knowledge_no: KB-SYSTEM-0001
domain: SYSTEM
title: 机构的层级与编码规则
keywords: 机构,机构层级,总部,省级机构,市级机构,上级机构,层级关系,机构编码
version: 1
status: PUBLISHED
source_ref: prompts/business-assistant.st L70-L72（阶段三迁出）；db/schema.sql:sys_org
---

机构配置是一棵**三层树**：

- 第一层是总部，第二层是省级机构，第三层是市级机构；
- 每级机构都指向上级机构，**总部是唯一的根节点**；
- 机构编码形如 ORG3301，**创建后不可修改**。

"某省有哪些市级机构"就是沿这棵树按层级与上下级关系展开。
