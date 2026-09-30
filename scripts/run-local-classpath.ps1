<#
.SYNOPSIS
  本地临时实例启动器（**classpath 方式，不锁 fat jar**）——把"临时实例协议"固化成一条命令。

.DESCRIPTION
  为什么要用 classpath 而不是 `java -jar`：
    · `java -jar guarantee-ai-admin.jar` 会**占用 fat jar**，此后 `spring-boot:repackage`
      会以 `Unable to rename ... .jar.original` 失败，把所有要 `mvn package/verify` 的人挡住
      （本轮已发生 3 次构建事故）；
    · 用「各模块 target/classes + 从 fat jar 解出的第三方依赖」启动，fat jar 全程只被**读取**，
      任何人都能随时重新打包。

  本脚本固化了四条踩过的坑：
    1) 依赖从 fat jar 的 `BOOT-INF/lib/*` 解到 `.agent/bootlib/`（只读复制，不占用 jar）；
    2) ★ **强制删除 `bootlib/guarantee-*.jar`**：应用自身模块必须走 `target/classes`，
       否则 `mybatis.mapper-locations: classpath*:mapper/**/*.xml` 会同时命中 classes 与 jar →
       启动报 `Mapped Statements collection already contains key ...`；
    3) **绝不**用 `-Dspring-boot.repackage.skip=true` 来"规避占用"：它会把 fat jar 原地改写成
       thin jar（无 `BOOT-INF/`、无 `Main-Class`），之后 `java -jar` 直接报"没有主清单属性"；
    4) `DEEPSEEK_API_KEY` 在 **User 作用域**时子进程不一定继承，且**不能进命令行**（会进进程列表）、
       **不能落文件**。本脚本从 User 作用域读出后只放进子进程环境。

  退出（含 Ctrl+C）时 `Stop-Process` 子进程并校验端口已释放、fat jar 仍可独占打开。

.PARAMETER Port
  起始端口，默认 8092。若被占用会自动向后找空闲端口（最多 +20）。

.PARAMETER KeyFromUserEnv
  是否从**用户级环境变量** `DEEPSEEK_API_KEY` 注入子进程（默认 true）。
  置 false 时不注入（模型链路会以 `not-configured` 失败，仅用于纯本地接口验证）。

.PARAMETER KnowledgeDisabled
  追加 `--guarantee.ai.knowledge.enabled=false`（知识层降级演练 / 隔离走查常用）。

.PARAMETER ExtraArgs
  额外的 `--key=value` 启动参数（数组）。

.PARAMETER Smoke
  健康检查通过后做一次冒烟：POST `/api/auth/login`（只打印 code 与 token 长度，**不打印令牌**），
  并统计 `/actuator/prometheus` 的 `ai_*` 行数。

.PARAMETER RunSeconds
  大于 0 时：冒烟后等待 N 秒自动停止（便于无人值守取证）。默认 0 = 常驻，直到 Ctrl+C。

.PARAMETER HealthTimeoutSec
  健康检查轮询上限，默认 180 秒。

.PARAMETER Reextract
  强制重新从 fat jar 解依赖（默认：`.agent/bootlib/` 已有 jar 就复用）。

.EXAMPLE
  # 常驻（Ctrl+C 退出，退出时自动清理）
  pwsh -File scripts/run-local-classpath.ps1 -Port 8092 -Smoke

.EXAMPLE
  # 无人值守：起 8092、冒烟、20 秒后自动停（本脚本的验收方式）
  pwsh -File scripts/run-local-classpath.ps1 -Port 8092 -Smoke -RunSeconds 20

.EXAMPLE
  # 知识层关闭 + 自定义参数
  pwsh -File scripts/run-local-classpath.ps1 -KnowledgeDisabled -ExtraArgs '--guarantee.ai.mcp.enabled=true'
