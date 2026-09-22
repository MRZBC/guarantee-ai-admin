# =====================================================================
#  部门树收敛为单一机构（ORGHQ）迁移执行器
#
#  作用：只保留 ORGHQ 一棵部门树（11 个部门），删掉其余 220 个；
#        300 个用户全部改挂 ORGHQ 并铺满这 11 个部门。
#
#  用法：
#     pwsh -File scripts/migrate-dept-single-org.ps1          # 演练（不写库）
#     pwsh -File scripts/migrate-dept-single-org.ps1 -Apply   # 执行（先自动全库备份）
#
#  ⚠ 后果：operator/analyst/user0004 的数据范围演示失效（org_id 都变成总部）。
#
#  与 migrate-dept-tree.ps1 的关系：那个脚本把 21 个机构各建成一棵 11 部门的树；
#  本脚本在此基础上前进一步，把树收敛到单一机构。两者可独立重跑（本脚本幂等：
#  已是目标状态时直接退出）。
# =====================================================================
param(
    [switch]$Apply,
    [string]$MysqlHome = 'D:\environment\mysql-8.0.29-winx64',
    [string]$Db = 'guarantee_ai_admin',
    [string]$DbHost = '127.0.0.1',
    [int]$Port = 3307,
    [string]$User = 'guarantee',
    [string]$Password = 'guarantee@2026'
)

$ErrorActionPreference = 'Stop'

$mysql   = Join-Path $MysqlHome 'bin\mysql.exe'
$dump    = Join-Path $MysqlHome 'bin\mysqldump.exe'
$sqlFile = Join-Path $PSScriptRoot 'migrate-dept-single-org.sql'

foreach ($f in @($mysql, $dump, $sqlFile)) {
    if (-not (Test-Path $f)) { throw "找不到文件：$f" }
}

function Invoke-MySql([string]$sql, [switch]$Table) {
    $args = @("--host=$DbHost", "--port=$Port", "--user=$User", "--password=$Password",
              '--default-character-set=utf8mb4')
    $args += if ($Table) { '--table' } else { '--skip-column-names' }
    $args += $Db
    # 标准输入喂 SQL：--execute 会按 ';' 拆开逐条发送，不支持多语句脚本里的复杂结构
    $sql | & $mysql @args
    if ($LASTEXITCODE -ne 0) { throw "mysql 执行失败（exit=$LASTEXITCODE）" }
}

function Invoke-MySqlFile([string]$path, [switch]$Table) {
    $args = @("--host=$DbHost", "--port=$Port", "--user=$User", "--password=$Password",
              '--default-character-set=utf8mb4')
    if ($Table) { $args += '--table' }
    $args += $Db
    Get-Content -LiteralPath $path -Raw -Encoding UTF8 | & $mysql @args
    if ($LASTEXITCODE -ne 0) { throw "mysql 执行脚本失败（exit=$LASTEXITCODE）" }
}

function Scalar([string]$sql) {
    return ((Invoke-MySql $sql) | Where-Object { $_ -ne '' } | Select-Object -First 1)
}

# ---------------------------------------------------------------------
# 状态
# ---------------------------------------------------------------------
$deptTotal   = [int](Scalar "SELECT COUNT(*) FROM sys_department WHERE is_deleted = 0;")
$deptOrgs    = [int](Scalar "SELECT COUNT(DISTINCT org_id) FROM sys_department WHERE is_deleted = 0;")
$hqDepts     = [int](Scalar "SELECT COUNT(*) FROM sys_department WHERE org_id = (SELECT id FROM sys_org WHERE org_code='ORGHQ') AND is_deleted = 0;")
$userOrgs    = [int](Scalar "SELECT COUNT(DISTINCT org_id) FROM sys_user WHERE is_deleted = 0;")
$userTotal   = [int](Scalar "SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0;")

Write-Host "当前状态：部门 $deptTotal 个（分布在 $deptOrgs 个机构，其中 ORGHQ 下 $hqDepts 个）/ 有效用户 $userTotal 个（分布在 $userOrgs 个机构）"

if ($deptOrgs -eq 1 -and $hqDepts -eq $deptTotal -and $userOrgs -eq 1) {
    Write-Host "`n✔ 已是目标状态（部门只在 ORGHQ、用户只属一个机构），无需迁移。" -ForegroundColor Green
    Invoke-MySqlFile -path $sqlFile -Table
    return
}

if (-not $Apply) {
    Write-Host "`n=== 演练模式（不写库）===" -ForegroundColor Cyan
    Write-Host "将要执行："
    Write-Host "  1) 把非 ORGHQ 部门的用户 dept_id 置空"
    Write-Host "  2) 删除非 ORGHQ 的部门（预计 $($deptTotal - $hqDepts) 个）"
    Write-Host "  3) 300 个用户 org_id 全部改为 ORGHQ，并铺满其 $hqDepts 个部门"
    Write-Host "`n⚠ 后果：operator（省级）/ analyst（市级）/ user0004 的数据范围演示将失效" -ForegroundColor Yellow
    Write-Host "`n加 -Apply 真正执行（会先自动全库备份到 %TEMP%）。" -ForegroundColor Yellow
    return
}

# ---------------------------------------------------------------------
# 备份
# ---------------------------------------------------------------------
$stamp  = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $env:TEMP "guarantee_before_single_org_$stamp.sql"
Write-Host "`n[1/2] 备份到 $backup ..."
& $dump "--host=$DbHost" "--port=$Port" "--user=$User" "--password=$Password" `
    '--single-transaction' '--no-tablespaces' "--result-file=$backup" $Db 2>$null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $backup)) { throw "备份失败，已中止（未做任何修改）" }
Write-Host ("      完成：{0:N2} MB" -f ((Get-Item $backup).Length / 1MB))

# ---------------------------------------------------------------------
# 执行
# ---------------------------------------------------------------------
Write-Host "`n[2/2] 执行迁移 ..."
Invoke-MySqlFile -path $sqlFile -Table

Write-Host "`n全库备份：$backup" -ForegroundColor Green
Write-Host "回滚：Get-Content `"$backup`" -Raw | & `"$mysql`" --host=$DbHost --port=$Port --user=$User --password=*** $Db"
