# 第四阶段（AI 配置 → Human Confirmation → Audit）独立验证报告

| 项 | 内容 |
|---|---|
| 验证人 | `verifier`（独立验证员，未参与本阶段任何实现） |
| 验证对象 | 路线图第四阶段 T4-00 ~ T4-05（task-5/6/7/8/9/10） |
| 需求真源 | `docs/REQ-第四阶段-AI配置与确认审计.md` §8 测试要求、§9 验收标准（L342–372） |
| 分支 | `feature/ai-roadmap-phase3-5` |
| **我验证的快照** | **HEAD = `878d04a`（2026-09-30 04:07:23，文档 v1.1）**；工作区代码状态 = 第二次 `mvn verify` 编译时点（约 **04:10:50**，即 phase4「门禁移出事务」修复**之前**） |
| 环境 | Windows + pwsh；MySQL 3307（`guarantee_ai_admin`）、Redis 6379 运行中；Java 21；无 `DEEPSEEK_API_KEY` |
| 验证时间 | 2026-09-30 04:05 – 04:16（Asia/Shanghai） |
| 方法 | 亲自复跑（单测 / IT / 真机 HTTP / 真机命令）；独立只读核对真库；从 git 基线逐项比对默认值；只读代码追踪执行路径 |

> **移动靶声明**：phase4 在 **04:11:17 – 04:12:38**（我第二次 `mvn verify` 运行期间/之后）修改了 `PromptVersionService.java`、`PromptVersionServiceTest.java`、`frontend/views/system/AiConfig.vue`、`frontend/api/ai.ts`，并新增 REQ §17；这些改动**未包含**在我的 verify 结果里。第四阶段的最终认证应在该修复提交后重跑一次（Lead 已承诺修复落地后通知）。
>
> 因此：**本报告对 `PromptVersionService.java` / `AiConfig.vue` / `api/ai.ts` 引用的行号对应"我验证的修复前版本"**；这三个文件当前已被修改，行号会整体偏移（例如 `CommandPromptGate` 现在约在 `:389` 起）。其余文件的引用与当前工作区一致。

## 0. 结论口径与免责

**三态定义**：**成立** = AC 的**全部子句**都有可复核证据；**不成立** = 存在与 AC 字面矛盾的证据（含"子句缺失"）；**无法验证** = 证据不足。

**口径（按 Lead 裁决执行）**

- 页面面 API = **HTTP 200 + 业务码**（权限不足 `code:403`、无 data）；**只有 MCP 协议面 `/api/ai/mcp/**` 用真实 HTTP 状态**（401/403/429）。这是**有意不一致**，本报告不据此判失败。
- 已登记偏差按"**是否如实登记**"验，不重新判为缺陷。
- 本机无 `DEEPSEEK_API_KEY` → `--suite=live`（真机 25/33 条）一律 **未跑**，不计入通过。

**结论总览（x/y/z = 成立 / 不成立 / 无法验证）**

> # **成立 10 / 不成立 2 / 无法验证 0**

| 编号 | 三态 | 证据类型 | 一句话结论 |
|---|---|---|---|
| AC-CFG-01 | **成立** | 单测 + 真库 IT + 真机 HTTP | 改 temperature/model/开关后**同一进程下一请求**即变（含真库落库 config_version）；我另以真机 HTTP 独立复核读接口 |
| AC-CFG-02 | **成立** | 真库 IT + 单测 + SQL | 草稿→发布→同一进程读 DB 正文；`updateDraft` 的 SQL 只命中 DRAFT（已发布不可改） |
| AC-CFG-03 | **成立** | 真库 IT + 单测 | 回滚后读回旧正文，且发布/回滚各写一条 `CONFIG_UPDATE` 审计；历史版本内容不被改写 |
| AC-CFG-04 | **成立** | 单测（2×2 矩阵）+ 真库 IT + 静态 | `tools.proposal.enabled=false` 时**连 ADMIN** 也注册不到 `propose*`（开关 ∧ 权限）；权限码/权限矩阵本身未被修改 |
| AC-CFG-05 | **成立** | IT 4/4 + 单测 6 + 真机 HTTP | before→after 正确、source=WEB、target_id=NULL；密钥类只记 `<changed>`（新旧引用名都不落库）；值未变不写审计 |
| AC-CFG-06 | **不成立（按字面）** | 静态 | 页面能改、助手**没有**任何配置能力 → 与 AC"助手也能（经确认卡）"字面矛盾。**属已批准决策**（§0 Q-CFG-06/07 + §2.3-3 红线允许单边）；但 §2.1-InScope#5 仍写"新增 `AiConfigProposalExecutor`"，**文档自相矛盾待修** |
| AC-CFG-07 | **成立** | 单测 + IT + 真机 HTTP | 越界/类型错/未知键均在触库前被拒并给可读错误（我实测 3 例）；库中非法值启动回落默认 + ERROR 告警；DB 不可用不阻断启动 |
| AC-CFG-08 | **成立** | 单测 + 真库 IT + 我独立比对 git 基线 | 20/4/12/60000/16384/10000/0.2/deepseek-chat 与改造前**逐项一致**；未显式配置时不覆盖 starter/yml |
| AC-CFG-09 | **成立** | 单测 + 真库 IT + 真库只读核对 | 每轮 `updateVersionTrace(promptVersion, configVersion)`；真库 `ai_conversation` 两列已建、`config_version` 已落库（无发布版时 `prompt_version=NULL`，如实） |
| AC-CFG-10 | **不成立（按字面）** | 单测 + 真机命令 + 静态 | 子句①（确定性集全绿才发布、发布时**强制重跑**）**成立且实测**（命令 exit 0、12/12）；子句②"**真机集缺失时页面明确标注『未跑』**"**无任何前端实现**（见 §3.10） |
| AC-CFG-11 | **成立** | 单测 5 + 真库 IT 1/1 + 静态 | 指纹在生产者侧写入、`confirm` 抢占后执行前比对，不一致→`INVALIDATED`+审计+可读原因；全仓唯一执行入口就是 `confirm`（无旁路） |
| AC-CFG-12 | **成立** | 脚本 + SOP + **我亲自演练** | `scripts/archive-operation-audit.ps1` 默认 dry-run；我复跑 `-RetentionMonths 6` → exit 0、14 个到期分区、0 行、未执行任何 DROP |

## 1. 我亲自复跑的命令与输出摘要

