# DEC-助手「按维度看订单分布」的能力与权限口径

> 状态：**已落地**（后端工具 + 提示词 + 单测 + 真实库 IT）
> 触发场景：「请分析 2026 年第二季度投标订单，和第一季度比较，并从区域、机构、险种三个维度找出主要变化」
> 相关：工具清单见 `docs/REQ-系统管理助手能力.md` §5.1（系统域）；本文只讲**业务域（订单）**这一次新增。

---

## 一、现场：同一个问题问了三次，三种结果

2026-09-29 两位用户问同一句话（「请分析 2026 年第二季度投标订单，和第一季度比较，
并从区域、机构、险种三个维度找出主要变化」），库里留下了三次不同的会话：

| 用户 | 实际发生的（`ai_tool_call` 实测） | 结果 |
|---|---|---|
| ADMIN（23:01） | **34 次 `queryOrderSummary`**（总量 2 次 + 8 个区域 × 2 季度 + 8 家省级机构 × 2 季度）+ `queryOrg` + `queryInsuranceType` | 蛮力**成功了**：产出了完整的区域表、机构表，模型自己算的增量与增幅 |
| ADMIN（22:25） | 同样 36 次调用，但 4 轮**全部**用在取数上 | 收尾轮被上限截断 → **一个字都没有**，前端只剩空气泡 |
| 运营 / 只读（23:07~23:08） | 只调用 2~4 次 | 如实回答「我给不出」，用户反问「为什么不能」 |

> **修正一处早先的说法**：本文第一版写的是"助手没有这个工具，于是只有两条坏路"，
> 把"没有专用工具"说成了"做不到"。**那是不准确的**——模型可以靠蛮力枚举做出来
> （上表第一行就是证据）。准确的说法是"没有它就只能靠模型临场枚举"，而这条路有三个硬伤 ↓

### 1.1 蛮力那条路的三个硬伤（本工具要解决的正是这些）

| 硬伤 | 证据 |
|---|---|
| **成败取决于轮次预算，不可靠** | 同一天、同一账号、同一句话：23:01 那次成功，22:25 那次一个结论都没给出（4 轮用满被截断） |
| **险种维度根本取不到** | `queryOrderSummary` 没有险种参数，逐次调用也拆不出各险种订单量。23:01 那次"成功"的回答里，险种小节只能写「本轮未能获取到按险种拆分的订单量/金额数据……**不构成险种维度的业务变化结论**」 |
| **机构维度取决于模型自己枚举得全不全** | 那次只枚举了 `orgLevel=2` 的 8 家省级机构，而**实际有投标订单的机构有 20 家**（8 家省级 + 12 家市级，实测 `SELECT COUNT(DISTINCT org_id) FROM tender_order`）。于是它得出了「浙江、江苏…这些区域由单一机构独家承做」——**与事实相反**的结论。新工具按 `org_id` 分组，覆盖全部出函机构 |

**再澄清一个误解**：这**不是权限或数据范围问题**。实测该账号的身份是
OPERATOR + VIEWER（或 ADMIN + ANALYST），`order:tender:view`、`order:performance:view`、
`analysis:overview:view` 都在手；`AiDataScopeResolver` 的输出也是"数据范围：全量
（阶段一 O3：机构维度已移除）"。

---

## 二、决策一：补工具，而不是提高轮次上限

三个维度的分布数据**系统里本来就有**——数据概览页的
`/api/analysis/order-region`、`order-institution`、`order-insurance`
（`OrderAnalysisService` + `OrderAnalysisMapper`）。助手缺的只是"接上去"。

| 方案 | 判断 |
|---|---|
| **新增 `queryOrderDistribution`**（采纳） | 一次调用拿到一个维度的**完整**分布（含排名、编码、名称、订单量、保函金额、保费、去重企业数）——不再依赖模型自己枚举成员，也不会漏掉没被枚举到的对象。三个维度 × 两个区间 = 3~6 次调用 |
| 继续靠蛮力枚举 `queryOrderSummary`（否） | 三个硬伤见 §1.1：预算内成败不定、险种拆不出来、机构只能枚举到模型想到的那一层（实测漏了 12 家市级机构并因此给出错误结论） |
| 提高 `MAX_TOOL_ROUNDS`（未采纳） | 治标：逐个区域×逐个区间是 O(维度 × 区间 × 成员) 次调用，提到 8 轮只是把"答不出"推迟，而且每次都多一次模型往返 |
| 让模型自己算排名（不新增工具，仅放开提示词） | 模型没有 SQL，成员靠枚举、数字靠自己拼，正是"编造数字"的温床 |

实现上**只新增一个工具类**，SQL 一行没写：`OrderDistributionTool` 只依赖
`OrderAnalysisService`（分层约束与 `OrderSummaryTool` 一致：Tool → Service → Mapper）。
因此助手口径与页面口径**天然是同一份**，IT 里直接断言两者逐字段一致。

### 2.1 「对比两个区间」的用法写进了工具描述与提示词第 43 条

