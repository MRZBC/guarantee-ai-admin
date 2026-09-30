# 发布说明 · AI 第三 / 四 / 五阶段（`feature/ai-roadmap-phase3-5`）

> 面向"要看这次到底交付了什么、升级时要注意什么"的读者。逐条 AC 的证据见 `docs/交付汇总-AI第三四五阶段.md`
> 与四份验证报告；本文件只讲**范围、变更、升级注意、已知边界**。

| 项 | 值 |
|---|---|
| 分支 | `feature/ai-roadmap-phase3-5`（基线 `940e023`，相对该基线 **34 个提交**） |
| 需求真源 | `docs/REQ-第三阶段-RAG业务知识.md` / `REQ-第四阶段-AI配置与确认审计.md` / `REQ-第五阶段-MCP评测与可观测.md`（三份 **v1.1**，§0 决策按用户确认全部照建议执行） |
| 独立验证 | `docs/TEST-第三阶段-验证报告.md`、`TEST-第四阶段-验证报告.md`、`TEST-第五阶段与全量回归-验证报告.md`、`TEST-第二阶段-验证报告.md` |
| 最终三态 | 阶段三 **9/0/0**、阶段四 **12/0/0**、阶段五 **13/0/0** → **34 成立 / 0 不成立 / 0 无法验证** |

---

## 一、这次交付了什么

### 1. 业务知识（第三阶段 · RAG）

- 18 条业务知识真源（Markdown + YAML front-matter，`KB-SYSTEM-\*` / `KB-ORDER-\*` / `KB-CONCEPT-*`），启动时**幂等导入** `ai_knowledge_item`
- 新工具 `queryBusinessKnowledge`（只读、登录可见，条目级 `permission_code` 由**服务端**裁剪）
- **服务端溯源**：回答末尾的「知识来源：KB-…《…》vN」由服务端按本轮真实检索结果追加；模型自写的会被剥离
- 降级开关 `guarantee.ai.knowledge.enabled`（关掉后数字链路不受影响，并如实说明知识层不可用）
- 提示词 **298 → 268 行**：知识迁出、行为约束一条未迁（编号 1~48 完整）

### 2. AI 配置化 + 确认 + 审计（第四阶段）

- `系统管理 → AI 配置` 页（模型 / 提示词 / 能力开关 / 变更历史四页签）
- 配置真源 `AiConfigCatalog`（**20 项**），运行期快照落 `ai_config_item`：**改配置不重启、下一个请求生效**
- 提示词版本化（DRAFT/PUBLISHED/ARCHIVED）+ **发布门禁**：`node scripts/ai-golden-questions.mjs --suite=deterministic` 退出码 0 才允许发布（门禁在事务外先评估，NOT_RUN/FAILED 一律拒）
- 页面渠道配置写 100% 落审计 `CONFIG_UPDATE` / `AI_CONFIG`（before→after；密钥类只记 `<changed>`）
- 写操作**指纹闭环**（执行前比对，不一致 → `INVALIDATED` + 审计）+ 审计归档执行体 `scripts/archive-operation-audit.ps1`

### 3. MCP + 评测 + 可观测（第五阶段）

- 指标：`ai_turn_metric`（每轮一行，含 `outcome`/`trace_id`/`source`）、`AiChatMetrics`（10 个 meter，标签全枚举维度）、`/actuator/prometheus`（免登录，仅内网/白名单）
- `系统管理 → AI 运行` 页（概览 / 趋势 / Top 工具 / 提案计数）
- 评测框架：可重跑脚本 + 评测集（当前 **35 条**，含真机与确定性两套）+ 基线 diff + 单一事实源 `scripts/single-source-of-truth.mjs`
- 业务 MCP：网关 `tools/business-mcp`（Node + stdio）+ 平台 HTTP 面（`/api/ai/mcp/tools`、`/api/system/mcp-tokens`），**13 个只读工具**、Redis 限流配额、`ai:mcp:read` / `ai:mcp:manage`；**默认关闭**
- 服务账号强校验：`sys_user.account_type=SERVICE` 才可签发 MCP Token；**SERVICE 账号禁止登录**（同码同文案防枚举）

