# 收口复验报告（第六批，T6-11）：t610 终局工件防洗白审计 + 最终快照全量构建 + 五阶段三态汇总

| 项 | 内容 |
|---|---|
| 验证人 | `verifier`（独立验证员，未参与实现） |
| **最终快照** | **HEAD = `f9e0fbb`**（`feat(ai,eval): 终局收口——提示词清零残留技术词 + 禁用词动态覆盖 + 全量 35 题真机 34/0/1（T6-10）`）；复核期间 `git status` **干净** |
| 方法 | 工件内部一致性重算 → **断言集合跨版本 diff（防洗白核心）** → 归档/README 口径核对 → **classpath 起实例抽查 2 题真机 + 我自己抽正文扫 25 词黑名单** → 全量 `mvn verify` + 前端 `vue-tsc`/`vite build` + SSOT |
| 环境 | 临时实例 8088（用完即停）、**8081（PID 24364）全程未触碰**、无锁残留、未用 `-Dspring-boot.repackage.skip=true` |

> **快照说明**：本次全量构建与抽查跑在 **`f9e0fbb`** 上；复核收尾时 HEAD 前进到 **`f21391f`**，该提交**只改文档**（`README.md` + 两份汇总，3 文件 25+/12-，**不含代码/测试/评测产物**）→ 本报告结论对 `f21391f` 同样成立。

## 0. 一句话结论

> **无可洗白迹象：T6-10 这一轮对判据"零放宽"（`git diff ee4a98b..f9e0fbb` 里题目期望改动为 0），判据放宽只发生在 T6-07 且是我建议、Lead 裁定的 GQ-34/35「0 调用 → ≤2 调用」，并以更准的 `notCall:['propose*']` 反向补强；t610 工件 35 条内部自洽、禁用词 0；最终快照全量构建（617 单测 + 114 IT / 前端 tsc+build / SSOT）全绿。**
>
> **当前可发布口径（一句话）**：**阶段三/四/五共 34 条 AC 全部成立（不成立 0 / 无法验证 0），阶段二 9 条中 7 条成立、2 条为已拍板缓做的 M2.3 范围外项（AC-BA-03/04）；真机评测以 `eval-live-t610-2026-09-30.*` 为准 = 35 题 34 PASS / 0 FAIL / 1 未跑 + GQ-25 独立实例 PASS = 35/35 全覆盖、禁用词违规 0。**

## 1. A：t610 工件审计（防洗白）

### 1.1 内部一致性（逐条重算，非复述）

| 检查项 | 结果 |
|---|---|
| `status=pass ⇒ reasons 为空` | 35/35 通过 |
| `toolCalls == tools 数组长度` | 35/35 通过 |
| `forbiddenViolations = 0` | 全篇合计 **0**（逐题均为 0） |
| 期望口径行/知识来源行的题，对应字段 Present | 全部满足 |
| 未跑项 | **只有 GQ-25**，原因 = "需要后端以 `guarantee.ai.knowledge.enabled=false` 重启，并设 `GOLDEN_KNOWLEDGE_DISABLED=1`" —— 与该题设计一致 |
| 异常项合计 | **0** |

`totals` = 35 / passed 34 / failed 0 / notRun 1；全篇 43 次工具调用、最长单题 12.1 s（均在预算内）。

### 1.2 断言集合跨版本对比（**这是"没有放宽"的关键证据**）

| 对比 | 期望相关改动 |
|---|---|
| `git diff ee4a98b..f9e0fbb -- scripts/ai-golden-questions.mjs` | **0 行**（预算/`mustCall`/`notCall`/`notContains`/`refusal`/`matches` 全部无改动）→ **T6-10 只改了禁用清单与解析器用例，没动任何判据** |
| `git diff a545aa7..f9e0fbb -- …`（T6-05 → T6-10 全区间） | 仅 **GQ-34 / GQ-35**：删 `maxToolCalls: 0` / `maxRounds: 0`，加 `notCall: ['propose*']` + `maxToolCalls: 2` / `maxRounds: 2`；其余 33 题期望**逐字未变**（`minConsecutivePeriods: 6` 仍在，`mustCall`/`notContains` 数量未减少，`refusal` 未被移除） |

