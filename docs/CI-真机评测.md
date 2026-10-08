# CI 与真机评测（A1 / task-34）

> 交付：`scripts/run-live-eval.ps1`（本地一键真机评测，**已实测**）+ `.github/workflows/ai-eval.yml`
> （GitHub Actions 工作流；**Job1 已于 2026-10-08 首跑（失败 → 已修 → 本地干净库复演 8/8 全绿，见 §3.0），
> Job2/Job3 仍未在 CI 上执行过**）。

## 0. 为什么要做这件事

发布门禁目前只有**确定性集 12/12**（`EvaluationDeterministicIT` + Stub ChatModel）：它快、免费、
可离线复现，但**完全不碰真模型**。第四/六阶段抓到的 3 处问题——拒答正文泄漏「SQL」、GQ-34 的方差、
周期误判——**全是真机才能暴露**，确定性集一道都拦不住。

所以真机集（`--suite=live`，25/33 条）不是"更全的测试"，而是**另一个维度**：
模型行为回归。它慢（分钟级）、要钱（真实 API）、依赖数据基线，因此定位是
**"手动/每日可跑的一道补充门"**，而不是替代确定性门禁。

## 1. 本地一键：`scripts/run-live-eval.ps1`

把本轮手工做过的一串动作固化成一条命令：

```powershell
pwsh scripts/run-live-eval.ps1                       # 自动挑空闲端口（从 8092 起），全量 live 集
pwsh scripts/run-live-eval.ps1 -Port 8092 -Only GQ-31 # 只跑一题（省时间、便于定位）
pwsh scripts/run-live-eval.ps1 -KnowledgeDisabled -Only GQ-25   # 关知识层实例（GQ-25 前置）
pwsh scripts/run-live-eval.ps1 -SkipBuild            # jar 已是最新，跳过打包
```

流程与纪律：

| 步骤 | 做什么 | 为什么 |
|---|---|---|
| 1 | `mvn -pl guarantee-web -am -DskipTests package` | **`-am` 必须带**：`-pl guarantee-web` 会从 `~/.m2` 取**旧** `guarantee-ai`，"真机评测"就跑成了上一次构建的代码（假绿）。本轮真踩过 |
| 1b | 打包后**校验 BOOT-INF 非 0** | 防 thin jar 事故：`-Dspring-boot.repackage.skip=true` 会把 fat jar 原地改写成 thin jar（143 KB / BOOT-INF=0，所有 `java -jar` 都起不来）。**本脚本从不传该参数**，并把这行写进 `-Help` |
| 2 | 从**用户级环境变量**取 `DEEPSEEK_API_KEY`，经 `Start-Process` 注入子进程 | **不进命令行参数**（会出现在 `Get-CimInstance` 进程列表里）、**不落文件**、**不打印值** |
| 3 | 起实例 → 轮询 `/actuator/health` 到 `UP`（默认 120s） | 不靠 `sleep` 猜启动时间；未就绪即判环境问题 |
| 4 | `node scripts/ai-golden-questions.mjs --suite=live [--only=…]` | 报告落 `reports/eval-live-<日期>.{md,json}` |
| 5 | **try/finally**：停实例 → 复核端口释放 → 复核 jar 未被占用 | 实例泄漏会锁住 jar，直接卡死别人的 `repackage`（本轮真实事故） |
| 6 | 退出码**原样透传** | 0 通过 / 1 断言失败 / 2 环境问题（与评测脚本自身的语义一致） |

端口纪律：**8081 是本机用户自己的实例，脚本在参数层直接拒绝**（`-Port 8081` → exit 2）。

`-KnowledgeDisabled` 的副作用：它通过临时把共享库里的 `knowledge.enabled` 置 `false` 再起实例
（配置是 DB 驱动的，没有 Spring 属性开关），跑完在**同一个 finally** 里还原原值。
期间用户自己的实例也会看到"知识层关闭"，**请在安静窗口使用**。

自测钩子：`-EvalScript <路径>`（默认 `scripts/ai-golden-questions.mjs`）。真机上要产生一次
"断言失败（exit 1）"必须等模型真的答错——不可控且不该伪造，因此用桩脚本走**同一条链路**验证透传。

## 2. 本地实测记录（2026-10-01）

### 2.1 exit 0：真机跑通（正面证据）

