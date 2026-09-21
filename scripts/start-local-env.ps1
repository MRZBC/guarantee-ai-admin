# =====================================================================
#  一键启动本地依赖：MySQL 8 + Redis（可选：后端）
#
#  用法（在你自己的终端里执行，进程归你的终端所有）：
#     pwsh -File scripts/start-local-env.ps1
#     pwsh -File scripts/start-local-env.ps1 -WithBackend
#     pwsh -File scripts/start-local-env.ps1 -Stop
#
#  说明：
#    本项目开发环境使用便携版 MySQL 8.0.29（监听 3307）与本机 Redis（6379）。
#    这两个进程是普通进程，不会随系统自启；本脚本用于手动拉起。
#    若想开机自启，需要管理员权限把 MySQL 注册为 Windows 服务（见 README）。
# =====================================================================
param(
    [switch]$WithBackend,
    [switch]$Stop,
    [string]$MysqlHome = 'D:\environment\mysql-8.0.29-winx64',
    [string]$RedisHome = 'D:\environment\Redis',
    [int]$MysqlPort   = 3307,
    [int]$RedisPort   = 6379
)

$ErrorActionPreference = 'Stop'

$MysqldExe = Join-Path $MysqlHome 'bin\mysqld.exe'
$MysqlIni  = Join-Path $MysqlHome 'my-guarantee.ini'
$RedisExe  = Join-Path $RedisHome 'redis-server.exe'
$RepoRoot  = Split-Path $PSScriptRoot -Parent
$BootJar   = Join-Path $RepoRoot 'guarantee-web\target\guarantee-ai-admin.jar'

function Test-Port([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Stop-LocalEnv {
    Write-Host '正在停止本地依赖 ...'
    Get-Process mysqld -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Get-Process redis-server -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Host '✔ 已停止 mysqld / redis-server（后端若在运行请自行结束 java 进程）'
}

if ($Stop) { Stop-LocalEnv; return }

# ---------------- MySQL ----------------
if (Test-Port $MysqlPort) {
    Write-Host "✔ MySQL 已在 $MysqlPort 端口运行，跳过"
} else {
    if (-not (Test-Path $MysqldExe)) { throw "找不到 mysqld：$MysqldExe" }
    if (-not (Test-Path $MysqlIni))  { throw "找不到配置文件：$MysqlIni" }

    Write-Host "启动 MySQL（端口 $MysqlPort）..."
    Start-Process -FilePath $MysqldExe `
        -ArgumentList "--defaults-file=`"$MysqlIni`"" `
        -WindowStyle Hidden

    $ok = $false
    foreach ($i in 1..30) {
        Start-Sleep -Milliseconds 700
        if (Test-Port $MysqlPort) { $ok = $true; break }
    }
    if ($ok) { Write-Host "✔ MySQL 已启动：127.0.0.1:$MysqlPort / [::1]:$MysqlPort" }
    else     { throw "MySQL 启动超时，请检查 $MysqlIni 与 data 目录" }
}

# ---------------- Redis ----------------
if (Test-Port $RedisPort) {
    Write-Host "✔ Redis 已在 $RedisPort 端口运行，跳过"
} else {
    if (-not (Test-Path $RedisExe)) {
        Write-Host "⚠ 找不到 Redis（$RedisExe），跳过。后端仍可启动（令牌撤销会降级为放行）"
    } else {
        Write-Host "启动 Redis（端口 $RedisPort）..."
        Start-Process -FilePath $RedisExe `
            -ArgumentList "--port", $RedisPort, "--save", '""', "--appendonly", "no" `
            -WindowStyle Hidden
        Start-Sleep -Seconds 2
        if (Test-Port $RedisPort) { Write-Host "✔ Redis 已启动：127.0.0.1:$RedisPort" }
        else { Write-Host "⚠ Redis 启动失败或超时（不影响后端启动）" }
    }
}

# ---------------- 后端（可选） ----------------
if ($WithBackend) {
    if (Test-Port 8081) {
        Write-Host "✔ 后端已在 8081 端口运行，跳过"
    } elseif (-not (Test-Path $BootJar)) {
        Write-Host "⚠ 找不到 $BootJar，请先执行：mvn -B -ntp clean install -DskipTests"
    } else {
        Write-Host '启动后端（8081）...'
        Start-Process -FilePath 'java' -ArgumentList '-jar', "`"$BootJar`"" `
            -WorkingDirectory $RepoRoot
        Write-Host '✔ 后端启动中，日志见弹出窗口；约 10 秒后可访问 http://localhost:8081/'
    }
}

Write-Host ''
Write-Host '连接信息：'
Write-Host "  MySQL : jdbc:mysql://127.0.0.1:$MysqlPort/guarantee_ai_admin"
Write-Host '          用户 guarantee / 密码 guarantee@2026（root 密码为空）'
Write-Host "  Redis : 127.0.0.1:$RedisPort"
Write-Host '  后端  : http://localhost:8081/    前端 dev: http://localhost:5273/'