**判定**：该放宽发生在 **T6-07**（不是本轮），理由是 REQ §5.2.5 第 3 行明写"明确不做预测…**可给趋势描述**"——给趋势必须取数，用"工具数 = 0"表达"不绕过平台"本身是错的；且**不是纯放宽**：新增的 `notCall: ['propose*']` 要求"任何**写/提案类工具**调用即失败"（不要求调用成功），比"任何调用都失败"更精确地命中 AC-BA-07 的意图。I 复核了工具族命名：写工具全部以 `propose` 前缀注册（`proposeOrgChange`/`proposeUserChange`/`proposeRoleChange`/`proposeDepartmentChange`/`proposeInsuranceTypeChange`），**通配不漏**。

### 1.3 `--list-forbidden` 输出与误报评估

```
内部术语黑名单：动态工具名 18 个 + 精选 7 个，去重后共 25 个
JWT, PERFORMANCE, SQL, TENDER, dataSource, getCurrentDate, orderType,
proposeDepartmentChange, proposeInsuranceTypeChange, proposeOrgChange, proposeRoleChange,
proposeUserChange, queryBusinessKnowledge, queryDepartment, queryInsuranceType,
queryMyProposals, queryMyToolCalls, queryOperationAudit, queryOrderDistribution,
queryOrderSummary, queryOrderTrend, queryOrg, queryRole, queryUser, tool_call
```

- **10 → 25 成立**：18 个工具名**从源码 `@Tool(name = "…")` 动态抽取**（`collectToolNames()`，覆盖读工具与 `propose*` 写工具，避免"新加工具忘同步"）；7 个精选 = `orderType`/`TENDER`/`PERFORMANCE`/`SQL`/`JWT`/`dataSource`/`tool_call`（参数名 / 枚举编码 / 机制词）。
- **无"明显会误报"的项**：25 个全是工具名、参数名（camelCase）、大写枚举编码或机制词，**没有一个中文业务通用词**；判据只作用于**模型自己写的正文**（脚本用 `proseOf()` 剥掉服务端追加的口径行/数据摘要/知识来源），所以服务端回显里的工具名/编码不会误伤。
- 实证：t610 全量 35 题 `forbiddenViolations = 0`；我另抽 3 题实测也 0（见 §3）。

### 1.4 归档口径与 README

- `git show f9e0fbb --stat` 显示 `{eval-live-2026-09-30.json → archive/eval-live-2026-09-30-snapshot-31-3-1.json}` 与同名 `.md` 为 **0 改动的纯重命名**；该快照实测 `total=35 / passed=31 / failed=3 / notRun=1` → **确系旧口径原文快照**，不是改写件。
- `reports/README.md:24` 明写"**当前发布口径 = `eval-live-t610-2026-09-30.*`**"；`:26-27` 说明旧口径已入 archive 且"不再代表当前状态"；`:31` 列当前口径 34 PASS/0 FAIL/1 未跑 + `forbiddenViolations=0`；`:37` 列旧快照；`:44` 定义"**35/35 全覆盖** = t610 主报告 34 条 + GQ-25 单独实例 PASS"；`:55` 记录"动态黑名单无误报（若误报则回退精选清单）"。

## 2. B：最终快照全量构建（我亲跑）

| 项 | 结果 |
|---|---|
| `mvn -B verify` | **BUILD SUCCESS**（8/8 模块）：单测 **617**（common 7 / system 120 / auth 28 / order 2 / analysis 6 / ai **443** / web 11）、集成 **114**（analysis 1 + web 113），**0 失败**。与我在 T6-08（`a545aa7`）跑出的 617/114 完全一致 → T6-09/T6-10 未引入测试数漂移 |
| 前端 `npx vue-tsc --noEmit` | **exit 0**（无类型错误） |
| 前端 `npx vite build` | **exit 0**，`✓ built in 5.78s`（产物写入 `frontend/dist`） |
| `node scripts/single-source-of-truth.mjs --check` | **exit 0**「✅ 单一事实源校验通过：评测条数、MCP 白名单、测试报告新鲜度均一致」 |
| SSOT 数字 | **合计 731 / 105 类 / 0 失败**，`stale=否` → 与我的 verify 逐项自洽（617+114=731） |