```
$ pwsh scripts/run-live-eval.ps1 -Port 8092 -Only GQ-31
===== 1/5 打包（-pl guarantee-web -am -DskipTests package） =====
[INFO] BUILD SUCCESS
  jar: 129.4 MB, BOOT-INF 条目 212          ← fat jar 自检通过
===== 3/5 起实例（端口 8092） =====
  pid=27156，日志=.agent/live-eval-8092.log
  health = UP
===== 4/5 运行 -suite=live 黄金问题集 =====
  node scripts/ai-golden-questions.mjs --suite=live --only=GQ-31   (BASE_URL=http://127.0.0.1:8092)
数据基线 OK：总订单量 150000（投标 100000 / 履约 50000），数据区间 2025-01-01 ~ 2026-09-30
GQ-31 [越界拒答] … PASS
live | ok | 全部通过         结果：通过 1/1
完整报告：reports/archive/eval-live-2026-10-01-t34-smoke.md / reports/archive/eval-live-2026-10-01-t34-smoke.json
  退出码 = 0  (0 通过 / 1 断言失败 / 2 环境问题)
===== 5/5 收尾（必停实例 + 复核） =====
  端口 8092：已释放
  jar：可独占打开（未被占用）
```

### 2.2 exit 2：环境问题的两条路径

```
$ pwsh scripts/run-live-eval.ps1 -Port 8081
[环境问题 → exit 2] 拒绝：8081 是本机用户自己的实例，本脚本绝不触碰（换一个端口，或留空自动分配）

$ pwsh scripts/run-live-eval.ps1 -Port 3307        # 3307 = MySQL 在监听
[环境问题 → exit 2] 端口 3307 已被占用，换一个（或留空自动分配）
```

> 坑记：这两条最初用 `Write-Error` 实现，在 `$ErrorActionPreference='Stop'` 下会**当场终止并把退出码
> 变成 1**——"环境问题"被误报成"断言失败"。已收敛到 `Exit-Environment`（`Write-Host` + `exit 2`）。

### 2.3 exit 1：退出码透传（桩脚本，同一条链路）

```
$ $env:STUB_EXIT=1
$ pwsh scripts/run-live-eval.ps1 -Port 8092 -SkipBuild -EvalScript .agent/stub-exit1.mjs -Only GQ-31
  node .agent/stub-exit1.mjs --suite=live --only=GQ-31   (BASE_URL=http://127.0.0.1:8092)
[stub] 故意以退出码 1 结束（用于验证 run-live-eval.ps1 的透传）
  退出码 = 1  (0 通过 / 1 断言失败 / 2 环境问题)
  端口 8092：已释放
  jar：可独占打开（未被占用）
```

结论：**0 / 1 / 2 三种语义在真实链路上都验证过**；且失败路径同样会停实例、释放端口、解锁 jar。

## 3. GitHub Actions：`.github/workflows/ai-eval.yml`

> ⚠️ **状态（2026-10-08 更新）**：Job1（`mvn -B verify`）已于 2026-10-08 在 GitHub Actions
> 上**首跑**，结果是**红**（失败在 `guarantee-system`）→ 已在本地复现、修好、
> 并用**干净库复演到 8/8 全绿**（详见 §3.0）。**Job2/Job3 仍未在 CI 上执行过**，
> 其差异仍是按 runner 环境推断的：
> 1. **schema 初始化**：应用靠 `guarantee-web/src/main/resources/db/schema.sql` 自建表
>    （`spring.sql.init.mode=always`）。仓库**没有 Flyway**，`db/migration/V1..V10` 是**手工**执行的
>    幂等脚本 → 新增列/表必须**同时**改 schema.sql（空库路径）与 migration（存量路径），
>    只改一处就是 §3.0 的根因 ②。
> 2. **live 集数据基线**：真机集要求订单量 ≥1000 且数据区间正确（`GOLDEN_BASELINE_MIN_ORDERS`）。
>    仓库目前**没有订单 seed 脚本**（只有 `db/seed/region.sql`）→ CI 上直接跑 live 极可能被判
>    `environment`（退出码 2）。因此 Job3 对退出码 2 采取"**打印未跑/环境不足但不失败**"的口径；
>    **接入 seed 后应把退出码 2 也改为失败**，否则这道门会长期空转。
>    （注：Job1 现在会起一次后端播种 15 万订单，真机集可以直接复用这套做法。）
> 3. `scripts/run-live-eval.ps1` 用 Windows API（`Get-NetTCPConnection`），**不能**直接在 ubuntu
>    runner 上跑；Job3 用 bash 重写了同样的步骤，纪律不变（带 `-am`、禁 `repackage.skip`、
>    不用用户自己的端口）。

### 3.0 首跑结果、根因与修复（2026-10-08）

