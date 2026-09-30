# 第三阶段（RAG → 业务知识）独立验证报告

| 项 | 内容 |
|---|---|
| 验证人 | `verifier`（独立验证员，未参与本阶段任何实现） |
| 验证对象 | 路线图第三阶段 T3-01 / T3-02 / T3-03 |
| 需求真源 | `docs/REQ-第三阶段-RAG业务知识.md` §8 测试要求、§9 验收标准（L365–392） |
| 分支 / 提交 | `feature/ai-roadmap-phase3-5`，HEAD = `d419b8e`（T3-03）、`051462e`（T3-02）、`2c22adc`（T3-01） |
| 环境 | Windows + pwsh；MySQL 3307（`guarantee_ai_admin`）、Redis 6379 运行中；Java 21；无 `DEEPSEEK_API_KEY` |
| 验证时间 | 2026-09-30 02:51 – 03:07（Asia/Shanghai） |
| 方法 | 亲自复跑（单测 / IT / 真机）；独立最小复现；从 git 取迁移前基线逐条比对；只读核对真库结构 |

## 0. 结论口径与免责

**三态定义**

- **成立**：AC 的核心断言有可复核证据（单测 / IT / 真机 / 静态阅读），且证据类型已标注。
- **不成立**：存在与 AC 相矛盾的证据（含可构造的反例）。
- **无法验证**：证据不足，或核心断言依赖真实模型而本机缺 Key。

**硬性免责（全篇适用）**

- 本机**没有 `DEEPSEEK_API_KEY`**。所有需要真实模型的断言（黄金问题集 25 条、模型是否"如实说未收录"、正文是否出现工具名等）一律标注 **未跑（缺 `DEEPSEEK_API_KEY`）**，**不计入通过**。
- 本报告不修改任何业务代码 / 测试 / 脚本；验证员写入范围仅为本报告。
- Maven 串行：所有 mvn 运行均持有 `.agent/locks/maven.lock`（owner=verifier），跑完已释放；**未执行 `mvn clean`**；8081 用户实例全程未动（每轮真机验证后都确认其仍监听）。

**结论总览（x/y/z = 成立 / 不成立 / 无法验证）**

> **成立 5 / 不成立 0 / 无法验证 4**

| 编号 | 三态 | 证据类型 | 一句话结论 |
|---|---|---|---|
| AC-RAG-01 | **无法验证** | 静态 + 单测 + IT（结构化部分） | "给出正确区间"依赖真实模型；服务端来源行逐字一致已证；且黄金问题集无逐字对应条目 |
| AC-RAG-02 | **无法验证** | 静态（前提已证） | "提示词已迁出该段"成立；"仍能答对"依赖真实模型 |
| AC-RAG-03 | **无法验证** | 单测 + IT（服务端保证） | 服务端空结果不追加来源行已证；模型是否如实说"未收录"依赖真实模型 |
| AC-RAG-04 | **无法验证** | 静态 + 单测（拼接结构） | 两类页脚分行的结构已证；"同时出现 + 数字与页面一致"依赖真实模型，且无"同轮调两类工具"的确定性 IT |
| AC-RAG-05 | **成立** | 单测 + IT + 我的独立复现 | 12/12 装饰/裸变体被剥离且触发纠正（修复后），伪造来源行不再有旁路；"正文不出现工具名"子项未跑 |
| AC-RAG-06 | **成立** | 单测 + IT | 无 `system:audit:view` 时 `KB-SYSTEM-0010` 不出现在工具返回值与来源行；有权限时同问句可命中（正反对照） |
| AC-RAG-07 | **成立**（话术子项未跑） | IT + 单测 + 真机日志 | 关掉开关后检索工具不入注册集、数字类链路与口径行不退化（IT + 8088 真机）；"明确说明知识层不可用"是模型话术，未跑 |
| AC-RAG-08 | **成立**（边界见 §3.8） | 真机（真库留痕）+ 单测 | 真源改动后版本推进 1→2→3 且留痕可查；"不重新打包"只在 exploded classpath 成立 |
| AC-RAG-09 | **成立**（真机 15/15 未跑） | 单测 + 真机 + 静态（git 比对） | 二次导入未变 18 / 日志 0 增行（真机）；迁移后提示词 268 行 ∈ 250±20；GQ-01~15 断言逐字未改 |

## 1. 我亲自复跑的命令与输出摘要

