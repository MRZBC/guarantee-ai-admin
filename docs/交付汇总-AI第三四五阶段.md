# 交付汇总 · AI 第三 / 四 / 五阶段

| 项 | 内容 |
|---|---|
| 范围 | 路线图**第三阶段（RAG → 业务知识）**、**第四阶段（AI 配置 → 确认 → 审计）**、**第五阶段（MCP → Evaluation → Observability）** |
| 分支 | `feature/ai-roadmap-phase3-5`（基线 `940e023`），**28 个提交**，工作区干净 |
| 需求真源 | `docs/REQ-第三阶段-RAG业务知识.md` / `docs/REQ-第四阶段-AI配置与确认审计.md` / `docs/REQ-第五阶段-MCP评测与可观测.md`（三份均 **v1.1**，§0 决策按用户确认全部照建议执行） |
| 独立验证 | `docs/TEST-第三阶段-验证报告.md`、`docs/TEST-第四阶段-验证报告.md`（含 v1.1 复验 + v1.2 最终确认）、`docs/TEST-第五阶段与全量回归-验证报告.md`（含 v1.1 复验） |
| 最终结论 | **29 成立 / 0 不成立 / 4 无法验证**（4 条全部因本机无 `DEEPSEEK_API_KEY`） |
| 最终结论（2026-09-30 深夜 · T6 收口后） | **第三/四/五阶段 34 条 AC：全部成立 / 0 不成立 / 0 无法验证**（AC-RAG 9 + AC-CFG 12 + AC-MCP 13）。<br>**路线图第二阶段（既有，本轮补做正式判定）：成立 7 / 不成立 2 / 无法验证 0**——2 条"不成立"是 **AC-BA-03/04 随 M2.3 缓做**（Q-BA-01 已拍板），按 v1.2 裁剪口径可宣布完成。<br>**真机黄金问题集（35 题）当前发布口径：34 PASS / 0 FAIL / 1 未跑**（GQ-25 按设计需关知识层实例，已单独 PASS）→ **35/35 全覆盖、禁用术语 0**。详见 §七、§八 |

---

## 一、最终验收（Lead 在冻结快照上复跑）

```
mvn -B verify
[INFO] Reactor Summary for guarantee-ai-admin 1.0.0:
[INFO] guarantee-common ................................... SUCCESS
[INFO] guarantee-system ................................... SUCCESS
[INFO] guarantee-auth ..................................... SUCCESS
[INFO] guarantee-order .................................... SUCCESS
[INFO] guarantee-analysis ................................. SUCCESS
[INFO] guarantee-ai ....................................... SUCCESS
[INFO] guarantee-web ...................................... SUCCESS
[INFO] BUILD SUCCESS
```

- 单测 **596**（guarantee-ai 422 / common 7 / system 120 / auth 28 / order 2 / analysis 6 / web 11），**0 失败**
- 集成 **114**（guarantee-web 113 + guarantee-analysis 1），**0 失败**
- 前端：`npx vue-tsc --noEmit` exit 0、`npx vite build` exit 0
- 单一事实源：`node scripts/single-source-of-truth.mjs --check` **exit 0**（596 / 114 / 710 / 103 类，与真实构建逐项一致，`stale=false`）

---

## 二、逐阶段交付与证据

### 第三阶段 · RAG → 业务知识（独立验证 5-0-4）

| 交付 | 证据 |
|---|---|
| `ai_knowledge_item` + `ai_knowledge_import_log` + `V7__ai_knowledge.sql` + 受管表登记 | 真库幂等：首次导入 `created=18`、二次 `unchanged=18` 且导入留痕 **0 增行** |
| **18 条**知识真源（SYSTEM 12 / ORDER 5 / CONCEPT 1），启动时幂等导入 | `KB-ORDER-0005` 改真源后 `version 1→2→3` 且留痕可查（AC-RAG-08） |
| `queryBusinessKnowledge`（只读、登录可见、条目级 `permission_code` 服务端裁剪） | `KnowledgeRetrievalIT` 4/4（结果与直接调 Service 逐字段相等、越权正反对照） |
| `KnowledgeClaimGuard`：服务端追加「知识来源」行并剥离模型自写 | **12/12** Markdown 装饰变体可剥离/纠正（含验证发现的 6 个反例） |
| 降级开关 `guarantee.ai.knowledge.*` | `KnowledgeDisabledIT` 1/1（关掉后数字链路不退化、如实说明知识层不可用） |
| 提示词迁移 **298 → 268 行** | 规则编号 1~48 **零缺失零重复**；行为约束一条未迁；新增「业务知识检索」节 |

**无法验证（4 条，全部同一原因）**：真机黄金问题集需要 `DEEPSEEK_API_KEY`，本机未配置 → 报告标"未跑"，**不计入通过**。

