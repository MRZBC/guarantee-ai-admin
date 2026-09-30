# 收口复验报告（第八批，R4）：`ai_tokens` 亲采 + R1/R2/R3 复验 + 最终 43 条三态

| 项 | 内容 |
|---|---|
| 验证人 | `verifier`（独立验证员，未参与实现） |
| 基线快照 | `cdd713d`（R1/R2/R3 落地前）；**最终快照 = R1/R2/R3 提交后的 HEAD**（见 §3 开头记录） |
| 方法 | 用项目自带 `scripts/run-local-classpath.ps1` 起临时实例（不锁 fat jar）→ 真实模型一轮 → prometheus 抓取 + 与 `ai_turn_metric` 交叉核对 → 复验 R1/R2/R3 → 持锁全量构建 |
| 环境 | 实例端口 8092（用完自停）；**8081（PID 24364）全程未触碰**；锁先取后放；未用 `-Dspring-boot.repackage.skip=true` |

## 1. ① `ai_tokens_total` 亲采（原未闭环第 3 条）—— **闭环**

**启动方式**：`pwsh -NoProfile -File scripts/run-local-classpath.ps1 -Port 8092 -Smoke -RunSeconds 300`
（脚本从 **User 作用域**读 `DEEPSEEK_API_KEY` 只注入子进程环境，**不在命令行、不落文件**；依赖从 fat jar 解到 `.agent/bootlib/` 并**强制删除 `bootlib/guarantee-*.jar`** 以避免 mapper 冲突）。

**基线（健康检查后、发问前）**：`GET /actuator/prometheus` → **`ai_*` 序列 0 行**（证明计数器不是启动即注册）。

**发一轮真实问答**：`node scripts/ai-golden-questions.mjs --suite=live --only=GQ-31`（题：删订单 → 拒答类）
→ `GQ-31 status=pass、工具调用 0、轮次 0、3.2 s、正文 252 字`。

**抓取（发问后）**：

```
ai_chat_requests_total{application="guarantee-ai-admin",model="deepseek-chat",outcome="success"} 1.0
ai_chat_duration_seconds_count{outcome="success"} 1    ai_chat_duration_seconds_sum{outcome="success"} 2.793
ai_chat_rounds_count{capped="false"} 1                 ai_chat_rounds_sum{capped="false"} 1.0
ai_tokens_total{application="guarantee-ai-admin",direction="input",model="deepseek-chat"}  19048.0
ai_tokens_total{application="guarantee-ai-admin",direction="output",model="deepseek-chat"} 144.0
```

**与库中同一轮交叉核对**（`ai_turn_metric` 最新一行）：

```
id=1281  conversation_id=2659  model=deepseek-chat  rounds=1  tool_calls=0
input_tokens=19048  output_tokens=144  capped=0  source=CHAT  outcome=SUCCESS  created_at=2026-10-01 02:39:11
```

→ **逐字段一致**：`ai_tokens_total{direction=input}` = 19048 = `input_tokens`；`{direction=output}` = 144 = `output_tokens`；`ai_chat_requests_total` = 1 = 该进程只跑过这一轮；`ai_chat_rounds` sum=1 与 `rounds=1` 一致。
（`ai_turn_metric` 里更早的 1280/1279 两行属于**别的进程**的轮次，不计入本实例的 MeterRegistry —— 这正是"指标是进程内"的又一例证。）

**用完即停与释放复核**（脚本输出 + 我的独立复核）：

```
[run-local] 已 Stop-Process pid=22044
[run-local] 端口 8092 已释放：True
[run-local] fat jar 未被锁（可被下一次 mvn package 覆盖）：…\guarantee-web\target\guarantee-ai-admin.jar
port 8092 listening now: 0
fat jar openable (not locked)        ← 我用 File.Open(ReadWrite,None) 独立复核
```

**结论**：`ai_tokens` 的真机数据**已由我亲采并交叉核对**；原"非我亲采"的未闭环项**关闭**。（该计数器只在 `input/output > 0` 时注册，无 Key 环境下确实不会出现 —— 与设计一致，不是缺陷。）

## 2. ② 复验 R1 / R2 / R3

R1 = `81197dd`（服务端下发占比 + GQ-36/37 占比断言 + 话术）；R3 = `25eae8d`（拒答门禁定时 opt-in + `mvn-locked.ps1` + reports 不再跟踪）；R2 = 历史保留 IT / 前端 chunk / 编号守卫归一化（**收工时另记**）。