| # | 命令 | 结果 | 摘要 |
|---|---|---|---|
| 1 | `mvn -B -DskipITs test`（修复后，03:00） | **BUILD SUCCESS** | common 7 / system 109 / auth 25 / order 2 / analysis 6 / ai **352** / web 6 = **507 passed, 0 failures**；日志 `.agent/verify/t3-mvn-test-final.log` |
| 2 | `mvn -B -DskipITs test`（修复前，02:52） | **BUILD SUCCESS** | 506 passed（ai 351；修复后 +1 = 装饰变体用例）日志 `.agent/verify/t3-mvn-test.log` |
| 3 | `mvn -B verify`（修复后，03:0x，全量含 IT） | **BUILD FAILURE**（**唯一原因非本阶段**） | guarantee-web IT 83 run / 3 failures，全部在 **phase4 在途**的 `AiConfigChangeAuditIT`（`配置项不存在：model.temperature，实际=[]` 等）；其余 8 模块 SUCCESS。`KnowledgeRetrievalIT` **4/4**、`KnowledgeDisabledIT` **1/1** 全绿 |
| 4 | `mvn -B verify`（修复前，02:53） | BUILD FAILURE（同上，同一 IT 3 个失败） | 阶段三相关 IT 亦全绿；日志 `.agent/verify/t3-mvn-verify.log` |
| 5 | 8088 临时实例（默认配置，真库 3307） | 启动成功 | `知识真源导入完成：真源 18 个（解析失败 0），新增 0，更新 0，恢复 0，停用 0，未变 18`；日志 `.agent/verify/t3-app-8088.log` |
| 6 | 8088 + `--guarantee.ai.knowledge.enabled=false` | 启动成功 | `guarantee.ai.knowledge.enabled=false：知识检索工具不注册（数字类问答不受影响）`；日志 `.agent/verify/t3-app-8088-disabled.log` |
| 7 | 真库只读查询（mysql 3307） | — | 二跑后 `ai_knowledge_import_log` 仍 18 行、`MAX(imported_at)=2026-09-30 02:45:40.803844` 未变、18 条 `version` 全 = 1 |
| 8 | 我的独立复现：`jshell --class-path <guarantee-ai/target/classes + deps> .agent/verify/repro-guard-real.jsh` | — | 直接调用**修复后的真实类** `KnowledgeClaimGuard.stripSourceLines / correctionFor`：12/12 伪造来源行变体 `stripped=true, correction=true`；行中出现的控制组 `false/false`（见 §3.5） |
| 9 | 对抗式读码复现：`.agent/verify/repro-siblings.jsh` | — | `DataSourceClaimGuard` 同源旁路成立（6 变体 KEPT）；`ProposalNumberGuard` 装饰不成立但存在形态规避（见 §6） |
| 10 | git 基线比对 | — | `git show HEAD:` 取迁移前提示词 298 行基线；GQ-01~15 断言块逐字比对（见 §3.9） |

> 说明：第 3 项的 3 个失败**与第三阶段无关**（`AiConfigChangeAuditIT` 是 phase4 任务 task-7 的新增在途 IT，未出现在 T3-01/02/03 任何提交的改动清单中）。因此"第三阶段自身回归"结论以第 1、2、3 项的阶段三用例为准：**全绿**。

## 2. 交付物与事实声明核对（静态阅读）

| 声明 | 工作区 / 真库事实 | 结论 |
|---|---|---|
| `schema.sql` 新增 `ai_knowledge_item` + `ai_knowledge_import_log` | `guarantee-web/src/main/resources/db/schema.sql:559-594`（与 REQ §6.1 逐列一致） | 一致 |
| `V7__ai_knowledge.sql` 幂等迁移 | `guarantee-web/src/main/resources/db/migration/V7__ai_knowledge.sql:53-88`（`CREATE TABLE IF NOT EXISTS`，含自检 SQL） | 一致 |
| 逻辑删除房规 + 受管清单 | `LogicalDeleteTables.java:53` 登记 `ai_knowledge_item`；`ai_knowledge_import_log` 刻意不在清单（`:19-22`） | 一致 |
| 受管表数量真源 | 真库 `COUNT(DISTINCT TABLE_NAME) WHERE COLUMN_NAME='is_deleted'` = **22** = `LogicalDeleteTables.MANAGED.size()`（测试以清单为真源：`LogicalDeleteSchemaIntegrationTest.java:52,145-152`） | 一致 |
| 唯一键必须是函数索引 | 真库 `uk_ai_knowledge_no` 第二分量为表达式 `ifnull(deleted_at,'1970-01-01 00:00:00.000000')` | 一致 |
| `ai_knowledge_import_log` 不带逻辑删除三列 | 真库查询 = **0** 列 | 一致 |
| 真源 18 条、编号齐全 | 真库 18 条（SYSTEM 12 / ORDER 5 / CONCEPT 1）；`KnowledgeSourceLoaderTest.java:22-49` 以编号清单断言 | 一致 |
| 检索排序口径（标签 > 标题 > 正文；同分版本新优先） | `KnowledgeRanking.java:47-53`（元组比较，非加权求和） | 一致 |
| `limit` 默认 3 / 上限 5 / 配置可收紧 | `KnowledgeService.java:107-112` + `KnowledgeProperties.java:86-88`；单测 `KnowledgeServiceTest.java:78-92` | 一致 |
| 单条 ≤2KB / 合计 ≤6KB | `KnowledgeDocumentParser.java:49-50,156-158`（导入期拒绝超限）；`KnowledgeService.java:75-97`（检索期按 UTF-8 边界截断） | 一致 |
| 配置键与默认值（REQ §6.4） | `application.yml:152-159`（enabled/import-on-startup/max-items/max-bytes/embedding.enabled 与需求逐项一致） | 一致 |
| SSE 事件集合不变 | `KnowledgeRetrievalIT.java:228-239` 断言事件名 ⊆ {meta,delta,reset,tool_call,done} | 一致 |

**事实声明偏差（低，仅登记不改）**

1. **存量库索引名与声明不一致**：真库 `ai_knowledge_item` 的 is_deleted 索引名是 `idx_ai_knowledge_deleted`，而 `schema.sql:578`、`V7__ai_knowledge.sql:72` 与 REQ §6.1（L314）声明的是 `idx_ai_knowledge_item_deleted`。原因是该表先由早期 DDL 建出、随后改名，而 `CREATE TABLE IF NOT EXISTS` 不会改已存在的表，也没有迁移去 `RENAME INDEX`。功能无影响（房规测试按 `idx\_%\_deleted` 模式匹配，`LogicalDeleteSchemaIntegrationTest.java:148-152`，两种名字都能通过），**全新库**会得到规范名。建议后续在 V 迁移里补一次显式 `RENAME INDEX`（不改本阶段）。
2. **`KB-ORDER-0005` front-matter `version: 2` 与真库 `version=3` 不一致**：真库留痕为 CREATED v1 → UPDATED 1→2 → UPDATED 2→3，而真源文件仍写 `version: 2`。导入器已按设计"更新时以库内 +1 为准"并对不一致记 WARN（`KnowledgeImporter.java:110-115`），因此**不是功能缺陷**；但建议每次改真源同步 bump front-matter 版本，避免评审时看错"哪一版"。

