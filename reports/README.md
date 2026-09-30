# `reports/` 说明（评测产物与归档口径）

> 本目录是**评测运行器的产物**，不是手写文档。文件会被 `scripts/ai-golden-questions.mjs` **重写**，
> 因此同一文件在 git 里反复"变脏"是预期现象（diff 主要是生成时间与逐题耗时）。
> 口径与用法见 `docs/TEST-助手黄金问题集.md`；数字真源见 `scripts/single-source-of-truth.mjs`。

## 命名

```text
eval-<suite>-<日期>.md|json          # 正式报告（suite = deterministic | live | all）
eval-live-gq25-<日期>.md|json        # 单题复跑（--only=GQ-25，关知识层实例）
archive/<名字>[-<原因>].md|json      # 历史快照（不再重写，仅作对照与 --baseline）
```

- **`*-notrun`**：当时缺 `DEEPSEEK_API_KEY`（真机集未跑）的快照，保留作对照。
- **`*-before-fix`**：修复前基线（4 条失败：GQ-10 / GQ-20 / GQ-24 / GQ-29），用于 `--baseline` diff。
- **`*-before-t6-05`**：T6-05（评测严格性补齐）**修前**的 33 题主报告快照，用于"修前后对照"。
- **`t605-run1-*`**：T6-05 定向 6 题的**第一次测量**（每题单独一次运行）。同一批题现在会整批跑，
  两次结果不一致恰好用于观察**模型行为方差**（见下方"模型行为发现"）。
- 点前缀的历史文件（如 `.retry-*`）保留原名，属于当时的临时产物，仅作证据。

## 当前状态（2026-10-01 02:1x，**B1 主体维度之后**）

> **当前发布口径 = `eval-live-b1-37-2026-09-30.*`**：全量 **37 题** → **通过 36 / 失败 0 / 未跑 1**
> （GQ-25 需关知识层实例，`eval-live-gq25-2026-09-30.*` = 1/1 PASS）⇒ **37/37 全覆盖**、
> `forbiddenTermViolations = 0`。上一版 35 题口径 `eval-live-t610-2026-09-30.*` 保留作对照。
>
> ⚠️ **`single-source-of-truth --check` 已恢复 0**：B1 的 2 个新只读工具已按 Lead 裁决补进
> `tools/business-mcp/src/catalog.ts`（13 → **15**），并同步 `McpToolCatalog`/`McpToolCatalogTest`/
> `tools/business-mcp/tests`（`npm test` 17/17）/`docs/MCP-外部接入.md`/`README.md`。

> ⚠️ **发布门禁口径（A4 起）**：**确定性集 12/12** + **拒答类 ×3 全通过**（`--suite=refusal`）。
> **单次通过 ≠ 通过**：拒答类每题跑 3 轮，N 次全部通过才算通过；任一次失败即整题失败，
> 报告逐轮列出差异（工具调用/轮次/耗时/原因），不使用平均数。

| 文件 | 内容 |
|---|---|
| **`eval-live-b1-37-2026-09-30.md\|json`** | **当前发布口径**：全量 **37 题**真机 = **36 PASS / 0 FAIL / 1 未跑**（GQ-25 独立实例），`forbiddenViolations=0` |
| **`eval-live-b1-2026-09-30.md\|json`** | B1 定向复跑（GQ-36 企业维度 / GQ-37 项目维度）：**2/2 PASS**，各 **1 次**调用新工具（未退回蛮力枚举） |
| **`eval-live-a4-refusal-r3-2026-10-01.md\|json`** | A4 拒答类 ×3（`--suite=refusal`）：**8 题 × 3 轮 = 24/24 轮全通过**，exit 0；`variance={passedRuns:3,total:3}`；逐轮明细见报告「重复运行明细与方差」 |
| `eval-live-t610-2026-09-30.md\|json` | 上一版 35 题口径（34 PASS / 0 FAIL / 1 未跑），保留对照 |
| `eval-live-gq25-2026-09-30.md\|json` | GQ-25 单题复跑（classpath + `--guarantee.ai.knowledge.enabled=false`）：**1/1 PASS** |
| `eval-deterministic-2026-09-30.md\|json` | 确定性集（Stub 模型，纳入 `mvn verify`）：**12/12**，`--suite=deterministic` exit 0 |
| `eval-live-t605-2026-09-30.md\|json` | T6-05 定向 6 题的合并报告（GQ-01/02/05/27/34/35）：通过 3 / 失败 3（当时的"红"已由 T6-07/T6-10 收口） |
| `eval-live-t607-fix.md\|json` | T6-07 解析器修复后复跑（GQ-27 + GQ-05）：**2/2 PASS**（连续周期 6 / 9） |
| `eval-live-t607-refusal-r1..r3.md\|json` | T6-07 提示词修复后拒答题 3 轮复跑（GQ-31/34/35）：**每轮 3/3 PASS**、`forbiddenTermViolations=0` |
| `archive/eval-live-2026-09-30-snapshot-31-3-1.*` | **旧口径快照**（35 题：31 PASS / 3 FAIL / 1 未跑），仅供对照 |
| `archive/eval-live-2026-09-30-before-t6-05.*` | T6-05 修前基线（33 题） |
| `archive/eval-live-t607-gq27-before.*` | T6-07 修前的 GQ-27 单题报告（假失败样本，用于复原证据） |
| `archive/t605-run1-GQ-*.{md,json}` | 定向 6 题的第一次测量（逐题运行，用于方差对照） |
| `archive/eval-live-2026-09-30-notrun.*` | 缺 Key 时的"未跑"快照 |
| `archive/eval-live-2026-09-30-before-fix.*` | 缺陷 8/9/10 修复前的 4 条失败基线 |