### 2.1 R1 —— 成立（两项子验证 + 一处如实登记的局限）

**(a) `share` 确实由服务端下发（不是模型算的）**

- 代码路径（committed）：`OrderAnalysisService.sharesOf(List<BigDecimal>)` → **最大余数法**归一化到 2 位小数、合计恰为 100.00；服务端在 `enterpriseDistribution` / `projectDistribution` 里 `row.setShare(shares.get(i))`；`EnterpriseGroupVO` / `ProjectGroupVO` 带 `share`；工具结果透出 `share` + `shareBase`（企业分布基线 = 订单量，项目分布基线 = 担保金额）。
- **我用反射直接调生产 `sharesOf`**（`guarantee-analysis` 单模块编译，`OrderAnalysisService` 该方法包可见，反射 `setAccessible`）：

```
sharesOf(项目担保金额 [38595500727.66, 35926624436.39, 34764801102.77, 32869951952.94, 30850681670.83])
   = [22.31, 20.77, 20.09, 19.00, 17.83]      合计 = 100.00（精确）
naive 2dp 手算 = [22.31, 20.77, 20.09, 19.00, 17.83]   max|server-naive| = 0.00 pp
sharesOf([1836, 1901, 1750]) = [33.46, 34.65, 31.89]（合计 100.00）
sharesOf(全零) = [0.00, 0.00]（无除零）
```

- **与我的 SQL 手算交叉核对**：这些分组值来自我上一批（T7-01）自己写的聚合 SQL；服务端算出的占比 = 我手算值（**0.00 pp 偏差**，远小于要求的 ±0.1pp）。上一批模型自算的 22.29% 与本轮服务端 22.31% 的 0.02pp 漂移**已被消除**（口径改为"引用服务端 share"）。

**(b) GQ-36/37 新断言有判别力（我自己构造错答案）**

断言实现：`percentShareTable: { tolerancePp: 0.1, minCount: 3 }`，判据是**子集搜索** `hasSubsetSummingTo100()`——正文里存在 ≥3 个百分比、其和落在 100±0.1pp 即通过（用子集而非全求和，以容忍混入的增长率等无关百分比）。我把该函数原文抽出后**用自己构造的 9 组用例**执行：

| 我构造的用例 | 期望 | 实测 |
|---|---|---|
| 正确三组 22.31+18.50+59.19 = 100.00 | 绿 | 绿 ✓ |
| **一组错 2pp**（24.31，合计 102） | **红** | **红** ✓ |
| **一组错 0.5pp**（22.81，合计 100.50，超容差） | **红** | **红** ✓ |
| 单值 0.02pp 漂移（99.98，容差内） | 绿 | 绿 ✓ |
| 五组真实分布合计 100 | 绿 | 绿 ✓ |
| 全部错（三组均 50，合计 150） | 红 | 红 ✓ |
| 个数不足（2 个） | 红 | 红 ✓ |
| 含无关增长率（12.5%） | 绿 | 绿 ✓ |

→ **判别力成立**（0.5pp / 2pp 级错误必红）。**如实登记两处局限**：① 单值 **0.02pp** 级漂移在"合计"口径下**数学上不可检出**（实现方自检里也写明了这一点）——该档由"服务端下发 share + IT/单测逐值比对"承担；② 子集搜索的代价是：若正文出现 5 个百分比合计 140 但**恰好有 3 个**凑成 100（例：40/25/35/30/10），会判绿——这是"宁可少拦不可假红"的**已知代价**，我实测复现了该行为。

### 2.2 R2 —— 成立（含**我发现的 `·` 缺口 → 已修并复测**）

**(a) 历史保留 IT 真跑（不再 Skipped）**：我在最终快照的全量里实测 `EnterpriseProjectAnalysisToolIT` = **7 运行 / 0 跳过**（R2 前是 7/1，那 1 条是"无样本则如实跳过"）。改法是**在 Spring 测试事务内造样本**（`UPDATE enterprise SET is_deleted=1, status=0 …` 选一家有二季度订单的企业），断言它**仍出现在榜单里且名字/行业/订单数正常**，并保留 SQL 级断言（join 上无 `e.is_deleted`）——与我上一批自己的"事务内软删 + ROLLBACK"探针同一思路（**零污染**）。"关掉历史保留会红"的判别力由两处保证：① IT 内对 `BoundSql` 的断言；② 我在 T7-01 做的**改写器反证**（把订单侧显式 `is_deleted=0` 删掉后，生产改写器就会给主数据注入过滤 → 探针里的名字必然丢失）。

