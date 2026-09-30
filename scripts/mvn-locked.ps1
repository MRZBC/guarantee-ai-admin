<#
.SYNOPSIS
  持 `.agent/locks/maven.lock` 的 `mvn` 包装器：**改代码后跑 Maven 一律走它**。

.DESCRIPTION
  为什么要锁：本机所有阶段共用**同一个** MySQL(3307)/Redis(6379)。两个 `mvn` 并发跑，
  IT 会互相踩夹具——本轮真实事故：`LogicalDeleteWebIT` 因两个构建共用一个库而红，
  隔离重跑全绿（不是代码问题，是环境问题，却得靠人肉分辨）。

  锁协议（与仓内其它 Agent 的约定一致）：

      .agent/locks/maven.lock 内容形如：
        owner=<谁> time=<ISO8601> nonce=<随机串>

    · 锁不存在      → 取锁（写入后等 3 秒回读 nonce 自证，避免竞态）；
    · 锁存在且新鲜  → 按 `-LockPollSeconds` 轮询等待，最多 `-LockWaitMinutes`；
    · 锁存在但**陈旧**（`time` 距今 ≥ `-LockStaleMinutes`，默认 20 分钟）→ **打印告警后抢占**
      （陈旧锁多半是上一次进程被 kill 留下的，不抢占就会永久卡住所有人）；
    · 等待超时      → **不跑 mvn**，以退出码 3 结束（绝不"没拿到锁也跑"，那正是事故成因）。

  释放：`finally` 里总是释放，且**只删自己写的那把**（回读 nonce 一致才删）——
  不会误删别人的锁。退出码：原样透传 `mvn` 的退出码（0..255）；3=未取到锁；4=找不到 mvn。

  ⚠️ **CI 内不需要**：GitHub Actions 的 runner 之间环境隔离（各自的 MySQL/Redis service），
  不存在共享夹具，所以 workflow 里**照常直接 `mvn`**，不要包这一层。

.PARAMETER LockStaleMinutes
  锁的陈旧阈值（分钟）。默认 20：与本仓"锁存在且 < 20 分钟视为有效"的约定一致。

.PARAMETER LockWaitMinutes
  未取到锁时的最长等待（分钟）。默认 20。设为 0 表示"不等待，立刻以 3 退出"（适合快速探测）。

.PARAMETER LockPollSeconds
  轮询间隔（秒）。默认 60。

.PARAMETER LockOwner
  写入锁的 owner 字段。默认取环境变量 `DSH_AGENT_NAME`，取不到则 `unknown`。

.PARAMETER LockQuiet
  只在取锁/抢占/释放时输出一行，不打印每次轮询。

.EXAMPLE
  # 与 mvn 同形：所有参数原样转发
  pwsh -File scripts/mvn-locked.ps1 -pl guarantee-ai test
  pwsh -File scripts/mvn-locked.ps1 -B verify
  pwsh -File scripts/mvn-locked.ps1 -pl guarantee-web -am -DskipTests package

.EXAMPLE
  # 快速探测：有人持锁就立刻退出（不等待）
  pwsh -File scripts/mvn-locked.ps1 -LockWaitMinutes 0 -v
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [int]$LockStaleMinutes = 20,
    [int]$LockWaitMinutes = 20,
    [int]$LockPollSeconds = 60,
    [string]$LockOwner = $(if ($env:DSH_AGENT_NAME) { $env:DSH_AGENT_NAME } else { 'unknown' }),
    [switch]$LockQuiet,
    # 逃生舱 A：显式给出 maven 参数（**推荐用于 -v/-e/-o/-P 这类会被 PowerShell
    # 公共参数/前缀匹配吃掉的开关**）。**必须用冒号语法**，否则 `-MvnArgs '-v'` 里那个
    # 无引号的 -v 会被当成参数名（外层 pwsh 会把引号解析掉）：
    #   pwsh -File scripts/mvn-locked.ps1 -MvnArgs:'-v'
    #   pwsh -File scripts/mvn-locked.ps1 -MvnArgs:'-P prod test'
    # 在 pwsh 会话内用 `&` 调用时，引号是有效的： .\scripts\mvn-locked.ps1 -MvnArgs '-v'
    [string[]]$MvnArgs,
    # 其余参数**原样**转发（覆盖绝大多数用法：`-pl`/`-B`/`-am`/`-D…`/`test`/`verify`…）。
    # 必须配 `CmdletBinding(PositionalBinding=$false)`：否则 PowerShell 会把 `guarantee-ai`
    # 这类裸词按位置绑到 `-LockStaleMinutes`（int）上，报"无法将值 test 转换为 Int32"。
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest
)