---

## 二、升级 / 部署注意

1. **数据库迁移（手工幂等，无 Flyway）**：`V7__ai_knowledge.sql`、`V8__ai_config.sql`、`V9__ai_observability.sql`、`V10__ai_mcp.sql`；新库直接跑 `schema.sql`（已含全部）。
2. **配置项**：`guarantee.ai.knowledge.*`、`guarantee.ai.observability.*`、`guarantee.ai.mcp.*`（**默认关闭**，yml 级、改后需重启）；`/actuator/prometheus` 已放行（内网采集；生产必须靠网络层收敛）。
3. **新增权限码**：`ai:config:view` / `ai:config:update` / `ai:mcp:read` / `ai:mcp:manage`——需在 `sys_permission` 与角色分配里同步（`PermissionCatalog` 与 `docs/REQ-角色管理与权限分配页面.md` 已登记）。
4. **提示词**：`prompts/business-assistant.st` 已迁移到 268 行；DB 无发布版时回落 classpath（冷启动可用）。
5. **重启才生效的东西**：`guarantee.ai.mcp.enabled`、`guarantee.ai.knowledge.*`、`guarantee.ai.observability.*`；**DB 配置项（20 项）不需要重启**。
6. **审计分区命名**：`ai_operation_audit` 的**新建库**已修正为 MySQL `TO_DAYS()` 口径；**既有库**的分区名比真实覆盖早一年（数据没丢、归档脚本按真实上界判定）。需要"名实一致"时用 `scripts/repartition-operation-audit.ps1`（**默认 DRY-RUN**，永不 DROP、永不碰 `pmax`）。

---

## 三、本次修复的缺陷（12 项，全部有独立复现或反证）

| # | 缺陷 | 影响 | 修法 |
|---|---|---|---|
| 1 | `KnowledgeClaimGuard` 只认裸「知识来源」行 | 模型用粗体/列表写伪造来源行时不剥离也不纠正 | 容忍 9 类装饰，**12/12** 变体正确 |
| 2 | `GET /api/ai/config/prompts` 同步跑分钟级门禁 | 配置页打开即超时 | 列表只回最近结果；门禁由"刷新门禁"与**发布**触发 |
| 3 | 发布门禁跑在 `@Transactional` 内 + 前端仅 30s 超时 | 分钟级长事务 + 页面误报失败 | 门禁移出事务、前端单独 180s |
| 4 | AC-CFG-10 子句②（真机集"未跑"标注）无实现 | 页面会误导 | `/prompts/gate` 增 `live` 维度（只读真实报告，缺 Key 必回 NOT_RUN） |
| 5 | 三个模型参数可编辑但不生效 | 配置改了没反应 | 接线（**缺省零漂移**：只有显式设置才覆盖）；`catalog.wired` + 服务端拒写未接线项 |
| 6 | `McpBackendIT` 鉴权夹具缺 `SecurityContext` | 3 条红被误判为守卫失效 | 补齐夹具，8/8 |
| 7 | `AI_TURN_COST` 日志 13 占位符只传 12 实参 | 字段整体错位 | 补齐 + 按键断言（反证证明断言有效） |
| 8 | `queryInsuranceType` 按险种名恒返回 0 条（关键字同时塞进 `keyword`/`typeName`/`typeCode` 三个并列 AND） | 真机 GQ-10/GQ-20 失败，白烧 3 次调用 4 轮 | 只设 `keyword` + 防线断言 |
| 9 | 评测运行器把"只读账号确实调了工具"记成 0 次（`tool_call` 事件按 `ai:debug:view` 下发） | GQ-24 假失败 | 改为回读 `GET /api/ai/tool-calls/{conversationId}` |
| 10 | `ai.proposals` 是**死指标**（无生产调用点） | `/actuator/prometheus` 无该序列，AC-MCP-07 被高估 | `ProposalService` 六条真实流转打点，真机样本已取到 |
| 11 | `DataSourceClaimGuard` 装饰绕过（与 #1 同类但未同步修） | 假口径行留在正文 | 与知识守卫**对称化**（9 变体全对 + 两种反证） |
| 12 | 提示词缺"拒绝时不解释实现"约束，且**原句把「SQL」这个词教给模型** | 拒答正文泄漏「SQL」（GQ-31/GQ-35） | 改第 42/48 条（总则 + 话术模板）并**删掉那句反例**；禁用词清单从 10 动态扩到 **25**；话术补"拒绝只说一次" |

