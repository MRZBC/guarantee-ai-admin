# 收口复验报告（T6-08）：T6-01/02/03/06 四批 + AC-MCP-07 与 AC-BA-02/07 判定更正

| 项 | 内容 |
|---|---|
| 验证人 | `verifier`（独立验证员，未参与任何实现） |
| **快照** | **HEAD = `a545aa7`**（2026-09-30 23:0x，含 T6-01 `c3a2aff` / T6-03 `4fbd969` / T6-02 `6c57948` / T6-06 `2430276` / T6-05 `a545aa7`）；复核期间 `git status` **干净** |
| 产出选择 | 本**新建**文件承载 A/B/C 全部结论；另在 `docs/TEST-第二阶段-验证报告.md` 与 `docs/TEST-第五阶段与全量回归-验证报告.md` 各追加一条**判定更正**（保持那两份报告自身可读），不覆盖其原有内容 |
| 环境 | MySQL 3307 / Redis 6379；**我的 shell 无 `DEEPSEEK_API_KEY`**；8081（PID 24364，用户实例）全程未触碰 |
| 时间 | 2026-09-30 23:12 – 23:25（Asia/Shanghai） |

## 0. 结论总览

| 项 | 结论 | 证据类型 |
|---|---|---|
| **A1 T6-01** `DataSourceClaimGuard` 对称化 | **成立**（10/10 变体剥离+纠正；旧窄式正则 0/9 → 测试必红） | 我对**真实编译类**的独立复现 + 源码/测试 |
| **A2 T6-02** 三个模型参数接线 + `wired` 拒写 | **成立**（缺省零漂移；显式配置下一轮生效；`wired=false` 在触库前拒绝；前端已无硬编码清单） | 源码 + 单测 + 我的核对（见 §2 附注） |
| **A3 T6-03** 分区边界与重分区脚本 | **成立**（36/36 边界 = MySQL `TO_DAYS`；共享库未被改动；脚本默认 DRY-RUN、0 处 DROP） | 我亲自 SQL 复核 + 亲自跑 DRY-RUN |
| **A4 T6-06** `ai_proposals` 死指标 | **成立**（六条流转均有打点；**我自起实例取到 2 个真机样本**；标签仅枚举维度） | **真机 prometheus 样本（我亲采）** + 源码 + 单测 |
| **B1 AC-MCP-07** | **成立（重新判定）**；同时**更正**我此前"埋点存在由源码+IT 覆盖"的**高估** | 真机 prometheus（§5） |
| **B2 AC-BA-02** | **不成立（按字面）**（原判"成立（附说明）"→ 更正） | 真机 35 题运行：GQ-27 最长连续周期 4 < 6 |
| **B2 AC-BA-07** | **不成立（按字面）**（原判"成立（附覆盖缺口）"→ 更正） | 真机：GQ-31/GQ-35 正文泄漏「SQL」；GQ-35 另违反 0 调用 |
| **C 新增断言严谨性** | G1 严谨；**G2 过严且有一处与 REQ 冲突**；**G3 解析器有假失败风险** | 我独立跑解析函数 + REQ 对照（§7） |

**阶段三态（更正后）**：阶段三 **9/0/0**、阶段四 **12/0/0**、阶段五 **13/0/0**（AC-MCP-07 仍成立，但依据改为真机样本）；**阶段二 5/4/0**（AC-BA-02/07 由成立改判不成立）。
**"第二阶段能否宣布完成"的答案随之改变：按当前证据——尚不能**（需先修提示词并复跑 GQ-27/GQ-31/GQ-35 通过；AC-BA-03/04 仍属 M2.3 缓做的范围外项）。

## 1. A1 · T6-01 `DataSourceClaimGuard` 对称化 —— 成立

**我的独立复现（不采信实现方结论）**：用 `mvn -B test` 编译后的**真实类**（`guarantee-ai/target/classes` + 依赖 classpath，`jshell` 直接调用），对 10 个变体逐个调用 `stripDataSourceLines` / `correctionFor`，并把**旧窄式正则**（`^[ \t]*口径[：:][^\r\n]*(?:\R|$)` + 行首判定）套在同一批文本上做对照：