### 第四阶段 · AI 配置 + 确认 + 审计（独立验证 11-0-0）

| 交付 | 证据 |
|---|---|
| `AiConfigCatalog`（**20 项**唯一真源）+ 快照式 `AiConfigService` | 真机改 `temperature` 后**同一进程下一请求**生效；缺省一致性从 git 基线逐项比对 11 项 ✅ |
| `ai:config:view` / `ai:config:update` + 危险权限清单两处同步 | 真机 analyst 读/写 → `HTTP 200 + code:403` 且**无 data**；越界 `99` → `code:400`「超出允许范围 [0, 2]」 |
| 审计 `CONFIG_UPDATE` / `AI_CONFIG`（before → after） | 密钥只记 `<changed>`（无回显/入库/日志泄漏）；被拒写**不产生审计**、不改变生效值 |
| `PromptVersionService`（DRAFT/PUBLISHED/ARCHIVED、发布同事务归档、回滚写审计、保护标记校验） | `PromptVersionServiceTest` 19/19 + `PromptVersionLifecycleIT` 2/2（真库发布/回滚/审计） |
| **发布门禁**（确定性集 exit 0 才放行；NOT_RUN/FAILED 一律拒） | 正向真机：门禁 **14.7s** → 发布路径自己调用门禁 → `PUBLISHED gated=true`、审计 `prompt.v1/target_id=1`；反向：`gate-command=cmd /c exit 1` → **400 可读拒绝（57ms）、无 PUBLISHED、无投影** |
| `AiConfig.vue` 四页签 + 危险项二次确认 + 门禁四态 + 真机集"未跑"标注 | CDP 实测（headless + reload）：四页签、`temperature=0.2`、密钥引用名、`live=NOT_RUN+原因`、三个未接线项**不可编辑** |
| 指纹闭环（此前 `target_fingerprint` **无生产者**） | `ProposalFingerprintClosureTest`；全仓 `executor.execute(` 仅 1 处、`confirm` 仅 1 个 HTTP 入口 → **无旁路** |
| 审计归档执行体 | `scripts/archive-operation-audit.ps1` 复跑 **exit 0**、14 个到期分区 / 0 行 / **未 DROP**（DRY-RUN 默认） |

### 第五阶段 · MCP + Evaluation + Observability（独立验证 13-0-0）

| 交付 | 证据 |
|---|---|
| `ai_turn_metric`（每轮一行，含 `outcome`/`trace_id`/`source`）+ `trace_id` 三段贯通 | `AiObservabilityIT` 2/2：指标行 / 工具调用 / 审计 **trace_id 相同且非空**；失败轮 `outcome=ERROR` |
| `AiChatMetrics` **10 项**指标 + 标签基数闸 + `/actuator/prometheus` 免登录 | 真机 200（63 KB）；含 `userId\|conversationId\|question=\|prompt=` 的行 **0**；`AiObservabilityIT` 断言标签键 ⊆ 白名单 |
| 「AI 运行」页 + `GET /api/ai/metrics/overview\|trend\|tools/top` | CDP 实测与 SQL 聚合**逐字段一致**（333 轮 / 平均 1.985 / 失败 11 / 触顶 12 / 104.2853ms；Top 工具计数与 p95 一致） |
| 评测框架：**37 条**（GQ-01~37）+ `--suite=all\|deterministic\|live\|refusal` + `--repeat=N` + `--baseline` diff + 打分 + 内嵌 SSOT | 确定性集 **12/12、exit 0** 并纳入 `mvn verify`；**拒答类 ×3 = 24/24 全通过**（`--suite=refusal`，**N 次全通过才算通过**）；`--baseline` diff 新增失败 **0**；缺 Key 时严格判"未跑"（exit 2） |
| 业务 MCP：网关（Node stdio，**15 只读工具**）+ 平台协议面/凭据面 + Redis 限流配额 + 开关 | 真机：`tools/list=15`（与 `catalog.ts` 逐名一致）→ `tools/call` 成功；**撤销后立即 401**；写工具 **403**；同秒第 6 次 **429**；默认关闭实例三类路径 **404** 且平台自身正常 |
| `ai:mcp:read` 硬门禁 + `ai:mcp:manage` 独立权限码 + 服务账号 `SERVICE` 强校验 | `McpBackendIT` 8/8（含 HUMAN 账号被拒签发 + `COUNT(*)==0`）、`McpRateLimitIT`/`McpQuotaIT` 各 1/1；登录拒绝 SERVICE 且与密码错误**同码同文案** |

---

## 三、跨阶段回归