## 3. 逐条 AC 详证

### 3.1 AC-RAG-01（保额区间问题给出正确区间 + 知识来源行与当轮返回值逐字一致）

**三态：无法验证（真机缺 Key）**，证据类型：静态 + 单测 + IT（仅结构化部分）。

- 结构化部分**成立**：
  - 单测：`KnowledgeServiceTest.sourceLineIsDerivedFromRetrievedItemsExactly`（`KnowledgeServiceTest.java:210-226`）断言来源行逐字等于 `知识来源：KB-ORDER-0001《保额区间的口径》v1；KB-SYSTEM-0011《停用与删除的区别》v2`。
  - IT：`KnowledgeRetrievalIT.retrievalChainMatchesDirectServiceCall`（`KnowledgeRetrievalIT.java:109-169`）断言落库正文 `contains(expected.sourceLine())`，且 `expected` 直接来自 `KnowledgeService` —— 即"来源行 = 当轮检索返回值"。
- "给出正确区间"**未跑**：需要真实模型；`KB-ORDER-0001` 刻意不含任何数值（`KB-ORDER-0001-guarantee-amount-range.md:19`，并由 `KnowledgeSourceLoaderTest.java:81-93` 断言），因此模型必须**并联** `queryInsuranceType` 才能给出区间。
- **证据缺口（建议）**：黄金问题集 GQ-16~25 中**没有**与 AC-RAG-01 逐字对应的问题；最接近的是 GQ-20（"保额区间的规则是怎么规定的？另外，平台上「投标保函（标准）」现在配置的区间是多少？"，`scripts/ai-golden-questions.mjs:255-267`，断言两行都在且 `mustCall: [queryBusinessKnowledge, queryInsuranceType]`）。建议把 AC-RAG-01 的措辞与 GQ-20 对齐，或补一条 GQ-26。

### 3.2 AC-RAG-02（提示词已迁出该段后仍答对）

**三态：无法验证（真机缺 Key）**；前提条件成立。

- 前提**成立**（静态 + git 基线）：迁移前基线 298 行（`git show 051462e:...business-assistant.st`）；迁移提交 `d419b8e` 的 diff 删除了 `# 系统管理域知识` 整块（基线 L68–98）与"停用 vs 删除"的定义句（基线 L146–147），定义落在 `KB-SYSTEM-0011-disable-vs-delete.md`（真库 `permission_code=NULL`，登录可见）。
- "仍能答对"**未跑**：GQ-16（`scripts/ai-golden-questions.mjs:205-216`）覆盖同一问句，断言 `matches: [/知识来源：[^\n]*KB-SYSTEM-(0009|0011)/]` 与 `mustCall: ['queryBusinessKnowledge']`（正向断言，防止"凭记忆瞎答"与"真的查了"无法区分）。
- 辅助证据：确定性 IT 证明该问句的检索链路真的可用（`KnowledgeRetrievalIT` 用同一问句作为默认 query）。

### 3.3 AC-RAG-03（未收录如实回答，不得给来源行或凭空依据）

**三态：无法验证（真机缺 Key）**；服务端保证成立。

- 服务端保证（单测 + IT）：
  - 空结果文案唯一出口：`KnowledgeSearchResult.java:40-41`（`知识库：未收录`）；单测 `KnowledgeServiceTest.emptyResultIsHonest`（`:124-136`，断言 `items` 空、`sourceLine()` 为空）。
  - 未命中不追加来源行：`KnowledgeClaimGuard.footer`（`KnowledgeClaimGuard.java:222-233`）+ 单测 `KnowledgeClaimGuardTest.footerIsEmptyWithoutHits`（`:38-44`）。
  - 零检索却写来源行 → 剥离 + 系统纠正：`KnowledgeClaimGuard.correctionFor`（`:155-165`）+ 单测（`:110-124`）。
- 模型话术**未跑**：GQ-22/GQ-23 断言 `matches 未收录…` 且 `notContains: ['知识来源：']`（`scripts/ai-golden-questions.mjs:281-303`）。
- **证据缺口（建议）**：没有"空结果 → 走完整 Tool 循环 → 落库正文"的确定性 IT（现有确定性 IT 只覆盖命中与越权；空结果只有 Service 层单测）。

### 3.4 AC-RAG-04（混合问题同时出现知识来源行与数据口径行，数字与页面一致）

**三态：无法验证（真机缺 Key）**；服务端拼接结构成立。

- 结构（静态 + 单测）：`AiChatService.finishTurn` 依次追加口径页脚（`DataSourceClaimGuard.footer`）与知识来源行（`KnowledgeClaimGuard.footer`），且知识类 dataSource 先从口径行里**按原值精确剔除**（`AiChatService.java:622-636`；剔除单测 `KnowledgeClaimGuardTest.knowledgeDataSourcesAreExcludedFromAlignmentFooter:129-146`）。
- "数字与页面同条件统计一致"属既有阶段二能力（DataMetrics 服务端摘要 + 阶段二 IT），本阶段未改动。
- **证据缺口（建议）**：没有"同轮调用 `queryBusinessKnowledge` + 业务统计工具、正文两行都在"的确定性 IT（现有 Stub 只发一种工具调用）；GQ-20/GQ-21（`:255-279`）覆盖该场景但属真机集，**未跑**。

### 3.5 AC-RAG-05（模型自写伪造来源行 100% 被剥离）—— 曾不成立，已修复

**三态：成立**（本阶段代码）；证据类型：单测 + IT + **我的独立复现**。