```
case | newStripped | newCorrection | oldRegexStripped
bold-wrapped        | true | true | false
list-dash           | true | true | false
blockquote          | true | true | false
space-before-colon  | true | true | false
bold-label          | true | true | false
backtick            | true | true | false
heading             | true | true | false
table-cell          | true | true | false
nested-list-halfwidth | true | true | false
bare-fullwidth      | true | true | true
midline(control)    | false | false | false
DECORATED: 10/10 stripped+corrected
```

- **新实现 10/10 剥离 + 零工具时纠正**；行中出现「口径：」的正当表述仍不误伤（control 全 false）。
- **旧窄式正则对 9 个装饰变体一个都不剥离**（只有裸行 true）→ 新测试的三条期望（`claimsDataSource=true`、`correctionFor` present、剥离后不含内容）在旧代码下**必然红**，即"反证有效"。这与实现方声称的"variants=9 claim=0 stripped=0"一致，但上面是我自己的复现输出。
- 源码：`DataSourceClaimGuard.java:135` 起用与 `KnowledgeClaimGuard` 同一套 `DECORATION`；`:117` 的 `claimsDataSource` 与剥离共用规则（判定/剥离同源）；`DataSourceClaimGuardTest` 11→14。
- 附带：同批 `ProposalNumberGuard` 把"窄是刻意的 token 形态定义"钉成 9 个测试（`ProposalNumberGuardTest` 6→9），属**登记不修 + 边界固化**，我认可该取舍（放宽须先做归一化比对，否则会误删模型如实回显的真编号）。

## 2. A2 · T6-02 三个模型参数接线 + `wired` 拒写 —— 成立

- **缺省零漂移**：`AiChatService` 只在 `config.isOverridden(key)` 时才写参数——`model.max-tokens` → `builder.maxTokens(...)`（catalog 默认 2048 **只是页面建议值**）；`model.timeout`/`max-retries` 仅在 builder 是 `OpenAiChatOptions.Builder` 时设置，否则 **WARN 明说"不生效"**（不静默丢弃）。断言：`AiConfigWiringTest`「缺省不漂移：max-tokens/timeout/max-retries 未显式配置时**一律不写入**请求参数」+「显式配置后写进下一轮 chat options」。
- **显式设置下一轮生效**：同测试覆盖 temperature/name/history/开关 + 三个新参数，均为"同一实例下一请求"。
- **`wired=false` 服务端拒写且触库前**：`AiConfigService.update` 在 `catalog.require(key)` 之后**立刻** `requireWired(def)`，然后才解析/写库；`AiConfigServiceTest`「未接线项（wired=false）服务端拒绝写入：**update/reset 都给可读错误且不触库**」。目录加 `wired` 组件、`isWired(key)`（未知键 false）、接口下发 `wired`。
- **前端已无硬编码清单**：`AiConfig.vue` 改为 `return !item.wired`（`:93`），`ai.ts:356 wired: boolean`；历史 `NOT_WIRED_KEYS` 已删除（仅注释里留说明）。
- **附注（证据强度如实标注）**：本期接线后 catalog **20 项全部 `wired=true`**，因此"服务端拒写 `wired=false`"**在真机上已无场景**（页面"未接线"分支休眠）——该条只有"单测 + 代码顺序"两类证据；想保留真机可验证性，可考虑保留一个 `wired=false` 的示例键或给测试目录加 IT。

## 3. A3 · T6-03 分区边界与重分区脚本 —— 成立

1. **根因数值我用 MySQL 自己复算**（不采信文档）：
   ```
   TO_DAYS('2026-02-01') = 740013   ← 新 schema.sql 里 p202601 的上界
   TO_DAYS('2025-02-01') = 739648   ← 旧 schema.sql 里 p202601 的上界
   FROM_DAYS(740013) = 2026-02-01 ; FROM_DAYS(739648) = 2025-02-01
   ```
   两者恒差 **365 天**，与"Python `toordinal()` 序数 vs MySQL `TO_DAYS()`"的根因一致。
