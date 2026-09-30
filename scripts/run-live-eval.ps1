<#
.SYNOPSIS
  一键真机评测：打包 → 起临时实例 → 跑 `--suite=live` 黄金问题集 → 必停实例并复核端口。

.DESCRIPTION
  把本轮（第四/六阶段）手工做过的真机评测链路固化成一条命令。真机评测是**发布门禁拦不住的
  那一半**：确定性集用 Stub 模型，不碰真模型；本轮抓到的 3 处问题（拒答泄漏「SQL」、GQ-34 方差、
  周期误判）全都只有真机才能暴露。

  流程（严格按顺序）：
    1. `mvn -pl guarantee-web -am -DskipTests package`  ← **必须带 -am**，否则 jar 里是 ~/.m2 的旧 guarantee-ai；
    2. 从**用户级环境变量** DEEPSEEK_API_KEY 取值，经 `Start-Process` 注入子进程
       （**不进命令行参数、不落文件、不打印值**——命令行会出现在进程列表里，等于泄密）；
    3. 起实例 → 轮询 `/actuator/health` 到 UP（默认 120s 超时）；
    4. `node scripts/ai-golden-questions.mjs --suite=live [--only=…]`，报告落 `reports/`；
    5. **try/finally：无论成败都停实例并复核端口释放、jar 未被占用**；
    6. 退出码透传：**0 通过 / 1 断言失败 / 2 环境问题**（与脚本自身的语义一致）。

.PARAMETER Port
  实例监听端口。缺省 0 = 自动从 8092 起找第一个空闲端口。
  **8081 是本机用户自己的实例，脚本会直接拒绝**（绝不触碰）。

.PARAMETER KnowledgeDisabled
  用于 GQ-25：临时把 DB 配置项 `knowledge.enabled` 置 false 再起实例，跑完**在同一 finally 里还原**。
  ⚠ 该配置在**共享库**里，期间用户自己的实例也会看到"知识层关闭"——请在安静窗口使用。

.PARAMETER Only
  只跑指定题号（逗号分隔），如 `-Only GQ-31,GQ-34`（省时间、便于定位）。

.PARAMETER SkipBuild
  跳过第 1 步打包（jar 已经是最新时用）。**注意：仍会校验 jar 是 fat jar**。

.PARAMETER SkipLock
  跳过 `.agent/locks/maven.lock`（团队本地约定；跨人协作时不要跳过）。

.PARAMETER EvalScript
  评测脚本路径（默认 `scripts/ai-golden-questions.mjs`）。
  这是**自测用**的可替换点：用它可以验证"退出码透传"（0/1/2）——真机上要造一次断言失败
  必须等模型真的答错，不可控也不该伪造，所以自测用桩脚本走同一条链路。

.EXAMPLE
  pwsh scripts/run-live-eval.ps1 -Port 8092 -Only GQ-31
  pwsh scripts/run-live-eval.ps1 -KnowledgeDisabled -Only GQ-25
