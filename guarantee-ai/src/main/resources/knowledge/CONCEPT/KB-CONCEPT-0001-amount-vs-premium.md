---
knowledge_no: KB-CONCEPT-0001
domain: CONCEPT
title: 保额与保费的区别
keywords: 保额,保费,担保金额,保函金额,费率,计算关系,概念,区别
version: 1
status: PUBLISHED
source_ref: guarantee-web DataInitializer（premium_amount = guarantee_amount × premium_rate）；InsuranceTypeService.toPercent:342-351
---

保额与保费是两个不同的量：

- **保额（担保金额）**：保函承担的担保金额，是"最多赔多少"的上限；
- **保费**：投保人为取得这份保函支付的费用；
- 二者的关系是 **保费 = 保额 × 费率**；费率由险种配置决定，
  每笔订单还会记录自己当时适用的费率。

由此可以区分两类问题：

- "保额区间是多少"问的是险种可承保的金额范围（**定义/规则**）；
- "总保费是多少"问的是订单数据的汇总（**统计**）。

保额、费率、保费的具体数值必须来自订单与险种数据，不能用定义、示例或历史印象代替。