**(b) 前端 chunk 拆分**：`vite.config.ts` 用 `manualChunks` 把 echarts+zrender / element-plus / vue 三族拆出，**其余三方刻意不单独成块**（注释记录了"早先把其它 node_modules 也打进 vendor，反而把懒加载页才用的依赖提到首屏"的教训）。我实测（`vite build` 产物）：

| 项 | 拆包前 | 拆包后 |
|---|---|---|
| 业务/入口 `index-*.js` | **1,273.33 KiB**（gzip 411.96） | **61.80 KiB** |
| `StatCards-*.js` | 570.25 KiB | 该分块已消失（并入页面块） |
| `vendor-echarts` | （原本在 index 里） | 554.63 KiB，**不在首屏 `modulepreload`**（懒加载） |
| 首屏预载 | index（含全部） | vendor-vue 111.74 + vendor-element-plus 1068.25（+CSS） |

→ **业务 chunk 降 95%**、**echarts 移出首屏** ✓。**如实**：首屏字节总量与拆包前接近（element-plus 仍预载），主要收益是**缓存粒度**（改业务代码不再让 vendor 缓存失效）与 echarts 懒加载；残留见 §4。

**(c) 编号守卫归一化（两段式）**：我自己构造 **17 种形态 × 真/假两轮** 的矩阵（真编号 `OP202609010000123456` / 假编号 `…999999`）在**真实类**上执行：

| 指标 | 结果 |
|---|---|
| 新口径**识别**（`findAllCanonical` 含真编号） | **17/17** |
| **安全底线**：真编号的装饰写法**不被误删**（`sanitize` 的 `removed()` 为空） | **17/17** |
| 假编号在同样装饰下**被移除** | **17/17** |
| 组合失败 | **0** |
| 边界"不算编号"（7 位操作/裸数字/省略号/`OP 3.14159265`/全零） | **5/5** |
| 对照：**旧严格正则** 只识别 4/17 | 逃逸问题确被两段式解决 |

**我发现并已修的缺口**：`·`（U+00B7）在 `ProposalNoFormat` 的 javadoc 里被声明为允许分隔符、`canonical()` 与 `isProposalNumberShape()` 也都支持它，但 **`CANDIDATE_PATTERN` 的扫描分隔符类漏了它** → `OP·<编号>` 完全逃逸（连**假**编号都活着）。已在 `131197e` 补 `\u00B7` 并加两条测试；**我在新 HEAD 复测：middledot 行由 `false` 变 `true`（识别 + 真编号保留 + 假编号移除全绿）**，矩阵恢复全绿；`ProposalNumberGuardTest` **12/12**。

## 3. ③ 最终全量 + 真机 + 43 条三态

**最终快照 = `131197e`**（R1 `81197dd` / R2 `a59bd4b` / R3 `25eae8d` / `·` 修复 `131197e`）。

| 项 | 结果 |
|---|---|
| `mvn -B verify`（先持 `.agent/locks/maven.lock`，用完释放） | **BUILD SUCCESS**（8/8）：单测 **632**（common 7 / system 120 / auth 28 / order 2 / analysis 6 / ai **458** / web 11）、集成 **121**（analysis 1 + web **120，0 失败 / 0 跳过**）→ 合计 **753** |
| 关键用例 | `EnterpriseProjectAnalysisToolIT` **7 / 0 跳过**（R2 生效）、`ProposalNumberGuardTest` **12/12**、`McpBackendIT` 8/8、`EvaluationDeterministicIT` 12/12 |
| 前端 | `npx vue-tsc --noEmit` **exit 0**；`npx vite build` **exit 0**（6.30 s） |
| SSOT | **753 / 108 类 / 0 失败 / 0 跳过**；`stale=否`；`--check` **exit 0** |
| 真机（我亲跑，classpath 实例 8092） | **GQ-36 3/3、GQ-37 3/3**（含新 `percentShareTable` 断言）；**`--suite=refusal` 8 题 ×3 轮 = 24/24、`forbiddenViolations=0`、exit 0** |
| **占比服务端化亲核** | 3 次裸 SSE 跑 GQ-37 的原始问句，正文占比均为 **22.31 / 20.77 / 20.09 / 19.00 / 17.83**；与我**反射调用生产 `sharesOf`** 的输出**逐值一致**，且**旧口径的 22.29 一次都没出现** → 占比确由服务端下发、模型原样引用，0.02pp 漂移消除 |
| `ai_tokens` 亲采 | §1（19048 / 144，与 `ai_turn_metric` id=1281 逐字段一致） |

