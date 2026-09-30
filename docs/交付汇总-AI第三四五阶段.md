# 交付汇总 · AI 第三 / 四 / 五阶段

| 项 | 内容 |
|---|---|
| 范围 | 路线图**第三阶段（RAG → 业务知识）**、**第四阶段（AI 配置 → 确认 → 审计）**、**第五阶段（MCP → Evaluation → Observability）** |
| 分支 | `feature/ai-roadmap-phase3-5`（基线 `940e023`），**28 个提交**，工作区干净 |
| 需求真源 | `docs/REQ-第三阶段-RAG业务知识.md` / `docs/REQ-第四阶段-AI配置与确认审计.md` / `docs/REQ-第五阶段-MCP评测与可观测.md`（三份均 **v1.1**，§0 决策按用户确认全部照建议执行） |
| 独立验证 | `docs/TEST-第三阶段-验证报告.md`、`docs/TEST-第四阶段-验证报告.md`（含 v1.1 复验 + v1.2 最终确认）、`docs/TEST-第五阶段与全量回归-验证报告.md`（含 v1.1 复验） |
| 最终结论 | **29 成立 / 0 不成立 / 4 无法验证**（4 条全部因本机无 `DEEPSEEK_API_KEY`） |
| 最终结论（2026-09-30 晚更新） | **34 条 AC 全部成立 / 0 不成立 / 0 无法验证** —— 原 4 条「无法验证」随 `DEEPSEEK_API_KEY` 就位解除阻塞：真机黄金问题集 **32/32 PASS + GQ-25 在关知识层实例 PASS（33/33 题全覆盖）**。详见 §七 |

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
| `AiChatMetrics` **9 项**指标 + 标签基数闸 + `/actuator/prometheus` 免登录 | 真机 200（63 KB）；含 `userId\|conversationId\|question=\|prompt=` 的行 **0**；`AiObservabilityIT` 断言标签键 ⊆ 白名单 |
| 「AI 运行」页 + `GET /api/ai/metrics/overview\|trend\|tools/top` | CDP 实测与 SQL 聚合**逐字段一致**（333 轮 / 平均 1.985 / 失败 11 / 触顶 12 / 104.2853ms；Top 工具计数与 p95 一致） |
| 评测框架：**33 条** + `--suite=all\|deterministic\|live` + `--baseline` diff + 打分 + 内嵌 SSOT | 确定性集 **12/12、exit 0** 并纳入 `mvn verify`；`--baseline` diff 新增失败 **0**；缺 Key 时严格判"未跑"（exit 2） |
| 业务 MCP：网关（Node stdio，**13 只读工具**）+ 平台协议面/凭据面 + Redis 限流配额 + 开关 | 真机：`tools/list=13`（与 `catalog.ts` 逐名一致）→ `tools/call` 成功；**撤销后立即 401**；写工具 **403**；同秒第 6 次 **429**；默认关闭实例三类路径 **404** 且平台自身正常 |
| `ai:mcp:read` 硬门禁 + `ai:mcp:manage` 独立权限码 + 服务账号 `SERVICE` 强校验 | `McpBackendIT` 8/8（含 HUMAN 账号被拒签发 + `COUNT(*)==0`）、`McpRateLimitIT`/`McpQuotaIT` 各 1/1；登录拒绝 SERVICE 且与密码错误**同码同文案** |

---

## 三、跨阶段回归

- **提示词完整性**：`business-assistant.st` 268 行、规则 **1~48 连续**、5 个保护标记与 `PROTECTED_MARKERS` **逐字一致**
- **SSE 事件集合未变**（阶段二断言不回归）；`/actuator/health` = `{"status":"UP"}`，`exposure` 仍含 health/info
- **共享开发库未被污染**：评测前后 tender 100000 / 险种 6 / 机构 21 与文档基线一致
- **`AiChatService` 三阶段改动自洽**：公开 `@Autowired` 构造器 + 包内测试构造器并存；traceId 显式下传、版本写入一次两列、失败轮也回填

---

## 四、实现期与验证期发现并已修复的缺陷（8 项）

1. 伪造「知识来源」行的 6 类 Markdown 装饰变体不被剥离 → 修后 **12/12**（并补 6 个反例测试）
2. `GET /api/ai/config/prompts` 同步跑分钟级门禁 → 页面超时；改为列表回最近结果、门禁由刷新与**发布**触发
3. 发布门禁跑在 `@Transactional` 内（分钟级长事务）+ 前端仅 30s 超时 → 门禁移出事务 + 前端 180s
4. AC-CFG-10 子句②（真机集"未跑"标注）无实现 → 补 `/prompts/gate` 的 `live` 维度（只读真实报告，**缺 Key 必回 NOT_RUN，不伪造**）
5. `model.max-tokens/timeout/max-retries` 可编辑但不生效 → 页面标注"本期未接线"且不可编辑
6. `McpBackendIT` 鉴权夹具缺 `SecurityContext`（3 条红被误判为守卫失效）→ 修后 8/8 并由另一队友**独立复跑**确认
7. `AI_TURN_COST` 日志 13 占位符只传 12 实参 → 字段整体错位；修后四键正确（用**反证实验**证明新断言能抓住它）
8. SSOT 测试项数被 `target/` 陈旧 surefire XML **虚增 88**（682 vs 594）→ 按类名归属归一，与真实构建逐项一致

另：服务账号安全缺口（`account_type` 未映射 + 登录不拒绝 SERVICE）由 task-15 暴露 → **单独立项 T5-06** 补齐。

---

## 五、遗留问题（待用户裁决）

| 类别 | 项 |
|---|---|
| 既有缺陷（只登记） | `DataSourceClaimGuard` 的 Markdown 装饰绕过（与已修的知识守卫同类）；`ProposalNumberGuard` 窄格式检测 |
| 数据/运维 | `ai_operation_audit` 分区名与真实 `TO_DAYS` 边界差一年（归档脚本已按真实上界规避，DRY-RUN 默认）；`ai_config_item` 2 行历史 NULL（无行为影响） |
| 本期未接线 | `model.max-tokens` / `model.timeout` / `model.max-retries`（页面已标注） |
| 后续优化候选 | 门禁结果按 `contentHash + TTL` 复用；`AiConfigCatalog` 增加 `wired` 标志（现为前端清单防护） |
| 环境受限（**2026-09-30 晚部分解除**） | 真机黄金问题集 33 条：**已跑通**（32 PASS + GQ-25 单独 PASS，见 §七）；`ai_tokens`/`ai_proposals` 真机指标：本轮真机集产生了真实轮次数据（`ai_turn_metric`/`ai_tool_call` 均有写入，如 viewer 会话 `rounds=2/tool_calls=1`），但未按该指标口径单独核对 |
| 工程教训 | 临时 8088 实例会锁 fat jar 导致 `repackage` 失败（本次 3 次，约定：先 package 再起、用完立即停并复核端口与 jar） |

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

> 真机黄金问题集（33 条）需要 `DEEPSEEK_API_KEY`：`node scripts/ai-golden-questions.mjs --suite=live`
> —— **没有 Key 时输出"未跑（环境问题）"，不是失败。**
> **2026-09-30 晚该 Key 已就位，33 条已全部跑通**（含 GQ-25 的关知识层实例），见 §七。

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