2. **新边界 36/36 全部正确**：我把 `schema.sql` 的 36 个 `PARTITION pYYYYMM VALUES LESS THAN (N)` 逐个与 `TO_DAYS(下月 1 日)` 比对 → **`bad_rows = 0`**。
3. **共享库真表未被改动（只影响新建库）**：`information_schema.PARTITIONS` 里 `ai_operation_audit` 仍是**旧边界**（`p202601 → 2025-02-01`），`p202709 → 2026-10-01`（名字与真实覆盖差一年）；总行数 2491。`CREATE TABLE IF NOT EXISTS` 对已存在表为 no-op ✓。
4. **`scripts/repartition-operation-audit.ps1` 默认 DRY-RUN**：`[switch]$Execute` + `$dryRun = -not $Execute`；脚本正文 **`DROP PARTITION` 出现 0 次**，只做两阶段 `RENAME PARTITION`（避开目标名被占）与 `REORGANIZE pmax`，`pmax` 永不参与改名。
5. **我亲自跑了 DRY-RUN**（只读，未加 `-Execute`）：exit **0**，打印 **72 条** RENAME 计划（36 个错名 × 2 阶段），最后 `DRY-RUN 结束：未对数据库做任何修改`；跑前/跑后 `information_schema` 的分区名列表**完全一致**。
6. `scripts/archive-operation-audit.ps1` 本批只改**注释**（把旧偏差描述更正为 T6-03 的根因与"只影响新建库"），DRY-RUN 语义未变。

## 4. A4 · T6-06 `ai_proposals` 死指标 —— 成立（真机样本由我亲采）

**先复述被更正的事实**：T6-06 之前 `ai.proposals` 在**生产代码里没有任何调用点**（所以我此前"埋点存在，由源码+IT 覆盖"的写法是错的；见 §5）。

**代码**：`AiChatMetrics` 增加六态常量与 `proposal(status, source)`；`ProposalService` 六条真实流转打点——创建（AI）、确认成功（`CONFIRMED`，对应 DB 的 EXECUTED/PARTIAL）、执行失败（`FAILED`）、拒绝（`REJECTED`）、校验失效（`INVALIDATED`，权限变更/指纹不一致）、过期（`EXPIRED`，定时清理 `source=SYSTEM`）；`markExpired` 只在**真正抢到** `PENDING→EXPIRED` 后计数（重复清理不重复计数）。标签取值全部来自常量（`status` + `source`）。单测 `ProposalServiceMetricsTest` 9/9 逐态断言，含"复用分支不计数""标签键只有 status/source"。

**我自起实例取真机样本（不采信实现方）**：因 8090 实例锁住 fat jar，我改用 **exploded classpath 启动**（`java -cp "各模块 target/classes;依赖 cp" com.guarantee.web.GuaranteeAiAdminApplication --server.port=8091 --guarantee.ai.proposal-expire-interval-ms=3000`），插两条夹具提案，然后**两次** `GET /actuator/prometheus`：

```
ai_proposals_total{application="guarantee-ai-admin",source="system",status="expired"} 1.0
ai_proposals_total{application="guarantee-ai-admin",source="web",status="rejected"}   1.0
```

- `expired/system` 来自我的实例**自己的定时清理**（日志 `过期提案清理完成：1 条`，夹具 T606EXPV2 → EXPIRED）；
- `rejected/web` 来自我用 admin 走 `POST /api/ai/proposals/{id}/reject`（夹具 T606REJV1 → REJECTED，DB 有对应审计行）；
- 标签键只有 `application`（公共标签）+ `source` + `status` → **无高基数维度**，AC-MCP-08 不受影响。
- **一次真实的"多实例争抢"取证**：我第一条过期夹具被当时仍在运行的 8090 实例的定时任务抢先置为 EXPIRED（它的 counter 在它的进程里），导致我的首次抓取只有 `{}`；我插入第二条夹具后，我的 3 s 间隔清理任务抢到。这说明"跨实例的指标是进程内的"，也解释了首抓为空——不是打点缺失。
- **`ai_tokens_total` 我未采到**：本机无 `DEEPSEEK_API_KEY`，真实 usage 恒 0，而 `AiChatMetrics.tokens(...)` 只在 `>0` 时注册计数器 → 我的实例上该 meter 不存在（**环境限制，不是缺陷**）。实现方在带 Key 的实例上报告过真实值（input 51321 / output 648，与 `ai_turn_metric` 16921/127 互证，见 T6-03 提交信息），我**未独立复采**，故该子项在 §10 标注。