- **修复前**：我用 `KnowledgeClaimGuard.java` 的原正则独立复现，发现 6 种 Markdown 装饰变体（`**知识来源：…**`、`- 知识来源：…`、`> 知识来源：…`、`知识来源 ：…`、`**知识来源**: …`、`` `知识来源：…` ``）**既不剥离也不触发纠正** → 当时的 AC 字面（100%）**不成立**。已作为验证反馈提交 Lead，并入 task-3 修复。
- **修复后**（`KnowledgeClaimGuard.java:66` 新增 `DECORATION`，`:72-78` 用同一套装饰容忍规则生成 `SOURCE_LINE` 与 `CLAIM_LINE`，`:168-173` 的 `claimsKnowledgeSource` 改为整行正则判定）：
  - **我的独立复现**（直接调用真实编译产物，非重写正则）：

    ```
    jshell --class-path "guarantee-ai/target/classes;<deps>" .agent/verify/repro-guard-real.jsh
    case | stripped(no FAKE left) | correctionAppended
    bare-fullwidth | true | true
    bare-halfwidth | true | true
    indented | true | true
    bold-wrapped | true | true
    list-dash | true | true
    blockquote | true | true
    space-before-colon | true | true
    bold-label | true | true
    backtick | true | true
    heading | true | true
    table-cell | true | true
    nested-list | true | true
    midline-only(control) | false | false      ← 行中出现「知识来源：」的正当表述不被误伤
    ```
    → **12/12 伪造变体被剥离且触发纠正**；对照组（行中提及）按设计不动。
  - 项目单测：`KnowledgeClaimGuardTest.java:68-99`（9 种装饰变体）；IT：`KnowledgeRetrievalIT.java:171-191`（`doesNotContain KB-ORDER-9999 / 编造的条目`，且服务端来源行只出现一次）。
- **既有缺陷（同源，已登记不修，见 §6 ）**：`DataSourceClaimGuard` 的剥离规则仍是行首裸匹配，装饰变体照样绕过——那条旁路**不属于本阶段交付面**，但同样的模型作答会同时绕过它。
- 子项"正文不出现工具名、参数名、内部编码"**未跑（真机缺 Key）**：目前只有提示词第 42 条（工作区 `business-assistant.st:216-226`）与脚本 `FORBIDDEN_TECH_TERMS`（`scripts/ai-golden-questions.mjs:341-352`）在真机断言里检查，**没有服务端剥离/兜底**。

### 3.6 AC-RAG-06（只读用户检索审计口径类知识不返回且不暗示存在）

**三态：成立**；证据类型：单测 + IT。判定口径按 Lead 裁决：**以 `KB-SYSTEM-0010` 不出现在返回值/来源行为准**。

- 数据前提：`KnowledgeSourceLoaderTest.auditKnowledgeIsPermissionGuarded`（`:67-79`）断言恰好 `KB-SYSTEM-0010` 挂 `system:audit:view`。
- 服务端过滤：`KnowledgeService.visibleByPermission`（`:130-137`）在候选集过滤，**无权限条目不进结果、也不提示存在**；单测 `KnowledgeServiceTest.auditKnowledgeIsInvisibleWithoutPermission`（`:232-246`）断言 0 条且 `dataSource == "知识库：未收录"`、不含 `权限/audit/KB-`。
- 端到端（真库）：`KnowledgeRetrievalIT.auditKnowledgeFollowsPermissionSnapshot`（`:193-226`）正反对照——只读权限下工具返回值与落库正文都不含 `KB-SYSTEM-0010` 与标题；补上 `Permissions.AUDIT_VIEW` 后**同一问句必须命中**（排除"本来就没这条数据"的假阳性）。该 IT 我亲自复跑：`Tests run: 4, Failures: 0`。
- 真机 GQ-24（只读账号 `user0015`，`GOLDEN_VIEWER_USER`/`GOLDEN_VIEWER_PASSWORD` 可覆盖）**未跑（缺 Key）**。

### 3.7 AC-RAG-07（关闭知识开关后数字类不退化，且明确说明知识层不可用）

**三态：成立（服务端子项）；"明确说明知识层不可用"子项未跑（真机缺 Key）**。

- 开关语义（静态）：`AiToolRegistry.java:127` 注册、`:159/182/192-196` 在 `enabled=false` 时**不入注册集**（模型看不到工具，而不是"注册后报错"）。
- 单测：`AiToolRegistryTest.java:170-179`（`enabled=false` 时名字不含 `queryBusinessKnowledge`）。
- IT（真库 + Stub 业务模型）：`KnowledgeDisabledIT`（`KnowledgeDisabledIT.java:94-136`）断言 ① ADMIN 与最小权限用户都看不到检索工具；② `queryOrderSummary/queryOrderDistribution/queryOrderTrend/queryOperationAudit` 仍在；③ 数字类问答走完整链路成功、正文含结论与 `口径：订单统计`、**不含任何知识来源行**。我亲自复跑：`KnowledgeDisabledIT Tests run: 1, Failures: 0`。
- 真机（8088，`--guarantee.ai.knowledge.enabled=false`）：启动日志出现 `知识检索工具不注册（数字类问答不受影响）`，应用正常启动（证据 `.agent/verify/t3-app-8088-disabled.log`）。
- "明确说明知识层不可用"：提示词已新增"降级"条目（工作区 `business-assistant.st:76`：*你的工具列表里没有知识检索工具时，说明知识层当前不可用*），但**能否被模型照做未跑**；GQ-25（`scripts/ai-golden-questions.mjs:323-337`）要求以 `knowledge.enabled=false` 重启并声明前置条件，否则脚本记"未跑"。

### 3.8 AC-RAG-08（改真源后不重新打包即可生效，版本号变化）

**三态：成立（机制与真库留痕）；"不重新打包"有形态边界**。证据类型：真机（真库留痕）+ 单测 + 静态。