#>
[CmdletBinding()]
param(
    [int]$Port = 0,
    [switch]$KnowledgeDisabled,
    [string]$Only = '',
    [switch]$SkipBuild,
    [switch]$SkipLock,
    [int]$HealthTimeoutSeconds = 120,
    [string]$EvalScript = 'scripts/ai-golden-questions.mjs'
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# 三条写死的坑（脚本存在的理由之一）
# ---------------------------------------------------------------------------
# 1) `-am` 必须带：`-pl guarantee-web` 单模块构建会从 ~/.m2 取**旧**的 guarantee-ai，
#    于是"真机评测"跑的是上一次构建的代码 —— 假绿。
# 2) **禁止** `-Dspring-boot.repackage.skip=true`：它会把 fat jar 原地改写成 thin jar
#    （本轮真实事故：143KB / BOOT-INF=0，所有 `java -jar` 都起不来）。
#    本脚本不传该参数，并且打包后**校验 BOOT-INF 非 0**，不满足直接 exit 2。
# 3) **8081 绝不触碰**：那是本机用户自己的实例（本脚本在参数层直接拒绝 8081）。
$MYSQL_DEFAULT = 'mysql'

function Write-Step([string]$text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }

<#
  环境问题统一出口：**必须 exit 2**（0 通过 / 1 断言失败 / 2 环境问题）。

  注意不能用 `Write-Error`：脚本启用了 `$ErrorActionPreference='Stop'`，
  `Write-Error` 会当场终止并把退出码变成 1，于是"环境问题"被误报成"断言失败"。
  这类细节正是 CI 里最容易踩的坑，所以收在一个函数里。
#>
function Exit-Environment([string]$message) {
    Write-Host "[环境问题 → exit 2] $message" -ForegroundColor Red
    exit 2
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $repoRoot

$jarPath = Join-Path $repoRoot 'guarantee-web\target\guarantee-ai-admin.jar'
$agentDir = Join-Path $repoRoot '.agent'
$logPath = Join-Path $agentDir "live-eval-$Port.log"

function Test-PortBusy([int]$candidate) {
    return [bool](Get-NetTCPConnection -LocalPort $candidate -State Listen -ErrorAction SilentlyContinue)
}

function Get-JarBootInfCount([string]$path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($path)
    try { return @($zip.Entries | Where-Object { $_.FullName -like 'BOOT-INF/*' }).Count }
    finally { $zip.Dispose() }
}

# ---------------------------------------------------------------------------
# 端口：拒绝 8081；缺省自动找一个空闲端口
# ---------------------------------------------------------------------------
if ($Port -eq 8081) {
    Exit-Environment '拒绝：8081 是本机用户自己的实例，本脚本绝不触碰（换一个端口，或留空自动分配）'
}
if ($Port -le 0) {
    $Port = 0
    foreach ($candidate in 8092..8130) {
        if (-not (Test-PortBusy $candidate)) { $Port = $candidate; break }
    }
    if ($Port -eq 0) { Exit-Environment '找不到空闲端口（8092-8130 全被占用）' }
}
if (Test-PortBusy $Port) { Exit-Environment "端口 $Port 已被占用，换一个（或留空自动分配）" }
Write-Host "目标端口：$Port" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 密钥：只从**用户级环境**取，只注入子进程环境
# ---------------------------------------------------------------------------
$apiKey = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
if ([string]::IsNullOrWhiteSpace($apiKey)) { $apiKey = $env:DEEPSEEK_API_KEY }
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Exit-Environment '缺少 DEEPSEEK_API_KEY（用户级环境变量）：真机评测无法进行'
}

$exitCode = 2
$proc = $null
$mavenLock = Join-Path $repoRoot '.agent\locks\maven.lock'
$lockHeld = $false
$knowledgeSaved = $null
$knowledgeChanged = $false

function Get-MysqlExe {
    $cmd = Get-Command mysql -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $fallback = 'D:\environment\mysql-5.7.40-winx64\bin\mysql.exe'
    if (Test-Path $fallback) { return $fallback }
    return $null
}

function Invoke-Mysql([string]$sql) {
    $mysql = Get-MysqlExe
    if (-not $mysql) { throw '找不到 mysql 客户端' }
    $env:MYSQL_PWD = 'guarantee@2026'
    return & $mysql -h 127.0.0.1 -P 3307 -u guarantee -N -B guarantee_ai_admin -e $sql
}

try {
    # ---------------- 1. 打包 ----------------
    if (-not $SkipBuild) {
        Write-Step '1/5 打包（-pl guarantee-web -am -DskipTests package）'
        if (-not $SkipLock) {
            New-Item -ItemType Directory -Force -Path (Split-Path $mavenLock) | Out-Null
            $deadline = (Get-Date).AddMinutes(20)
            while (-not $lockHeld -and (Get-Date) -lt $deadline) {
                if (Test-Path $mavenLock) {
                    $stale = $true
                    $raw = Get-Content $mavenLock -Raw -ErrorAction SilentlyContinue
                    if ($raw -match 'time=([^\s]+)') {
                        try { $t = [datetime]::Parse($Matches[1]); if (((Get-Date) - $t).TotalMinutes -lt 20) { $stale = $false } } catch { $stale = $true }
                    }
                    if ($stale) { Remove-Item $mavenLock -Force -ErrorAction SilentlyContinue; continue }
                    Write-Host '  等待 maven.lock（他人构建中）…'
                    Start-Sleep -Seconds 60
                    continue
                }
                try {
                    $fs = [System.IO.File]::Open($mavenLock, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
                    $sw = New-Object System.IO.StreamWriter($fs)
                    $sw.Write("owner=run-live-eval time=$( (Get-Date).ToString('o') ) nonce=$([guid]::NewGuid()) pid=$PID")
                    $sw.Flush(); $sw.Close(); $fs.Close()
                    $lockHeld = $true
                } catch { Start-Sleep -Seconds 5 }
            }
            if (-not $lockHeld) { throw '等待 maven.lock 超时（20 分钟）' }
        }

        & mvn -pl guarantee-web -am "-DskipTests" package 2>&1 |
            Tee-Object -FilePath (Join-Path $agentDir 'live-eval-build.log') |
            Select-String -Pattern 'BUILD|Building jar: .*guarantee-ai-admin.jar|ERROR\]' | Select-Object -Last 5
        if ($LASTEXITCODE -ne 0) { throw "mvn package 失败（exit=$LASTEXITCODE）" }

        if ($lockHeld) { Remove-Item $mavenLock -Force -ErrorAction SilentlyContinue; $lockHeld = $false }
    } else {
        Write-Step '1/5 跳过打包（-SkipBuild）'
    }

    # ------------- jar 健全性：必须是 fat jar（防 thin jar 事故） -------------
    if (-not (Test-Path $jarPath)) { throw "jar 不存在：$jarPath（去掉 -SkipBuild 重新打包）" }
    $bootInf = Get-JarBootInfCount $jarPath
    $jarMb = [math]::Round((Get-Item $jarPath).Length / 1MB, 1)
    Write-Host ("  jar: {0} MB, BOOT-INF 条目 {1}" -f $jarMb, $bootInf)
    if ($bootInf -le 0) {
        throw "jar 不是 fat jar（BOOT-INF=0，疑似被 -Dspring-boot.repackage.skip=true 改写过）：$jarPath"
    }

    # ---------------- 2. 可选：临时关闭知识层 ----------------
    if ($KnowledgeDisabled) {
        Write-Step '2/5 临时关闭知识层（GQ-25 前置）'
        $knowledgeSaved = (Invoke-Mysql "SELECT IFNULL(config_value,'<null>') FROM ai_config_item WHERE config_key='knowledge.enabled';" | Select-Object -First 1)
        Invoke-Mysql "UPDATE ai_config_item SET config_value='false', version=(SELECT * FROM (SELECT MAX(version)+1 FROM ai_config_item) t) WHERE config_key='knowledge.enabled';" | Out-Null
        $knowledgeChanged = $true
        Write-Host "  knowledge.enabled：原值=$knowledgeSaved → false（finally 里会还原）" -ForegroundColor Yellow
    } else {
        Write-Step '2/5 知识层保持现状'
    }

    # ---------------- 3. 起实例并等 health UP ----------------
    Write-Step "3/5 起实例（端口 $Port）"
    $env:DEEPSEEK_API_KEY = $apiKey
    if ($KnowledgeDisabled) { $env:GOLDEN_KNOWLEDGE_DISABLED = '1' } else { Remove-Item Env:GOLDEN_KNOWLEDGE_DISABLED -ErrorAction SilentlyContinue }
    $java = (Get-Command java).Source
    $proc = Start-Process -FilePath $java -ArgumentList @('-jar', $jarPath, "--server.port=$Port") -PassThru `
        -RedirectStandardOutput $logPath -RedirectStandardError (Join-Path $agentDir "live-eval-$Port.err.log")
    Write-Host "  pid=$($proc.Id)，日志=$logPath"

    $healthUrl = "http://127.0.0.1:$Port/actuator/health"
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    $up = $false
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) { throw "实例提前退出（exit=$($proc.ExitCode)），见日志 $logPath" }
        try {
            $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5 -ErrorAction Stop
            if ($health.status -eq 'UP') { $up = $true; break }
        } catch { }
        Start-Sleep -Seconds 3
    }
    if (-not $up) { throw "等 /actuator/health 到 UP 超时（${HealthTimeoutSeconds}s）" }
    Write-Host '  health = UP' -ForegroundColor Green

    # ---------------- 4. 跑真机集 ----------------
    Write-Step '4/5 运行 -suite=live 黄金问题集'
    $env:BASE_URL = "http://127.0.0.1:$Port"
    $evalArgs = @($EvalScript, '--suite=live')
    if (-not [string]::IsNullOrWhiteSpace($Only)) { $evalArgs += "--only=$Only" }
    Write-Host "  node $($evalArgs -join ' ')   (BASE_URL=$env:BASE_URL)" -ForegroundColor DarkGray
    & node @evalArgs
    $exitCode = $LASTEXITCODE

    Write-Step '报告'
    Get-ChildItem (Join-Path $repoRoot 'reports') -Filter 'eval-live-*' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 2 |
        ForEach-Object { Write-Host ("  {0}  ({1:N1} KB, {2})" -f $_.Name, ($_.Length / 1KB), $_.LastWriteTime) }
    Write-Host "  退出码 = $exitCode  (0 通过 / 1 断言失败 / 2 环境问题)"
} catch {
    Write-Host "`n[环境/流程错误] $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 2
} finally {
    # ---------------- 5. 无论成败：停实例 + 还原 + 复核 ----------------
    Write-Step '5/5 收尾（必停实例 + 复核）'
    if ($proc) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 3
        $left = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        foreach ($c in $left) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    }
    if ($knowledgeChanged) {
        if ($knowledgeSaved -eq '<null>') {
            Invoke-Mysql "UPDATE ai_config_item SET config_value=NULL WHERE config_key='knowledge.enabled';" | Out-Null
        } else {
            Invoke-Mysql "UPDATE ai_config_item SET config_value='$knowledgeSaved' WHERE config_key='knowledge.enabled';" | Out-Null
        }
        Write-Host "  knowledge.enabled 已还原为 $knowledgeSaved" -ForegroundColor Yellow
    }
    if ($lockHeld) { Remove-Item $mavenLock -Force -ErrorAction SilentlyContinue }
    $busy = Test-PortBusy $Port
    Write-Host ("  端口 {0}：{1}" -f $Port, $(if ($busy) { 'STILL LISTENING ⚠' } else { '已释放' })) -ForegroundColor $(if ($busy) { 'Red' } else { 'Green' })
    try {
        $fsx = [System.IO.File]::Open($jarPath, 'Open', 'ReadWrite', 'None'); $fsx.Close()
        Write-Host '  jar：可独占打开（未被占用）' -ForegroundColor Green
    } catch {
        Write-Host '  jar：仍被占用 ⚠' -ForegroundColor Red
    }
    if (-not $SkipBuild -and (Get-Command mvn -ErrorAction SilentlyContinue)) {
        Write-Host '  提示：本脚本从不传 -Dspring-boot.repackage.skip=true（会把 fat jar 改写成 thin jar）'
    }
}

exit $exitCode