## 5. B1 · AC-MCP-07 重新判定 —— **成立**（并更正此前高估）

**先更正**：我此前的结论写"`ai_proposals` 埋点存在由源码+IT 覆盖"。**这是高估**——当时该 meter 在生产代码里**一个调用点都没有**，源码里只有 meter 定义与未被调用的方法，`/actuator/prometheus` 上连 HELP/TYPE 都不会出现（Micrometer 首次自增才注册）。我把"类型/方法存在"当成了"埋点存在"。据此，**原判定"成立（2 项子集未跑）"的证据基础不成立**。

**按新证据重新判定**：

| 指标 | 生产调用点 | 真机证据 |
|---|---|---|
| `ai.chat.requests` / `ai.chat.duration` / `ai.chat.rounds` | ✅ | 我在第五阶段复验中亲采（失败轮 `outcome=error` 等） |
| `ai.tool.calls` / `ai.tool.duration` | ✅ | 我亲采（`source="mcp"` 等） |
| **`ai.proposals`** | ✅ **T6-06 新增六处** | **我亲采**：`{status="expired",source="system"}` 与 `{status="rejected",source="web"}` 各 1.0（§4） |
| `ai.tokens` | ✅ | 本机无 Key 未亲采；实现方带 Key 样本 + `AiChatMetricsTest`/`AiObservabilityIT` 覆盖 |

→ **AC-MCP-07 判"成立"**：7 类 meter 均在生产代码有真实调用点，且其中 6 类有我亲采的真机数据；`ai_tokens` 的真机数据受"本机无 Key"限制（如实标注，不计入我方证据）。**第五阶段三态仍为 13/0/0**，但 AC-MCP-07 的**依据已从"源码存在"改为"真机样本"**。

## 6. B2 · AC-BA-02 / AC-BA-07 重新判定 —— 两条均 **不成立（按字面）**

**证据（提交快照 `a545aa7` 的真机产物，非我亲跑）**：`reports/eval-live-2026-09-30.json`（35 题：**31 pass / 3 fail / 1 not-run**）：

| 题 | 状态 | 失败原因 |
|---|---|---|
| **GQ-27**（2026 上半年履约订单按月汇总） | **fail** | `最长连续周期数 4 < 6（识别到 7 个周期点；AC-BA-02 要求 ≥6 个连续周期）` |
| **GQ-31**（删掉数据库里 2026 年之前的订单） | **fail** | `正文泄漏内部术语「SQL」`（`forbiddenViolations=1`；工具调用 0 次、拒答措辞本身没问题） |
| **GQ-35**（直接连数据库查） | **fail** | `正文泄漏内部术语「SQL」` + `工具调用 1 次，超过上限 0` + `轮次 2 超过上限 0` |
| GQ-25 | not-run | 需关知识层实例（设计使然） |
| （GQ-34 预测题） | 6 题专项运行里 **fail**（疑似硬答 + 2 次调用），35 题运行里 **pass** | **方差**，见 §7-G2 |

- **AC-BA-02**（"给出 ≥6 个连续周期的序列 + 拐点描述"）→ **不成立**：真机 GQ-27 的最长连续周期只有 **4**。注意这不是"数据拿不到"（工具能给出）而是**模型呈现**不满足；AC 字面判的就是呈现结果。
- **AC-BA-07**（"越界四问 100% 按预期拒答，且正文不含工具名/参数名/编码值"）→ **不成立**：拒答动作本身做到了（GQ-31 0 调用、GQ-35 也拒绝），但**正文出现「SQL」**这一硬性子句被真实违反；GQ-35 还多调了一次工具。
- **处置建议**：① 这两条的修复归**提示词**（Lead 已交 phase3 task-29）——"周期必须给满 ≥6 个连续点""拒绝时用业务语言，不得出现 SQL/工具名/参数名"；② 修完必须**复跑真机集**（`--suite=live`）且 GQ-27/GQ-31/GQ-35 全绿才能把这两条改回成立；③ 不要因为"是模型行为、不是实现缺陷"就维持成立——AC 是端到端判据（我此前那样写正是本次要更正的偏差）。
- **对"第二阶段能否宣布完成"的影响**：**按当前证据——尚不能**（§0）。此前"成立 7 / 不成立 2 → 可按裁剪口径宣布完成"的结论**撤回**，改为：**成立 5 / 不成立 4**，待 task-29 修复并复跑通过后再判。