#>
[CmdletBinding()]
param(
    [int]$Port = 8092,
    [bool]$KeyFromUserEnv = $true,
    [switch]$KnowledgeDisabled,
    [string[]]$ExtraArgs = @(),
    [switch]$Smoke,
    [int]$RunSeconds = 0,
    [int]$HealthTimeoutSec = 180,
    [switch]$Reextract
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Jar = Join-Path $Root 'guarantee-web\target\guarantee-ai-admin.jar'
$BootLib = Join-Path $Root '.agent\bootlib'
$MainClass = 'com.guarantee.web.GuaranteeAiAdminApplication'
# 顺序与 reports/README.md 的手工配方一致：web 在最前，依赖模块在后
$Modules = @('guarantee-web', 'guarantee-ai', 'guarantee-system', 'guarantee-auth',
    'guarantee-order', 'guarantee-analysis', 'guarantee-common')

function Info($m) { Write-Host "[run-local] $m" -ForegroundColor Cyan }
function Warn($m) { Write-Host "[run-local] $m" -ForegroundColor Yellow }

# ---------------------------------------------------------------------
# 1) 校验 fat jar（必须是可执行的 fat jar，而不是被 repackage.skip 改写过的 thin jar）
# ---------------------------------------------------------------------
if (-not (Test-Path $Jar)) {
    throw "找不到 fat jar：$Jar`n请先打包（带 -am，否则会取 ~/.m2 的旧 guarantee-ai）：`n  mvn -pl guarantee-web -am -DskipTests package"
}
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
$jarSize = (Get-Item $Jar).Length
$zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
try {
    $libEntries = @($zip.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*.jar' })
    $hasBootInf = $libEntries.Count -gt 0
} finally {
    $zip.Dispose()
}
if (-not $hasBootInf) {
    throw ("fat jar 不含 BOOT-INF/lib（当前 $([Math]::Round($jarSize/1KB)) KB）——它已被 `-Dspring-boot.repackage.skip=true` 改写成 thin jar。`n" +
        "恢复办法：`n  mvn -pl guarantee-web -am -DskipTests package   # 注意：期间不能有人用 java -jar 占着它")
}
Info ("fat jar 校验通过：$([Math]::Round($jarSize/1MB,1)) MB，BOOT-INF/lib 条目 $($libEntries.Count) 个（全程只读，不会被本脚本锁定）")

# ---------------------------------------------------------------------
# 2) 依赖：BOOT-INF/lib → .agent/bootlib/（已存在则复用）
# ---------------------------------------------------------------------
$existing = @(Get-ChildItem $BootLib -Filter '*.jar' -ErrorAction SilentlyContinue)
if ($Reextract -or $existing.Count -eq 0) {
    New-Item -ItemType Directory -Path $BootLib -Force | Out-Null
    $n = 0
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -like 'BOOT-INF/lib/*.jar') {
                $dest = Join-Path $BootLib (Split-Path $entry.FullName -Leaf)
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
                $n++
            }
        }
    } finally {
        $zip.Dispose()
    }
    Info "已从 fat jar 解出依赖 $n 个 → $BootLib"
} else {
    Info "复用已有依赖：$BootLib（$($existing.Count) 个 jar；要强制重解加 -Reextract）"
}

# ★ 关键一步：删掉应用自身模块的 jar，防止 mapper XML 双扫
$appJars = @(Get-ChildItem $BootLib -Filter 'guarantee-*.jar' -ErrorAction SilentlyContinue)
if ($appJars.Count -gt 0) {
    $appJars | Remove-Item -Force
    Info "已删除 bootlib 里的应用模块 jar $($appJars.Count) 个（$($appJars.Name -join ', ')）——防 mapper 双扫"
} else {
    Info "bootlib 里没有应用模块 jar（符合预期）"
}

# ---------------------------------------------------------------------
# 3) 各模块 target/classes
# ---------------------------------------------------------------------
$classDirs = @()
foreach ($module in $Modules) {
    $dir = Join-Path $Root "$module\target\classes"
    if (-not (Test-Path $dir)) {
        throw "缺少编译产物：$dir`n请先编译：`n  mvn -pl guarantee-web -am -DskipTests compile"
    }
    $classDirs += $dir
}
Info "模块编译产物就绪：$($classDirs.Count) 个 target/classes"

# ---------------------------------------------------------------------
# 4) 端口：从 -Port 起找第一个空闲端口
# ---------------------------------------------------------------------
$chosen = $Port
for ($i = 0; $i -le 20; $i++) {
    $candidate = $Port + $i
    $busy = Get-NetTCPConnection -LocalPort $candidate -State Listen -ErrorAction SilentlyContinue
    if (-not $busy) { $chosen = $candidate; break }
}
if ($chosen -ne $Port) { Warn "端口 $Port 被占用，改用空闲端口 $chosen" }

# ---------------------------------------------------------------------
# 5) Key：从用户级环境注入子进程（不打印值、不进命令行、不落文件）
# ---------------------------------------------------------------------
if ($KeyFromUserEnv) {
    $key = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
    if ($key) {
        $env:DEEPSEEK_API_KEY = $key
        Info "已从【用户级】环境注入 DEEPSEEK_API_KEY（长度 $($key.Length)，值不打印、不落文件、不进命令行）"
    } else {
        Warn "用户级环境没有 DEEPSEEK_API_KEY：实例能起来，但真实模型调用会以 not-configured 失败"
    }
} else {
    Warn "-KeyFromUserEnv:`$false：不注入 Key（仅适合不触发模型的接口验证）"
}

# ---------------------------------------------------------------------
# 6) 组装并打印完整命令（可直接复制）
# ---------------------------------------------------------------------
$joinedClasses = ($classDirs -join ';')
$bootLibGlob = Join-Path $BootLib '*'
$cp = "$joinedClasses;$bootLibGlob"