| # | 命令 | 结果 | 摘要 |
|---|---|---|---|
| 1 | `mvn -B verify`（第一次，04:06–04:07） | **BUILD FAILURE（环境类，非代码）** | `spring-boot:repackage` 失败：`Unable to rename 'guarantee-web\target\guarantee-ai-admin.jar' to '...jar.original'`——8088 上有他人实例（PID 14408，04:06:21 启动）持有 jar。**repackage 在 integration-test 之前，故该次 IT 完全没跑**。已跑到的单测全绿（含 `ProposalFingerprintClosureTest` 5/5、`PromptVersionServiceTest` 17/17、`AiConfigWiringTest` 10/10） |
| 2 | `mvn -B verify`（第二次，04:10–04:12，jar 释放后） | **BUILD SUCCESS** | 全模块 SUCCESS；各模块单测：common 7 / system 120 / auth 28 / order 2 / analysis 6 / **ai 418** / web 单元 11；**guarantee-web IT 113 run / 0 failures**（failsafe 汇总行见下）。日志 `.agent/verify/t4-mvn-verify2.log` |
| 3 | 第四阶段相关 IT（同一次 verify 内） | **全绿** | `AiConfigChangeAuditIT 4/4`、`PromptVersionLifecycleIT 2/2`、`ProposalFingerprintIT 1/1`、`AiConfigWiringIT 3/3`、`EvaluationDeterministicIT 12/12`、`AiToolChainIT 3/3`、`ProposalFlowIT 13/13`、`WebAuditIT 6/6`、`LogicalDeleteWebIT 4/4`、`PermissionDeniedMappingIT 2/2`、`ToolRoundCapFallbackIT 4/4` |
| 4 | `node scripts/ai-golden-questions.mjs --suite=deterministic`（**发布门禁的真实命令**） | **exit 0** | `deterministic: ok — 确定性集 12/12 通过（未跑 0）`；通过率 1、口径正确率 1、引用完整率 1、禁用术语违规 0；`GATE_EXIT=0`。日志 `.agent/verify/t4-gate-deterministic.log` |
| 5 | `npx vue-tsc --noEmit`（frontend，持 frontend.lock） | **exit 0** | 无类型错误 |
| 6 | `npx vite build`（frontend） | **exit 0** | `✓ built in 5.95s`；产物含 `dist/assets/AiConfig-D9XANu3G.js 20.14 kB` |
| 7 | 我自起 8088 临时实例（`java -jar … --server.port=8088`） | 启动成功 | `AI 配置快照已加载：version=207，配置项 20 项，显式值 0 项`；用完已 `Stop-Process` 并复核端口 |
| 8 | 真机 HTTP（我亲自，见 §3.5/§3.7） | 见下 | anonymous 401；admin 读 200/code=0/20 项；analyst 读与写均 **HTTP 200 + code=403**；越界→400 可读；未知键→400 可读；`/prompts/9999`→404 可读 |
| 9 | `pwsh -File scripts/archive-operation-audit.ps1 -RetentionMonths 6`（我亲自演练） | **exit 0** | `DRY-RUN 结束：到期分区 14 个、合计 0 行；未删除任何数据`；导出 `.tsv` 校验通过 |
| 10 | 真库只读核对（mysql 3307） | — | 表结构/索引/角色权限/对话回溯列/配置行（见 §2） |

