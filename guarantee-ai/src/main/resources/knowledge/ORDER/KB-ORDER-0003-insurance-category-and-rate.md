---
knowledge_no: KB-ORDER-0003
domain: ORDER
title: 险种分类与基准费率口径
keywords: 险种,险种分类,基准费率,费率,投标保函,履约保函,其他,小数,百分比
version: 1
status: PUBLISHED
source_ref: prompts/business-assistant.st L88-L90（阶段三迁出）；InsuranceTypeService.CATEGORY_NAMES:30-33；InsuranceTypeDto.MAX_BASE_RATE
---

险种有三个分类：**投标保函、履约保函、其他**。

基准费率是**小数**形式，取值必须落在 (0, 0.1] 区间：例如 0.013 表示 1.3%。
工具会同时返回小数与百分比两种表示，直接引用即可，不要自行换算——
费率看错数量级是保函业务最敏感的差错（乘 100 时尤其容易出错）。

费率变更**只影响变更之后的新订单**，历史订单记录的费率与保费不变。
