---
title: 业务口径唯一实现
status: active
---

# 决策 - 业务口径唯一实现

### decision

任何业务统计口径**只能有一处实现**。订单统计的全部口径落在 `OrderStatisticsService`，
页面接口与 AI 工具**共用同一个 Service**；AI 侧只允许通过 Tool → Service 访问。

具体到订单类型聚合：`orderType=ALL` 时，分析查询对 `tender_order` 与 `performance_order`
做 `UNION ALL` **之后**再聚合，而不是分别统计再相加。

### why

- **两处实现必然分叉。** 页面与 AI 各写一份统计，短期看是「快」，长期是
  「同一问题两个答案」——而这恰恰是 AI 助手最致命的信任问题。
- 共用同一个 Service 之后，AI 的回答与页面数字**天然一致**，
  这让「交叉验证」从「需要人工核对」变成「结构性保证」。
- `UNION ALL` 后聚合是为了**去重口径正确**：同一企业可能同时有投标与履约订单，
  分别统计再相加会把企业数、项目数算重。

### context

AI 助手要回答「2026年第三季度投标订单有多少」这类问题。
最省事的做法是在 Tool 里直接查库或写一段聚合 SQL，
但那就等于把业务口径复制到 AI 模块。README §十一 明确：
禁止 AI 直接访问数据库、禁止生成任意 SQL、禁止直接操作 Mapper，所有 Tool 只能调用业务 Service。

### alternatives

- **Tool 里直接注入 Mapper 并写查询** —— 口径复制到 AI 模块，页面与 AI 会分叉；
  且违反分层铁律
- **让 AI 生成 SQL 再执行** —— 安全与口径双重不可控，明确禁止
- **页面与 AI 各调各的接口** —— 看起来复用了「接口」，但接口内部仍可能各写各的口径；
  且面向浏览器的接口契约（分页、筛选语义）不适合模型调用
- **分别统计投保与履约再相加** —— 去重的企业数 / 项目数会偏高，口径错误

### constraints

- `Tool → Service → Mapper → DB` 是铁律，Tool 不注入 Mapper
- `orderType` 需支持 `TENDER` / `PERFORMANCE` / `ALL`，且接受中文「投标」「履约」
- 统计结果必须回显查询条件与 `dataSource`，便于核对口径
- 订单表索引需支撑 15 万行规模（`apply_date`、`region_code`、`org_id`、
  `insurance_type_id`、`(org_id, apply_date)`）

### consequences

**正面**

- AI 回答与页面统计**结构性一致**，交叉验证成为可能（README §九 的 Demo 步骤正是靠它）
- `AiToolChainIT` 能断言「Tool 返回的 5 个指标与直接调用 Service 逐一相等」——
  这条断言只有在共用实现时才成立
- 口径变更只需改一处，页面与 AI 同时受益
- 安全边界清晰：AI 模块内不存在 SQL

**负面**

- `orderType=ALL` 做 `UNION ALL` 比单表查询慢，且随数据量增长
- 共用 Service 意味着面向页面的口径变更会同时影响 AI 回答，
  需要意识到「改一处、动两处」
- 统计 Service 成为热点依赖，任何性能问题都会同时影响页面与 AI

### revisitConditions

- 当 `UNION ALL` 聚合成为真实性能瓶颈，且无法用索引或预聚合解决时
- 当 AI 需要与页面**不同的**聚合粒度（例如逐笔明细），需要扩展 Service 而非另写一份时
- 当引入缓存或预聚合表时（必须保证页面与 AI 读的是同一份）

### related

- [[决策 - 采用模块化单体而非微服务]]
- [[技术 - AI 工具调用链路]]
- [[概念 - 保函业务领域模型]]