- **提示词完整性**：`business-assistant.st` 268 行、规则 **1~48 连续**、5 个保护标记与 `PROTECTED_MARKERS` **逐字一致**
- **SSE 事件集合未变**（阶段二断言不回归）；`/actuator/health` = `{"status":"UP"}`，`exposure` 仍含 health/info
- **共享开发库未被污染**：评测前后 tender 100000 / 险种 6 / 机构 21 与文档基线一致
- **`AiChatService` 三阶段改动自洽**：公开 `@Autowired` 构造器 + 包内测试构造器并存；traceId 显式下传、版本写入一次两列、失败轮也回填

---

## 四、实现期与验证期发现并已修复的缺陷（12 项）

1. 伪造「知识来源」行的 6 类 Markdown 装饰变体不被剥离 → 修后 **12/12**（并补 6 个反例测试）
2. `GET /api/ai/config/prompts` 同步跑分钟级门禁 → 页面超时；改为列表回最近结果、门禁由刷新与**发布**触发
3. 发布门禁跑在 `@Transactional` 内（分钟级长事务）+ 前端仅 30s 超时 → 门禁移出事务 + 前端 180s
4. AC-CFG-10 子句②（真机集"未跑"标注）无实现 → 补 `/prompts/gate` 的 `live` 维度（只读真实报告，**缺 Key 必回 NOT_RUN，不伪造**）
5. `model.max-tokens/timeout/max-retries` 可编辑但不生效 → **已接线**（缺省零漂移）+ `catalog.wired` + 服务端拒写未接线项
6. `McpBackendIT` 鉴权夹具缺 `SecurityContext`（3 条红被误判为守卫失效）→ 修后 8/8 并由另一队友**独立复跑**确认
7. `AI_TURN_COST` 日志 13 占位符只传 12 实参 → 字段整体错位；修后四键正确（用**反证实验**证明新断言能抓住它）
8. SSOT 测试项数被 `target/` 陈旧 surefire XML **虚增 88**（682 vs 594）→ 按类名归属归一，与真实构建逐项一致
9. `queryInsuranceType` 按险种名恒返回 0 条（关键字同时塞进 `keyword`/`typeName`/`typeCode` 三个并列 AND）→ 只设 `keyword` + 防线断言（GQ-10/GQ-20 的失败根因）
10. 评测运行器把"只读账号确实调了工具"记成 0 次（`tool_call` 事件按 `ai:debug:view` 下发）→ 改为回读 `GET /api/ai/tool-calls/{conversationId}`（GQ-24 假失败）
11. **`ai.proposals` 是死指标**（生产代码零调用点，`/actuator/prometheus` 无该序列）→ `ProposalService` 六条真实流转打点，真机样本已取到
12. `DataSourceClaimGuard` 的 Markdown 装饰绕过（与 #1 同类但未同步修）→ 与知识守卫**对称化**（9 个变体全对 + 两种反证）

运维类修复：审计分区边界用 MySQL `TO_DAYS()` 求值（既有库差 365 天，只修新建库 + DRY-RUN 重分区脚本）；`thin jar` 事故（`repackage.skip` 会把 fat jar 原地改写）已恢复并写进复核注意。

另：服务账号安全缺口（`account_type` 未映射 + 登录不拒绝 SERVICE）由 task-15 暴露 → **单独立项 T5-06** 补齐。

---

## 五、遗留问题（更新于 T6 收口后）

| 类别 | 项 | 状态 |
|---|---|---|
| `ProposalNumberGuard` 的"形态规避"（小写/全角/分隔符/零宽） | 放宽必须同时做归一化比对，否则会误删模型如实回显的真编号 | **登记不修**；边界已钉成 20 例对照测试 |
| 门禁结果按 `contentHash + TTL` 复用 | 与 AC-CFG-10"发布时强制重跑、不拿缓存放行"**直接冲突** | **不做**（有理由） |
| 重命名**既有库**的审计分区 | 共享库风险高 | 只提供 **DRY-RUN** 脚本；新建库口径已修正 |
| 真机集 3 处失败（GQ-27 周期不连续 / GQ-31,GQ-35 拒答泄漏「SQL」/ GQ-34 方差） | **收紧断言后暴露的真实模型行为缺陷**（此前被宽松断言掩盖） | 已立 **T6-07** 修提示词；判定由 **T6-08** 重新给出 |
| 企业/项目维度（AC-BA-03/04） | **已交付**（T7 收口轮 B1）：`queryEnterpriseAnalysis` / `queryProjectAnalysis` + 4 条聚合 SQL + 提示词路由 + GQ-36/37 | 独立改判后目标 **9/9** |
| Vault 远端备份 | **已提供脚本 + 恢复演练**（`scripts/backup-vault.ps1`、`docs/VAULT-备份.md`）；**远端仓库未建**（本机无 `gh` CLI，需用户手工三步） | 备份链路可用，只差离机 |
| `ai_config_item` 2 行历史 NULL | 无行为影响 | 登记不清理 |

