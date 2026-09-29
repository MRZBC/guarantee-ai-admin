# 交付汇总 · AI 第三 / 四 / 五阶段

| 项 | 内容 |
|---|---|
| 范围 | 路线图**第三阶段（RAG → 业务知识）**、**第四阶段（AI 配置 → 确认 → 审计）**、**第五阶段（MCP → Evaluation → Observability）** |
| 分支 | `feature/ai-roadmap-phase3-5`（基线 `940e023`），**28 个提交**，工作区干净 |
| 需求真源 | `docs/REQ-第三阶段-RAG业务知识.md` / `docs/REQ-第四阶段-AI配置与确认审计.md` / `docs/REQ-第五阶段-MCP评测与可观测.md`（三份均 **v1.1**，§0 决策按用户确认全部照建议执行） |
| 独立验证 | `docs/TEST-第三阶段-验证报告.md`、`docs/TEST-第四阶段-验证报告.md`（含 v1.1 复验 + v1.2 最终确认）、`docs/TEST-第五阶段与全量回归-验证报告.md`（含 v1.1 复验） |
| 最终结论 | **29 成立 / 0 不成立 / 4 无法验证**（4 条全部因本机无 `DEEPSEEK_API_KEY`） |

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
| 环境受限 | 真机黄金问题集 33 条与 `ai_tokens`/`ai_proposals` 真机指标：**缺 `DEEPSEEK_API_KEY`** |
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
