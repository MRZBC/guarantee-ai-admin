# 收口复验报告（第七批，T7-01）：B1 企业/项目维度转正 + A4 拒答轮次 + MCP 白名单 15 + 最终全量

| 项 | 内容 |
|---|---|
| 验证人 | `verifier`（独立验证员，未参与实现） |
| **最终快照** | **HEAD = `690e7e5`**（= `4add4aa` 的 B1/A4/MCP-15 + `690e7e5` 的 `McpBackendIT` 修复）；复核期间 `git status` 干净 |
| 方法 | 自写 SQL 交叉核对 → **事务内软删 + ROLLBACK** 数据探针 → **运行时真实 SQL + 生产改写器**机制证明（含反证）→ 真机定向复跑 → 独占全量构建 → 前端/SSOT |
| 环境 | classpath 启动 8088 实例（用完即停）、**8081（PID 24364）全程未触碰**、`maven.lock`/`frontend.lock` 先取后放、未用 `-Dspring-boot.repackage.skip=true` |

## 0. 一句话结论

> **B1 交付成立，AC-BA-03 / AC-BA-04 由"不成立（M2.3 缓做）"改判为「成立」→ 全项目 43 条 AC 达到 43 / 0 / 0。**
> 最终快照 `690e7e5` 的**全量构建全绿**：我亲跑 `mvn -B verify` **BUILD SUCCESS**（单测 **629** / 集成 **121**，其中 1 条如实跳过）、前端 `vue-tsc` + `vite build` 均 exit 0、SSOT **750 / 108 类 / 0 失败 / 1 跳过**、`--check` exit 0。
> 过程中抓到并处置两件事：① `4add4aa` 上 `McpBackendIT` 的**陈旧断言**（13→15）导致构建红——**属测试同步遗漏，不是功能回归**，已由 Lead 在 `690e7e5` 修复并经我独立复跑确认；② 我的首次全量的 `LogicalDeleteWebIT` 失败系**并发构建污染**（流程缺陷，详见 §5）。

## 1. B1（M2.3 企业/项目维度）—— AC-BA-03/04 改判的依据

### 1.1 聚合口径：我自己写 SQL 交叉核对

对**同一订单范围**（tender ∪ performance，`is_deleted=0`，全量）用两条不同写法的聚合比对：

| 口径 | 行业分组数 | Σ去重企业数 | Σ订单量 | Σ担保金额 |
|---|---|---|---|---|
| mapper 写法（`COUNT(DISTINCT enterprise_id)` + `LEFT JOIN enterprise`，按 industry 分组） | 8 | 3000 | 150000 | 1,144,564,604,550.82 |
| 我的等价写法（子查询聚合，另一套投影） | 8 | 3000 | 150000 | — |

**跨维度一致性**：`行业维度合计订单量 = 地区维度合计订单量 = 150000` ✓（业务基线 100000 投标 + 50000 履约，吻合）。
**项目类型**：真库枚举为 **5 个中文值**（房建 / 市政 / 交通 / 水利 / 其他）→ 与 REQ-BA-04"类型用中文、不翻译"一致 ✓。

### 1.2 "企业/项目名历史保留" —— 我用事务内软删 + ROLLBACK 证明（零污染）

**数据探针（同一个 MySQL 会话，事务内改状态 → 查询 → ROLLBACK）**：

| 步骤 | 企业侧（id=416，88 单） | 项目侧（51 单） |
|---|---|---|
| ① 改前 | 解析出「嘉华水利工程有限公司0416」/ 行业 交通运输 | 解析出「山东省房建工程项目4828」/ 类型 房建 |
| ② `UPDATE … SET is_deleted=1, status=0` 后，**历史 join（无过滤）** | **名字与行业照旧解析**，订单数 88 不变 | **名字与"房建"照样解析**，51 不变 |
| ③ **反证**：同一查询加 `AND e.is_deleted=0`（项目侧同） | 名字变 **NULL**、行业 NULL → 分组会变成"没名字的一行" | 名字与类型都变 **NULL** |
| ④ `ROLLBACK` 后 | `is_deleted=0, status=1` **完全复原** | `is_deleted=0, status=BUILDING` 复原 |