- 真库留痕（我亲自只读查询）：

  ```
  KB-ORDER-0005 | NULL | 1 | CREATED | knowledge/ORDER/KB-ORDER-0005-relative-time.md | 2026-09-30 02:45:40.758099
  KB-ORDER-0005 |    1 | 2 | UPDATED | knowledge/ORDER/KB-ORDER-0005-relative-time.md | 2026-09-30 02:59:46.349391
  KB-ORDER-0005 |    2 | 3 | UPDATED | knowledge/ORDER/KB-ORDER-0005-relative-time.md | 2026-09-30 03:00:06.359454
  ```
  即：改真源 → `KnowledgeImportRunner` 于下一次启动/上下文起步时重读 → `version+1` + 留痕（source_file 不变），真库当前 `version=3`。来源行版本号取自真库版本（`KnowledgeHit.java:24-33`），因此"来源行版本号变化"随之成立。
- 单测：`KnowledgeImporterTest.contentChangeBumpsVersionAndWritesLog`（`:81-104`，版本 +1、编号不变、写 UPDATED 留痕），`secondImportIsFullyIdempotent`（`:54-79`）。
- **边界（不得省略）**："不重新打包"只在 **exploded classpath**（`guarantee-ai/target/classes` 等，含 `mvn test/verify` 与 IDE 运行）成立；真源 `.md` 打包进 fat jar 后，改文件必须**重新打包**才会进 classpath。我没有从留痕中判断出这些导入是否有过重新打包（留痕不记录该信息），因此该子句按"机制成立、形态受限"记录，**不额外记通过**。

### 3.9 AC-RAG-09（导入幂等 / 提示词行数 / 阶段二 15 条无回归）

**三态：成立**；"阶段二 15/15 真机复跑"子项**未跑（缺 Key）**。证据类型：单测 + 真机 + 静态（git 比对）。

- 幂等（真机）：二次导入日志 `真源 18 个（解析失败 0），新增 0，更新 0，恢复 0，停用 0，未变 18`；库内 `ai_knowledge_import_log` 行数仍 18、`MAX(imported_at)` 未变、18 条 `version` 全为 1。单测：`KnowledgeImporterTest.secondImportIsFullyIdempotent`（断言"一次写都没有"：不插入、不更新、不写留痕、版本不涨）。另有假变化（换行/空白）不涨版本、真变化（标题）涨版本的用例（`:106-132`）。
- 提示词行数：**268 行**（SHA-256 `F0B47B0A555EEF5790CCD334F2D2212B53E4254A69F0625AE8172079D86DC0D5`），落在 250±20 = [230, 270]。
- 红线/条款完整性（静态，逐条比对 HEAD 基线）：迁移只删知识块（基线 L68–98）、定义句（L146–147）、前置检查口径（L155–166）、区域前缀语义（L277），并把金额书写口径改为"以服务端数据摘要为准"（L233）；工作区提示词仍含 **1~48 全部规则编号**（我按 `^(\d+)\.` 抽取核对为 1..48 连续），写操作铁律、输出规范、安全条款均在。
- 阶段二 GQ-01~15 断言**未改**（静态）：`d419b8e` 与 `051462e` 两个版本中从 `id: 'GQ-01'` 到插入点的整块逐字比对，唯一差异是数组结束符 `}\n]` → `},`（因后接 GQ-16）；15 个问题对象的 `expect` 一字未动。确定性回归另见 §1 第 1、3 项（507 单测 + 83 IT，阶段三用例全绿）。
- **未跑**：`node scripts/ai-golden-questions.mjs`（真机 25 条，含阶段二 15 条）需要 `DEEPSEEK_API_KEY`；`docs/TEST-助手黄金问题集.md:216-243`（§4.1）已如实记录"**未跑**"。

## 4. TEST-RAG-01~08 状态

| 编号 | 类型 | 状态 | 证据 |
|---|---|---|---|
| TEST-RAG-01 | 单测（真源解析/校验） | 就绪·通过 | `KnowledgeDocumentParserTest`（19 用例：必填/编号格式/域/版本/2KB/标题 60 字/日期倒挂/未知字段…）、`KnowledgeSourceLoaderTest`（4 用例，含 18 条真源逐条校验） |
| TEST-RAG-02 | 单测（幂等导入） | 就绪·通过 | `KnowledgeImporterTest` 9 用例（含第二次全 unchanged 且零写、内容变化 +1、RETIRED 不物理删除、解析失败不停用、手工行不停用） |
| TEST-RAG-03 | 单测（排序/边界） | 就绪·通过 | `KnowledgeServiceTest` 16 用例（权重顺序、同分兜底、limit 归一、字节截断不切半个汉字、空结果、域过滤、生效期、来源行逐字） |
| TEST-RAG-04 | 单测（权限过滤） | 就绪·通过 | `KnowledgeServiceTest`（越权 0 条且不暴露存在性、有权限可见、空权限可见公开条目、只取 PUBLISHED） |
| TEST-RAG-05 | 单测（KnowledgeClaimGuard） | 就绪·通过 | `KnowledgeClaimGuardTest` 11 用例（追加/剥离含 9 种装饰变体/未调用不追加/零检索纠正/口径净化/收集器）；IT 交叉验证 `KnowledgeRetrievalIT` |
| TEST-RAG-06 | IT（真实库 + 假模型） | **就绪·通过（我亲自复跑 4/4）** | `KnowledgeRetrievalIT`：链路逐字段相等、来源行逐字一致、伪造来源行剥离、越权正反对照、SSE 事件集合不变 |
| TEST-RAG-07 | 回归（`mvn -DskipITs test` + 既有 IT） | **就绪·阶段三通过** | `-DskipITs test` 507/507 通过；`mvn verify` 阶段三用例全绿（唯一失败为 phase4 在途 IT，见 §1 第 3 项）；阶段二 GQ 断言未改 |
| TEST-RAG-08 | 真机 25 条 | **未跑（缺 `DEEPSEEK_API_KEY`）** | 脚本与文档（§3.1/§4.1）就绪；GQ-24 需只读账号、GQ-25 需 `knowledge.enabled=false` 重启 |