**第二次 `mvn verify` 的 failsafe 关键汇总（原样）**

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.guarantee.web.ai.config.AiConfigChangeAuditIT
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.guarantee.web.ai.config.PromptVersionLifecycleIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.guarantee.web.ai.ProposalFingerprintIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.guarantee.web.ai.AiConfigWiringIT
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in com.guarantee.web.ai.EvaluationDeterministicIT
[INFO] Tests run: 113, Failures: 0, Errors: 0, Skipped: 0
[INFO] guarantee-web ...................................... SUCCESS [ 58.750 s]
[INFO] BUILD SUCCESS
```

## 2. 事实声明核对（表结构 / 权限码 / 接口路径 / 前端路由）

### 2.1 数据与迁移

| 声明 | 真库 / 工作区事实 | 结论 |
|---|---|---|
| `ai_config_item` 列集（§6.1） | 真库 22 列：config_key/config_value/value_type/default_value/min_value/max_value/enum_options/category/dangerous/description/version/updated_by/updated_at + 逻辑删除三列 | 一致 |
| `ai_prompt_version` 列集（§6.1） | 真库 `version_no/content(longtext)/content_hash/status/note/created_by/created_at/published_by/published_at` + 逻辑删除三列 | 一致 |
| 两张表 `version_no`/`config_key` 唯一 | `uk_ai_prompt_version_no`、`uk_ai_config_item_key`（NON_UNIQUE=0） | 一致 |
| 逻辑删除房规 | 两表均 `is_deleted tinyint NOT NULL DEFAULT 0`、`deleted_at datetime(6) NULL 无默认`、`deleted_by varchar(64) NOT NULL DEFAULT 'DB'`，各带 `idx_*_deleted` | 一致 |
| 回答级回溯加列（§6.4） | `ai_conversation.prompt_version INT NULL` + `config_version BIGINT NULL`（schema.sql:325-326） | 一致 |
| DDL 落在 schema.sql + V8 | `schema.sql:615-656`、`V8__ai_config.sql`（8689 B，幂等） | 一致 |
| 受管表清单 | `LogicalDeleteTables.MANAGED` 含两表；真库带 `is_deleted` 表数 24 = 清单 size（含阶段五表） | 一致 |

### 2.2 权限码 / 危险清单 / 菜单

| 声明 | 事实 | 结论 |
|---|---|---|
| `ai:config:view` / `ai:config:update` | `Permissions.java:45,55`；`PermissionCatalog.java:82-83`（ADMIN 取全量 ⇒ 默认拥有；OPERATOR/ANALYST/VIEWER 列表**均不含**这两个码） | 一致 |
| `ai:config:update` 属危险权限，**两处定义同步** | `PermissionTree.vue:89` 的 `DANGER_REASONS` 与 `docs/REQ-角色管理与权限分配页面.md:334` **逐字一致**（含"含一键关闭写能力、改密钥引用名"） | 一致 |
| 菜单按 `ai:config:view` 过滤 | `router/index.ts:145-148`、`AppLayout.vue:102-107`（`meta.permission='ai:config:view'`） | 一致 |

### 2.3 接口路径（REQ §6.3 vs 实现）

| REQ §6.3 | 实现 | 结论 |
|---|---|---|
| `GET /api/ai/config` | `AiConfigController.java:97-105`，`@PreAuthorize(ai:config:view)` | 一致 |
| `POST /api/ai/config/change` | `:122-156`，`@PreAuthorize(ai:config:update)` + `@Transactional`；**v1.1 更正已落地**：直接落库 + WEB 审计（不建 ConfigProposalExecutor） | 一致（与 v1.1 更正一致） |
| `GET /api/ai/config/prompts` / `{version}` | `:172-188` | 一致 |
| `POST …/prompts/draft|publish|rollback` | `:198-228` | 一致 |
| （v1.1 补）`GET /api/ai/config/prompts/gate` | `:191-195` | 一致 |

### 2.4 "已登记偏差"的**独立核对结果**（Lead 口径第 2 条要求验"是否如实登记"）

| Lead 口径中的偏差 | 我在文档中的核对结果 |
|---|---|
| `model.max-tokens/timeout/max-retries` **未接线**（catalog 仅展示） | ❌ **未找到登记**：`docs/*.md` + `README.md` 全文检索 `未接线` / `max-tokens` / `max-retries` **0 命中**；REQ §15/§16/§17 均未提。代码侧确认只在目录里定义、运行期从未读取（见 §6-D3） |
| MCP 开关 `guarantee.ai.mcp.enabled` 是 yml 级、需重启 | 属第五阶段文档，task-17 范围（本报告不判） |
| `ai:mcp:read` 在 MCP 入口是硬门禁 | 同上（`ca8d202` 已更正 MCP 接入文档口径） |
| `ai_operation_audit` 分区名与真实边界差一年 | ✅ **已登记**：§16 `RK-CFG-14`，含证据与影响；归档脚本按真实上界判定（§15.2） |
| `ai_config_item` 有 2 行历史 NULL（无行为影响） | ⚠️ **未在文档登记**；我实测确认**确无行为影响**（见 §3.8 末） |
| 门禁同步执行于 HTTP+事务（真机 e2e 发现） | ✅ **已登记**：§17（**未提交**，工作区 04:12:38 写入） |

## 3. 逐条 AC 详证

### 3.1 AC-CFG-01（改 temperature 不重启、下一请求生效）—— **成立**

- 单测（不重启、同一 `AiChatService` 实例）：`AiConfigWiringTest.temperatureTakesEffectOnNextRequest`（`AiConfigWiringTest.java:207-218`，0.2→0.9）；模型名同样（`:220-236`）；历史条数（`:195-205`）。
- **真库 IT**：`AiConfigWiringIT.changesTakeEffectOnNextRequestWithoutRestart`（`AiConfigWiringIT.java:134-162`）：改 temperature/model/工具开关后，下一请求的 `ToolCallingChatOptions` 已是 0.7 / deepseek-reasoner，且关掉的工具组不再注册。
- **真机 HTTP**：`AiConfigChangeAuditIT`（`:92-132`）经真实 HTTP 把 0.2→0.55，**同一进程**下一次 `GET /api/ai/config` 读到 0.55，并断言版本号推进。
- 关键实现：请求开始处 `AiChatService.java:354` 调 `refreshIfStale()`；仅当 `isOverridden` 时才覆盖 starter 参数（`:841-848`）。
- 我另以真机 HTTP 独立复核：`GET /api/ai/config` → HTTP 200 / code 0 / version=207 / 20 项。

### 3.2 AC-CFG-02（草稿 → diff → 发布 → 生效；已发布不可改）—— **成立**

- 真库 IT：`PromptVersionLifecycleIT`（`:83-141`）——保存草稿（DRAFT）→ 发布（PUBLISHED）→ `BusinessAssistantPrompt.loadTemplate()` 读到 DB 正文 → 再造一版 → 同一时刻 `publishedCount()==1`。
- 单测：`PromptVersionServiceTest` 17 用例（`:120-141` 发布归档旧版+投影+审计；`:142-154` 已发布不可再发布；`:176-191` 缺保护标记拒绝发布；`:281-290` 门禁结果如实透传）。
- **"已发布版本不可修改"由 SQL 保证**：`AiPromptVersionMapper.xml:54-59` 的 `updateDraft` 带 `AND status='DRAFT'`；`:68-75` 的 `publish` 限定 `status IN ('DRAFT','ARCHIVED')`。
- 运行期读取优先级：`BusinessAssistantPrompt.java:52-59`（DB 发布版 → 否则 classpath 兜底；冷启动可用）。
- 真机 HTTP：`/api/ai/config/prompts/{version}` 路径与权限已验证（不存在→code 404 可读；analyst→code 403）。

### 3.3 AC-CFG-03（回滚 → 用旧版内容 + 一条审计）—— **成立**

- 真库 IT：同 `PromptVersionLifecycleIT:83-141`——回滚后 `businessAssistantPrompt` 读回**旧正文**，且审计按版本号可追溯。
- 审计：`PromptVersionService.switchPublished`（`:287-318`）发布与回滚**共用**同一审计写入（`action=CONFIG_UPDATE / target_type=AI_CONFIG / before{status,contentHash} → after{status,contentHash}`），**不改历史版本内容**。
- 单测：`:237-258`（回滚置 PUBLISHED + 写审计；不重跑门禁——应急路径）；`:259-272`（不能回滚到草稿）。
- 回滚不过门禁是**有意的应急语义**，其安全性依赖不变量"只有曾 PUBLISHED 的版本才会 ARCHIVED"（`archivePublishedExcept` 只归档 PUBLISHED），见 §3.10 边界 ②。

### 3.4 AC-CFG-04（关写能力总开关 → 连 ADMIN 也没有 propose*；重开恢复）—— **成立**

- 开关 ∧ 权限的**与**关系在注册期裁剪：`AiToolRegistry.select`（`:291-304`）先 `enabled(group, config)` 再 `grants(permissions, …)`；`enabled`（`:312-325`）对 `PROPOSAL` 读 `tools.proposal.enabled`。
- 单测：`AiToolRegistryTest` 15 用例，其中 `:269-283`「写能力总开关：`tools.proposal.enabled=false` 时连 ADMIN 也注册不到 `propose*`」；`:293-309` 两个方向的"与"关系；`:234-268` 单组关闭只摘该组。
- 真库 IT：`AiConfigWiringIT:144-156` 关 `tools.order.enabled` 后下一请求工具集不再含 `queryOrderSummary`，其余组不受影响（同一裁剪路径）。
- "权限码本身未被修改"：开关只影响注册集，`Permissions` / `PermissionCatalog` / 角色权限矩阵无写入路径（静态）；`AiToolRegistryTest:345` 另有权限矩阵自检。

### 3.5 AC-CFG-05（每次配置变更可查：谁/何时/哪项/before→after；密钥只记"是否变化"）—— **成立**

- IT（真实 HTTP + 真实库）：`AiConfigChangeAuditIT` 4/4——
  - `:92-132` 断言 `source=WEB`、`action=CONFIG_UPDATE`、`target_type=AI_CONFIG`、`target_id IS NULL`、`target_name=配置键`、`operator_username=admin`、`before` 含 0.2 / `after` 含 0.55、`changed_fields` 含键名；
  - `:134-163` 密钥类只记 `<changed>`，且**新旧引用名都不落库**；
  - `:165-184` 值未变化 → 未写入且不多一条审计；
  - `:190-212` 未登录 HTTP 401；ANALYST 读/写 = **code 403**（服务端兜底）。
- 单测：`AiConfigChangeAuditTest` 6 用例（含 `<changed>`、恢复默认、非法/未知键不写审计）。
- 落地：`AiConfigController.java:122-156`（`@Transactional` 保证"配置 + 审计"同事务；值未变直接返回 `changed=false`）；密钥类判定 `:60-67`，脱敏 `:403-408`（`SensitiveFieldMasker.CHANGED_PLACEHOLDER`）；`OperationAuditService.java:60-65` 把 `AI_CONFIG` 归入 ADMIN-only 目标类型。
- **我亲自真机 HTTP**：admin 读配置 200/code 0，`model.api-key-ref` → `value=DEEPSEEK_API_KEY`（**引用名**）、`secretClass=true`、`configured=false`；三次被拒的写（analyst 403、越界 99、未知键）后 `model.temperature` 仍为 0.2，且未产生新的 AI_CONFIG 审计（10 分钟内 14 条审计均来自 verify 的两个 IT）。
- 密钥不泄漏核对：全仓 grep `log.*(configValue|rawValue|api-key|apiKey|password|secretValue)` **0 命中**；`AiConfigService.warnOnInvalid`（`:174-182`）只打键名。

### 3.6 AC-CFG-06（页面与助手配置能力对齐、无单边能力）—— **不成立（按字面）；已批准决策**

- 事实：**助手侧不存在任何写配置的工具/执行器**（`AiConfigController.java:40-44` 明确登记"第一版不给助手写配置的能力，不实现 `ConfigProposalExecutor`"；全仓 grep 无 `ConfigProposalExecutor`）。页面能改、助手不能改。
- 与 AC 字面的矛盾：AC 要求"**页面上能改的配置项，助手也能（经确认卡）**"→ 不成立。
- **但这是已批准决策**：REQ §0 L12-15「第一版不给助手写配置的能力（Q-CFG-07）…`ConfigProposalExecutor` 不在本期实现，作为后续扩展点登记」；§2.3-3 能力对齐红线明确「**页面上有而 AI 没有的配置项不违规**，但不得让 AI 拥有页面没有的配置能力」→ 当前状态**未违反红线**（AI ⊆ 页面）。
- **真实缺陷是文档自相矛盾**：§2.1 In-Scope 第 5 条仍写"新增 `AiConfigProposalExecutor` + …"（`REQ-第四阶段…md:85`），与 §0/§6.3 v1.1 冲突。
- 处置建议（Lead 裁决）：① 把 AC-CFG-06 按 Q-CFG-07 收窄重写为"**不得存在助手有而页面没有的配置能力**"；② 修订 §2.1#5 的 In-Scope 表述；③ 在 §15 补一条收窄说明。

### 3.7 AC-CFG-07（非法配置被拒 + 启动回落默认 + 助手仍可用）—— **成立**

- 校验：`AiConfigCatalog.validate`（`:199-257`）覆盖 STRING 长度 / INT 解析与范围 / DECIMAL 范围 / BOOLEAN / ENUM，消息带键名与原因；未知键 `require` 抛 `未知的配置项：X`（`:166-173`）。
- 单测：`AiConfigServiceTest` 15 用例（`:126-142` 库中非法值回落默认 + `invalidKeys`；`:143-160` 未知键被忽略不炸快照；`:193-204` 首次加载 DB 不可用 → 默认值；`:205-220` 重载失败保留上一份；`:221-240` 非法值触库前拒绝；`:328+` 启动预热失败不阻断）；`AiConfigWiringTest:252-266`。
- 实现：`AiConfigService.loadAndSwap`（`:72-90`）失败降级 + `warnOnInvalid`（`:174-182`）ERROR 告警；`AiConfigWarmUp` 启动预热。
- **我亲自真机 HTTP**：越界 `temperature=99` → HTTP 200 / **code 400** / `配置项 model.temperature 的值「99」超出允许范围 [0, 2]`；未知键 → code 400 `未知的配置项：no.such.key`；两者均未改变生效值、未写审计。

### 3.8 AC-CFG-08（配置缺省时行为与改造前逐项一致）—— **成立**

- 我从 **git 基线**（phase 4 之前，`d419b8e`）独立取出改造前的常量，与 catalog 默认值逐项比对：

  | 项 | 改造前（git `d419b8e`） | catalog 默认 | 一致 |
  |---|---|---|---|
  | 历史条数 | `AiChatService.HISTORY_LIMIT = 20` | `budget.history-limit=20` | ✅ |
  | 轮次上限 | `MAX_TOOL_ROUNDS = 4` | `budget.max-rounds=4` | ✅ |
  | 单轮调用 | `MAX_TOOL_CALLS_PER_ROUND = 12` | `budget.max-calls-per-round=12` | ✅ |
  | 软超时 | `SOFT_TIMEOUT_MS = 60_000L` | `budget.soft-timeout-ms=60000` | ✅ |
  | 工具结果上限 | `BoundedToolCallback.MAX_RESULT_BYTES = 16*1024` | `budget.tool-result-bytes=16384` | ✅ |
  | 工具超时 | `TOOL_TIMEOUT_MS = 10_000L` | `budget.tool-timeout-ms=10000` | ✅ |
  | temperature | `application.yml chat.temperature: 0.2` | `model.temperature=0.2` | ✅ |
  | 模型名 | `${DEEPSEEK_MODEL:deepseek-chat}` | `model.name=deepseek-chat` | ✅ |
  | base-url | `${DEEPSEEK_BASE_URL:https://api.deepseek.com}` | `model.base-url` 同值 | ✅ |
  | Key 引用名 | `${DEEPSEEK_API_KEY:not-configured}` | `model.api-key-ref=DEEPSEEK_API_KEY` | ✅ |
  | max-tokens / timeout / retries | **改造前未配置** | catalog 有值但运行期**不读取**（`isOverridden` 才覆盖） | ✅（行为一致，但见 D3） |

- 单测把"常量 = 配置默认值"用断言钉死：`AiConfigWiringTest.java:158-162`；缺省行为 `:137-189`；真库缺省 `AiConfigWiringIT.java:113-132`（未显式配置时**不覆盖** starter 的 0.2）。
- 真库现状的 2 行历史 NULL：`ai_config_item` 存在 `model.name`(v183) / `tools.order.enabled`(v184) 两行 `config_value IS NULL`；启动日志显示 `20 项 / 显式值 0 项`，HTTP 读回 `overridden=false`、`value=默认值` → **确无行为影响**（与 Lead 口径一致），但未登记（见 D7）。

### 3.9 AC-CFG-09（每轮可回溯 promptVersion 与配置版本）—— **成立**

- 实现：`AiChatService.java:724` 收尾调 `recordConfigVersion(conversationId, config.version(), cost.promptVersion())`；`:748-756` 经 `conversationMapper.updateVersionTrace` 写库，失败只告警不影响回答。
- 单测：`AiConfigWiringTest.recordsConfigVersionAtTurnEnd`（`:282-294`）断言 `updateVersionTrace(CONVERSATION_ID, 7, 0L)`，改配置后 config_version 随之变大。
- 真库 IT：`AiConfigWiringIT.java:129-131,158-161` 断言 `ai_conversation.config_version` = 本轮快照版本。
- 真库只读核对（我亲自）：`ai_conversation` 有 `prompt_version INT NULL` + `config_version BIGINT NULL`；最近 5 行 `config_version=184`、`prompt_version=NULL`——因 `ai_prompt_version` 当前 **0 行**（无发布版），promptVersion 如实为 NULL（classpath 兜底），不是缺陷。

### 3.10 AC-CFG-10（发布门禁：确定性集全绿才允许发布；真机集缺失时页面标注"未跑"）—— **不成立（按字面）**

**子句① 确定性集全绿才允许发布 —— 成立且已实测**

- 语义（单测）：`PromptVersionService.publish`（`:236-252`）顺序固定"保护标记 → **门禁** → 归档 → 发布 → 审计"；`!result.passed()` 一律拒绝，且 `ran=false` 单独给出"门禁未跑…不允许在门禁未跑时发布"（`:245-250`）。`PromptVersionServiceTest:204-236` 覆盖 NOT_RUN / FAILED 两种拒绝；`:281-290` 断言"未跑/未通过不会被包装成通过"。
- **发布是权威判定点**（Lead 指定对抗点）：`publish` **每次都调 `evaluateGate()`**（`:244`），不读 `lastGateResult`（`:184-186` 只给列表接口用）；`GET /prompts` 只回"最近结果/尚未检查"（`AiConfigController.java:172-181`）。→ **不存在"不跑门禁也能发布"的路径**（`publish` 唯一入口；`rollback` 见边界②）。
- **真机命令实测**：`node scripts/ai-golden-questions.mjs --suite=deterministic` → **exit 0**，`确定性集 12/12 通过（未跑 0）`，与 `CommandPromptGate`（`:369-420`）"只有 exit 0 才算 PASSED"一致。
- 保护标记：`PROTECTED_MARKERS`（`:66-71`）5 条红线小标题服务端逐条校验；`PromptVersionLifecycleIT:142-155` 断言"缺标记即使门禁通过也拒绝发布"。

**子句② "真机集缺失时页面明确标注『未跑』" —— 未落实**

- `frontend/src` 全仓检索 `真机`/`live`/`未跑` 相关 UI：**只有** `AiConfig.vue` 关于**确定性门禁**的四态（未检查/未跑/通过/未通过，`:197-234`、`:532-545`）。**没有任何页面显示真机集（`--suite=live`）的状态**；`GateView(ran,passed,summary)`（`AiConfigController.java:311-313`）也只有确定性集一个维度。
- 真机集"未跑（缺 Key）"只出现在 `docs/TEST-助手黄金问题集.md:216-243`（§4.1）与评测报告里，不在页面上。
- 因此按 AC 字面判 **不成立**；**若 Lead 把"页面"从宽解释为"交付文档/评测报告"，则该子句由 §4.1 的"未跑"记录满足**——请裁决。处置建议：在 AI 配置页门禁区域补一行"真机集：未跑（缺 `DEEPSEEK_API_KEY`）"（数据可来自 `/prompts/gate` 增加 live 字段，或读 `reports/eval-*-live-*.json` 的 `status`）。

**两个必须写明的边界**

1. **确定性集不校验被发布的提示词正文**：`EvaluationDeterministicIT` 由 Stub 模型按题号脚本化工具调用（`:287-370` 各 `@Test`），不加载草稿内容 → 门禁证明的是"应用行为回归全绿"，不是"新提示词文本达标"。这是 REQ 自身的设计（`--suite=deterministic` 用 Stub），但"发布内容是否达标"实际无人验证（真机集未跑）。
2. **`rollback` 不过门禁**（`:259-270`），它也能把版本置为 PUBLISHED。其安全性依赖不变量"非 DRAFT 版本必曾经过发布 → 曾过门禁"（`archivePublishedExcept` 只归档 `PUBLISHED`）。**旁路结论：应用内不存在绕过门禁的"发布"，但"回滚"是有意的免门禁切版本路径，且无审计上的"曾通过门禁"溯源字段**——作为候选改进登记。
3. **已知在修缺陷（§17）**：门禁命令在 `publish` 内**同步**执行且当时在 `@Transactional` 内 → 分钟级请求 + 长事务；前端 axios 全局 30s 超时 → 页面误报失败。REQ §17（**未提交**）记录：同命令独立执行 13.9s / 12-12 通过、经发布接口 >180s 未返回；修复=门禁移出事务（`TransactionTemplate` 只包短事务）+ 列表接口不再触发门禁 + 前端单独 180s 超时。**该修复未包含在我的 verify 结果内**，故"真机发布的正反两例"我**未复跑**。

### 3.11 AC-CFG-11（确认执行前指纹比对；不一致 → 拒绝 + INVALIDATED + 可读原因）—— **成立**

- 生产者闭环：`ProposalService.create`（`:172-178`）显式传入优先，否则按执行器计算并落库；`CREATE` 类为 `null`（无"确认前被改动"可言）。
- 消费侧：`ProposalService.confirm`（`:278-377`）在 **抢占 `PENDING→EXECUTING` 之后、`executor.execute` 之前**调 `verifyTargetFingerprint`（`:303-314`）；不一致 → 写审计（`result=REJECTED` + `FINGERPRINT_MISMATCH_REASON`）+ 置 `INVALIDATED` + 抛可读原因（`:584-615`）。
- 指纹 = 业务字段 + `updated_at` + `isDeleted`（`OrgProposalExecutor.java:43-55` 等 5 个执行器；`ProposalExecutor.fingerprintHash:74-85` 用不可打印分隔符 + `null` 独立标记；`MISSING_FINGERPRINT` 哨兵表达"目标已被删除"）。
- 单测：`ProposalFingerprintClosureTest` 5/5（稳定性 / 业务字段与仅 updated_at / CREATE=null 与哨兵 / 不一致拒绝且不执行 / 一致放行）。
- **真库 IT**：`ProposalFingerprintIT` 1/1——create 写入 64 位十六进制指纹 → 把记录改旧 → confirm 抛错、状态 `INVALIDATED`、`audit_id` 非空且审计 `result=REJECTED`、**业务行未被改动**（`:106-142`）。
- **对抗：直接调用 confirm 的旁路**——全仓 `executor.execute(` 仅 1 处调用点（`ProposalService.java:326`），`confirm` 仅 1 个 HTTP 入口（`AiController.java:142`）；**不存在绕过 confirm 的执行路径**。残余：`recorded == null` 时跳过比对（设计如此，仅 CREATE / 无目标类型；不影响其它校验）。秒级 `updated_at` 的残余风险：同秒内"改了又改回原值且业务字段全同"理论上指纹不变——但那等价于没有可观测变更，且业务字段本身也参与哈希。

### 3.12 AC-CFG-12（审计归档有可执行体 + SOP + 演练）—— **成立**

- 执行体：`scripts/archive-operation-audit.ps1`（7397 B）。默认 **DRY-RUN**，`-Execute` 才 DROP（`:36-47`）；顺序固定"导出 → 校验行数 → DROP"，任一失败 exit 1 且不删（`:10-15`）；口令走 `MYSQL_PWD` 不落命令行与日志（`:52-65`）；**永不触碰 pmax/MAXVALUE**；日志追加 `.agent/archive/operation-audit/archive-operation-audit.log`。
- **关键设计**：按 `FROM_DAYS(PARTITION_DESCRIPTION)` 的**真实上界**判定，不按分区名——规避 §16 `RK-CFG-14` 的一年偏差。
- SOP：REQ §15.2（`:474-492`）含 dry-run → 核对 → `-Execute` 三步与失败语义。
- **我亲自演练**（04:13，`-RetentionMonths 6`）：exit **0**、到期分区 **14 个**（`p202601`…`p202702`，真实上界 2025-02-01 … 2026-03-01）、合计 **0 行**、每个分区导出 `.tsv` 且行数校验通过、**未执行任何 DROP**；与 phase4 的 03:17 演练记录一致。
- 缺口（登记项）：本阶段只有"手工脚本 + SOP"，**无定时任务**——REQ 允许二选一（方案 A），已在 §15.2 显式登记为运维手工项 ✓。

## 4. TEST-CFG-01~08 状态

| 编号 | 类型 | 状态 | 证据 |
|---|---|---|---|
| TEST-CFG-01 | 单测（校验/默认值） | 就绪·通过 | `AiConfigCatalogTest` 12、`AiConfigServiceTest` 15（含非法值/未知键/降级）、`AiConfigWiringTest` 10 |
| TEST-CFG-02 | 单测（快照/刷新/并发） | 就绪·通过 | `AiConfigServiceTest:88-103`（读配置不查库）、`:161-192`（版本变化才重载）、`:308+`（并发一致） |
| TEST-CFG-03 | 单测（开关 × 权限 2×2） | 就绪·通过 | `AiToolRegistryTest` 15（`:234-309` 单组/总开关/两向"与"；`:269-283` ADMIN 也摘 `propose*`） |
| TEST-CFG-04 | 单测（提示词版本机） | 就绪·通过 | `PromptVersionServiceTest` 17（草稿/发布/已发布不可改/回滚+审计/保护标记/门禁三态） |
| TEST-CFG-05 | IT（配置变更全链路） | **就绪·通过（我复跑 4/4）** | `AiConfigChangeAuditIT`：before/after、密钥 `<changed>`、值未变不写审计、401/403 |
| TEST-CFG-06 | IT（改配置不重启生效） | **就绪·通过（我复跑 3/3）** | `AiConfigWiringIT`（真实库 + 随机端口的真实链路） |
| TEST-CFG-07 | 单测 + IT（指纹闭环） | **就绪·通过（5/5 + 1/1）** | `ProposalFingerprintClosureTest`、`ProposalFingerprintIT` |
| TEST-CFG-08 | 回归 | **就绪·阶段四通过** | `mvn -B verify` BUILD SUCCESS（113 web IT / 0 fail）；阶段二 GQ-01~15 断言未改（阶段三报告已证）；AC-CFG-08 已另外逐项比对 |

## 5. 对抗式检查逐项结论

| 对抗点 | 结论 | 证据 |
|---|---|---|
| 配置缺省时行为与改造前**逐项一致** | 成立 | 我从 git 基线独立比对 11 项（§3.8）+ 单测把常量与默认值钉死（`AiConfigWiringTest:158-162`） |
| 改配置是否真的**不重启**生效 | 成立 | 单测（同一实例下一请求）+ 真库 IT + 真机 HTTP IT（版本号推进） |
| 开关与权限是否真是「与」关系；关掉写能力后 ADMIN 也注册不到 `propose*` | 成立 | `AiToolRegistry:291-325` + `AiToolRegistryTest:269-283` |
| 密钥是否可能被回显 / 入库 / 进日志 | 未发现泄漏 | 读接口只回引用名 + `configured`；审计 `<changed>`；grep 日志 0 命中；IT 断言新旧引用名都不落库 |
| 审计 before/after 是否正确、密钥只记"是否变化" | 成立 | `AiConfigChangeAuditIT:92-184`（我复跑 4/4） |
| 提示词回滚是否产生审计且不改历史版本 | 成立 | `PromptVersionLifecycleIT:83-141` + `switchPublished:287-318` + SQL 只改状态不改内容 |
| 指纹闭环能否被绕过（直接 confirm 路径） | 不能（应用内仅一个执行入口） | grep：`executor.execute(` 1 处、`confirm(` 1 处 HTTP 入口；§3.11 |
| 是否存在**不跑门禁也能发布**的路径 | `publish` 无（发布强制重跑）；`rollback` 是**有意**免门禁路径（受不变量保护） | `PromptVersionService:244,259-270`；§3.10 边界② |
| "真机集缺失时页面标注未跑" | **未落实**（无任何前端实现） | `frontend/src` 全仓无 live/真机 UI；§3.10 子句② |
| 页面上可编辑但**运行期不生效**的配置项 | 存在 3 个且未登记/未标注 | `model.max-tokens` / `model.timeout` / `model.max-retries` 只被 catalog 定义，主代码从不读取（D3） |
| 已登记偏差是否**如实登记** | 部分：分区偏差 ✅、门禁缺陷 ✅（未提交）；"未接线" ❌、NULL 行 ❌ | §2.4 |
| 前端构建 | 成立 | `vue-tsc` exit 0；`vite build` exit 0（`AiConfig` chunk 生成） |

## 6. 缺陷与处置汇总

| # | 缺陷 | 严重度 | 状态 | 处置建议 |
|---|---|---|---|---|
| D1 | 发布门禁**同步**执行于 HTTP 请求 + DB 事务内（分钟级）→ 页面 axios 30s 超时误报"发布失败"；且发布请求持分钟级连接与行锁 | 中 | **已登记（§17，未提交）+ 修复在工作区未提交** | 我的 verify 是**修复前**版本；修复提交后需复跑 `mvn verify` + 真机发布正反两例（含"超时后无半成品：草稿仍 DRAFT、无 PUBLISHED、无投影行"） |
| D2 | **AC-CFG-10 子句②未落实**：真机集（`--suite=live`）的"未跑（缺 Key）"在任何页面都没有显示 | 中 | 新发现 | 配置页门禁区补一行 live 状态；或把 AC 收窄为"确定性门禁 + 文档如实标注"（Lead 裁决） |
| D3 | `model.max-tokens` / `model.timeout` / `model.max-retries` **页面可编辑但运行期完全不生效**；且 Lead 口径称"已登记"，实际三份 REQ + README **检索不到任何登记**，页面也未标注"未接线/仅展示" | 中 | 新发现（登记缺失） | 三选一：① 接线（temperature 已有先例，`ChatOptions` 支持 maxTokens/timeout/retries）；② 页面置灰 + 标注"仅展示，本期未接线"；③ 至少在 §16 显式登记 |
| D4 | 提示词发布/回滚审计写入 `target_id=版本号`，与 REQ §5.1.5 **v1.1 更正**"不使用 target_id"直接矛盾；`PromptVersionLifecycleIT:138` 还把该行为固化为断言 | 低 | 新发现（口径未落实） | 对齐其一：改代码 `target_id=null`（并同步 IT），或修订 §5.1.5 更正（说明版本号是数值型、允许用 target_id） |
| D5 | 提示词审计 `target_name=prompt.vN`，而 §5.1.5 v1.1 说"提示词版本类用 `target_name=版本号`" | 低 | 新发现 | 同上，统一口径或修订文档 |
| D6 | REQ §2.1 In-Scope 第 5 条仍写"新增 `AiConfigProposalExecutor`"，与 §0 决策、§6.3 v1.1 更正矛盾 | 低 | 新发现（文档自相矛盾） | 修订 §2.1#5，把该条移到"后续扩展点" |
| D7 | `ai_config_item` 2 行历史 NULL（`model.name` v183 / `tools.order.enabled` v184）未在文档登记 | 低 | 新发现（无行为影响） | 我实测确认按"未显式配置"处理（日志"显式值 0 项"、HTTP `overridden=false`）；建议在 §16 一句话登记或清理该两行 |
| D8 | `ai_operation_audit` 分区名与真实 `TO_DAYS` 边界差一年 | 中 | **已登记**（§16 `RK-CFG-14`） | 归档脚本已按真实上界规避；根治需重建分区表（T4-01 范围，未修） |
| D9 | **环境类**：8088 上他人临时实例（PID 14408，04:06:21 启动）持有 `guarantee-web/target/guarantee-ai-admin.jar` → 我的第一次 `mvn verify` 在 `spring-boot:repackage` 失败，**IT 完全没跑**（非代码缺陷） | 中（工程） | 已规避并记录 | 建议把"临时实例"纳入锁协议（例如 `instance-8088.lock`）；我已于 jar 释放后重跑成功（§1#2） |
| D10 | **副作用（需 Lead 处置）**：我为验证门禁执行了真实命令 `node scripts/ai-golden-questions.mjs --suite=deterministic`，它**重新生成了被 git 跟踪的** `reports/eval-deterministic-2026-09-30.{md,json}`（diff 为生成时间与逐题耗时，约 256 行） | 低（工程） | 待处置 | 我按验证纪律**未执行 `git checkout`**；请 Lead 决定是否还原这两个文件 |

**"不成立"两项的处置建议汇总**

- **AC-CFG-06**：AC 字面与 §2.3-3 红线冲突（红线允许页面 ⊃ 助手），且 §0 已拍板。建议**按 Q-CFG-07 收窄 AC 措辞**并修 §2.1#5；不需要补代码。
- **AC-CFG-10**：建议**补前端 live 状态标注**（真正落实字面），或在 AC 中把"页面标注未跑"改为"交付文档/评测报告如实标注未跑"（当前文档已如实）。二者取其一；D1 修复后仍需补一次真机发布的正反证据。

## 7. 未跑项与前置条件

1. **`--suite=live` 真机黄金问题集**：**未跑（缺 `DEEPSEEK_API_KEY`）**；门禁命令只跑确定性集，故"新提示词正文是否达标"实际未被验证。
2. **真机发布的正反两例**：**未由我复跑**——依赖 D1 修复落地（门禁移出事务 + 前端长超时）；且 publish 会写共享库的 `ai_prompt_version`/`prompt.active-version` 并影响 8081 用户实例的下一次问答，需 Lead 授权窗口。
3. **`GET /api/ai/config/prompts`（admin）与 `/prompts/gate` 的真机 HTTP**：我**故意未调用**——我的 jar 是修复前版本，这两个接口会**同步触发分钟级 maven 门禁**（§17），在共享环境里代价与干扰都不可接受。
4. **`ai:mcp:*` / MCP 协议面**：属第五阶段，task-17 范围。
5. **D3 的"接线后行为"**：未接线，故无运行期证据可验。

## 8. 证据文件索引（可复核）

| 文件 | 内容 |
|---|---|
| `.agent/verify/t4-mvn-verify2.log` | 第二次 `mvn -B verify` 全量输出（**BUILD SUCCESS**，113 IT） |
| `.agent/verify/t4-mvn-verify.log` | 第一次 verify（repackage 被 jar 锁失败的原始报错） |
| `.agent/verify/t4-gate-deterministic.log` | 真实门禁命令输出（deterministic ok / 12-12 / exit 0） |
| `.agent/verify/t4-vue-tsc.log` / `t4-vite-build.log` | 前端 `vue-tsc`（exit 0）与 `vite build`（exit 0） |
| `.agent/verify/t4-app-8088.log` | 我自起 8088 实例启动日志（快照 version=207、20 项、显式值 0） |
| `.agent/verify/archive-dryrun/` | 我亲自归档演练的导出文件 + `archive-operation-audit.log` |
| `docs/REQ-第四阶段-AI配置与确认审计.md` §15/§16/§17 | 已登记偏差与在修缺陷（§17 未提交） |

## 9. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-30 | v1.0 | 首版：AC-CFG-01~12 逐条三态（**成立 10 / 不成立 2 / 无法验证 0**）、TEST-CFG-01~08 状态、对抗式检查、事实声明与"已登记偏差"核对、10 项缺陷/副作用、未跑项清单。验证快照 = HEAD `878d04a` + 工作区 04:10:50 状态（**不含** phase4 04:11–04:12 的门禁修复）；真机证据取自 8088 临时实例（我自起自停）与 MySQL 3307 只读查询；`--suite=live` 未跑（缺 `DEEPSEEK_API_KEY`）。 |

---

# 复验记录（v1.1，2026-09-30 04:34–04:47）—— D1 / D2 / D3 修复复核

> 本节**追加**在原报告之后，**不修改**原报告任何内容。原报告 §0 声明的快照是「HEAD `878d04a` + 工作区 04:10:50」；本节针对 phase4 的修复提交 **`817d74c`**（04:34:18，phase4 已声明代码冻结）复验。

| 项 | 内容 |
|---|---|
| 复验快照 | **HEAD `817d74c`**（工作区无其它在途改动） |
| 复跑 | `mvn -B verify` → **BUILD SUCCESS**；`guarantee-web` IT **113 / 0 failures**；`PromptVersionServiceTest` **19/19**（原 17，新增 D1/D2 用例）、`AiConfigChangeAuditIT` **4/4**、`PromptVersionLifecycleIT` **2/2**、`EvaluationDeterministicIT` **12/12**、`AiConfigWiringIT` 3/3 |
| 前端 | `npx vue-tsc --noEmit` **exit 0**；`npx vite build` **exit 0**（`✓ built in 5.83s`） |
| 日志 | `.agent/verify/t5-mvn-verify.log`、`.agent/verify/t5-vue-tsc.log`、`.agent/verify/t5-vite-build.log` |

### D1（门禁同步执行于 HTTP + 事务内）—— **已修复，复核成立**

- **事务边界**：`publish`（`PromptVersionService.java:372-388`）与 `rollback`（`:396-406`）**均不再标 `@Transactional`**；门禁在 `evaluateGate()`（`:380`）先评估，**只有 PASSED 才**进入 `transactionTemplate.execute(...)`（`:387`/`:405`）；短事务只包"归档旧版 → 发布 → 投影 → 审计"（`:423+`；设计说明见 `:55-57` 与 `:125-132`）。NOT_RUN/FAILED 在切版本**之前**抛错（`:381-386`），因此"门禁未跑/未过就不可能有任何状态变更"。
- **前端超时**：`frontend/src/api/ai.ts` 给 `publishPrompt` 与 `getPromptGate` **各自单独 180s**，并有运行态提示（`AiConfig.vue:293`）。
- **测试**：`PromptVersionServiceTest` 17 → **19**（新增事务外门禁/短事务用例），我复跑 19/19 绿。
- 残留（与 §17 一致，非缺陷）：真机发布仍是**同步**分钟级请求；"按 `versionNo+contentHash+TTL` 复用 PASSED"已登记为后续候选（`:59-61`）。

### D2（真机集"未跑"标注）—— **已修复，复核成立**

- **只读报告、绝不伪造**：`PromptVersionService.liveGate()`（`:250-286`）只读 `reports/eval-live-*.json`（`latestLiveReport()` `:297-318`，取最新一份）；报告不存在/解析失败/缺 Key 一律 `NOT_RUN`（`:254-257/273-277/280-285`），只有 `failed=0 且 notRun=0 且 total>0` 才 `PASSED`（`:278-279`）。本方法**没有任何执行评测的代码路径**。
- **接口**：`GateView` 增加 `live`（`AiConfigController` 的 `GateView/LiveGateView` + `toLiveView`），`/prompts/gate` 与 `/prompts` 都回填。
- **页面**：配置页新增"真机集（--suite=live）"一行 + 原因 tooltip（`AiConfig.vue:103-118`、`:591-599`），四态标签。
- **不硬阻断发布**：`canPublish` 只要求 `gate.value?.passed === true`（确定性维度，`AiConfig.vue:218-220`）→ 与收窄后的 AC-CFG-10 一致。
- **真机数据核对**：现存 `reports/eval-live-2026-09-30.json` 的 `totals{total=1,notRun=1,failed=0}` → `liveGate()` 必回 `NOT_RUN`（"未跑：1 条未跑（其中环境类 1）"+ 未配置 Key 提示），**不会**因"报告存在"而冒充通过。

### D3（三个未接线项页面标注 + 不可编辑）—— **页面层已修复，复核成立；附一条残留**

- 前端 `NOT_WIRED_KEYS = {model.max-tokens, model.timeout, model.max-retries}`（`AiConfig.vue:95-101`）；模型页顶部警告条列出这些键（`:491-499`），每行打"本期未接线"标签（`:504-510`），操作列**不渲染"修改/恢复默认"**、只显示"未接线"说明（`:533-546`）→ 页面上不可编辑。
- **残留（低，新发现）**：判定只在前端；服务端 `AiConfigCatalog` 无 `wired` 元数据，`AiConfigService.update` 仍接受这三个键 → 直接 `POST /api/ai/config/change` 依然能写入（写了也不生效）。建议在目录加 `wired=false` 并在服务端拒绝，或至少登记为"UI-only 防护"。本轮**不**据此判 AC-CFG-01/06 不成立（页面口径已满足 Lead 要求）。

### 复验新增发现（跨阶段，详见 `docs/TEST-第五阶段与全量回归-验证报告.md`）

- **D11（中）**：`AI_TURN_COST` 结构化日志**字段错位**——实测 `capped=none capReason=CHAT source=ERROR outcome={}`：`capped/capReason/source/outcome` 四个字段被参数错位，且最后一个占位符**没有实参**（模板 13 个占位符、只传 12 个实参，`AiChatService.java:1608-1612`）。metric 行本身正确；`AiObservabilityIT` 只断言 metric 字段与 traceId，不断言日志文本 → 未被发现。
- **D12（低）**：AC-CFG-09 的"每轮"在**失败轮**不成立——`recordConfigVersion`（`AiChatService.java:724`）只在成功收尾调用；失败路径（`onErrorResume`）只写 `ai_turn_metric`。我实测 conversation 1582：metric 行有 `outcome=ERROR` + traceId，但 `ai_conversation.prompt_version/config_version` 仍为 NULL。

### 复验对原报告结论的影响

- 原报告「不成立 2」中：**AC-CFG-10 消解**（D2 落地 + Lead 把 AC 收窄为"确定性门禁 + 真机集标注不阻断"，见第五阶段报告 §3.10 对照）；**AC-CFG-06 仍为"不成立（按字面）"**（已批准决策，待收窄 §2.1#5 表述）。
- 其余 10 条 AC 的成立结论不受影响；D1 修复**未**改变任何其它 AC 的判定。
