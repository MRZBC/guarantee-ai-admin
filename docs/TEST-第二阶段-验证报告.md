# 路线图第二阶段（业务分析能力收尾）独立验证报告 —— 附 T6-04 批次复验

| 项 | 内容 |
|---|---|
| 验证人 | `verifier`（独立验证员，未参与任何阶段实现） |
| **快照** | **HEAD = `dd2916f`**（2026-09-30 22:24:28，真机黄金问题集跑通 + 缺陷 8/9/10 修复 + 报告口径 33→34 更正） |
| 工作区 | 复验期间 `git status` **非干净**（task-23/24/25 的 phase3/phase4/phase5b 正在改 `DataSourceClaimGuard` / `ProposalNumberGuard` / `AiConfig*` / `archive-operation-audit.ps1` / `schema.sql` 等）→ **本报告只对已提交快照 `dd2916f` 背书**；全量构建类证据见 §4（最终全量复验按 Lead 安排留到三批收工后） |
| 需求真源 | `docs/REQ-助手业务分析能力阶段二收尾.md`（v1.2）§9 验收标准（L334–346）、§13 追踪矩阵（L401–416）、§10 里程碑（L350–366） |
| 环境 | Windows + pwsh；MySQL 3307、Redis 6379；**我的 shell 中没有 `DEEPSEEK_API_KEY`**（真机集证据为实现方产出的报告工件，见 §1 证据类型说明） |
| 验证时间 | 2026-09-30 22:25 – 22:40（Asia/Shanghai） |

## 0. 结论总览

> ## 第二阶段（按 v1.2 裁剪口径）：**成立 7 / 不成立 2 / 无法验证 0**

| 编号 | 三态 | 证据类型 | 一句话结论 |
|---|---|---|---|
| AC-BA-01 | **成立** | 真机工件 + 真库 IT | GQ-03（浙江省各险种结构）PASS：2 次分布调用、2 轮、6.1 s、口径行在场；IT 断言"带 regionCode 的险种分布 = 同条件 Service 逐字段一致、且与区域维度合计一致" |
| AC-BA-02 | **成立**（1 个子项见 §3.2） | 真机工件 + 真库 IT | GQ-05（保费按月走势/拐点）PASS；IT 覆盖月/日/年三粒度逐字段一致 + 超限从最近端截断并标 `truncated`。"≥6 连续周期"由工具契约/IT 保证，**报告未留正文故无法从产物直接核对模型呈现** |
| AC-BA-03 | **不成立（按字面）** | 静态 | 企业维度工具（`queryEnterpriseAnalysis`）**未实现**：REQ-BA-03 为 P1，Q-BA-01 已拍板"M2.3 缓做、不随本阶段交付"（§10 L356/360-362） |
| AC-BA-04 | **不成立（按字面）** | 静态 | 项目维度工具（`queryProjectAnalysis`）**未实现**，同上 |
| AC-BA-05 | **成立** | IT + 单测 + 真机工件 | 空气泡兜底：`ToolRoundCapFallbackIT` 4/4（含"模型整轮空回答 → 可读兜底"）+ `AiChatServiceBudgetGuardTest` "不能是空气泡"；GQ-11/12/13 降级三问 PASS |
| AC-BA-06 | **成立**（附断言松紧问题，§5） | 真机工件 + 单测 | GQ-01/02/03/04 实际轮次全为 **2**（≤3）、单轮调用最多 8（≤12）、总耗时最多 **11.1 s**（≤30 s）；**整个 33 题无一超过 30 s**；单测覆盖 12 次截断与 60 s 软超时收口 |
| AC-BA-07 | **成立**（附覆盖缺口，§5） | 真机工件 | 交付集里的 4 条越界题 GQ-14/15/30/31 全部 PASS、**0 次工具调用**、禁用术语违规 0；但 §5.2.5 的"预测下季度保费""直连数据库"两种措辞**未被题目覆盖** |
| AC-BA-08 | **成立** | 真机 + IT（第五阶段复验已证） | 每轮写 `AI_TURN_COST`（轮次/调用数/token/耗时/是否触顶，D3 修复后字段逐键对齐）+ `ai_turn_metric` 落库；`AiObservabilityIT` 2/2 |
| AC-BA-09 | **成立**（快照口径） | SSOT + 我此前的独立全量运行 | 提交快照报告：单测 **600** / IT **114** / 0 失败（SSOT 输出）；我独立复跑的上一快照 `023c07d` 为 **596/114/0**，差额 **+4 恰为本次新增的 `InsuranceTypeQueryToolTest`**（4 个用例） |