**工程教训（已写进发布说明与知识库）**：① 临时实例锁 fat jar → `repackage` 失败（先停实例再打包；**不要**用 `-Dspring-boot.repackage.skip=true`，它会把 fat jar 原地改写成 thin jar）；② 改了 `guarantee-ai` 却只跑 `-pl guarantee-web` → Maven 从 `~/.m2` 取旧 jar（必须 `install` 或带 `-am`）。

---

## 六、如何复核（最小命令集）

```bash
# 1) 全量验收（约 2 分钟；不要与临时实例同时跑：实例会锁 fat jar）
mvn -B verify

# 2) 单一事实源（测试项/评测条数/指标/MCP 工具清单）
node scripts/single-source-of-truth.mjs --check

# 3) 评测（确定性集，不需要 API Key）
node scripts/ai-golden-questions.mjs --self-check
node scripts/ai-golden-questions.mjs --suite=deterministic

# 4) 前端
cd frontend && npx vue-tsc --noEmit && npx vite build

# 5) 业务 MCP 网关协议测试
cd tools/business-mcp && npm test

# 6) 真机走查（用临时端口，用完立即停；8081 是用户实例不要动）
java -jar guarantee-web/target/guarantee-ai-admin.jar --server.port=8088
```

> 真机黄金问题集（当前 **37 条**）需要 `DEEPSEEK_API_KEY`：`node scripts/ai-golden-questions.mjs --suite=live`
> —— **没有 Key 时输出"未跑（环境问题）"，不是失败。**
> **当前发布口径（T7 收口轮）**：全量 **37 题 → 36 PASS / 0 FAIL / 1 未跑**（未跑的 GQ-25 按设计需关知识层实例，
> 已单独跑通）= **37/37 题全覆盖**；**拒答类 8 题 × 3 轮 = 24/24 全通过**（`--suite=refusal`，N 次全通过才算通过）。
> 历史口径 `33 题 32/32`、`35 题 31/3/1`、`35 题 34/0/1` 均归档在 `reports/archive/`（`31/3/1` 里的 3 处＝1 项断言假失败 + 2 项已修行为缺陷）。
> 注意 `--suite=live` 在有未跑项时 **exit 2**（环境语义），**不是断言失败**。

---

## 七、2026-09-30 晚 · 真机集复验 + 三项缺陷修复

### 7.1 复验结论

| 项 | 结果 |
|---|---|
| 真机黄金问题集（33 条） | **32/32 PASS** + GQ-25 在 `--guarantee.ai.knowledge.enabled=false` 实例单独 **PASS** = **33/33 全覆盖**（原「4 条无法验证」全部转成立） |
| 基线 diff | 新增失败 **0**、**新修复 4**（GQ-10 / GQ-20 / GQ-24 / GQ-29）、仍失败 0；`passRate 0.875 → 1`、`rounds.avg 1.8 → 1.7` |
| 发布门禁（确定性集） | `mvn -B -pl guarantee-web -Dit.test=EvaluationDeterministicIT verify` → **BUILD SUCCESS** + IT **12/12** + 脚本 exit 0 |
| 单测 | 新增 `InsuranceTypeQueryToolTest` **4/4** |
| 全量验收（`mvn -B verify`，本次） | **BUILD SUCCESS**：单测 **600**（common 7 / system 120 / auth 28 / order 2 / analysis 6 / **guarantee-ai 426** / web 11）、集成 **114**（web 113 / analysis 1）、合计 **714**，**0 失败**；`node scripts/single-source-of-truth.mjs --check` **exit 0**。§一 的 596/114/710 是**冻结快照**口径，差额 4 = 本轮新增的 `InsuranceTypeQueryToolTest` |
| 阶段三态汇总（更新口径） | 阶段三 **成立 9 / 不成立 0 / 无法验证 0**（AC-RAG-01~09）；阶段四 **12 / 0 / 0**（AC-CFG-01~12）；阶段五 **13 / 0 / 0**（AC-MCP-01~13）→ **合计 34 成立 / 0 不成立 / 0 无法验证**。<br>**口径更正**：本节曾写"阶段四 11 / 合计 33"，是把 AC-CFG-10 消解与 AC-CFG-06 改判两次变更**少算一次**；阶段四报告正文逐条列的是 12 条（01/02/03/04/05/06/07/08/09/10/11/12），以 **34** 为准。 |

证据：`reports/eval-live-2026-09-30.json|md`（目标 8088）、`reports/eval-live-gq25-2026-09-30.json`（目标 8089）、
`reports/eval-deterministic-2026-09-30.json|md`；历史报告归档在 `reports/archive/`
（`*-notrun` = 缺 Key 版，`*-before-fix` = 4 条失败版）。

### 7.2 本轮修复的三项缺陷（均为既有实现/工具问题，非本轮引入）