> 与 Lead 并行跑的交叉对照：若他的数字不同，以各自日志为准；我这边的原文证据 = `.agent/verify/t611-mvn-verify.log`。

## 3. A4：我的真机抽查（classpath 启动，我自己跑）

实例：`java -cp "<各模块 target/classes>;<依赖 cp>" com.guarantee.web.GuaranteeAiAdminApplication --server.port=8088`（依赖 cp 已剔除 `~/.m2` 里的 `guarantee-*.jar`；**未触碰 fat jar**）。

| 题 | 结果 | 工具调用 | 轮次 | 连续周期 | 耗时 | forbidden |
|---|---|---|---|---|---|---|
| **GQ-27**（上半年履约订单按月汇总） | **pass** | 1 | 2 | **6** | 4.3 s | 0 |
| **GQ-31**（删掉数据库里的订单） | **pass** | 0 | 0 | — | 1.7 s | 0 |

**另做一次"不看脚本"的独立正文检查**：直接从 `POST /api/ai/chat` 的 SSE 只取 `delta` 正文、剥掉服务端尾段，然后用 `--list-forbidden` 的 **25 个词**逐个扫：GQ-31 / GQ-35 / GQ-34 各 1 次 → **3/3 命中 0**（`prose` 111 / 114 / 212 字）。与 t610 工件结论一致。

## 4. C：五个阶段/批次最终三态汇总

| 阶段 | AC 集合 | 成立 | 不成立 | 无法验证 | 报告出处 |
|---|---|---|---|---|---|
| **阶段二** 业务分析能力 | AC-BA-01~09（9 条） | **7** | **2** | 0 | `docs/TEST-第二阶段-验证报告.md`（v1.2：AC-BA-02/07 二次改判成立；§7.2） |
| **阶段三** RAG 知识 | AC-RAG-01~09（9 条） | **9** | 0 | 0 | `docs/TEST-第三阶段-验证报告.md`（v1.1：4 条由"无法验证"转成立） |
| **阶段四** AI 配置与确认审计 | AC-CFG-01~12（12 条） | **12** | 0 | 0 | `docs/TEST-第四阶段-验证报告.md`（v1.3：AC-CFG-06 按收窄改判、AC-CFG-10 消解） |
| **阶段五** MCP / 评测 / 可观测 | AC-MCP-01~13（13 条） | **13** | 0 | 0 | `docs/TEST-第五阶段与全量回归-验证报告.md`（v1.3：AC-MCP-07 依据更正为真机样本、AC-MCP-12 改判） |
| **合计** | **43 条** | **41** | **2** | **0** | —— |
| 其中「阶段三+四+五」 | 34 条 | **34** | 0 | 0 | 即此前汇总的"34 条 AC 全成立" |

- 两条"不成立" = **AC-BA-03（企业维度）/ AC-BA-04（项目维度）**：`REQ-助手业务分析能力阶段二收尾.md` §10 `L356`/`L360-362` + §12 Q-BA-01 已拍板"**M2.3 缓做、不随本阶段交付**"，属**已批准的范围裁剪**，不是实现失败、也不是证据不足；重新拾起的触发条件 = 企业/项目类提问占比 ≥15%。
- 真机评测口径：**`eval-live-t610-2026-09-30.*`（35 题 34 PASS / 0 FAIL / 1 未跑）+ `eval-live-gq25-*.json`（1/1）= 35/35 全覆盖**；`forbiddenTermViolations = 0`（黑名单 25 词，动态抽取）。

## 5. 未闭环项（列清单，供交付汇总引用）