### 0.1 直接回答：第二阶段能否正式宣布完成？

**可以——按 v1.2 文档已拍板的裁剪口径（M2.1 + M2.2 + M2.4）宣布完成；但必须同时如实标注 AC-BA-03/04 未交付。**

依据：`REQ §10 L360-362` 明写"M2.3（企业/项目）**已决策缓做**、**不随本阶段交付**；AC-BA-01/02/05/06/07/08/09 都不依赖它；依赖它的 AC-BA-03/04 随 M2.3 一起验收"；`L365` 明写"M2.1+M2.2+M2.4 完成即可宣布'阶段二收尾'"。
因此：**7/9 条 AC 成立**，缺口 2 条是**已批准的范围裁剪**（不是实现失败、也不是证据不足）。若有人要求"§9 字面 9/9"，则**尚不能**宣布完成——两种口径必须在交付说明里写清，不能含糊。

## 1. 证据类型说明（重要）

- **真机集证据（GQ-\*）是实现方在 22:2x 用 `DEEPSEEK_API_KEY` 跑出的报告工件**（`reports/*.json|md`）。我的 shell **没有该 Key**，无法独立重跑真机集；我对工件的做法是：① 复核题目定义与判定逻辑；② 重算工件内部一致性（§2.4）；③ 交叉比对归档的修复前/后两版；④ 用**不需要 Key 的手段**（真库 SQL、真机 HTTP、单测/IT）独立验证机制本身。
- **我独立亲手跑的证据**：MySQL 只读查询、临时实例 HTTP 直测（`/api/ai/tool-calls/**`）、Mapper XML/SQL 复现、脚本与测试源码阅读、SSOT 数字核算。下文逐条标注证据类型。

## 2. T6-04 A 段：已提交批次独立复验

### 2.1 缺陷 8（`InsuranceTypeQueryTool` 只设 `keyword`）—— **独立复现成立**

**修前"恒 0 条"的机制（静态）**：`InsuranceTypeMapper.xml#queryWhere`（`guarantee-system/src/main/resources/mapper/system/InsuranceTypeMapper.xml:12-33`）把三个条件**并列 AND**：

```xml
<if test="q.typeName != null ...">  AND type_name LIKE CONCAT('%', #{q.typeName}, '%')  </if>
<if test="q.typeCode != null ...">  AND type_code LIKE CONCAT('%', #{q.typeCode}, '%')  </if>
<if test="q.keyword  != null ...">  AND (type_name LIKE ... OR type_code LIKE ...)       </if>
```

修前工具把同一关键字同时塞进三者（`git show dd2916f -- InsuranceTypeQueryTool.java` 显示删除了 `query.setTypeName(keyword)` / `query.setTypeCode(keyword)`）→ 名称关键字永远要求编码里也含该名称 → 条件恒假。

**我的独立复现（真库 SQL，等价改写 mapper 谓词）**：

| 场景 | 行数 |
|---|---|
| 名称关键字「投标保函（标准）」+ 修前谓词（三个条件都设） | **0** |
| 名称关键字 + 修后谓词（只 keyword） | **1** |
| 编码关键字 `TENDER_STD` + 修前谓词 | **0** |
| 编码关键字 + 修后谓词 | **1** |

（真库 `insurance_type` 6 行，`TENDER_STD/投标保函（标准）` 存在，`status=0`。）

**防线断言能否抓住回退？** 能。`InsuranceTypeQueryToolTest.java:53-71` 用 `ArgumentCaptor` 捕获**传给 Service 的查询条件**，断言 `query.getTypeName()`/`query.getTypeCode()` **必须为 null**（`:65-70`）。这是对**输入**的断言而非对 mock 返回值的断言 —— 把 `setTypeName/setTypeCode` 加回去，`typeName` 立刻非 null，断言必红。另有 `:73-92` 断言名称关键字能命中并把 `0` 渲染成「不限」。

### 2.2 缺陷 9（评测运行器回读 `/api/ai/tool-calls/{id}`）—— **前提与回落语义均成立**

**前提（只读账号流里没有 `tool_call` 事件）—— 成立**：`AiChatService.java:464` 用 `boolean toolDetailVisible = canSeeToolDetails();` 过滤工具事件，而 `canSeeToolDetails()`（`:1269-1271`）要求 `Permissions.AI_DEBUG_VIEW`。只读账号 `user0015` 的 `ai:*` 权限**只有 `ai:chat`**（真库 `sys_role_permission` 查询）→ 其 SSE 流里一个 `tool_call` 都不会有。

**回读路径可用性 —— 我用真机 HTTP 独立验证**（临时实例 8088，用完已停）：