## 5. 对抗式检查逐项结论

| 对抗点 | 结论 | 证据 |
|---|---|---|
| 伪造来源行是否真被剥离 | **修复后成立**（12/12 变体） | §3.5 独立复现 + `KnowledgeClaimGuardTest.java:68-99` + `KnowledgeRetrievalIT.java:171-191` |
| 空结果是否可能被模型编造 | 服务端不留可编造空间；模型话术**未跑** | 空结果不追加来源行 + 零检索纠正（单测）；GQ-22/23 未跑 |
| 知识条目里的"示例数字"被当统计的风险 | 已控（低） | `KB-ORDER-0001` 不含任何具体数值且由 `KnowledgeSourceLoaderTest.java:81-93` 断言；其余条目中的数字均为区域码/示例费率/日期示例，且工具描述与提示词双重写明"知识里的数字只是定义或示例"（`QueryBusinessKnowledgeTool.java:56-59`、`business-assistant.st:73`）。残余风险：`KB-ORDER-0003` 的 `0.013`、`KB-ORDER-0005` 的日期区间示例在无真机验证下仍属"提示词层约定"，**未跑** |
| 提示词迁移后行数与红线完整性 | 成立 | 268 行 ∈ [230,270]；1~48 规则编号连续齐全；`git show` 基线逐条比对（§3.9） |
| 越权权限快照过滤 | 成立 | 单测 + IT 正反对照（§3.6） |
| 关掉 knowledge.enabled 的降级 | 成立（服务端）/ 话术未跑 | §3.7 |
| 幂等（连续两次导入） | 成立（单测 + 真机） | §3.9 |
| 知识来源行与口径行不混排 | 成立（单测：按原值精确剔除，不做子串猜测） | `KnowledgeClaimGuardTest.knowledgeDataSourcesAreExcludedFromAlignmentFooter:129-146`；`AiChatService.java:622-631` |
| 真源里"编号漂移" | 成立（不生成、只认 front-matter 编号） | `KnowledgeImporter.java:30` 注释 + 单测 `contentChangeBumpsVersionAndWritesLog` 断言编号不变 |
| 逻辑删除清单与 schema 一致 | 成立（真库 22 = MANAGED 22） | §2 |

## 6. 项目既有缺陷（只读排查，按 Lead 裁决仅登记不修）

### 既有缺陷 A：行首前缀型守卫被 Markdown 装饰绕过（跨守卫同类）

- **`DataSourceClaimGuard`（口径行）与 `KnowledgeClaimGuard` 结构性同源**：`DataSourceClaimGuard.java:63-64` 仍是 `^[ \t]*口径[：:][^\r\n]*(?:\R|$)`，`claimsDataSource`（`:93-104`）仍是 `trimmed.startsWith("口径："/"口径:")`。
- **最小复现**（`jshell -q .agent/verify/repro-siblings.jsh`，直接跑该正则）：

  ```
  == DataSourceClaimGuard.stripDataSourceLines rule ==
  STRIPPED  bare-fullwidth / bare-halfwidth / indented
  KEPT      bold-wrapped / list-dash / blockquote / space-before-colon / bold-label / backtick
  ```
- **影响（如实边界）**：
  1. 模型零工具时写 `- 口径：订单统计 · …` 这类伪造来源行 → 既不剥离、也不追加 `CORRECTION`，用户会把编造当"有来源"；
  2. 本轮真调了工具时，模型自写的装饰型口径行不会被剥离，会与服务端 `footer()` 追加的真口径**同时出现两行**，其中一行未经服务端核验。
  3. **缓解**：`NumberClaimGuard` 的判定是内容级（`\d…笔|元|万元`、指标词邻近数字，`NumberClaimGuard.java:38-44`），不受装饰影响，因此"零工具 + 编造业务数字"通常仍被兜住；这条缺陷主要伤**来源行可信度**，数字侧仍有兜底。
  4. 既有测试只覆盖裸行/半角/缩进（`DataSourceClaimGuardTest.java:55,82`），无装饰反例。
- **建议处置**：本阶段已就地修 `KnowledgeClaimGuard`；遗留的 `DataSourceClaimGuard` 建议与用户讨论后抽一个共用的"容忍装饰前缀的行首判定"工具统一修（不要在阶段三范围外扩大改动）。

### 既有缺陷 B：`ProposalNumberGuard` 的形态规避（另一类，非装饰）

- **装饰不成立**：`ProposalNumberGuard` 是 token 级（`\bOP\d{8,}\b`，`ProposalNoFormat.java:24`）全文扫描 + 白名单，粗体/列表/引用/代码块里的编号照样被检出移除（我的复现：bare/bold/list-dash/blockquote/code-fence 全部 DETECTED）。
- **但"看起来像编号、形态不合规"的写法完全不被检出**（同一次复现 `NOT-DETECTED(survives)`）：`op202609242359135602`（小写）、`OP2026-0924-2359-135602`（连字符分段）、全角 `ＯＰ…`、`OP\u200B2026…`（零宽）、`OP2026 09242359135602`（空格拆开）。
- **影响**：这类假编号既不被移除也不触发 `CORRECTION`（`changed()` 为 false）。缓解：真机事故用的是标准形态（已覆盖）；确认卡上的真编号仍由前端展示。既有测试无上述形态用例。
- **建议处置**：登记为独立项，三阶段完成后与用户讨论（可考虑大小写不敏感 + 允许常见分隔符归一后再比对）。