→ 既证明**历史保留真的成立**，又证明**该断言有判别力**（去掉口径就会退化），且**没有污染共享库**。

**机制证明（用生产代码 + 运行时真实 SQL，我自己写）**：从实例 DEBUG 日志取出 `selectEnterpriseDistribution` 的**实际执行 SQL**（634 字符，含 `LEFT JOIN enterprise` 与显式 `is_deleted = 0`），通过反射调用生产类 `com.guarantee.system.mybatis.LogicalDeleteSqlRewriter`：

```
1) hasExplicitIsDeletedCondition(真实运行时 SQL) = true
2) rewrite(真实 SQL) == 原 SQL                   => true      ← 整句跳过
3) 企业侧未被注入过滤（无 e.is_deleted / e.status） => true
4) 反证：把显式 "AND is_deleted = 0" 去掉后 rewrite != 原 SQL => true
5) 反证：此时改写器确实注入了 is_deleted                      => true
```

→ 历史保留**不依赖数据样本**，而是"订单侧有显式逻辑删除条件 → 改写器整句跳过 → 不给企业/项目主数据注入过滤"这一机制；反证 4/5 说明这条机制是可判别的（去掉显式条件就会开始过滤主数据）。

### 1.3 新工具准入条件（§5.1.5 六条逐条）

| 要求 | 事实 |
|---|---|
| record 返回 + `ToolResultMeta`（denied/truncated/dataSource） | `EnterpriseAnalysisToolResult` / `ProjectAnalysisToolResult` 均为 record；`ToolResultMeta.ok/truncated` 按是否截断选择 |
| `dataSource` 不含工具名与英文参数名 | `DataSourceText.of("企业分析", parts)`，键已译为「维度/排序依据/订单类型/起始日期」等，区域给中文名；工具单测含"无工具名、无英文参数名"断言 |
| limit 归一 + 截断显式标记 | 默认 10 / 上限 50（`Math.min`），`limit==null 或 <=0` 回落默认；用满即 `truncated` 并给出"只给了前 N 条" |
| 非法参数给可读中文、不抛堆栈 | 维度/模式/排序/日期非法 → `IllegalArgumentException("…")` 中文可读；单测覆盖 |
| 只依赖 Service、不注入 Mapper | 构造器只有 `OrderAnalysisService` |
| 单测 + 真实库 IT | `EnterpriseAnalysisToolTest` 7、`ProjectAnalysisToolTest` 5、`EnterpriseProjectAnalysisToolIT` 7 运行（1 如实跳过）全绿 |

### 1.4 真机与 IT

- **我亲跑**：`--only=GQ-36,GQ-37` **各 3 轮全部 PASS**（GQ-36 每轮 1 次 `queryEnterpriseAnalysis:SUCCESS`、2 轮、≈3.5 s；GQ-37 每轮 1 次 `queryProjectAnalysis:SUCCESS`）→ 新工具确实被模型走到、且未被判为蛮力枚举。
- **IT**：企业分布/排行、项目分布/排行四条"工具结果 = 同 Service 逐字段一致"，且**维度合计互等**（企业行业合计 == 订单地区合计；项目类型合计 == 地区合计）；历史保留机制用例断言运行时 `BoundSql` 里订单侧有显式 `is_deleted`、企业/项目侧没有，并 `rewrite(sql)==sql`。
- **被跳过的 1 条**：「历史保留探针（无此类数据则如实跳过）」 —— 共享库确实没有停用/软删样本，它**如实 Skipped 而不是假通过**；我用自己的事务内探针补上了这块证据（§1.2）。

### 1.5 改判