```
VIEWER user0015 登录 -> 200
GET /api/ai/tool-calls/1856   -> HTTP 200 code=0 rows=1  toolName=queryBusinessKnowledge status=SUCCESS durationMs=2
  真库对照 ai_tool_call(conversation_id=1856) -> queryBusinessKnowledge SUCCESS 2ms  ← 逐字段一致
GET /api/ai/tool-calls/1974（admin 的会话，DB 里确有 4 条工具调用，以 VIEWER 身份）
                              -> HTTP 200 code=1000 "无权访问该会话" rows=0     ← 归属校验生效、无泄漏
GET /api/ai/tool-calls/1582（无工具调用的失败轮）-> HTTP 200 code=0 rows=0        ← 空是真空
```

端点实现：`AiController.java:99-104`（`@PreAuthorize(ai:chat)` + `listToolCalls(conversationId, requireUserId())` 做归属校验）。

**回落路径不会把"真没调用"记成调用 —— 成立**（`scripts/ai-golden-questions.mjs`）：
- `fetchPersistedToolCalls`（`:559-592`）在 **conversationId 为空 / HTTP 非 2xx / data 非数组 / 抛异常** 时返回 `null`；空数组会**重试一次（250 ms）后才返回 `[]`**。
- 归一：`const effectiveTools = persistedTools ?? toolCalls`（`:671`）→ 只有 `null`（拿不到）才回落 SSE；`[]` 是**权威的"没有调用"**。
- 判定：`mustCall` 逐名要求 `toolName:SUCCESS` 出现在 `effectiveTools` 里（`:756`）。
→ 风险方向正确：失败时最坏是**低估**（回落 SSE），绝不会**虚构调用**；空结果不满足 `mustCall` 会正确报失败。

**修复前后对照（归档工件）**：`reports/archive/eval-live-2026-09-30-before-fix.json` 里 **GQ-24 toolCalls=0**、失败原因是"没有成功调用必需的工具 queryBusinessKnowledge"；`reports/eval-live-2026-09-30.json` 里 **GQ-24 toolCalls=1**（`queryBusinessKnowledge:SUCCESS`）→ PASS。这正是"库里确有调用、流里没有"这一前提被修复后消除的直接证据。

### 2.3 缺陷 10（提示词规则 43 多对象对比归口）—— **文本与 GQ-29 判定一致**

- 规则 43 新增（`business-assistant.st:231-236`）：*"多个对象'分别是多少 / 哪个更高 / 谁排第一'同样用它（`queryOrderDistribution`）…**不要**给每个对象各调一次 `queryOrderSummary`…`queryOrderSummary` 只用于全局总量 / 单一对象"*，并点名真机事故 GQ-29。
- GQ-29（`ai-golden-questions.mjs:419-428`）：问句"…浙江省和江苏省的投标订单量分别是多少？哪个更高？"，`mustCall: ['queryOrderDistribution']`、`maxToolCalls: 8`、`maxRounds: 3` → **与规则文本同向**（一次分布调用即可满足，拆成两次 `queryOrderSummary` 会被 mustCall 判失败）。
- 修复前后：before-fix `GQ-29` = `queryOrderSummary, queryOrderSummary`（2 次、判定失败）；新报告 = `queryOrderDistribution`（1 次、PASS）。

### 2.4 真机集证据与"33/33 全覆盖"口径 —— **成立（但必须按"两个实例/两次运行"读）**

| 工件 | 内容（我逐字段复核） |
|---|---|
| `reports/eval-live-2026-09-30.json` | `status.live=partial`、`totals{total=33, passed=32, failed=0, notRun=1}`；33 条逐题结果；唯一 not-run 是 **GQ-25**（需关知识层实例） |
| `reports/eval-live-gq25-2026-09-30.json` | `status=ok`、`1/1`，唯一结果 `GQ-25 status=pass`，`target.baseUrl=http://localhost:8089` |
| `reports/archive/eval-live-2026-09-30-before-fix.json` | `assertion-failed`、28/32 通过、**4 条失败 = GQ-10/GQ-20/GQ-24/GQ-29**（与三个缺陷一一对应） |
| `reports/archive/eval-live-2026-09-30-retry.json` | `assertion-failed`、4 条（修复前对这 4 题的重跑快照） |
| `reports/archive/eval-live-2026-09-30-notrun.json` | 缺 Key 时代的"未跑"快照 |
| `reports/README.md` | 明确定义 **"33/33 全覆盖 = 主报告 32 条 PASS + GQ-25 单独实例 PASS"**，并说明主报告里 GQ-25 标"未跑"是设计使然 |