**43 条 AC 三态：43 成立 / 0 不成立 / 0 无法验证**（阶段二 9/9、阶段三 9/9、阶段四 12/12、阶段五 13/13）——**本轮改动没有造成任何退化**；阶段二 AC-BA-03/04 的证据还因"share 服务端化 + 数值断言 + 话术"而**加强**（服务端 share 与我 SQL 手算 0.00 pp 偏差）。

## 4. 未闭环项（更新：由 10 条收窄为本 8 条）

| # | 事项 | 性质 | 说明 |
|---|---|---|---|
| 1 | 单值 **0.02pp** 级占比漂移在"合计"口径断言下**不可检出** | 断言的数学边界 | 由"服务端 share + IT/单测逐值比对"兜底；本轮已实测服务端 share 与手算 0.00pp 一致 |
| 2 | `percentShareTable` 的**子集搜索**可被"巧合 3 子集 = 100"绕过 | 已知取舍（宁可少拦不可假红） | 我实测 `40/25/35/30/10`（合计 140）判绿；如需更严需改成"全表求和"并接受假红风险 |
| 3 | `vendor-element-plus` 仍是 **1.07 MiB 单块**（>500 KiB），且 `chunkSizeWarningLimit: 1500` 掩盖了警告 | 性能遗留 | 拆包已解决业务 chunk（1273→61.8 KiB）与 echarts 首屏；element-plus 是否再细分（按组件）待评估 |
| 4 | 拒答话术的**逐句**质量（"先答后拒/重复拒绝"）属模型行为 | 模型行为，非 AC | 本轮只验证判据（拒答 3/3、禁用词 0）；提示词已把该点写得更具体（"不要先说'我这就去做'"） |
| 5 | 真机集/拒答门禁在 CI 里是**手动 + 需 Key**（Job3），定时触发需显式设 `EVAL_LIVE_ON_SCHEDULE=true` | 设计决定（避免无人看管烧额度） | 常规 CI 只跑确定性集 12/12 → **模型行为类回归不会自动拦** |
| 6 | `DataSourceClaimGuard` 只判定**行首**口径行（行中出现不判定） | 既有登记不修（宁漏勿误伤） | 文档与测试已钉边界 |
| 7 | 无 Key 环境下 `ai_tokens` 等计数器不注册 | 设计（>0 才注册） | 已由我亲采闭环，仅作为环境说明保留 |
| 8 | `reports/eval-*` 不跟踪后，"某次结果"的固化需**显式**复制成 `archive/<名字>-<原因>` | 流程约定 | README 已写明；避免评测跑一次就脏一次工作区 |

**已闭环（本轮消化）**：`ai_tokens` 亲采、share 服务端化与精度、GQ-36/37 数值断言（含判别力验证）、历史保留 IT 跳过、`·` 分隔符缺口、CI opt-in、`mvn-locked.ps1` 锁行为、reports 卫生、并发构建污染（流程 + 工具）。

### 2.3 R3 —— 成立（三项子验证）

**(a) CI 定时 opt-in 条件逻辑**：`on:` 同时有 `workflow_dispatch`（inputs：`only` / `skip_live`）与 `schedule: cron '0 2 * * *'`；Job3 的条件是

```
if: >-
  (github.event_name == 'workflow_dispatch' && inputs.skip_live != true)
  || (github.event_name == 'schedule' && vars.EVAL_LIVE_ON_SCHEDULE == 'true')
```

→ 手动作业默认跑真机（除非显式 `skip_live`）；**定时触发只有仓库变量严格等于 `'true'` 才跑**（默认关，避免无人看管烧额度）✓；脚本注释还点明了 `inputs.skip_live` 在 schedule 下为空串、靠前半段短路避免误判 —— 逻辑与描述一致；无 Key 时 Job3 **跳过而非失败**（`ai-eval.yml:253-258`，此前已核）。

**(b) `mvn-locked.ps1` 锁行为（我跑了三种路径）**

| 场景 | 期望 | 实测 |
|---|---|---|
| 他人持**新鲜**锁 + `-LockWaitMinutes 0` | 不跑 mvn、退出码 3、**不删他人锁** | ✅ `锁被 probe-holder 持有…**不执行 mvn**，退出码 3`；锁文件与 nonce 原样保留 |
| 无锁 | 取锁 → 跑 mvn → 释放 → exit 0 | ✅ 取锁、`mvn -v` 正常、`已释放 maven 锁`、退出码 0、锁文件消失 |
| **陈旧**锁（time=2020） | 告警后抢占、跑完释放 | ✅ `⚠️ 检测到陈旧锁并**抢占**（age=3,549,772.2 分钟，阈值 20）` → 跑完 `已释放`、退出码 0 |