**首跑**：手动触发 → Job1 在 `guarantee-system` 失败（`mvn -B verify`，120 用例中
3 failures + 41 errors），Job2/Job3 因 `needs: build-and-it` 跳过。用"空库"在本机复现，
报错与 CI **逐字一致**（surefire 3.5.6 / `There are test failures.` / `MojoFailureException`）。
**四处根因与修复**：

| # | 根因 | 证据 | 修复 |
|---|---|---|---|
| ① | **空库没有演示数据**：Job1 只起 MySQL/Redis service，**从不启动 guarantee-web**，`DataInitializer` 不执行。而 system / analysis / web 的 `*IT` 全按"演示库"写（21 机构 / 单棵 11 部门树 / 300 用户 / 角色权限矩阵 / 15 万订单） | `guarantee-system` 38 个用例红：`0 ≠ 21`、`EmptyResultDataAccess expected 1 actual 0`（查 `admin`）、`Column 'project_id' cannot be null`、`角色不存在或已停用: [OPERATOR]`、`权限码不存在: [system:audit:view]`；`guarantee-analysis` 的 `OrderTrendGranularityIT` 也因无订单必红 | Job1 新增两步：**打包 → 起一次后端播种（实测 6.0s）→ 断言形状 → 停**，与本地口径一致；形状断言把"演示数据规格漂移"变成 CI 上的响亮失败 |
| ② | **schema.sql 缺列**：`sys_user.account_type` 只加在 `db/migration/V10__ai_mcp.sql`，空库路径（`schema.sql`）里没有 → 与数据无关的纯表结构漂移 | `SysUserAccountTypeIntegrationTest` 6/6 `BadSqlGrammar: Unknown column 'account_type'`；`information_schema.columns` 全量 diff 后**唯一**真实差异就是这一列 | 补进 `schema.sql`，DDL 与 V10 的 `ADD COLUMN` 逐字一致（列 + 索引） |
| ③ | **新库的演示数据与测试目标态不一致**：`DataInitializer.seedDepartments` 产出 **21 棵**部门树（231 部门 / 21 个 `parent_id=0` 的根），而 `DataScopeIntegrationTest` 与 `scripts/verify-dept-tree-shape.mjs` 要求**单棵纯部门树**（11 部门 / 唯一根「总部」）。本地库之所以绿，是它早已被手工迁移 `migrate-dept-single-org` 收敛过 → **新库/新同事/CI 都是坏的** | 用"本地口径"（起一次后端播种）跑 verify：`guarantee-system` 反而红 2 个（`[部门树只有 1 个顶级节点] expected: 1 but was: 21`） | **对齐 DataInitializer**：`seedDepartments` 只建一棵树（id 从 1001 起、编码 `ORGHQ-*`）、`seedUsers` 按序号轮转挂 11 个部门、`DEPT_COUNT` 由 `ORG_COUNT × 11` 改为 `11`，并新增"唯一根 = 总部"的启动校验。与已执行迁移同形；该残留项原记录在 `docs/PLAN-部门配置树形改造方案.md` §16.3（现标注已闭环） |
| ④ | **（测试侧）`OperationAuditAllLimitIT` 依赖"历史累积"**：它要求库里近 7 天审计 > 200 条，而审计行是**运行时**产物（DataInitializer 不造），干净库只有 14 条 → 它测不到 `all` 与 `limit=200` 的差异 | `Expecting actual: 14L to be greater than: 200L`（两条用例都红在"数不出差异"上） | 用例自建 250 条近 7 天夹具（`@BeforeEach`）、按 `trace_id` 清理（`@AfterEach`），断言口径不变 |

**修复后的本地干净库复演**（等同 Job1 的序列；MySQL 8.0.29）：

```
全新建空库
  → mvn -pl guarantee-web -am -DskipTests package          # BUILD SUCCESS
  → 起 jar 播种（16.1s 含启动，DataInitializer 自身 6.0s）
    形状：机构 21 / 部门 11 / 顶级 1 / 用户 300 / 投标 100000 / 履约 50000
  → mvn -B verify
[INFO] guarantee-common ................................... SUCCESS
[INFO] guarantee-system ................................... SUCCESS
[INFO] guarantee-auth ..................................... SUCCESS
[INFO] guarantee-order .................................... SUCCESS
[INFO] guarantee-analysis ................................. SUCCESS
[INFO] guarantee-ai ....................................... SUCCESS
[INFO] guarantee-web ...................................... SUCCESS
[INFO] BUILD SUCCESS
```

合计 **753 项用例，0 失败 0 错误**（其中 `guarantee-system` 120、`guarantee-web` 11 单测 + 120 IT）。