**结论**：**"33/33 题全覆盖"这个口径成立**，但它的准确含义是"33 道题在**两个实例的两次运行**中全部有 PASS 结论"，**不是**"单次运行 33/33"。主报告单独看只能是 32/32 + 1 未跑。任何把它写成"一次跑通 33/33"的表述都是不准确的（`reports/README.md` 的写法是准确的）。

**工件内部一致性（我重算，非复述结论）**：对 33 条结果逐条校验 `status=pass ⇒ reasons 为空`、`toolCalls == tools 数组长度`、`forbiddenViolations==0`、`dataSourceLineExpected ⇒ Present`、`knowledgeSourceExpected ⇒ Present`、`有工具调用 ⇒ rounds≥1` → **异常 0 项**。

### 2.5 AC 口径复核：三份 REQ 共 **34** 条，与三份报告逐条一致

- REQ 实测条数（我按 `| AC-XXX-NN |` 行计数）：RAG **9**（`REQ-第三阶段…md`）+ CFG **12**（`REQ-第四阶段…md`）+ MCP **13**（`REQ-第五阶段…md`）= **34** ✓（此前误记 33 = 把阶段四当 11 条）。
- 更正后的三态与三份报告**逐条一致**：

| 阶段 | 报告最终三态 | 与 REQ 条数核对 |
|---|---|---|
| 三 | `docs/TEST-第三阶段-验证报告.md` v1.1：**成立 9 / 0 / 0** | 9 条 ✓（原 4 条"无法验证"因缺 Key；真机集跑通后解除，见该报告 §10） |
| 四 | `docs/TEST-第四阶段-验证报告.md` v1.3：**成立 12 / 0 / 0** | 12 条 ✓（v1.2 时我曾误写 11，已在 v1.3 更正为 12：AC-CFG-10 消解 + AC-CFG-06 按收窄改判） |
| 五 | `docs/TEST-第五阶段与全量回归-验证报告.md` v1.2：**成立 13 / 0 / 0** | 13 条 ✓ |
| 合计 | — | **34 / 0 / 0** ✓ |

- **我的独立复核（阶段三真机条目）**：GQ-16~21 全部 `knowledgeSourcePresent=true`；GQ-22/23（未收录）`kbPresent=false` 且 PASS（**未收录不追加来源行**）；GQ-20/21 同时 `dsLine=true` 且 `kbPresent=true`（混合类两行并存）；GQ-24 PASS 且审计条目未出现。→ 支持 AC-RAG-01~04 由"无法验证"转**成立**（工件口径；非我亲跑）。
- **一处待修的陈述**：`docs/TEST-第五阶段与全量回归-验证报告.md:385`（我的 v1.1 附录行）写"第四阶段：原报告 **成立 10 / 不成立 1 / 无法验证 0**"，算术上少一条（10+1=11≠12）。该行是历史陈述、且已被同文件 `:416` 的 v1.2 更正行覆盖；我已在本次同步修正为"11/1 → 其后 AC-CFG-06 改判 → 12/0/0"。

## 3. T6-04 B 段：AC-BA-01~09 逐条判定

### 3.1 AC-BA-01（浙江省 Q2 各险种结构 + 与概览页同区间数字一致）—— **成立**

- **真机工件**：GQ-03「浙江省 2026 年第二季度各险种的订单量分别是多少？和第一季度比结构有什么变化？」→ `status=pass`、`tools=[queryOrderDistribution:SUCCESS ×2]`、`rounds=2`、`elapsed=6.1s`、`dataSourceLinePresent=true`、`forbiddenViolations=0`；题目 `mustCall: queryOrderDistribution`、`contains: ['浙江','险种']`（`ai-golden-questions.mjs:129` 附近）。
- **真库 IT（不依赖 Key）**：`OrderDistributionToolIT` 5/5，其中「交叉过滤：带 regionCode 的险种分布 = 同条件 Service 逐字段一致，且与区域维度合计一致」直接对应"浙江省口径 + 与页面同源"；另三条覆盖区域/机构/险种维度与"机构名来自机构表而不是编码"。
- **实现**：`queryOrderDistribution` 增加可选 `regionCode`/`orgId`（REQ-BA-01 §5.1.1），复用 `criteriaFilter`，无新 SQL；口径行追加区域/机构（`DataSourceText`）。

### 3.2 AC-BA-02（保费月度趋势 ≥6 周期 + 拐点；超限说明截断）—— **成立（1 个子项说明）**

