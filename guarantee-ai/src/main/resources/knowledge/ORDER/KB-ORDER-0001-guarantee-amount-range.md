---
knowledge_no: KB-ORDER-0001
domain: ORDER
title: 保额区间的口径（不填=不限）
keywords: 保额区间,保额,担保金额,承保金额,上下限,不限,险种配置
version: 1
status: PUBLISHED
source_ref: guarantee-system/src/main/java/com/guarantee/system/service/InsuranceTypeService.java:99-117（AMOUNT_UNLIMITED）
---

保额区间是**险种配置**的一部分，不是统计结果：

- 每个险种可以配置最小保额与最大保额（单位：元），表示该险种可承保的保函金额范围；
- **不填 = 不限**：不设下限或不设上限；系统内部以 0 表示"不限"，界面与工具都会显示"不限"；
- 不同险种的区间可以不同；"投标保函 / 履约保函的区间是多少"要**逐个险种读取配置**，
  不能用某个类别的数值代表全部，也不能用历史订单的金额分布反推区间；
- 区间是面向新业务的承保条件，历史订单不受区间调整影响。

本条目只解释口径，**不含任何具体数值**——具体数值必须查险种配置。
