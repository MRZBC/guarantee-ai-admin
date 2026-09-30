# CI 与真机评测（A1 / task-34）

> 交付：`scripts/run-live-eval.ps1`（本地一键真机评测，**已实测**）+ `.github/workflows/ai-eval.yml`
> （GitHub Actions 工作流，**未在 CI 上执行过**，见 §3 的如实标注）。

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

> ⚠️ **本 workflow 从未在 GitHub Actions 上执行过。** 本机没有 CI 环境，也无法在本地跑 GitHub runner；
> 它是按 runner 环境**推断**写出的第一版，**首次运行很可能需要按实际报错调整**。以下差异是已知的：
> 1. **schema 初始化**：应用靠 `guarantee-web/src/main/resources/db/schema.sql` 自建表
>    （`spring.sql.init.mode=always`）。仓库**没有 Flyway**，`db/migration/V1..V10` 是**手工**执行的
>    幂等脚本 → CI 首跑要确认表结构完整（缺表/缺列会以 SQL 异常暴露）。
> 2. **live 集数据基线**：真机集要求订单量 ≥1000 且数据区间正确（`GOLDEN_BASELINE_MIN_ORDERS`）。
>    仓库目前**没有订单 seed 脚本**（只有 `db/seed/region.sql`）→ CI 上直接跑 live 极可能被判
>    `environment`（退出码 2）。因此 Job3 对退出码 2 采取"**打印未跑/环境不足但不失败**"的口径；
>    **接入 seed 后应把退出码 2 也改为失败**，否则这道门会长期空转。
> 3. `scripts/run-live-eval.ps1` 用 Windows API（`Get-NetTCPConnection`），**不能**直接在 ubuntu
>    runner 上跑；Job3 用 bash 重写了同样的步骤，纪律不变（带 `-am`、禁 `repackage.skip`、
>    不用用户自己的端口）。

| 作业 | 触发 | 内容 |
|---|---|---|
| `build-and-it` | 手动 + 每日 02:00 UTC | MySQL 8 + Redis service；`mvn -B verify`；上传 surefire/failsafe 报告 |
| `deterministic-eval` | 手动 + 每日 | `--suite=deterministic`（发布门禁）；上传确定性报告 |
| `live-eval` | **仅手动** | 有 `secrets.DEEPSEEK_API_KEY` 才跑；起后端（8092）→ `--suite=live` **→ `--suite=refusal`（拒答类默认 3 轮）** → 上传 `reports/**`；缺 Key **打印"未跑"并跳过**；**两个步骤任一退出码 1（断言失败）即让作业失败**，退出码 2 按"未跑"告警不失败 |

> **发布门禁口径（与 `docs/TEST-助手黄金问题集.md` §5.1 一致）**：**确定性集 12/12**（`deterministic-eval` 作业）
> **+ 拒答类 ×3 全通过**（`live-eval` 里的 `--suite=refusal` 步骤）。拒答类是唯一有**方差实证**的一类题，
> 因此判据是"**N 次全部通过才算通过**"，任一次失败 → 整题失败并逐轮列出差异，**不做平均**。

定时任务**刻意不跑 live**：真模型花钱且依赖数据基线，无人看管时跑等于烧额度 + 长期假绿。

## 4. 只读校验与语法自检

```powershell
python -c "import yaml; d=yaml.safe_load(open('.github/workflows/ai-eval.yml',encoding='utf-8')); print(list(d['jobs']))"
# → YAML 解析 OK；triggers=['workflow_dispatch','schedule']；jobs=['build-and-it','deterministic-eval','live-eval']
#   build-and-it: runs-on=ubuntu-latest, services=['mysql','redis'], steps=6
#   deterministic-eval: 同上，steps=6
#   live-eval: if=github.event_name=='workflow_dispatch' && inputs.skip_live!=true, steps=9
```

**语法自检 ≠ 执行验证**：解析通过只能说明 YAML 合法，**不能**说明 job 在 runner 上会成功
（service 健康检查、schema、seed、artifact 路径都需要首跑校准）。

## 5. 后续（本任务不做）

1. 订单 seed 脚本（≥1000，区间正确）→ 之后把 Job3 的 `exit 2` 从"警告"改成"失败"。
2. 真机集**每日**跑的前提是基线数据可复现；否则维持"仅手动"。
3. 本地脚本若要跨平台（Linux/macOS），把 `Get-NetTCPConnection` 换成 `Test-NetConnection`/`lsof` 抽象层，
   或直接复用 Job3 的 bash 版本。