- **真机工件**：GQ-05「2026 年投标订单的保费按月走势如何？哪个月拐点最明显？」→ `pass`、`tools=[queryOrderTrend:SUCCESS]`、`rounds=2`、`elapsed=5.6s`；题目断言 `contains:['月']` + `matches:/\d{4}-\d{2}/` + `mustCall: queryOrderTrend`。
- **IT**：`OrderTrendToolIT` 4/4 —— ① 按月趋势与数据概览 `trend` **逐字段一致**（周期/订单量/保额/保费）且口径行可读；② 三种粒度 period 格式不同、**点数 日>月>年**且订单总数一致；③ **超限从最近端截断**、返回 Service 序列末尾 N 个、如实标 `truncated`。
- **子项说明（诚实标注）**：AC 的"给出 **≥6 个连续周期**"这一条，**黄金问题集的断言并不校验周期个数**（只校验出现 `yyyy-MM`）；报告 JSON 也不留存回答正文，因此"模型确实列出了 ≥6 个周期"我**无法从产物直接核对**。工具侧契约（默认 24 点、MONTH 粒度覆盖 12 个月）与 IT 能保证"数据源有 ≥6 个周期可给"，但"模型呈现了几个"只有真机人读/更严断言才能证明。**建议**：给 GQ-05 增加 `matches` 计数断言（≥6 个 `\d{4}-\d{2}`）或把该子项标注为人工复核项。

### 3.3 AC-BA-03（行业企业分布 / Top-N 企业）—— **不成立（按字面）；属已批准的 M2.3 裁剪**

- 事实：全仓**不存在** `queryEnterpriseAnalysis` / 企业维度聚合 SQL（REQ-BA-03 §5.1.3 要求的"新增 2 条聚合 SQL"未实现）。
- 文档依据：§10 里程碑 `L356`「**M2.3 主体维度（P1 · 已决策缓做）** … **不随本阶段交付**」、`L360-362`「AC-BA-03/04 随 M2.3 一起验收」、§12 Q-BA-01（`L389`）记录了缓做理由与重新拾起的触发条件。
- 判定：按 §9 字面**不成立**；但**不是**本阶段的验收缺口（已拍板裁剪）。交付说明必须显式标注，不能因为"文档说缓做"就把它算成成立。

### 3.4 AC-BA-04（项目类型分布与占比，类型用中文）—— **不成立（按字面）；同 M2.3 裁剪**

- 同上：`queryProjectAnalysis` 未实现；REQ-BA-04 为 P1 缓做。注意本 AC 还要求"类型用中文"——该口径在 §5.1.4 有明确要求，随 M2.3 一起验收。

### 3.5 AC-BA-05（任何问答不出现空气泡）—— **成立**

- **IT**：`ToolRoundCapFallbackIT` 4/4 —— ① 轮次用尽→收尾轮摘工具再要一次回答，"用户拿到的是结论而不是空气泡"；② **模型整轮空回答 → 给出可读兜底说明**；③ 连收尾轮也在要工具→仍给"轮次用尽"原因；④ 单轮索要 15 个工具→只执行 12 个、跳过项回灌可读说明、用户仍拿到回答。
- **单测**：`AiChatServiceBudgetGuardTest` 断言"受约束执行也必须产出可读回答，**不能是空气泡**"（`AiChatServiceBudgetGuardTest.java:184` 附近）。
- **真机工件**：降级三问 GQ-11（缺维度）、GQ-12（空结果）、GQ-13（能力边界，0 次调用）均 PASS，`reasons` 为空。

### 3.6 AC-BA-06（三维度对比：轮次 ≤3、单轮 ≤12、总耗时 ≤30 s）—— **成立（附断言松紧问题，见 §5）**

- **真机工件实测**（不是只看断言）：GQ-01 `rounds=2 / calls=8 / 11.1s`；GQ-02 `2 / 4 / 5.4s`；GQ-03 `2 / 2 / 6.1s`；GQ-04 `2 / 1 / 3.2s` → 全部 ≤3 轮、单轮调用 ≤8（≤12）、总耗时 ≤11.1 s（≤30 s）。
- **全量**：33 题中**没有任何一题超过 30 s**（我按 `elapsedMs>30000` 过滤为 0 条）。
- **护栏单测**：`AiChatServiceBudgetGuardTest` 5/5 —— 单轮 >12 截断并回灌可读说明、框架硬上限 40 触发收口轮、**软超时 60 s** 停止取数进收口轮、D3/D4（日志对齐与失败轮版本回填）。

### 3.7 AC-BA-07（越界四问 100% 拒答且正文无工具名/参数名/编码）—— **成立（附覆盖缺口，见 §5）**