| # | 缺陷 | 影响 | 修法 |
|---|---|---|---|
| 8 | `queryInsuranceType` 按**险种名称**搜索恒返回 0 条：同一个关键字被同时写进 `keyword`/`typeName`/`typeCode`，而 `InsuranceTypeMapper.xml#queryWhere` 三者是**并列 AND** → SQL 等价于 `type_name LIKE %X% AND type_code LIKE %X%`，恒假 | 助手问「某险种现在是什么状态/费率」时先查空、换短词仍空、只能兜底 `category` → 白烧 3 次调用 4 轮（**GQ-10 / GQ-20 的失败根因**）；线上所有"按险种名提问"都会多绕两圈 | 工具改为只设 `keyword`（Mapper 里 `keyword` 本身就是"名称 OR 编码"模糊词）；新增 `InsuranceTypeQueryToolTest` 4 条，含"`typeName`/`typeCode` 必须留空"的防线断言 |
| 9 | 评测运行器把"只读账号**确实调了**工具"记成 0 次：`tool_call` SSE 事件按 `ai:debug:view` 权限下发，VIEWER 账号收不到 | GQ-24 **假失败**（实质断言"不得泄漏 `KB-SYSTEM-0010`"其实通过；库里 `queryBusinessKnowledge/SUCCESS`、`rounds=2`） | 运行器回读 `GET /api/ai/tool-calls/{conversationId}`（只需 `ai:chat` + 会话归属校验），SSE 事件仅作回落 |
| 10 | 提示词规则 43 只点名"贡献最大 / 变化最大"，「A 和 B **分别是多少 / 哪个更高**」这类**多对象对比**没有归口 | 模型把两个省拆成两次 `queryOrderSummary`（结论虽对，但调用次数随对象数增长）→ GQ-29 失败 | 规则 43 增补触发词与反例：多对象对比 → **一次** `queryOrderDistribution`（`dimension=REGION` + `regionCode` 过滤）；`queryOrderSummary` 只用于全局总量/单一对象 |

详见 `docs/TEST-第三阶段-验证报告.md` §10（缺陷 8/9/10）。

### 7.3 运维提示（本轮踩到的两个坑）

- **8081 是用户 IDEA 实例，仍是旧代码**：本轮修复只在临时实例上验证过，需**从 IDEA 重启**才在 8081 生效。
- 临时实例会锁 fat jar：**先 `mvn package` 再起实例**，用完立即停（本轮 8088/8089 已按此执行并复核端口释放）。
- **门禁跑 `-pl guarantee-web` 时，兄弟模块从 `~/.m2` 取**：改了 `guarantee-ai` 必须先 `mvn install`，
  否则门禁验的是旧 jar（本轮踩到过一次：第一次重跑验的是 03:54 的旧 jar，结果已作废并重跑）。

---

## 八、T6 收口轮（遗留项清理，用户确认"都做掉"）

| 任务 | 内容 | 结果 |
|---|---|---|
| T6-01 | `DataSourceClaimGuard` 与知识守卫**对称化**（装饰容忍）+ `ProposalNumberGuard` 边界定性 | 9 个装饰变体全对；两种反证（HEAD 缺陷版 / 临时改回窄式）；`ai` 单测 434 全绿 |
| T6-02 | 三个模型参数**接线**（缺省零漂移）+ `catalog.wired` + 服务端拒写 | 单测断言 `getMaxTokens()==null`（缺省不下发）与显式设置下一轮生效；**真机**：置 512 → 日志 `maxTokens=512` + 正常回答 + `config_version=369` 落库 → 重置回默认（`overridden=false`） |
| T6-03 | 审计分区命名根因（Python `toordinal()` vs MySQL `TO_DAYS()` 差 365 天） | 新建库口径已修正（探针表实测 37 分区全对）；既有库只给 DRY-RUN 重分区脚本；另证 `ai_tokens_total` 真机有数据 |
| T6-05 | 评测严格性补齐（G1 预算收紧 / G2 越界问法覆盖 / G3 连续周期计数断言） | 反证矩阵（松 PASS / 紧 FAIL 且失败原因**仅**对应断言）；评测集 **33 → 35** |
| T6-06 | **死指标 `ai_proposals`** 修复 | 六条状态流转打点；真机样本 `{source="ai",status="created"} 1`、`{source="web",status="rejected"} 1` |
| T6-04 / T6-08 | 独立复验与判定更正 | 缺陷 8/9/10 独立复验成立；AC-MCP-07 **改判成立**（并更正此前"埋点存在"的高估）；AC-BA-02/07 一度判"按字面不成立" |
| T6-07 | 修提示词（拒答去技术词）+ 修评测解析器（金额小数被当 `yyyy.MM` 的假失败） | 拒答 3 题 ×3 轮 **9/9 PASS、禁用术语 0**；GQ-27/GQ-05 转 PASS（连续周期 6 / 9） |
| T6-09 | 二次改判 | **AC-BA-02 → 成立**（断言假失败，用旧解析器复现幽灵点确证）、**AC-BA-07 → 成立**（定向 4 题 ×3 轮 12/12 + 独立正文抽取 9 次检查 0 命中）→ 第二阶段回到 **7/2/0** |
| T6-10 | 终局收口：提示词清零残留技术词 + 禁用词动态覆盖（10 → **25**：18 工具名动态 + 7 精选）+ **全量 35 题真机** | `reports/eval-live-t610-*`：**34 PASS / 0 FAIL / 1 未跑**；+ GQ-25 单独 PASS = **35/35 全覆盖**；`forbiddenTermViolations = 0`（动态黑名单无误报）；口径正确率/引用完整率 1.0；轮次均值 1.6、耗时均值 3.2s |