| AC | 原判 | **改判** | 依据与边界 |
|---|---|---|---|
| **AC-BA-03** | 不成立（M2.3 缓做） | **成立** | `queryEnterpriseAnalysis` 提供按行业/等级/地区的分布（企业数 + 订单量/保额/保费）与 Top-N 排行；聚合我独立核对一致；企业名历史保留已证；真机 GQ-36 三轮通过。**口径说明**：本工具按 **订单聚合**（企业数 = 该范围内有订单的去重企业），符合 AC 的"或**明确说明按订单聚合**"分支；它**不等于**企业管理页的主数据总量，二者本就不是一回事 |
| **AC-BA-04** | 不成立（M2.3 缓做） | **成立** | `queryProjectAnalysis` 按项目类型/地区分布（项目数 + 订单指标）与按担保金额的排行榜；**类型为中文原样透传**（真库 5 个中文枚举）；真机 GQ-37 三轮通过。**占比数值我做了独立核对**：分类型担保金额与模型答案**逐分一致**，占比 我算 **22.31%** / 模型答 **22.29%**（差 0.02 pp，属模型自行做除法时的舍入，见 §8 未闭环项） |

## 2. A4 拒答类 N 轮制度化 —— 成立

- **语义（代码核对）**：`--repeat=N`（默认 1、上限 10）→ 每题跑 N 次；`--suite=refusal` 未显式给 repeat 时**默认 3**（`LIVE_REPEAT = REPEAT ?? (SUITE==='refusal' ? 3 : 1)`）；判定 `allPass = passedRuns === repeatCount`，失败打印 `FAIL（n/N 次通过；原因）` **逐轮标注**；环境不满足 → `skipped=true` 记**未跑**（不半截通过）；`--suite=refusal` = `QUESTIONS.filter(q => q.expect?.refusal === true)`；退出码 **断言失败=1 / 环境=2 / 全过=0** 语义未变。
- **我亲跑**：`node scripts/ai-golden-questions.mjs --suite=refusal`（classpath 实例 + 真实模型）→ **8 题 × 3 轮 = 24/24 PASS、forbiddenViolations 0、exit 0，本轮未出现方差**。
- 与实现方工件 `reports/eval-live-a4-refusal-r3-2026-10-01.*` 对照：同为 8 题 × repeat 3、全过、禁用词 0 —— 两边一致。
- CI：`ai-eval.yml` 里拒答类步骤位于**手动 + 需 `secrets.DEEPSEEK_API_KEY`** 的 Job3（无 Key 时作业跳过而非失败，已核 `:253-258`）→ 不会造成 CI 假红（但也意味着常规 CI 不跑它，见 §8）。

## 3. MCP 白名单 13 → 15 —— 成立

- **三处逐名一致**（我逐个比对）：Java 只读 `@Tool(name=…)` **15 个** == `McpToolCatalog.readOnlyToolNames()` **15 个** == 网关 `catalog.ts` 的 `backendName` **15 个**（新增 `queryEnterpriseAnalysis`、`queryProjectAnalysis`），差集为空。
- 网关 `npm test` → **17/17 PASS**（exit 0）；`McpToolCatalogTest` 6/6；
- `McpBackendIT`：`4add4aa` 上因**陈旧断言** `hasSize(13)` 必红（`Expected size: 13 but was: 15`，含注释/`@DisplayName`/方法名四处旧口径）→ `690e7e5` 修为 `hasSize(15)` 并补 `contains("queryEnterpriseAnalysis","queryProjectAnalysis")` 后 **8/8 绿**（我在新 HEAD 上独立复跑确认）。

## 4. 最终快照全量（我亲跑，`690e7e5`）

| 项 | 结果 |
|---|---|
| `mvn -B verify` | **BUILD SUCCESS**（8/8）：单测 **629**（common 7 / system 120 / auth 28 / order 2 / analysis 6 / ai **455** / web 11）、集成 **121**（analysis 1 + web **120，0 失败 / 1 如实跳过**）→ 合计 **750** |
| 关键用例 | `McpBackendIT` **8/8**、`EnterpriseProjectAnalysisToolIT` **7 运行 / 1 跳过**、`EvaluationDeterministicIT` 12/12、`AiObservabilityIT` 2/2 |
| 前端 | `npx vue-tsc --noEmit` **exit 0**；`npx vite build` **exit 0**（`✓ built in 5.90s`） |
| SSOT | **合计 750 / 108 类 / 0 失败 / 1 跳过**，`stale=否`；`--check` **exit 0**「✅ 单一事实源校验通过」 |
| 与 Lead 并行结果交叉对照 | 他报的 `guarantee-web` failsafe 120 通过 / 0 失败 / 1 跳过、SSOT exit 0 与我的数字**完全一致** |