$ErrorActionPreference = 'Continue'

# ---------------------------------------------------------------------
# maven 参数来源（三者按顺序拼接）：
#   1) -MvnArgs:'…'            —— 逃生舱 A（argv，需冒号语法）
#   2) 环境变量 MVN_LOCKED_ARGS —— 逃生舱 B（**数据不走 argv**，任何 PowerShell 解析都吃不到它；
#                                  例：$env:MVN_LOCKED_ARGS = '-P prod -v'）
#   3) 其余位置参数            —— 常规转发
# 命名刻意统一 `Lock` 前缀：maven 的单字母开关都不会与它们前缀冲突。
# ---------------------------------------------------------------------
$envArgs = @()
if ($env:MVN_LOCKED_ARGS) {
    $envArgs = @($env:MVN_LOCKED_ARGS -split '\s+' | Where-Object { $_ -ne '' })
}
$mvnArgs = @($MvnArgs) + $envArgs + @($Rest)

$Root = Split-Path -Parent $PSScriptRoot
$LockDir = Join-Path $Root '.agent\locks'
$LockFile = Join-Path $LockDir 'maven.lock'
$EventLog = Join-Path $LockDir 'maven-lock.log'
$nonce = [guid]::NewGuid().ToString()
$held = $false

function Log-Event {
    param([string]$Message, [string]$Level = 'INFO')
    $line = "{0}`t{1}`t{2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    Write-Host "[mvn-locked] $Message"
    Add-Content -Path $EventLog -Value $line -Encoding UTF8 -ErrorAction SilentlyContinue
}

function Read-Lock {
    if (-not (Test-Path $LockFile)) { return $null }
    $raw = Get-Content $LockFile -Raw -ErrorAction SilentlyContinue
    if (-not $raw) { return $null }
    $owner = if ($raw -match 'owner=([^\s]+)') { $matches[1] } else { 'unknown' }
    $timeText = if ($raw -match 'time=([^\s]+)') { $matches[1] } else { $null }
    $time = $null
    if ($timeText) { try { $time = [datetime]::Parse($timeText) } catch { $time = $null } }
    return [pscustomobject]@{ Raw = $raw; Owner = $owner; Time = $time; AgeMinutes = if ($time) { ((Get-Date) - $time).TotalMinutes } else { $null } }
}

# ---------------------------------------------------------------------
# 0) 拦不住的坑要出声：PowerShell **公共参数**会吃掉部分 maven 开关
#
#    实测（本机 pwsh 7）：
#      -v  → 被当成 -Verbose（**静默吞掉**，maven 收不到）→ 本脚本检测到就告警
#      -e / -o → 报错（-e 歧义 ErrorAction/ErrorVariable；-o 歧义 OutVariable/OutBuffer）
#      -P  → 报错（前缀匹配 PipelineVariable）
#    **可靠的逃生舱**：用 `-MvnArgs` 显式传参（已验证 `pwsh -File … -MvnArgs '-v'` 生效）：
#      pwsh -File scripts/mvn-locked.ps1 -MvnArgs '-v'
#      pwsh -File scripts/mvn-locked.ps1 -MvnArgs '-P','prod','test'
#    注意：**加引号不管用**——从命令行跑时 `'-v'` 的引号会被外层 pwsh 解析掉，
#    到达脚本时仍是无引号的 `-v`，照样被绑成 -Verbose（实测踩过）。
#    （-q/-B/-U/-am/-N/-f/-T/-X/-D.../-pl 等都不受影响，原样转发。）
# ---------------------------------------------------------------------
if ($PSBoundParameters.ContainsKey('Verbose')) {
    Log-Event "⚠️ 检测到 -Verbose/-v 被 PowerShell 当作公共参数（**不会**转发给 maven）。要跑 `mvn -v` 请用：-MvnArgs '-v'" 'WARN'
}