> 为什么最后不走"测试仓库里再放一份最小基线"这条路：`guarantee-system/src/test/resources/application.yml`
> 自己写着"演示数据由 `DataInitializer` 生成……**由真实初始化器生成的数据比测试里手工插入的更有验证价值**"。
> 手工基线虽然能让该模块在空库上变绿，却会**掩盖**根因 ③（新库的部门形状与目标态不符），
> 且要随 `DataInitializer` 的规格漂移手工同步。所以最终选择：**对齐初始化器 + 让 CI 用真实初始化路径播种**。

**本地开发库不受影响**：`DataInitializer` 只在 `sys_user` 为空时执行，存量库一次都不会跑到；
`schema.sql` 的 `CREATE TABLE IF NOT EXISTS` 对已存在的表是空操作（列由 migration V10 补）。

### 3.0.1 第二轮：CI 上 `guarantee-web` 的红（同日）

第一版修复推送后重跑：**播种与前三处修复都生效**——CI 上 `guarantee-system` /
`guarantee-analysis` / `guarantee-ai` 全部通过，失败收窄到最后一个模块的
`AiObservabilityIT.failingTurnStillPersistsTraceableMetric`。

**根因是"用例间的隐藏数据依赖"**（这类问题只在执行顺序变化时暴露，而 CI 是 Linux、
文件遍历顺序与本地不同）：

1. 它断言 `ai_conversation.config_version > 0`，而该列 = `MAX(ai_config_item.version)`、
   **`0` 表示空表**（`AiConfigSnapshot` 口径）；应用启动与演示数据播种**都不预置配置行**
   → 该断言实际要求"先跑过某个写 `ai_config_item` 的 IT"。实测：**干净库单独跑本类必红**
   （`Expecting actual: 0L to be greater than: 0L`），把 IT 顺序反转同样必红。
2. 它用"该用户最新一条会话"定位自己的会话，会被别的 IT 抢先建出的会话顶掉。

**修复**（均在 `guarantee-web/src/test/java/com/guarantee/web/ai/AiObservabilityIT.java`）：
`config_version` 改为断言"等于那一刻 `MAX(version)` 的快照"（空表 0 也成立、非空表仍验正版本）；
会话改为"本轮新建的那一个"（先取基线，再取 `id > 基线`），并断言恰好新建 1 个。

**验证**：反转顺序（`-Dfailsafe.runOrder=reversealphabetical`）**120/0/0**；
正常顺序全量 **8/8 BUILD SUCCESS**。

> 教训（写给以后看的人）：`*IT` 之间有共享的 MySQL/Redis，**任何"取最新一行""断言某计数 > N"
> 都必须限定在用例自己创建的数据上**，否则本地绿、CI 红，且报错信息完全指向别处。

| 作业 | 触发 | 内容 |
|---|---|---|
| `build-and-it` | 手动 + 每日 02:00 UTC | MySQL 8 + Redis service；**先打包并起一次后端播种演示数据（Job1 的 `mvn -B verify` 依赖演示库，见 §3.0）**；`mvn -B verify`；上传 surefire/failsafe 报告 + 播种日志 |
| `deterministic-eval` | 手动 + 每日 | `--suite=deterministic`（发布门禁）；上传确定性报告 |
| `live-eval` | 手动；**或 定时 + 仓库变量 `EVAL_LIVE_ON_SCHEDULE='true'`（opt-in，默认关）** | 有 `secrets.DEEPSEEK_API_KEY` 才跑；起后端（8092）→ `--suite=live` **→ `--suite=refusal`（拒答类默认 3 轮）** → 上传 `reports/**`；缺 Key **打印"未跑"并跳过**；**两个步骤任一退出码 1（断言失败）即让作业失败**，退出码 2 按"未跑"告警不失败 |

> **发布门禁口径（与 `docs/TEST-助手黄金问题集.md` §5.1 一致）**：**确定性集 12/12**（`deterministic-eval` 作业）
> **+ 拒答类 ×3 全通过**（`live-eval` 里的 `--suite=refusal` 步骤）。拒答类是唯一有**方差实证**的一类题，
> 因此判据是"**N 次全部通过才算通过**"，任一次失败 → 整题失败并逐轮列出差异，**不做平均**。

### 3.1 定时跑真机：显式 opt-in（`EVAL_LIVE_ON_SCHEDULE`）

**默认关**：定时任务**无人看管**——真机集会真花钱（DeepSeek 额度）、且依赖订单数据基线，
而仓库当前**没有订单 seed**，无人值守地跑大概率长期停在 `environment`（exit 2）→ 既烧额度又假绿。
因此"定时也跑 live/refusal"必须由人**显式**打开：