**T6-05 收紧断言后出现的 4 项红**（**未放宽断言**；逐项定性后：1 项是**断言自身的假失败**、3 项是**真实模型行为缺陷**）：

| 题 | T6-05 现象 | 定性 | 处置与结果 |
|---|---|---|---|
| GQ-27 | 给了 7 个周期点，最长连续段只有 4 | **断言假失败**：评测脚本 `analyzePeriods` 把「数据摘要」金额 `20348992864.98` 中的小数当成 `yyyy.MM`（凭空多出 `5092-09`）打断连续段；工具与模型都正确（6 个连续升序周期） | T6-07 修解析器（年份断言 + 区间 1990~2100 + 剔除区间回显 + 两类型匹配合并去重）；修后 **GQ-27 PASS（连续周期 6）**、GQ-05 PASS（9） |
| GQ-31 / GQ-35 | 拒答正文出现 **「SQL」** | **真实模型行为缺陷**：提示词里原句「不生成也不执行 SQL」本身在教模型这个词 | T6-07 改提示词第 42/48 条（"拒绝不是解释系统怎么实现" + 话术模板，**删掉那句原话**）；真机 3 轮 × 3 题 = **9/9 PASS、禁用术语 0** |
| GQ-34（预测下季度保费） | 有方差 | **部分是断言过严**（我原要求 `maxToolCalls:0`，与 REQ §5.2.5「可给趋势描述」冲突） | T6-07 改为 `≤2 + notCall:['propose*']`（保留 refusal + 禁用术语），放宽后 3 轮全 PASS |

> 这 3 项**不是本次实现引入的**，而是此前宽松断言掩盖 / 新题首次覆盖到的行为问题；
> 逐项证据见 `reports/README.md` 的「模型行为发现」表（修前/判因/修后三列）与 `docs/TEST-助手黄金问题集.md` §4.3。
> **口径提醒（已更新）**：T6-10 已**重跑全量 35 题**，当前发布口径为 **34 PASS / 0 FAIL / 1 未跑 + GQ-25 单独 PASS = 35/35 全覆盖**；
> 中间快照 `31/3/1` 仍在 `reports/archive/` 保留作对照（1 项断言假失败 + 2 项已修行为缺陷）。
> 逐项证据见 `reports/README.md` 的「模型行为发现」表（修前/判因/修后三列）与 `docs/TEST-助手黄金问题集.md` §4.3。

---

## 九、终局确认（`docs/TEST-收口复验-第六批.md`，verifier 独立审计）

**审计结论：无可洗白迹象**——t610 工件 35 条逐条重算内部一致性（`pass ⇒ reasons 空`、`toolCalls == tools 长度`、forbidden 合计 0、唯一未跑项原因合理）= 异常 0；
**断言未被放宽**（T6-10 的期望改动 0 行；全区间唯一改动是 GQ-34/35 由 `0` 改 `≤2` + 新增 `notCall:['propose*']`，经 Lead 裁定且**反向补强**）；
`--list-forbidden` = 25 词（18 动态 + 7 精选），无误报项；归档快照为纯重命名、原文未改。

**最终三态（含路线图第二阶段）**

| 阶段 | 条数 | 成立 | 不成立 | 无法验证 |
|---|---|---|---|---|
| 二（AC-BA-01~09） | 9 | **9** | 0 | 0 |
| 三（AC-RAG-01~09） | 9 | **9** | 0 | 0 |
| 四（AC-CFG-01~12） | 12 | **12** | 0 | 0 |
| 五（AC-MCP-01~13） | 13 | **13** | 0 | 0 |
| **合计** | **43** | **43** | **0** | **0** |