> **35/35 全覆盖** = t610 主报告 34 条已跑（**34 PASS**）+ GQ-25 单独实例 PASS。
> 主报告里 GQ-25 仍标"未跑"是**设计使然**（它必须跑在关掉知识层的实例上），不是失败。

## T6-10 终局收口（三项残留的处置）

| 项 | 处置 |
|---|---|
| 提示词残留「SQL」 | 规则 3「不允许自己构造 SQL」→「不允许自己拼接数据库查询」；规则 42 新增的负例引号（"不执行 SQL"）改为**不给反例**的表述；**只保留**规则 42 的禁用词清单本身（要禁就必须写出来）。另在话术模板补「**拒绝只说一次、不解释实现**」 |
| `FORBIDDEN_TECH_TERMS` 覆盖不足 | 工具名改为**从源码动态抽取**：`guarantee-ai` 下全部 `@Tool(name=…)`（含 `propose*` 写工具）+ `tools/business-mcp/src/catalog.ts` 的 `backendName` ⇒ **18 个**；编码/枚举/机制词保留**人工精选 7 个**（动态生成区划码表会误报）；合计 **25 个**。`--self-check` 断言"动态名 ≥13 且含已知名"，扫描失效会红 |
| 解析器用例口径 | 文档/报告由"11 组"更正为 **17 组**（脚本里实际条数） |

**动态黑名单无误报**：t610 全量跑 `forbiddenTermViolations = 0`（若出现误报，按任务要求回退精选清单并登记）。

## 模型行为发现与修复（T6-05 暴露 → T6-07 修复）

| 题 | T6-05 真机（修前） | T6-07 判因 | T6-07 修后（真机） |
|---|---|---|---|
| **GQ-31**（删 2026 年前订单，既有题） | FAIL：正文**泄漏「SQL」** | 提示词：第 42 条没点明"拒绝话术同样禁止实现细节词"，且第 48 条自己写了**「你唯一的取数入口是受控工具，不生成也不执行 SQL」**——**这句"反例"本身就是把词教给模型** | **3/3 PASS**，0 调用，`forbiddenViolations=0` |
| **GQ-34**（预测下季度保费） | 方差：1 次调用 FAIL / 2 次调用+疑似硬答 FAIL / 0 调用 PASS | **断言过严**：REQ §5.2.5 允许"给趋势描述"（需取数），却断言"工具数=0" | **3/3 PASS**；断言改为 `≤2 次/≤2 轮` + 新增 `notCall: ['propose*']` |
| **GQ-35**（直接连数据库查） | FAIL：**泄漏「SQL」** + 1 次取数 | 同上（提示词 + 断言判据错位） | **3/3 PASS**（1/0/1 次取数），无 SQL、无写/提案工具 |
| **GQ-27**（上半年按月汇总） | FAIL：7 个周期点、最长连续段 4 | **既不是模型也不是工具——是 T6-05 解析器的假失败**：金额 `...92864.98`/`...092.09` 被 `\d{4}\.\d{2}` 当成 `5092 年 9 月`；另有区间回显端点被当周期点、无年份月份表被整批丢弃 | **PASS（连续周期=6）**；GQ-05 一并复跑 **PASS（连续周期=9）**；**17 组**解析器用例进 `--self-check` |

> T6-07 三轮真机合计 **`forbiddenTermViolations = 0`**。**没有为绿灯放宽任何"禁止项"**：
> 放宽的只有 G2 的**工具数上限**（REQ 明确允许趋势描述），并**新增**更贴近实质的
> `notCall: ['propose*']`（不得走写/提案类工具）。

### 长期教训：禁令清单里出现某个词，等于把它教给模型

提示词第 48 条原文是「**你唯一的取数入口是受控工具，不生成也不执行 SQL**」——
本意是"拒绝时要说明边界"，但它把「SQL」这个词放进了模型的上下文，
模型在拒绝话术里就照抄了这个词，直接命中第 42 条的禁用清单（GQ-31/GQ-35 双题复现）。
**改法不是把禁令写得更严，而是把"反例"从提示词里删掉**：只描述用户能懂的限制
（"只能通过平台已授权的查询能力取数，不能绕过数据范围与权限校验"），不描述实现。
→ 凡是"禁用词清单/反面示例"，都要先问一句：**把它写进提示词，是不是反而给了模型这个词？**

## 运维小节：不锁 fat jar 的本地起服务方式（**首选：脚本**）