New-Item -ItemType Directory -Force -Path $LockDir | Out-Null

# ---------------------------------------------------------------------
# 1) 取锁（等待 / 抢占陈旧）
# ---------------------------------------------------------------------
$deadline = (Get-Date).AddMinutes([Math]::Max(0, $LockWaitMinutes))
while (-not $held) {
    $existing = Read-Lock
    if (-not $existing) {
        Set-Content -Path $LockFile -Value ("owner={0} time={1} nonce={2}" -f $LockOwner, (Get-Date).ToString('o'), $nonce) -Encoding utf8
        Start-Sleep -Seconds 3
        $confirm = Get-Content $LockFile -Raw -ErrorAction SilentlyContinue
        if ($confirm -and $confirm -match [regex]::Escape($nonce)) {
            $held = $true
            Log-Event ("已获得 maven 锁（owner={0}）" -f $LockOwner) 'LOCK'
        } else {
            Log-Event '抢锁竞争失败（3 秒内被别人覆盖），重试' 'WARN'
        }
        continue
    }

    $stale = ($null -eq $existing.Time) -or ($existing.AgeMinutes -ge $LockStaleMinutes)
    if ($stale) {
        $age = if ($null -eq $existing.AgeMinutes) { '时间戳不可解析' } else { "{0:N1} 分钟" -f $existing.AgeMinutes }
        Log-Event ("⚠️ 检测到陈旧锁并**抢占**：owner={0} age={1}（阈值 {2} 分钟）。陈旧锁多是上次进程被 kill 留下，不抢占会卡住所有人" -f `
                $existing.Owner, $age, $LockStaleMinutes) 'WARN'
        Set-Content -Path $LockFile -Value ("owner={0} time={1} nonce={2}" -f $LockOwner, (Get-Date).ToString('o'), $nonce) -Encoding utf8
        Start-Sleep -Seconds 3
        $confirm = Get-Content $LockFile -Raw -ErrorAction SilentlyContinue
        if ($confirm -and $confirm -match [regex]::Escape($nonce)) {
            $held = $true
            Log-Event '已（抢占后）获得 maven 锁' 'LOCK'
        }
        continue
    }

    $waiting = "锁被 {0} 持有（age={1:N1} 分钟，阈值 {2} 分钟）" -f $existing.Owner, $existing.AgeMinutes, $LockStaleMinutes
    if ((Get-Date) -ge $deadline) {
        Log-Event ("$waiting；等待已达上限 -LockWaitMinutes={0} → **不执行 mvn**，退出码 3" -f $LockWaitMinutes) 'ERROR'
        exit 3
    }
    if (-not $LockQuiet) { Log-Event ("{0}；{1} 秒后重试（等待上限 {2} 分钟）" -f $waiting, $LockPollSeconds, $LockWaitMinutes) }
    Start-Sleep -Seconds ([Math]::Max(1, $LockPollSeconds))
}

# ---------------------------------------------------------------------
# 2) 透传执行 mvn（参数原样，退出码原样）
# ---------------------------------------------------------------------
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Log-Event '找不到 mvn（PATH 里没有）' 'ERROR'
    # 锁由 finally 释放
    $script:mvnMissing = $true
}
$exit = 0
try {
    if ($script:mvnMissing) { $exit = 4 }
    else {
        $display = ($mvnArgs -join ' ')
        Log-Event ("执行：mvn{0}" -f ($(if ($display) { " $display" } else { '' })))
        $started = Get-Date
        & mvn @mvnArgs
        $exit = $LASTEXITCODE
        Log-Event ("mvn 结束：exit={0}，耗时 {1:N1}s" -f $exit, ((Get-Date) - $started).TotalSeconds)
    }
} finally {
    # -----------------------------------------------------------------
    # 3) 释放：只删自己写的那把锁
    # -----------------------------------------------------------------
    if ($held) {
        $current = Get-Content $LockFile -Raw -ErrorAction SilentlyContinue
        if ($current -and $current -match [regex]::Escape($nonce)) {
            Remove-Item $LockFile -Force -ErrorAction SilentlyContinue
            Log-Event '已释放 maven 锁' 'LOCK'
        } else {
            Log-Event '锁已不属于本进程（可能被抢占），跳过释放' 'WARN'
        }
    }
}
exit $exit