```bash
# 开启（仓库级变量；Settings → Secrets and variables → Actions → Variables → New repository variable）
gh variable set EVAL_LIVE_ON_SCHEDULE --body true --repo <owner>/<repo>

# 查看 / 关闭
gh variable list --repo <owner>/<repo>
gh variable delete EVAL_LIVE_ON_SCHEDULE --repo <owner>/<repo>   # 或 set --body false
```

判定口径：**只有值恰好是字符串 `'true'` 才算开**（`TRUE`/`1`/空值/未设置一律视为关）。

Job3 的进入条件（`.github/workflows/ai-eval.yml`，折叠标量，等价于单行）：

```yaml
if: >-
  (github.event_name == 'workflow_dispatch' && inputs.skip_live != true)
  || (github.event_name == 'schedule' && vars.EVAL_LIVE_ON_SCHEDULE == 'true')
```

- 第一条：手动触发且没勾"跳过 live" → 跑（与改动前一致）；
- 第二条：**定时**触发**且**仓库变量为 `'true'` → 跑；
- `inputs.skip_live` 只在 `workflow_dispatch` 下有值（schedule 下为空串），
  但被前半段的 `github.event_name == 'workflow_dispatch' &&` **短接**，不会误判；
- 手动触发**不受**该变量影响（仍然照跑），定时触发**只认**该变量。

**不变的两条宽松口径**（手动与定时都适用，且**未在 CI 上执行过**，仅静态自检）：

1. **缺 `secrets.DEEPSEEK_API_KEY` → 打印"未跑"并跳过作业，不失败**（`has_key=false` 时后续步骤全部 `if:` 挡住）；
2. **`--suite=live` / `--suite=refusal` 退出码 2（environment）→ 告警不失败**
   ——注释里已写明"**接入订单 seed 之后应改为 `exit 1`**"，否则这道门会长期以"未跑"姿态空转。

## 4. 只读校验与语法自检

```powershell
python -c "import yaml; d=yaml.safe_load(open('.github/workflows/ai-eval.yml',encoding='utf-8')); print(list(d['jobs']))"
# → YAML 解析 OK；triggers=['workflow_dispatch','schedule']；jobs=['build-and-it','deterministic-eval','live-eval']
#   build-and-it: runs-on=ubuntu-latest, services=['mysql','redis'], steps=6
#   deterministic-eval: 同上，steps=6
#   live-eval: if=(github.event_name == 'workflow_dispatch' && inputs.skip_live != true) || (github.event_name == 'schedule' && vars.EVAL_LIVE_ON_SCHEDULE == 'true')，steps=10
```

**语法自检 ≠ 执行验证**：解析通过只能说明 YAML 合法，**不能**说明 job 在 runner 上会成功
（service 健康检查、schema、seed、artifact 路径都需要首跑校准）。
**Job1 已首跑并据此修好（§3.0，本地干净库复演 8/8 全绿）；Job2/Job3 仍未在 CI 上执行过**，其 `if:` 逻辑只做了静态自检（见上面的解析输出）。

## 5. 后续（本任务不做）

1. **订单 seed 已有替代路径**：Job1 现在会起一次后端播种 Demo 数据（15 万订单 / 2025-01-01~2026-09-30，
   见 §3.0），已满足真机集的基线要求；Job3 若也要走这条路，把 Job1 的"打包 → 起一次播种"
   两步复制过去即可。补一个**独立的 seed 脚本**（不依赖起服务）仍值得做，做完后把 Job3 的
   `exit 2` 从"警告"改成"失败"。
2. 真机集**定时跑**已由仓库变量 `EVAL_LIVE_ON_SCHEDULE` 控制（opt-in，默认关）；
   等基线数据可复现（seed 落地）后，再把该变量设为 `true` 打开每日门禁。
3. 本地脚本若要跨平台（Linux/macOS），把 `Get-NetTCPConnection` 换成 `Test-NetConnection`/`lsof` 抽象层，
   或直接复用 Job3 的 bash 版本。
4. **Job2 与 Job1 存在重复执行**：`--suite=deterministic` 内部就是重跑 `EvaluationDeterministicIT`
   （`scripts/ai-golden-questions.mjs` L1368 起），而该 IT 在 Job1 的 `mvn -B verify` 里已经跑过
   （本地复演 12/12 绿）。可改为"下载 Job1 的 surefire 工件 → 核验 12/12 → 出报告"，
   发布门禁口径不变、少一次全量构建。**（未实施，待决策）**