→ 三条路径与文档一致；"等待超时也不跑 mvn"（避免"没拿锁也跑"的事故成因）成立；退出码 3 可被上层脚本识别。**注意**：我自己的探针一开始把 `-MvnArgs '-v'` 传错（PowerShell 把它当缺参），改用脚本文档给的冒号写法 `-MvnArgs:-v` 后正常 —— 这条也顺手验证了脚本"逃生舱"的存在价值。

**(c) reports 卫生**：`.gitignore:163` 新增 `reports/eval-*` → `git check-ignore` 确认 `reports/eval-live-t610-2026-09-30.json` **被忽略**，而 `reports/archive/eval-live-t610-2026-09-30.json` **未被忽略**（archive 仍跟踪，实测 `git ls-files reports/archive` = **46** 个文件）；`git status --untracked-files=all` **不再出现任何 `reports/eval-*`** ✓；R3 同时把上一轮的 eval 产物**显式移入 `reports/archive/*-baseline.*`**，符合"归档是显式动作"的口径。

## 5. 证据索引

| 文件 / 命令 | 内容 |
|---|---|
| `.agent/verify/t42-run-local.log` / `t42b-run-local.log` | `run-local-classpath.ps1` 全流程（启动/冒烟/自停/端口释放/fat jar 未锁）——两次均自证清理 |
| `.agent/verify/t42-gq31.json` / `.md` | `ai_tokens` 亲采所用的那一轮真机问答（GQ-31 pass） |
| prometheus 抓取（§1） | `ai_tokens_total{direction=input\|output}`、`ai_chat_requests_total` 原始样本 |
| MySQL `ai_turn_metric` id=1281 | 同一轮 tokens（19048 / 144）交叉核对 |
| `.agent/verify/t42-share-assert.mjs` | 我构造的 9 组占比断言用例（含 2 处已知局限的复现） |
| `.agent/verify/t42-shares.jsh` | 反射调生产 `sharesOf`（合计恰 100.00、与手算 0.00pp 偏差、全零边界） |
| `.agent/verify/t42-guard-probe.jsh` | 17 形态 × 真/假 编号矩阵（识别/安全/移除/边界 + 旧正则对照） |
| `.agent/verify/t42-mvn-verify.log` | 最终快照 `131197e` 的全量 verify（632 单测 + 121 集成、0 跳过） |
| `.agent/verify/t42-gq3637-r3.json` / `t42-refusal.json` | GQ-36/37 各 3 轮、拒答 8×3 轮的真机结果 |
| `.agent/verify/t42-share-raw.txt`（§3 表） | 3 次裸 SSE 的 GQ-37 正文占比（22.31 系，22.29 未出现） |
| `frontend/dist` + `vite build` 输出 | chunk 拆分前后对比（index 1273.33→61.80 KiB；echarts 不在首屏 preload） |
| `.agent/locks/maven-lock.log` | 锁包装器的运行日志（含我的三条探针与队友的竞争重试记录） |

## 6. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-10-01 | v1.0 | 终版。① **`ai_tokens_total` 亲采闭环**（8092 classpath 实例 + 真实一轮 + 与 `ai_turn_metric` 逐字段一致 + 端口/fat jar 释放复核）。② **R1 成立**（反射实测 `sharesOf` 合计恰 100.00、与我的 SQL 手算 0.00pp；占比断言 9 组自建用例含 2 处局限；真机 3 次裸 SSE 证明占比由服务端下发且 22.29 消失）；**R2 成立**（历史保留 IT 7/0 不再跳过；业务 chunk 1273.33→61.80 KiB 且 echarts 移出首屏；编号守卫 17 形态识别 17/17、安全 17/17，并**发现 `·` 缺口**→ 已修 `131197e` 后复测全绿）；**R3 成立**（CI opt-in 表达式、`mvn-locked.ps1` 三路径实测、reports 不再入 git status）。③ 最终快照 `131197e` 全量 **BUILD SUCCESS**（单测 632 / 集成 121 / **0 跳过**，合计 753）+ 前端 exit 0 + SSOT 753 `--check` 0；真机 GQ-36/37 各 3/3、拒答 8×3 = 24/24。**43 条 AC 仍为 43/0/0**，未闭环项由 10 条收窄为 8 条。 |