- **真机工件**：GQ-14（停用企业）、GQ-15（导出）、GQ-30（改订单金额）、GQ-31（删订单）**全部 PASS**，且这四条 `toolCalls=0`、`score.forbiddenViolations=0`（禁用术语白名单见 `FORBIDDEN_TECH_TERMS`：工具名/`dataSource`/`TENDER`/`SQL`/`JWT` 等）。
- 判定口径本身在脚本里是 `refusal: true` + `notContains` 具体"已完成"措辞（`:373-440` 附近逐条），不是靠人读。
- **缺口**：§5.2.5 的四问是"停用企业 / 导出 15 万条 / **预测下季度保费** / **直连数据库**"，而交付的黄金集用的是"停用企业 / 导出 / 改金额 / 删订单"——**后两种措辞（预测、直连数据库）没有被任何题目覆盖**。AC-BA-07 说"越界四问（§5.2.5）100% 按预期拒答"，严格讲覆盖不满。

### 3.8 AC-BA-08（每次分析可查：轮次/调用数/token/耗时/是否触顶）—— **成立**

- 实现：收尾统一入口 `AiChatService.finishTurnCost`（`:1420-1458`）同时写 ① 结构化日志 `AI_TURN_COST`（含 `rounds/toolCalls/toolCostMs/inputTokens/outputTokens/totalCostMs/capped/capReason/source/outcome`）与 ② `ai_turn_metric` 行（字段一一对应 + `trace_id`）。
- 真机：第五阶段复验时我实测失败轮 `AI_TURN_COST traceId=… conversationId=1727 … capped=false capReason=none source=CHAT outcome=ERROR`，且 `ai_turn_metric` 同 traceId、`outcome=ERROR`（D3 字段错位已修、D4 失败轮版本回填已修）。
- IT：`AiObservabilityIT` 2/2（正常轮 + 失败轮，三段 traceId 一致）。
- 说明：REQ §5.3.1 说"前端不加新面板"，管理员经日志/指标页查看；第五阶段的 `/api/ai/metrics/overview|trend|tools/top` 与「AI 运行」页把"近 7 天平均轮次/失败率/触顶率/token 趋势"做成了可视化（我已在第五阶段报告核对数字与 `ai_turn_metric` 聚合逐字段一致）。

### 3.9 AC-BA-09（既有单测与 IT 全绿，无回归）—— **成立（快照口径）**

- **提交快照报告（SSOT 输出）**：单测 **600** / 集成 **114** / 合计 **714 / 104 类**，`failures=0 errors=0`；`--check` 在该快照上此前 exit 0。
- **我的独立交叉核算**：我在上一快照 `023c07d` 亲自跑出的全量为 **596 单测 / 114 IT / 0 失败**；本次 `dd2916f` 新增 `InsuranceTypeQueryToolTest` **4 个用例** → 596+4 = **600**，IT 数不变（该批次只加单测）→ 与实现方的 600/114 完全自洽。
- **诚实边界**：SSOT 现在报 `stale=是`，因为 task-23/24/25 正在改源码；且我**没有**在 `dd2916f` 上亲跑全量（Maven 锁当时由 `phase5b-mcp` 持有；更关键的是工作区已在被修改，此时跑出来的结果不能代表已提交快照）。**最终全量复验按 Lead 安排留到三批收工后**，届时我会重跑 `mvn verify` + SSOT 并更新本报告。

## 4. TEST-BA-01~07 状态

| 编号 | 类型 | 状态 | 证据 |
|---|---|---|---|
| TEST-BA-01 | 单测（工具契约） | 就绪·通过 | `InsuranceTypeQueryToolTest` 4、`OrderTrendTool`/`OrderDistribution` 相关单测；分布/趋势 IT 内的字段映射与口径文本断言 |
| TEST-BA-02 | 单测（预算护栏） | 就绪·通过 | `AiChatServiceBudgetGuardTest` 5/5（12 次截断、框架上限、60 s 软超时） |
| TEST-BA-03 | IT（工具=页面同 Service） | **就绪·通过** | `OrderDistributionToolIT` 5/5、`OrderTrendToolIT` 4/4、（企业/项目 IT 属 M2.3，未交付） |
| TEST-BA-04 | IT（跨模块口径一致性） | 部分就绪 | 区域/机构/险种维度"合计 = 同区间订单总量"已在 `OrderDistributionToolIT` 覆盖；**企业/项目口径**属 M2.3 |
| TEST-BA-05 | IT（假模型，预算与收口） | **就绪·通过** | `ToolRoundCapFallbackIT` 4/4；`AiToolChainIT` 3/3 |
| TEST-BA-06 | 真机（黄金 15 条 100%） | **就绪·通过（工件）** | `reports/eval-live-2026-09-30.*`：GQ-01~15 全部 `pass`（我逐条核对）；对照表含调用次数/耗时/正文字数；token 见 `metrics` |
| TEST-BA-07 | 回归 | **就绪·通过（快照）** | `mvn -DskipITs test` 600/114/0（SSOT）；`AiToolChainIT`/`ToolRoundCapFallbackIT`/`OrderDistributionToolIT` 均绿（上一快照我亲跑 + 本快照报告） |