另有运维/工具修复：SSOT 测试计数被 `target/` 陈旧报告虚增 88（按类名归属归一）；审计分区边界口径（Python `toordinal` vs MySQL `TO_DAYS` 差 365 天）；
**评测脚本 `analyzePeriods` 把金额小数当 `yyyy.MM`** 造成的 GQ-27 假失败（解析器已修 + 17 组自检用例）。

> **一条值得记住的教训（"反例即教学"）**：提示词原文「你唯一的取数入口是受控工具，不生成也不执行 SQL」本意是划边界，
> 但它把「SQL」放进了模型上下文，模型在拒答时就照抄。**改法不是把禁令写得更严，而是把反例从提示词里删掉**——
> 凡是要写"禁用词清单/反面示例"时，先问一句：**写进提示词是不是反而给了模型这个词？**

---

## 四、已知边界（不做或延后，均有理由）

| 项 | 状态 |
|---|---|
| `ProposalNumberGuard` 的"形态规避"（小写/全角/分隔符/零宽） | **登记不修**：放宽必须同时做归一化比对，否则会把模型**如实回显**的真编号误删；边界已钉成 20 例测试 |
| 门禁结果按 `contentHash + TTL` 缓存 | **不做**：与 AC-CFG-10"发布时强制重跑、不拿缓存放行"直接冲突 |
| 重命名**既有库**的审计分区 | **不执行**：只提供 DRY-RUN 脚本；共享库风险高 |
| 真机集 GQ-25（关知识层） | 需单独实例（`--guarantee.ai.knowledge.enabled=false`），已在独立实例跑通（**35/35 覆盖**由此达成） |
| 企业/项目维度（AC-BA-03/04） | 随 **M2.3 缓做**（Q-BA-01 已拍板），第二阶段按 v1.2 裁剪口径验收（**7 成立 / 2 不成立=这 2 条**） |
| `FORBIDDEN_TECH_TERMS` 的编码/枚举部分 | 只收**精选 7 个**（工具名 18 个已动态生成）：动态铺全区划码/险种码表会把模型**正当引用**的编码误判为泄漏 |
| Vault 远端备份 | 仍缺（本地 git only） |

---

## 五、复核命令（想自己验一遍）

```bash
mvn -B verify                                   # 全量：单测 + IT（约 2 分钟；注意先停临时实例，否则 jar 被锁）
node scripts/single-source-of-truth.mjs --check  # 评测条数 / MCP 白名单 / 测试报告新鲜度
node scripts/ai-golden-questions.mjs --self-check
node scripts/ai-golden-questions.mjs --suite=deterministic
cd frontend && npx vue-tsc --noEmit && npx vite build
cd tools/business-mcp && npm test
# 真机集（需要 DEEPSEEK_API_KEY；Windows 用户级环境变量即可）
# 先：mvn -pl guarantee-web -am -DskipTests package  ← 必须带 -am，否则取 .m2 旧 guarantee-ai
java -jar guarantee-web/target/guarantee-ai-admin.jar --server.port=8088
node scripts/ai-golden-questions.mjs --suite=live
```

> **两个已踩过的坑**：① 临时实例会锁住 fat jar，`repackage` 会失败（`Unable to rename …jar.original`）——
> 先停实例再打包；**不要**用 `-Dspring-boot.repackage.skip=true` 绕（它会把 fat jar 原地改写成 thin jar）。
> ② 改了 `guarantee-ai` 却只跑 `-pl guarantee-web` 时，Maven 从 `~/.m2` 取旧 jar——**必须先 `install` 或带 `-am`**。