## 5. 过程中发现的两件事（必须写进交付记录）

1. **`4add4aa` 上构建是红的 —— 陈旧断言，不是功能回归。**
   `McpBackendIT.java:224` 仍断言 `hasSize(13)`，白名单已 15 → `Expected size: 13 but was: 15`；我**隔离重跑仍红**（确定性）。它是 MCP 13→15 批次**漏改的测试**（`McpToolCatalogTest`、网关测试都改了，漏了这一个 IT），提交信息里"McpBackendIT 8/8"与快照不符。已由 Lead 在 `690e7e5` 修复，我在新 HEAD 复跑 8/8 绿。**结论：测试同步遗漏；产品功能无回归。**
2. **并发构建污染 IT 夹具（流程缺陷）。**
   我的首次全量里 `LogicalDeleteWebIT` 报 `Biz 用户不存在: 3019`；**在无并发时隔离重跑 4/4 全绿**。根因：当时另一个未取锁的 `mvn verify`（Lead 已确认是他起的）与我的构建**共用同一 MySQL**，两个 IT JVM 互相删/改夹具。→ **工程坑（建议进知识库）：多人共库时，未持 `.agent/locks/maven.lock` 的构建会污染 IT 夹具；所有 `mvn` 构建必须先取锁（或各自独立库）。**

## 6. 五阶段/批次最终三态汇总（**43 / 0 / 0**）

| 阶段 | AC 集合 | 条数 | 成立 | 不成立 | 无法验证 |
|---|---|---|---|---|---|
| 阶段二 业务分析能力 | AC-BA-01~09 | 9 | **9** | 0 | 0 |
| 阶段三 RAG 知识 | AC-RAG-01~09 | 9 | **9** | 0 | 0 |
| 阶段四 AI 配置与确认审计 | AC-CFG-01~12 | 12 | **12** | 0 | 0 |
| 阶段五 MCP / 评测 / 可观测 | AC-MCP-01~13 | 13 | **13** | 0 | 0 |
| **合计** | —— | **43** | **43** | **0** | **0** |

阶段二本轮变化：AC-BA-03 / AC-BA-04 由"不成立（M2.3 缓做）"→ **成立**（§1.5）；其余 7 条此前已成立（AC-BA-02/07 在 T6-09 二次改判）。

## 7. 当前可发布口径（一句话）

> **可发布：全项目 43 条 AC 全部成立（不成立 0 / 无法验证 0）；最终快照 `690e7e5` 全量构建全绿（单测 629 / 集成 121，其中 1 条如实跳过；前端 tsc + build exit 0；SSOT 750/108/0/1，`--check` exit 0）；真机评测 = 37 题 36 PASS / 0 FAIL / 1 未跑（GQ-25 在关知识层实例单独 PASS ⇒ 37/37 全覆盖）+ 拒答类 8 题 ×3 轮 24/24。**

## 8. 未闭环项（最终清单）

