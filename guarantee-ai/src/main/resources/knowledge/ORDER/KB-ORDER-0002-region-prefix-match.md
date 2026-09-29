---
knowledge_no: KB-ORDER-0002
domain: ORDER
title: 区域编码的层级前缀匹配语义
keywords: 区域,区域编码,行政区划,前缀匹配,省,市,区县,筛选口径
version: 1
status: PUBLISHED
source_ref: prompts/business-assistant.st L277（阶段三迁出）；guarantee-order RegionCodePrefix.of()
---

区域筛选按**行政区划编码的层级前缀**匹配，不是精确等于：

- 选省（例如浙江省 330000）会包含该省下全部市、区县的数据；
- 选市（例如杭州市 330100）只包含该市及其区县；
- 不选区域则表示不限。

因此"全省合计"与"逐个市相加"在同一口径下应当一致，但"选省"与"选某个市"
是不同粒度，比较时要说清用的是哪一级。