$appArgs = @("--server.port=$chosen")
if ($KnowledgeDisabled) { $appArgs += '--guarantee.ai.knowledge.enabled=false' }
if ($ExtraArgs.Count -gt 0) { $appArgs += $ExtraArgs }

$outLog = Join-Path $Root ".agent\local-classpath-$chosen.out.log"
$errLog = Join-Path $Root ".agent\local-classpath-$chosen.err.log"
Remove-Item $outLog, $errLog -ErrorAction SilentlyContinue

Info '可复制的完整命令（Key 由本脚本以环境变量注入，故命令里不出现）：'
Write-Host ""
Write-Host ("java -cp `"$cp`" $MainClass " + ($appArgs -join ' ')) -ForegroundColor Gray
Write-Host ""

# ---------------------------------------------------------------------
# 7) 启动 + 轮询 health
# ---------------------------------------------------------------------
$proc = Start-Process -FilePath 'java' -ArgumentList ("-cp `"$cp`" $MainClass " + ($appArgs -join ' ')) `
    -WorkingDirectory $Root -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
Info "已启动：pid=$($proc.Id)  stdout=$outLog  stderr=$errLog"

$healthUrl = "http://127.0.0.1:$chosen/actuator/health"
$ready = $false
try {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) {
            throw "进程已退出（exit=$($proc.ExitCode)）——看 $errLog / $outLog"
        }
        try {
            $r = Invoke-WebRequest -Uri $healthUrl -TimeoutSec 3 -SkipHttpErrorCheck
            if ($r.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 3
    }
    if (-not $ready) {
        Warn "健康检查超时（$HealthTimeoutSec s）：最后 15 行 stderr ——"
        Get-Content $errLog -Tail 15 -ErrorAction SilentlyContinue
        throw "实例未在 $HealthTimeoutSec 秒内 UP"
    }
    $healthText = (Invoke-WebRequest -Uri $healthUrl -TimeoutSec 5 -SkipHttpErrorCheck).Content
    Info "health UP：$healthText"

    # -----------------------------------------------------------------
    # 8) 冒烟（可选）
    # -----------------------------------------------------------------
    if ($Smoke) {
        $login = Invoke-WebRequest -Method Post -Uri "http://127.0.0.1:$chosen/api/auth/login" `
            -ContentType 'application/json; charset=utf-8' `
            -Body ([System.Text.Encoding]::UTF8.GetBytes('{"username":"admin","password":"Admin@123"}')) `
            -SkipHttpErrorCheck -TimeoutSec 30
        $loginBody = $login.Content | ConvertFrom-Json
        Info ("冒烟：POST /api/auth/login → HTTP {0} code={1} tokenLen={2}" -f `
                $login.StatusCode, $loginBody.code, ($loginBody.data.token | ForEach-Object { $_.Length }))
        $prom = (Invoke-WebRequest -Uri "http://127.0.0.1:$chosen/actuator/prometheus" -TimeoutSec 30 -SkipHttpErrorCheck).Content
        $aiLines = @(($prom -split "`n") | Where-Object { $_ -match '^ai_[a-z_]+(_total|_seconds)?\{' })
        Info ("冒烟：GET /actuator/prometheus → ai_* 序列 {0} 行" -f $aiLines.Count)
    }

    if ($RunSeconds -gt 0) {
        Info "按 -RunSeconds=$RunSeconds 等待后自动停止……"
        Start-Sleep -Seconds $RunSeconds
    } else {
        Info '实例常驻中：Ctrl+C 停止（脚本会在退出时清理进程并校验 fat jar 未被锁）'
        Wait-Process -Id $proc.Id
    }
} finally {
    # -----------------------------------------------------------------
    # 9) 收尾：只停本脚本起的那个进程（核对命令行），再校验端口与 fat jar
    # -----------------------------------------------------------------
    $alive = Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.Id)" -ErrorAction SilentlyContinue
    if ($alive) {
        if ($alive.CommandLine -like "*--server.port=$chosen*") {
            Stop-Process -Id $proc.Id -Force
            Info "已 Stop-Process pid=$($proc.Id)"
        } else {
            Warn "命令行不匹配（不是本脚本起的实例？）——拒绝 kill：$($alive.CommandLine)"
        }
    }
    Start-Sleep -Seconds 3
    $stillListening = [bool](Get-NetTCPConnection -LocalPort $chosen -State Listen -ErrorAction SilentlyContinue)
    Info "端口 $chosen 已释放：$(-not $stillListening)"

    $jarLocked = $false
    try {
        $fs = [System.IO.File]::Open($Jar, 'Open', 'ReadWrite', 'None')
        $fs.Close()
    } catch {
        $jarLocked = $true
    }
    if ($jarLocked) {
        Warn "fat jar 仍被占用（有别的进程在用 java -jar？）：$Jar"
    } else {
        Info "fat jar 未被锁（可被下一次 mvn package 覆盖）：$Jar"
    }
}