否则模型仍可能退回老路。工具描述里明确写了"比较两个时间区间时，对同一维度各调用一次
再对比"以及"不要为了找变化逐个区域去调 `queryOrderSummary`"；提示词第 43 条给出
调用预算（三个维度 = 3~6 次）与截断时的表述要求（必须说"只看了前 N 名"）。

---

## 三、决策二：权限与 `queryOrderSummary` **完全一致**（不新增权限码）

| 选项 | 判断 |
|---|---|
| 注册时要求 `analysis:overview:view` | **未采纳**：页面侧的 `/api/analysis/**` 本身**没有** `@PreAuthorize`（只要求登录），单方面在助手侧收紧会造成"页面上能看、助手说没权限"的新一类困惑 |
| 与 `queryOrderSummary` 一样**不额外要求权限**（采纳） | 两者是同一域、同一批数据的两种切法：一个给汇总值，一个给分布。既有的 `queryOrderSummary` 对所有 `ai:chat` 用户开放，这里不该更严 |

`AiToolRegistry` 里为此写了注释说明"不新增权限码、不改权限矩阵"。

> **既有未决项（本轮没动）**：`/api/analysis/**` 与 `/api/orders/**` 目前都只要求登录
> （见 `DEC-订单筛选下拉的选项口径.md` 第六节第 1 条）。订单域要不要加权限码，
> 需要单独拍板；本轮**没有**借这次改动顺手收紧或放宽任何权限。

---

## 四、变更清单

| 文件 | 变更 |
|---|---|
| `guarantee-ai/.../tool/OrderDistributionTool.java` | 新增只读工具 `queryOrderDistribution(dimension, orderType, startDate, endDate, limit)`；维度支持 `REGION/ORG/INSURANCE` 与中文别名；limit 默认 10、上限 50；截断时返回 `truncated` + 提示 |
| `guarantee-ai/.../tool/OrderDistributionToolResult.java` | 工具返回值 record（含嵌套 `DistributionItem` 与名次） |
| `guarantee-ai/.../tool/AiToolRegistry.java` | 注册为 READ 工具，权限口径同 `queryOrderSummary` |
| `guarantee-ai/src/main/resources/prompts/business-assistant.st` | 新增第 43 条：维度对比用本工具、调用预算、截断表述、缺维度要如实说 |
| `guarantee-ai/src/test/.../OrderDistributionToolTest.java` | 新增 8 项：三档维度字段映射、别名/非法值、limit 归一、口径行讲人话、日期区间校验、schema 生成 |
| `guarantee-ai/src/test/.../AiToolRegistryTest.java` | 构造器补参数；fail-closed 用例期望值加入新工具 |
| `guarantee-web/src/test/.../OrderDistributionToolIT.java` | 新增 4 项（真实 MySQL）：三档维度与 `OrderAnalysisService` 逐字段一致、区域合计 = 同区间投标订单总量、只持 `ai:chat` 也可见 |

---

## 五、验证记录

- 单测：`mvn -pl guarantee-ai test` → **114 项全绿**（原 106 + 新增 8）。
- IT：`mvn -pl guarantee-web verify -Dit.test=OrderDistributionToolIT` → **4 项全绿**；
  其中"区域维度合计 = 同区间投标订单总量"是**跨模块口径一致性**断言，防的是
  "助手说的数和数据概览页对不上"。
- 回归：`-Dit.test=ToolRoundCapFallbackIT,AiToolChainIT` → 6 项全绿（既有订单问答链路未受影响）。
- **真机复验（重启后，同一句话再问一次）**：`ai_tool_call` 显示 8 次调用 ——
  `queryOrderSummary` ×2（两个季度的总量）+ `queryOrderDistribution` ×6
  （区域 / 机构 / 险种 × 两个季度，每次 limit=50），回答 2647 字，三个维度全部有数：
  - 区域：8 个区域 Q1/Q2 对比，浙江 +1515 笔、江苏 +998 笔
  - 机构：**这次是从 20 家（含 12 家市级）里排名**，因此得出了与旧答案相反的结论——
    「江苏省第3保函运营机构由第 5 跃居第 1」，而旧答案因为只枚举到省级机构，
    曾断言"这些区域由单一机构独家承做"
  - 险种：**首次拿到真实拆分** —— 投标保函（标准）4860→6607、电子投标保函 3938→5575、
    投标保函（小额）2151→2923（旧答案此节只能写"本轮未能取得"）
- 写作过程中踩到并修掉的一个测试自身的坑：险种维度若拿 `orderType=ALL` 的期望值去比
  `TENDER` 的实际值，会得到"排名对不上"的假失败——已在用例里注明。
- **本文档的一处自我更正**：第一版把"没有专用工具"写成了"做不到"（"只有两条坏路"），
  忽略了"模型可以蛮力枚举"这条实际发生过且成功过的路径；§1 已按 `ai_tool_call`/`ai_message`
  的实测记录改写。结论没变（工具仍应补），但理由从"做不到"更正为
  "**做得到但不稳、且险种做不到、机构还可能漏**"。