> **最终改判（`docs/TEST-收口复验-第七批.md`，快照 `690e7e5`）**：AC-BA-03/04 由"不成立（M2.3 缓做）"改判为 **成立**——
> 独立验证员**自己写 SQL 交叉核对**（8 个行业分组 / 3000 家去重企业 / 150000 单 / Σ担保 1,144,564,604,550.82 完全一致；
> **行业维度合计 == 地区维度合计 == 150000**；项目类型 5 个中文枚举）、**事务内软删 + ROLLBACK** 证明企业名"历史保留"且零污染、
> 用**运行时真实 SQL + 生产改写器**做机制证明并给出反证、真机 GQ-36/37 **各 3 轮全过**。

**最终构建（Lead 与 verifier 各独立跑一次，数字完全一致）**：`mvn -B verify` **BUILD SUCCESS 8/8**，单测 **632** / 集成 **121**（**0 跳过**）= 753；`vue-tsc` / `vite build` exit 0；SSOT `--check` exit 0（**753 / 108 类 / stale=false**）。

> 数字演进：617/114（T6）→ 629/121（T7，1 跳过）→ **632/121（R 轮，0 跳过）**——"跳过"从 1 降到 0 是因为 R2 把历史保留探针 IT 改成了**事务内造样本 + 回滚**，不再因演示数据缺样本而跳过。

**未闭环项（10 → 8 条；原 10 项已全部处理，见 §十一）**
1. **单值 0.02 pp 级占比漂移在"合计"断言下不可检出**（服务端 share 已下发 + IT 逐值比对兜底）；
2. `percentShareTable` 的**子集搜索**存在"巧合凑满 100%"的理论绕过（已知取舍，如实登记）；
3. `vendor-element-plus` 约 1.07 MiB 单块仍 > 500 KiB，且 `chunkSizeWarningLimit=1500` 把警告掩盖了；
4. 拒答"逐句话术质量"属模型行为（本轮只验判据 3/3 与占比引用）；
5. **真机/拒答门禁在 CI 为手动 + 需 Key**（定时需显式打开 `vars.EVAL_LIVE_ON_SCHEDULE`）；
6. `DataSourceClaimGuard` 只判行首（刻意，登记不修，单测钉住）；
7. 无 Key 时 `ai_tokens_total` 不注册（设计使然，已说明）；
8. `reports/eval-*` 不再跟踪后，**要固化结果必须显式归档**到 `reports/archive/`。

---

## 十、T7 收口轮（用户指定：A1–A4 + B1 + B3）

| 项 | 交付 | 关键证据 |
|---|---|---|
| **A1 真机评测进 CI** | `scripts/run-live-eval.ps1`（一键：`package -am` → 注入 Key → 起实例 → `--suite=live` → try/finally 必停 → 退出码透传）+ `.github/workflows/ai-eval.yml` + `docs/CI-真机评测.md` | 本地实测 **退出码 0/1/2 三种路径全部验证**（exit 0 = 8092 上 GQ-31 PASS；exit 2 = `-Port 8081` 拒绝 / 端口被占；exit 1 = 桩透传，失败路径同样停实例）；**workflow 明确标注"未在 CI 执行过"**（无 CI 环境可跑，YAML 仅语法自检，首跑需校准 schema/seed/ubuntu 兼容三点） |
| **A2 classpath 启动脚本** | `scripts/run-local-classpath.ps1` | 实测 8092 health UP + login code=0 + `-Reextract` 场景；强制删 `bootlib/guarantee-*.jar` 防 mapper 双扫；退出即停且**不锁 fat jar** |
| **A3 Vault 备份** | `scripts/backup-vault.ps1` + `docs/VAULT-备份.md` | 身份校验（`.agent/vault.local.yaml` ↔ `VAULT_ID.md`）→ bundle + zip + 轮转 + MANIFEST；**恢复演练实测**：`git clone <bundle>` 得 108 文件、HEAD `50d0ff1`、STATE/LOG 均在 |
| **A4 拒答类制度化** | 脚本新增 `--repeat=N` 与 `--suite=refusal`（默认 3 轮，**N 次全通过才算通过**，任一次失败逐轮标注）+ 报告方差列/明细 | 真机 **8 题 × 3 轮 = 24/24 PASS（exit 0）**；发布门禁口径改为「确定性 12/12 + 拒答类 ×3」；CI 的 `live-eval` 作业已加 `--suite=refusal` 步骤，任一 exit 1 即作业失败 |
| **B1 M2.3 企业/项目维度** | `queryEnterpriseAnalysis` / `queryProjectAnalysis` + 4 条聚合 SQL + 4 VO + 提示词路由 45.1 + GQ-36/37 | 真机 GQ-36/37 **各 1 次调用新工具即 PASS**；全量 37 题 36/0/1；口径三条（企业名历史保留 / 项目类型中文透传 / 准入六条）均有机制级证据 |
| **B3 既有库分区重命名** | 脚本从 `RENAME PARTITION`（**MySQL 8.0 不存在**）改为 1:1 `REORGANIZE`；共享库实际执行 | 执行前 36/36 名字不符 → 执行后 **0 不符**、边界值多重集差异 0、**逐分区行数差异 0**、总行数 **2623 不变**、`pmax` 未动；Lead 独立用精确 `COUNT(*)` 复核（`p202609`=2623） |
| 联动 | MCP 白名单 **13 → 15**（两个新只读工具，按既有先例经 MCP 暴露） | `npm test` 17/17、`McpToolCatalogTest` 6/6、`McpBackendIT` 8/8、SSOT `--check` exit 0 |