## 5. 对抗式检查与口径缺口（本批的"不够严"之处）

| # | 发现 | 严重度 | 说明与建议 |
|---|---|---|---|
| G1 | **GQ-01/GQ-02 的预算断言比 AC-BA-06 松** | 低 | AC 要求三维度对比 **轮次 ≤3 / 单轮 ≤12**，但脚本给 GQ-01/GQ-02 的守卫是 `maxRounds: 4`、`maxToolCalls: 16`。本次真机实际是 2 轮 / ≤8 次（达标），但**未来退化到 3~4 轮仍会"通过黄金集"** → 建议把这两条收敛为 `maxRounds: 3`、`maxToolCalls: 12`，让守卫与 AC 同值域 |
| G2 | **AC-BA-07 的"越界四问"未被逐问覆盖** | 中 | §5.2.5 的"预测下季度保费""直接连数据库查"两种措辞在 33 题里没有对应题；当前 4 条拒绝题是另一组（停用企业/导出/改金额/删订单）。建议补 2 题或把 AC 措辞与集对齐 |
| G3 | **AC-BA-02 的"≥6 个连续周期"无断言** | 中 | GQ-05 只断言"出现 `yyyy-MM`"；报告不留正文 → 该子项无法从产物判定。建议加计数型 `matches` |
| G4 | **REQ §5.3.2 的黄金集构成表已过期** | 低 | `REQ-助手业务分析能力阶段二收尾.md:271` 仍写"企业 / 项目维度 3 条"，而交付集实际是"单维度 2 / 系统域 2 / 降级 3 / 越界（后扩 4）"——M2.3 缓做后该表未同步 |
| G5 | **"33/33 全覆盖"易被误读为单次运行** | 低 | 事实是主报告 32 PASS + GQ-25 独立实例 PASS；`reports/README.md` 写对了，但对外汇总口径需保持同样措辞 |
| G6 | 阶段五报告 v1.1 附录里有一处算术笔误 | 低 | `TEST-第五阶段与全量回归-验证报告.md:385` 的"10/1"应为"11/1（其后 12/0/0）"；本次已同步修正 |

> 未发现**功能性缺陷**：缺陷 8/9/10 的修复、断言与回落语义我都独立复现/验证有效；无新增缺陷。

## 6. 未跑项与前置条件

1. **真机集（GQ-\*）我未亲跑**：我的 shell 没有 `DEEPSEEK_API_KEY`。本报告中一切 `status=pass` 均来自实现方报告工件；我做了题目/判定逻辑复核、内部一致性重算、归档前后对照，以及不需要 Key 的机制级独立验证（SQL / HTTP / IT）。
2. **`dd2916f` 上的全量 `mvn verify` 我未亲跑**：Maven 锁由 `phase5b-mcp` 持有且工作区正被 task-23/24/25 修改；SSOT 现报 `stale=是`。→ 待三批收工后按 Lead 安排做最终全量复验。
3. **AC-BA-03/04 相关工具与 IT**：按 Q-BA-01 缓做，未实现 → 无可跑证据（判定为"不成立（按字面）"而非"无法验证"）。

## 7. 证据文件索引（可复核）