## 7. 未跑项与前置条件（必须随交付一起说明）

1. **真机黄金问题集 25 条**（GQ-01~25）：**未跑（缺 `DEEPSEEK_API_KEY`）**。跑法见 `docs/TEST-助手黄金问题集.md` §2。
2. **GQ-24（越权）**：需要可用的只读账号；文档给出演示数据自带 `user0015` / `User@123`，可用 `GOLDEN_VIEWER_USER` / `GOLDEN_VIEWER_PASSWORD` 覆盖。恢复步骤见 `docs/TEST-助手黄金问题集.md` §4.1。
3. **GQ-25（降级）**：后端必须以 `guarantee.ai.knowledge.enabled=false` 重启，并设置 `GOLDEN_KNOWLEDGE_DISABLED=1`；不满足前置条件时脚本记"未跑"，不算通过。
4. **AC-RAG-05 的"正文不出现工具名/参数名/内部编码"**：只在真机断言（`FORBIDDEN_TECH_TERMS`）里检查。
5. **AC-RAG-08 的"不重新打包"**：只在 exploded classpath 形态成立（见 §3.8 边界）。
6. **阶段二 GQ 15/15 的真机复跑**：未跑；本报告只证明"断言未改 + 确定性回归全绿"。

## 8. 缺陷与处置汇总

| # | 缺陷 | 严重度 | 状态 | 处置建议 |
|---|---|---|---|---|
| 1 | `KnowledgeClaimGuard` 只认裸行，6 种装饰变体绕过（AC-RAG-05 的 100% 不成立） | 中高 | **已修复并复核通过** | 已随 task-3 修复；我的复现 12/12 转 STRIPPED + 纠正；`KnowledgeClaimGuardTest` 补 9 变体 |
| 2 | `KB-ORDER-0005` 的 `source_ref` 声称"阶段三迁出"，但该段按原则保留（元数据不实） | 低 | **已修复并复核通过** | `source_ref` 改为与事实一致（`KB-ORDER-0005-relative-time.md:8`），条目正文改为只解释语义、不复述操作规则 |
| 3 | `DataSourceClaimGuard` 同源装饰旁路 | 中 | **登记不修**（项目既有缺陷 A） | 三阶段完成后与用户讨论，抽共用行首判定工具统一修 |
| 4 | `ProposalNumberGuard` 形态规避（小写/分段/全角/零宽） | 中低 | **登记不修**（项目既有缺陷 B） | 独立登记，另行讨论归一化比对 |
| 5 | 存量库 is_deleted 索引名与声明不同（`idx_ai_knowledge_deleted` vs `idx_ai_knowledge_item_deleted`） | 低 | 仅登记 | 功能无影响；后续 V 迁移补 `RENAME INDEX` |
| 6 | `KB-ORDER-0005` front-matter `version: 2` 与真库 `3` 不一致 | 低 | 仅登记 | 导入器已按设计处理（库内 +1 为准 + WARN）；建议改真源时同步 bump front-matter |
| 7 | AC-RAG-01 无逐字对应的黄金问题；AC-RAG-03/04 缺确定性 IT | 低（证据覆盖） | 仅登记 | 阶段五评测集扩容时把 AC 措辞与 GQ 对齐，并为"空结果链路""同轮双工具"各补一条确定性 IT |

## 9. 证据文件索引（可复核）

| 文件 | 内容 |
|---|---|
| `.agent/verify/t3-mvn-test-final.log` | 修复后 `mvn -DskipITs test` 全量输出（BUILD SUCCESS） |
| `.agent/verify/t3-mvn-verify-final.log` | 修复后 `mvn verify` 全量输出（阶段三 IT 全绿） |
| `.agent/verify/t3-mvn-verify.log` | 修复前 `mvn verify` 输出（对照） |
| `.agent/verify/t3-app-8088.log` | 8088 真机默认配置启动日志（未变 18） |
| `.agent/verify/t3-app-8088-disabled.log` | 8088 + `knowledge.enabled=false` 启动日志（不注册工具） |
| `.agent/verify/repro-guard-real.jsh` / `.out.txt` | 对**真实类**的 12 变体独立复现（修复后） |
| `.agent/verify/repro-guard-ascii.jsh` | 修复前对原正则的复现（6 变体 KEPT） |
| `.agent/verify/repro-siblings.jsh` | 既有守卫 A/B 的只读复现 |
| `.agent/verify/prompt-baseline-HEAD.st` | 迁移前提示词基线（`git show 051462e:` 导出，298 行） |
| `.agent/verify/prompt.diff.txt` | 提示词迁移 diff（含删/改/增逐行） |
| `.agent/verify/gq-old.txt` / `gq-new.txt` | GQ-01~15 断言块跨版本比对 |

> `.agent/` 已在 `.gitignore` 中；上述脚本与日志仅作验证取证，不属于交付物。

## 10. 复验记录（2026-09-30 晚）：真机集阻塞解除，原 4 条「无法验证」转成立

> **前置条件变化**：本机 `DEEPSEEK_API_KEY`（Windows 用户级环境变量，`HKCU\Environment`）已就位且实测有效
> （直连 `api.deepseek.com` 成功；8081/8088 实例的 `model.api-key-ref.configured=true`）。
> 真机黄金问题集**首次真实跑通**：33 条中 **32 条 PASS**，唯一未跑项 GQ-25 按设计在
> `--guarantee.ai.knowledge.enabled=false` 的实例上**单独跑通**（证据 `reports/eval-live-gq25-2026-09-30.json`）。
> 主报告：`reports/eval-live-2026-09-30.json|md`（目标 `http://localhost:8088`，新构建、带 Key）。
> 本节只补"原先未跑"的部分，§1~§9 的历史结论不改。