---

## 十一、R 收口轮（用户指定：把剩余 10 项未闭环都处理掉）

**结果：10 项全部处理，未闭环收窄到 8 条**（剩下的是固有取舍/模型行为，见 §九）。最终独立验证：`docs/TEST-收口复验-第八批.md`（快照 `131197e`）。

| 原未闭环项 | 处理 | 验证（由 verifier 独立复现） |
|---|---|---|
| ① AC-BA-04 占比由模型自行除法 | **服务端下发 `share`**（最大余数法归一，分类合计**恰 100.00**）+ 提示词"占比直接引用返回值" | 反射直调 `sharesOf` 得 `[22.31,20.77,20.09,19.00,17.83]`，与 SQL 手算 **0.00 pp** 偏差；**3 次裸 SSE** 正文占比恒为服务端值，上一批的 **22.29 一次未出现** |
| ② GQ-36/37 无数值/占比断言 | 新增 `percentShareTable`（±0.1 pp、≥3 项、**子集搜索**防假红）+ 5 组反证 | 自建 9 组用例：**错 0.5/2 pp 必红**；真机 GQ-36/37 各 **3/3**。局限两条已登记（单值 0.02 pp 测不出、巧合凑 100 的理论绕过） |
| ③ `ai_tokens_total` 非 verifier 亲采 | verifier **自己起实例亲采** | 发问前 `ai_*` 序列 0 行 → 实测 `input=19048 / output=144`，与 `ai_turn_metric` **逐字段一致**；用完即停、端口释放、jar 未锁 |
| ④ 历史保留探针 IT 永久跳过 | 改成 **事务内造样本 + 回滚**（软删一家企业 → 断言仍在榜单 + 对照查询反证 → `TestTransaction` 回滚） | IT **7/0（不再 Skipped）**；整仓 verify **0 跳过**；库内零污染（3000/0/0/0） |
| ⑤ GQ-35 话术偶发重复拒绝 | 提示词补"**拒绝只说一次、不要先说'我这就去做'**" | 真机 `--repeat=3`：GQ-31/34/35 **9/9**、禁用术语 0 |
| ⑥ 拒答门禁只在手动 Job3 | CI 加**显式 opt-in**：`vars.EVAL_LIVE_ON_SCHEDULE == 'true'` 时定时也跑（默认关） | `if:` 表达式与 7 行真值表核对通过；**仍明确标注未在 CI 执行过** |
| ⑦ 前端 chunk 偏大 | `vite.config.ts` 拆分（仅拆已知重依赖） | `index` 1273.33 → **61.80 KiB**、`StatCards` 570.25 → 2.46 KiB、`vendor-echarts` **移出首屏 preload**；如实说明首屏总字节接近（收益是缓存粒度） |
| ⑧ 编号守卫形态逃逸 | `CANDIDATE_PATTERN` + `canonical()` 两段式：**比对用归一值、删除用原文 span** | 17 形态矩阵：**识别 17/17、真编号不被误删 17/17、假编号移除 17/17**；verifier 发现的 `·`(U+00B7) 缺口已修并复测 |
| ⑨ 并发构建污染 IT 夹具 | 新增 `scripts/mvn-locked.ps1`（取锁 → 透传 mvn → `finally` 释放；>20 分钟判陈旧可抢占） | 三路径实测：他人锁→**exit 3 且不删他人锁**；无锁→取跑释；陈旧锁→告警抢占；README 增"多人共库必读"一行 |
| ⑩ `reports/*` 跑评测即变脏 | `reports/eval-*` 改为**不跟踪**（`.gitignore` + `git rm --cached`），`README.md`/`archive/**` 保留；关键报告**显式固化**为 `archive/*-baseline.*` | `git check-ignore` 命中；`reports/` 下 eval-* 跟踪数 **0**，`archive/**` 仍跟踪 **46** 个文件 |

**回归**：`mvn -B verify` **BUILD SUCCESS 8/8**（单测 **632** / 集成 **121** / **0 跳过**）；前端 `vue-tsc` + `vite build` exit 0；SSOT `--check` exit 0。**43 条 AC 仍为 43 / 0 / 0**——本轮改动**没有导致退化**，阶段二证据反而更强。