| # | 事项 | 性质 | 建议 |
|---|---|---|---|
| 1 | AC-BA-04 的**占比**由模型自行做除法，实测与我算的差 **0.02 pp**（金额逐分一致） | 精度瑕疵（非功能缺陷） | 让服务端在「数据摘要」里直接给各分组占比，模型只转述 |
| 2 | GQ-36/GQ-37 的判据只校验 `mustCall` + 关键词，**不校验数值/占比** | 断言不够严 | 把数值断言（或与 `DataMetrics` 摘要比对）纳入；我本轮用自写 SQL 手工补核 |
| 3 | `ai_tokens_total` 的真机数据**非我亲采**（本机无 Key 时 usage=0，该计数器不注册） | 环境限制 | 需要时在带 Key 机器采一次 prometheus |
| 4 | 历史保留的**数据探针 IT 因"无停用/软删样本"永久跳过** | 测试覆盖缺口 | 把"事务内软删 + 查询 + ROLLBACK"固化为 IT（我本轮已用该法验证，零污染） |
| 5 | GQ-35 话术偶发"先答后拒 / 拒绝说两遍"，正文含「直连数据库」等实现相邻词 | 模型行为（不违反 AC-BA-07） | 模板补"拒绝只说一次、不解释实现" |
| 6 | 拒答类门禁已进 CI，但位于**手动 + 需 Key** 的 Job3；常规 CI 不执行 | 门禁覆盖 | 需要时加定时手动触发或专用 runner |
| 7 | 前端 chunk 偏大（`StatCards` 570 KB / `index` 1.27 MB） | 性能遗留 | 按需拆包 |
| 8 | `ProposalNumberGuard` 格式逃逸 / `DataSourceClaimGuard` 行中口径行不判定 | 既有登记不修（宁漏勿误伤） | 已由单测钉边界 |
| 9 | **并发构建污染**（未持锁的 `mvn` 会污染共享库上的 IT 夹具） | **流程缺陷** | 所有构建先取 `.agent/locks/maven.lock`；已建议进知识库 |
| 10 | `reports/*` 跑评测即被重写导致工作区变脏 | 设计使然 | 提交时只带想固化为基线的版本 |

## 9. 证据索引

| 文件 / 命令 | 内容 |
|---|---|
| `.agent/verify/t701-mvn-verify.log`（`4add4aa`，红） | `McpBackendIT` 13 vs 15 失败（陈旧断言） |
| `.agent/verify/t701-targeted-it.log` | 隔离重跑：`McpBackendIT` 必红、`LogicalDeleteWebIT` 4/4 绿（证明是污染） |
| `.agent/verify/t701b-mvn-verify.log`（`690e7e5`，绿） | 我亲跑全量：629 / 121 / BUILD SUCCESS |
| `.agent/verify/t701-gq3637.json` + `-r23.json` | GQ-36/GQ-37 各 3 轮真机全过（新工具被调用） |
| `.agent/verify/t701-refusal.json` / `.md` | 我亲跑 `--suite=refusal`：8 题 ×3 轮 24/24 |
| `.agent/verify/t701-runtime-sql.txt` + `t701-rewriter.jsh` | 运行时真实 SQL + 生产改写器 `rewrite==sql` 与反证 |
| MySQL 3307 事务内探针（§1.2 表） | 企业 416 / 项目：软删后历史保留、加过滤即 NULL、ROLLBACK 复原 |
| `reports/eval-live-a4-refusal-r3-2026-10-01.json` | 实现方 A4 工件（与我的 24/24 一致） |
| `git show 690e7e5` | `McpBackendIT` 13→15 修复（+注释/DisplayName/方法名/contains） |
| 前端 `npx vue-tsc --noEmit` / `npx vite build`；`node scripts/single-source-of-truth.mjs --check` | 均 exit 0；SSOT 750/108/0/1 |

## 10. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-10-01 | v1.0 | 首版（T7-01 终局）。**AC-BA-03/04 改判成立 → 43/43/0**；B1 用自写 SQL 交叉核对 + 事务内软删/ROLLBACK 探针 + 运行时 SQL 喂生产改写器（含反证）独立验证；A4 我亲跑 8×3=24/24；MCP 15 三处逐名一致；最终快照 `690e7e5` 全量构建全绿（629/121/1 skipped、前端 exit 0、SSOT 750 `--check` 0）。同时登记：`4add4aa` 的构建红是**陈旧断言**（非功能回归，已修）与**并发构建污染 IT 夹具**这一流程缺陷，以及 10 项未闭环清单。 |