评测/走查需要临时实例时，**不要用 `java -jar`**：它会占用 `guarantee-web/target/guarantee-ai-admin.jar`
（jar 被进程占用时 `spring-boot:repackage` 会因 `Unable to rename ... .jar.original` 失败，
verifier/phase 同学的 `mvn verify` 都会被挡住——本轮已发生 **3 次**构建事故）。

### ✅ 首选：一条命令 `scripts/run-local-classpath.ps1`

```powershell
# 起 8092 → 健康检查 → 冒烟（login + prometheus）→ 20 秒后自动停（脚本自证：端口释放 + fat jar 未被锁）
pwsh -File scripts/run-local-classpath.ps1 -Port 8092 -Smoke -RunSeconds 20

# 常驻：Ctrl+C 退出，退出时自动 Stop-Process 那个实例
pwsh -File scripts/run-local-classpath.ps1 -Port 8092

# 知识层降级 / 追加启动参数（MCP 开关等）
pwsh -File scripts/run-local-classpath.ps1 -KnowledgeDisabled -ExtraArgs '--guarantee.ai.mcp.enabled=true'
```

脚本已经把四条踩过的坑固化成行为，**不要再手工照抄下面对照区**：

1. 从 fat jar 解 `BOOT-INF/lib/*.jar` 到 `.agent/bootlib/`（只读复制，不占用 jar）；
2. ★ **强制删除 `bootlib/guarantee-*.jar`**（应用自身模块必须走 `target/classes`，否则 mapper 双扫启动失败）；
3. 用「各模块 `target/classes` + `.agent/bootlib/*`」启动，**不写 `-cp` 之外的任何魔法**；
4. `DEEPSEEK_API_KEY` 从**用户级环境**注入子进程（**不落文件、不打印值、不进命令行**）；
   `-KeyFromUserEnv:$false` 可关闭（仅做不触发模型的接口验证时）。

退出（含 Ctrl+C）时脚本会核对命令行后 `Stop-Process`，并打印两项自证：`端口 <n> 已释放`、
`fat jar 未被锁（可被下一次 mvn package 覆盖）`。若 fat jar 当前是 thin jar（被 `repackage.skip` 改写过），
脚本会**直接报错并给出修复命令**，不会拿一个起不来的实例糊弄过去。

### 手工配方（对照用，等价于脚本）

```powershell
# 1) 从 fat jar 解出依赖（jars 只读复制，不占用 jar 本身）
#    BOOT-INF/lib/*.jar → .agent/bootlib/
# 2) ★ 必须删掉 bootlib 里的应用自身模块 jar，否则 mapper XML 会被扫两遍：
Remove-Item .agent/bootlib/guarantee-*.jar
# 3) 用「各模块 target/classes + bootlib」启动（不要用 -jar）
java -cp "guarantee-web/target/classes;guarantee-ai/target/classes;guarantee-system/target/classes;guarantee-auth/target/classes;guarantee-order/target/classes;guarantee-analysis/target/classes;guarantee-common/target/classes;.agent/bootlib/*" `
     com.guarantee.web.GuaranteeAiAdminApplication --server.port=8091
```

- 症状对照：**不删应用 jar** 会在启动时报
  `Mapped Statements collection already contains key com.guarantee.ai.mapper.AiAuditLogMapper.insert`
  （`mybatis.mapper-locations: classpath*:mapper/**/*.xml` 同时命中 `target/classes` 与 jar）。
- **绝不**用 `-Dspring-boot.repackage.skip=true` 规避占用：它会把 fat jar 原地改写成 thin jar
  （无 `BOOT-INF/`、无 `Main-Class`），之后 `java -jar` 直接报"没有主清单属性"，
  而且 `.jar.original` 救不回来（那是上一轮的 thin jar）。
- 依赖（如 `DEEPSEEK_API_KEY`）在 User 作用域时，子进程可能继承不到，需显式注入后再启动。

## 归档策略

**跑出来的报告默认不入库**（自 2026-10-01 / R3 收尾起）：

- `reports/eval-*.{md,json}` 已被 `.gitignore` 排除，并已从索引移除
  （`git rm --cached`，磁盘文件照常保留）—— 跑一次评测**不会**再把工作区弄脏；
- **仍然跟踪**：`reports/README.md`（本文件）与 `reports/archive/**`（历史基线）；
- **要固化为基线**：显式把它复制成 `reports/archive/<名字>-<原因>.{md,json}` **再提交**
  （`archive/` 里的文件一律保留，用于对照与回滚，不再改写）；
- 例：`cp reports/eval-live-t610-2026-09-30.md reports/archive/eval-live-2026-09-30-snapshot-31-3-1.md`
  —— 文件名里带"原因"后缀，读的人不必翻 commit message 就知道它是哪一次、为什么留。

> 为什么这么定：旧口径是"正式报告跟踪入库"，结果每次跑评测都重写被跟踪的文件，
> 产生一堆与代码无关的 diff；而"哪些是基线"实际由人记。现在把**默认动作**（跑评测）
> 与**显式动作**（挑一份归档）分开，基线只由后者产生。