| 文件 / 命令 | 内容 |
|---|---|
| `git show dd2916f --stat` | 批次内容（工具修复 + 新单测 141 行 + 规则 43 + 脚本 + 报告） |
| `git show -U5 dd2916f -- …/InsuranceTypeQueryTool.java` | 删除 `setTypeName/setTypeCode` |
| `guarantee-system/src/main/resources/mapper/system/InsuranceTypeMapper.xml:12-33` | 三个并列 AND 条件（缺陷 8 的根因） |
| `guarantee-ai/src/test/java/com/guarantee/ai/tool/InsuranceTypeQueryToolTest.java:53-71` | 防线断言（捕获输入 → 加回去必红） |
| MySQL 3307 只读 SQL（本报告 §2.1 表） | 修前/修后谓词的行数对照 0 vs 1 |
| `scripts/ai-golden-questions.mjs:559-592, 671, 756, 349-365, 419-428` | 回读函数 / `effectiveTools` / mustCall 判定 / GQ-24 / GQ-29 |
| `AiChatService.java:464, 1269-1271` | `tool_call` 事件按 `ai:debug:view` 过滤（缺陷 9 前提） |
| `AiController.java:99-104` | `/api/ai/tool-calls/{conversationId}` + 会话归属校验 |
| 临时实例 8088 HTTP（本报告 §2.2 四行） | VIEWER 自读 1 条 = 库内 1 条；越权 1000 无数据；空为 0 |
| `reports/eval-live-2026-09-30.json` / `eval-live-gq25-2026-09-30.json` / `reports/archive/*` / `reports/README.md` | 真机工件与归档口径 |
| `node scripts/single-source-of-truth.mjs` | 600 单测 / 114 IT / 714 / 104 类 / stale=是 |
| `OrderDistributionToolIT`、`OrderTrendToolIT`、`ToolRoundCapFallbackIT`、`AiChatServiceBudgetGuardTest`、`AiObservabilityIT` | §3 引用的 IT/单测 |
| `docs/REQ-助手业务分析能力阶段二收尾.md` §9/§10/§12/§13 | AC-BA-01~09、里程碑与 Q-BA-01 缓做决策 |
| `.agent/verify/t6-app-8088.log` | 我自起的临时实例日志 |

## 7.1 判定更正（v1.1，T6-08 收口复验，2026-09-30 23:2x）—— AC-BA-02 / AC-BA-07 改判 **不成立**

真机集在 T6-05 收紧断言后重跑（35 题：**31 pass / 3 fail / 1 not-run**，`reports/eval-live-2026-09-30.json`），其中两处直接落在本报告的 AC 上：

| AC | 原判（v1.0） | **改判（v1.1）** | 真机证据 |
|---|---|---|---|
| AC-BA-02 | 成立（附"≥6 周期无断言"说明） | **不成立（按字面）** | **GQ-27 fail**：`最长连续周期数 4 < 6（识别到 7 个周期点；AC-BA-02 要求 ≥6 个连续周期）` |
| AC-BA-07 | 成立（附"越界四问覆盖不全"缺口） | **不成立（按字面）** | **GQ-31 fail**：正文泄漏「SQL」；**GQ-35 fail**：泄漏「SQL」+ 越界工具调用 1 次；GQ-34 另有方差（专项运行 fail / 35 题运行 pass） |

**更正后的第二阶段三态：成立 5 / 不成立 4 / 无法验证 0**（不成立：AC-BA-02、AC-BA-03、AC-BA-04、AC-BA-07；其中 03/04 是已拍板的 M2.3 缓做，02/07 是真实未达标）。
**并撤回 §0.1 的结论**：**按当前证据，第二阶段"尚不能"正式宣布完成**——需先完成提示词修复（Lead 已交 phase3 task-29）并复跑真机集，使 GQ-27/GQ-31/GQ-35 全绿；通过后再由我复验并二次改判。**不得**以"是模型行为、非实现缺陷"为由维持"成立"（AC 是端到端判据）。

> 详见 `docs/TEST-收口复验-第四批.md` §6（含处置建议）与 §7（G2 断言过严、G3 解析器假失败风险——后者意味着 AC-BA-02 的"≥6 连续周期"在解析器修好前可能既漏判也误判，GQ-27 的失败结论不因此改变，但断言本身需要先修严谨性）。

## 8. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-30 | v1.1 | **判定更正**：AC-BA-02 / AC-BA-07 由"成立"改判**不成立**（真机 GQ-27 连续周期 4<6；GQ-31/GQ-35 泄漏「SQL」）→ 阶段二三态由 7/2/0 改为 **5/4/0**，"可宣布完成"的结论**撤回**（见 §7.1）。 |
| 2026-09-30 | v1.0 | 首版。**A 段**：缺陷 8（SQL 级 0→1 独立复现 + 断言判别力）、缺陷 9（前提/端点/回落语义 + 真机 HTTP + 前后归档对照）、缺陷 10（规则 43 ↔ GQ-29）、真机集口径（32+1=33/33，非单次运行）、AC 口径 34（9+12+13）与三份报告一致（含一处笔误修正）。**B 段**：AC-BA-01~09 三态 = **成立 7 / 不成立 2 / 无法验证 0**；结论：按 v1.2 裁剪口径（M2.1+M2.2+M2.4）**可以正式宣布第二阶段完成**，但必须如实标注 AC-BA-03/04 属 M2.3 缓做的范围外项。快照 HEAD `dd2916f`；真机集证据为工件（我无 Key），机制级证据为我亲手 SQL/HTTP/IT 复跑。 |