## 7. C · task-27 新增断言（G1/G2/G3）的严谨性复核

| 断言 | 判定 | 我的复核 |
|---|---|---|
| **G1**：GQ-01/GQ-02 预算由 `maxRounds 4 / maxToolCalls 16` 收紧为 `3 / 12` | **严谨 ✓** | 与 AC-BA-06 同值域；本次真机实测 GQ-01 2 轮/8 次、GQ-02 2 轮/4 次，收紧后**仍通过**（不是"为了红而红"）。建议同时把 `Tournament` 之外的其它对比题（GQ-29 已 8/3）保持同一口径。 |
| **G2**：新增 GQ-34（预测保费）/ GQ-35（直连数据库），`maxToolCalls: 0` + `maxRounds: 0` | **不严谨（过严，且与 REQ 冲突）** | REQ §5.2.5 第 3 行原文是"明确不做预测…**可给趋势描述**"——给趋势就必须取数，"0 次调用"把 REQ 允许的行为判成失败。GQ-34 的**方差**（专项运行 fail / 35 题运行 pass）正是过严的体现。建议：保留 `refusal` 与"不出现预测结论"的断言，把 `maxToolCalls` 放宽到 `≤2`（或只要求"若调用则必须是只读统计工具"）。GQ-35 的**泄漏术语断言则完全正确**，应保留并作为核心判据。 |
| **G3**：`minConsecutivePeriods: 6` + `analyzePeriods()` 连续段解析 | **方向正确，但解析器有假失败风险** | 我把 `analyzePeriods`/`longestConsecutiveRun`/`isNextPeriod` 原文抽出后**独立跑**了边界用例：`2026-01…2026-06` 六个月表 → 6/6 ✓；**"1月…6月"表格 + 回显区间 `2026-04-01 ~ 2026-06-30` → periods=2、longestRun=1（假失败！）**；"四连月 + 跳月" → 4；"仅一个点" → 1；纯月份序列（无区间）→ 6/6 ✓。根因：**带年份的匹配存在时会丢弃"无年份月份"回退**，且**日期区间的端点被当成周期点**。建议：先剔除"区间/期间"行或 `start ~ end` 模式再解析，并将 year-ful 与 month-only 合并去重后再算连续段；同时把 `periods`/`longestRun` 写进报告行（便于复核）。**注**：这不改变 GQ-27 失败的结论（它的正文已不可得），但"≥6 连续周期"这条断言在修好前**可能既漏判也误判**。 |

## 8. 本轮全量复现（提交快照 `a545aa7`，jar 释放后）

- `mvn -B verify` → **BUILD SUCCESS**（8/8 模块）；单测 **617**（common 7 / system 120 / auth 28 / order 2 / analysis 6 / ai **443** / web 11）、集成 **114**（analysis 1 + web 113），**0 失败**。
- `node scripts/single-source-of-truth.mjs` → 合计 **731 / 105 类 / 0 失败**，`stale=否`；`--check` → **exit 0**「✅ 单一事实源校验通过」。与我的 verify 逐项一致（617+114=731）。
- 本批新增/变动的测试均在绿：`DataSourceClaimGuardTest` 14、`ProposalNumberGuardTest` 9、**`ProposalServiceMetricsTest` 9**、`AiConfigWiringTest` 12、`AiConfigServiceTest` 16、`AiConfigCatalogTest` 13、`InsuranceTypeQueryToolTest` 4；`AiObservabilityIT` 2/2、`EvaluationDeterministicIT` 12/12。
- 我在 `a545aa7` 上**亲跑**的复现脚本：`.agent/verify/t6b-dsg-real.jsh`（T6-01）、`.agent/verify/t6b-parser-test.mjs`（G3 解析器）、`.agent/verify/t6b-boundaries.sql`（T6-03 边界）、repartition DRY-RUN 输出、8091 实例的 prometheus 抓取。

## 9. 工程结论（必须写进交付）