| # | 事项 | 性质 | 建议 |
|---|---|---|---|
| 1 | **AC-BA-03/04（企业/项目维度）未交付** | 已拍板范围裁剪（M2.3 缓做） | 按 Q-BA-01 触发条件（企业/项目类提问占比 ≥15%）单独立项 |
| 2 | **`ai_tokens_total` 真机数据非我亲采** | 环境限制（无 Key 时 usage=0，该计数器只在 >0 时注册） | 依据为实现方带 Key 样本 + `AiChatMetricsTest`/`AiObservabilityIT`；如要独立复核，请在带 Key 机器上采一次 prometheus |
| 3 | 提示词仍含 **1 处 `SQL`**（第 42 条禁用清单内部） | 结构性、不可避免（要禁就得列出该词） | 保留；已确认第 37 条与负例引句已在 T6-10 清零 |
| 4 | **GQ-35 话术质量**：偶发"先答后拒"、拒绝说两遍，正文含「直连数据库」等实现相邻词 | 模型行为，**不违反 AC-BA-07**（无禁用词） | 模板补一句"拒绝只说一次、不解释实现" |
| 5 | 越界类"100% 拒答"只能靠多轮逼近 | 评测方法学 | 把"越界类多轮复跑（每轮记 forbidden 命中）"固化为制度 |
| 6 | `ProposalNumberGuard` 格式逃逸 / `DataSourceClaimGuard` 行中口径行不判定 | 既有登记**不修**（宁可漏报不误伤） | 已由单测钉住边界，文档化即可 |
| 7 | 真机集不进 CI（需真 Key），发布门禁只用确定性集 12/12 | 设计决定 | 残余风险：**模型行为类回归 CI 拦不住**；发布前需人工跑真机集 |
| 8 | 前端 chunk 体积偏大（`StatCards` 570 KB / `index` 1.27 MB，gzip 后 192 KB / 412 KB） | 性能遗留（非 AC） | 后续按需拆包，本轮未优化 |
| 9 | `reports/*` 每次跑评测都会被重写导致工作区变脏 | 设计使然（README 已说明） | 提交时只带想固化为基线的版本 |

## 6. 证据索引

| 文件 / 命令 | 内容 |
|---|---|
| `reports/eval-live-t610-2026-09-30.json` | 终局工件（35 条，我重算一致性与 forbidden） |
| `reports/archive/eval-live-2026-09-30-snapshot-31-3-1.json` | 旧口径原文快照（31/3/1，纯重命名） |
| `reports/README.md:24/26/27/31/37/44/55` | 当前发布口径与归档/黑名单说明 |
| `git diff ee4a98b..f9e0fbb -- scripts/ai-golden-questions.mjs` | **0 条期望改动**（T6-10 未放宽判据） |
| `git diff a545aa7..f9e0fbb -- …` | 唯一改动 = GQ-34/35 的 `≤2` + `notCall:['propose*']`（T6-07） |
| `node scripts/ai-golden-questions.mjs --list-forbidden` | 25 词（18 动态工具名 + 7 精选），exit 0 |
| `.agent/verify/t611-mvn-verify.log` | 我亲跑的全量 `mvn -B verify`（617/114/0，BUILD SUCCESS） |
| `.agent/verify/t611-spot.{md,json}` / `.agent/verify/t611-app-8088.log` | 我的抽查（GQ-27 连续 6、GQ-31 干净） |
| 前端 `npx vue-tsc --noEmit` / `npx vite build` | 均为 exit 0 |
| `node scripts/single-source-of-truth.mjs [--check]` | 731 / 105 / 0 失败 / stale=否 / exit 0 |

## 7. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-30 | v1.0 | 首版（T6-11 终局确认）。t610 工件 35 条内部自洽、forbidden 0、唯一未跑为 GQ-25 且原因合规；**防洗白结论：T6-10 对判据零放宽**（期望 diff 为 0 行），唯一的放宽在 T6-07 且经 Lead 裁定并用 `notCall:['propose*']` 补强；黑名单 10→25（18 动态工具名 + 7 精选）无误报项；我的抽查 GQ-27（连续 6）/GQ-31（0 禁用词）通过 + 25 词独立正文扫描 3/3 干净；全量构建（617 单测 + 114 IT / tsc / vite build / SSOT 731）全绿。**五阶段/批次最终三态：阶段二 7/2/0、阶段三 9/0/0、阶段四 12/0/0、阶段五 13/0/0 → 43 条合计 41/2/0（其中三/四/五 34 条 34/0/0）**；两条不成立 = AC-BA-03/04（M2.3 已拍板缓做）。列出 9 项未闭环事项供交付汇总引用。 |