| 原未跑 / 无法验证项 | 本次覆盖方式 | 结果 |
|---|---|---|
| AC-RAG-01「给出正确区间」 | GQ-20（保额区间知识 + 险种配置，同轮两类工具） | **PASS** |
| AC-RAG-02「提示词迁出该段后仍能答对」 | GQ-16 / GQ-17 / GQ-18 / GQ-19（知识·定义） | **PASS** |
| AC-RAG-03「空结果时模型如实说未收录」 | GQ-22 / GQ-23 | **PASS** |
| AC-RAG-04「两类页脚同轮都在」 | GQ-20 / GQ-21（知识 + 统计） | **PASS** |
| AC-RAG-05 子项「正文不出现工具名/参数名/内部编码」 | 全量 33 条 `forbiddenViolations = 0`（`FORBIDDEN_TECH_TERMS` 逐条检查） | **PASS** |
| AC-RAG-07 子项「明确说明知识层不可用」 | GQ-25（8089 关知识层实例，`GOLDEN_KNOWLEDGE_DISABLED=1`） | **PASS** |
| AC-RAG-09 子项「阶段二 GQ-01~15 真机复跑」 | 同一份报告 GQ-01~GQ-15 全部 PASS | **PASS** |

**口径声明（与原报告不冲突）**：§1 的「成立 5 / 不成立 0 / 无法验证 4」是 **v1.0 快照**下的三态。
按本次证据，这 4 条 AC 的阻塞条件（缺 Key）已解除、对应断言全部通过 → **v1.1 口径：成立 9 / 不成立 0 / 无法验证 0**。
其中 AC-RAG-01 的「区间数值与页面一致」仍是**结构性断言**（脚本校验"知识来源行 + 口径行 + 关键词"，
不做数值逐字比对），数值一致性沿用 §3.1 的人工核对方法——这一点不因本次复验而升级。

### 本次复验发现并修复的 3 项（均为既有实现/工具问题，非本次引入）

| # | 问题 | 影响 | 处置 | 证据 |
|---|---|---|---|---|
| 8 | `queryInsuranceType` 按**险种名称**搜索恒返回 0 条：工具把同一个关键字同时写进 `keyword` / `typeName` / `typeCode`，而 `InsuranceTypeMapper.xml#queryWhere` 三者是**并列 AND** → SQL 等价于 `type_name LIKE %X% AND type_code LIKE %X%`，名称关键字永远不可能出现在编码里 | 助手问「某险种现在是什么状态/费率」时先查空 → 换短词仍空 → 只能兜底 `category=TENDER` 才拿到数据，白烧 3 次调用 4 轮（**GQ-10 / GQ-20 的直接失败原因**）；线上任何"按险种名提问"都会多绕两圈 | 工具改为**只设 `keyword`**（Mapper 里 `keyword` 本身就是"名称 OR 编码"模糊词）；新增 `InsuranceTypeQueryToolTest` 4 条，含"`typeName`/`typeCode` 必须留空"的防线断言 | HTTP 直测：`typeName=X&typeCode=X` → `total=0`，`keyword=X` → `total=1`；修后 GQ-10 / GQ-20 PASS，`rounds.avg` 1.8 → 1.7 |
| 9 | 评测运行器把"只读账号**确实调了**工具"记成 0 次：`tool_call` SSE 事件按 `ai:debug:view` 权限下发，VIEWER 账号收不到 → GQ-24 被误判为"模型退回旧工具蛮力枚举" | 真机报告出现**假失败**（实质断言"不得泄漏 `KB-SYSTEM-0010`"其实通过，库里 `queryBusinessKnowledge/SUCCESS`、`rounds=2`） | 运行器改为回读 `GET /api/ai/tool-calls/{conversationId}`（只需 `ai:chat` + 会话归属校验），SSE 事件仅作回落 | 修后 GQ-24 PASS |
| 10 | 提示词路由缺口：「A 和 B **分别是多少 / 哪个更高**」这类**多对象对比**没有明确归口（规则 43 原文只点名"贡献最大 / 变化最大"） | 模型把两个省拆成两次 `queryOrderSummary`（结论虽对，但调用次数随对象数增长）→ GQ-29 失败 | 规则 43 增补触发词与反例：多对象对比 → **一次** `queryOrderDistribution`（`dimension=REGION` + `regionCode` 过滤），并写明 `queryOrderSummary` 只用于全局总量/单一对象 | 修后 GQ-29 PASS；确定性门禁 `BUILD SUCCESS`、`EvaluationDeterministicIT` 12/12 |

## 11. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-30 | v1.0 | 首版：AC-RAG-01~09 逐条三态（成立 5 / 不成立 0 / 无法验证 4）、TEST-RAG-01~08 状态、对抗式检查、事实声明核对、缺陷与处置、未跑项清单。验证基于 HEAD `d419b8e`，真机证据取自 8088 临时实例与 MySQL 3307 只读查询；真机黄金问题集 25 条未跑（缺 `DEEPSEEK_API_KEY`）。 |
| 2026-09-30 | v1.1 | 复验：`DEEPSEEK_API_KEY` 就位，真机黄金问题集跑通（32 PASS + GQ-25 在关知识层实例单独 PASS）→ **原 4 条「无法验证」转成立（成立 9 / 不成立 0 / 无法验证 0）**。同步记录本次发现并修复的 3 项：险种名称搜索恒 0 条（缺陷 8）、只读账号工具记账假失败（缺陷 9）、多对象对比路由缺口（缺陷 10）。证据 `reports/eval-live-2026-09-30.json\|md`、`reports/eval-live-gq25-2026-09-30.json`。 |