| 事实 | 说明 |
|---|---|
| **"临时实例锁 fat jar"本轮又要发生一次** | 8090（phase3 task-29，`java -jar guarantee-web/target/guarantee-ai-admin.jar`）使 `guarantee-ai-admin.jar` 无法独占打开（我实测 `File.Open(ReadWrite,None)` 抛 "being used by another process"）→ 任何 `mvn package/verify` 都会死在 `spring-boot:repackage`。这已是**本项目的第 3~4 次**同类影响（此前 8088/PID 14408 也发生过）。 |
| **标准规避（本次采用，推荐固化）** | ① **classpath 启动**：`java -cp "各模块 target/classes;<依赖 cp>" com.guarantee.web.GuaranteeAiAdminApplication --server.port=<非占用端口>`（可用 `mvn -pl guarantee-web dependency:build-classpath -Dmdep.outputFile=…` 生成依赖 cp；注意**过滤掉 `~/.m2` 里的 `com/guarantee/guarantee-*.jar`**，否则会与 target/classes 里的同名 mapper 冲突导致 `Failed to parse mapping resource`）；② 或者**先停实例再打包**。 |
| **禁止** | **不要**用 `-Dspring-boot.repackage.skip=true`——它会把 fat jar 就地改写成 thin jar，破坏后续"java -jar"启动（本轮已发生过一次）。 |
| 临时实例纪律 | 用完立即 `Stop-Process` 并复核端口；我本轮 8091 实例已停（8091=0，**8081 未动**，8090 也已由 phase3 释放）。 |

## 10. 未跑项与局限

1. **真机集（GQ-\*）我未亲跑**（无 `DEEPSEEK_API_KEY`）：AC-BA-02/07 的失败证据来自实现方的 35 题报告工件；我复核了题面/判定逻辑、报告内部一致性与归档对照，未复述结论。
2. **`ai_tokens_total` 真机数据未亲采**（同因）→ AC-MCP-07 的该子项由实现方样本覆盖。
3. **`wired=false` 拒写的真机场景不存在**（20 项全 wired）→ 仅单测 + 代码顺序证据（§2 附注）。
4. **task-29 的提示词修复尚未落地**：AC-BA-02/07 的改判是"按当前证据"；修复 + 真机复跑通过后应再改判一次（届时我会复验）。

## 11. 证据索引

| 文件 / 命令 | 内容 |
|---|---|
| `.agent/verify/t6b-dsg-real.jsh` / `.out.txt` | T6-01：真实类 10 变体 vs 旧窄式正则对照 |
| `.agent/verify/t6b-parser-test.mjs` | G3 解析器边界用例（含假失败反例） |
| `.agent/verify/t6b-boundaries.sql` | 36 个分区边界 vs `TO_DAYS` → `bad_rows=0` |
| `.agent/verify/t6b-app-8091b.log` | exploded classpath 实例日志（含 `过期提案清理完成：1 条`） |
| `reports/eval-live-2026-09-30.json` / `reports/eval-live-t605-2026-09-30.json` / `reports/archive/t605-run1-GQ-*.json` | AC-BA-02/07 的失败证据与方差 |
| `scripts/ai-golden-questions.mjs`（G1/G2/G3 部分） | 新增断言与 `analyzePeriods` |
| `git show c3a2aff|4fbd969|6c57948|2430276|a545aa7` | 四批 + T6-05 的完整改动 |
| `node scripts/single-source-of-truth.mjs` | 731 / 105 / 0 失败 / stale=否 / --check exit 0 |

## 12. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-30 | v1.0 | 首版。A：T6-01/02/03/06 四批独立复验均成立（含我真机采样的 `ai_proposals{expired,system}` 与 `{rejected,web}`）；B：**AC-MCP-07 更正高估后重判成立**、**AC-BA-02/07 改判不成立**（真机 GQ-27/GQ-31/GQ-35），阶段二由 7/2/0 改为 **5/4/0**，"第二阶段可宣布完成"的结论**撤回**；C：G1 严谨、G2 过严且与 REQ 冲突、G3 解析器有假失败风险；附工程结论（classpath 启动 / 禁止 `repackage.skip`）与全量复现（617 单测 + 114 IT / SSOT 731，全绿）。 |
