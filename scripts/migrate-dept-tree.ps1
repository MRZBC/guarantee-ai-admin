# =====================================================================
#  ⛔ 历史留存 · 已执行完毕 · 勿再执行（DO NOT RUN AGAIN）
#
#  本运行器执行的 migrate-dept-tree.sql / migrate-dept-tree-apply.sql 引用了
#  sys_user.org_id / sys_department.org_id 两列，它们**已被 V4 迁移删除**
#  （guarantee-web/src/main/resources/db/migration/V4__drop_org_from_user_and_dept.sql），
#  现在再跑必然在状态查询或阶段 2 报 "Unknown column 'org_id' in ..."；
#  阶段 2 开头的 `UPDATE sys_user SET dept_id = NULL` 在 dept_id 已收紧为
#  NOT NULL 之后也会直接失败。
#
#  它是一次性迁移运行器，已执行完毕并成为现状；保留此文件仅为记录当时的迁移口径，
#  **不要重跑，也不要按它改逻辑**（改逻辑会掩盖历史）。
#
#  阶段一之后的结构校验改用：pwsh -File scripts/verify-no-org-on-user-dept.ps1
# =====================================================================
# =====================================================================
#  部门树改造迁移执行器（两阶段）
#
#  目标结构（每个机构一棵，共 21 棵）：
#      总部 → 业务部 / 财务部 / 人事部 / 行政部 / 技术部
#               业务部 → 杭州部 / 台州部 / 温州部
#               技术部 → 大数据部 / 系统部
#  并把 sys_user.dept_id 重新分配到新部门。
#
#  用法：
#     pwsh -File scripts/migrate-dept-tree.ps1          # 演练（不写库）
#     pwsh -File scripts/migrate-dept-tree.ps1 -Apply   # 执行（自动全库备份 → 阶段1 → 校验 → 阶段2）
#
#  为什么分两阶段：阶段 1 只建映射表与定点备份、不动业务数据；运行器校验映射完整后才执行
#  阶段 2（置空 dept_id → 删旧部门 → 插新部门 → 回写用户）。
#  反面教材：本脚本第一版把校验写成裸 SQL 里的 SIGNAL（存储程序语法，裸 SQL 非法），
#  导致"删除执行了、插入没执行"，留下部门表空、300 个用户 dept_id 全空的半残状态，只能靠备份恢复。
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

$mysql    = Join-Path $MysqlHome 'bin\mysql.exe'
$dump     = Join-Path $MysqlHome 'bin\mysqldump.exe'
$prepare  = Join-Path $PSScriptRoot 'migrate-dept-tree.sql'
$applySql = Join-Path $PSScriptRoot 'migrate-dept-tree-apply.sql'

foreach ($f in @($mysql, $dump, $prepare, $applySql)) {
    if (-not (Test-Path $f)) { throw "找不到文件：$f" }
}

function Invoke-MySql([string]$sql) {
    $args = @("--host=$DbHost", "--port=$Port", "--user=$User", "--password=$Password",
              '--default-character-set=utf8mb4', '--skip-column-names', $Db)
    $sql | & $mysql @args
    if ($LASTEXITCODE -ne 0) { throw "mysql 执行失败（exit=$LASTEXITCODE）" }
    return $out
}

function Invoke-MySqlTable([string]$sql) {
    $args = @("--host=$DbHost", "--port=$Port", "--user=$User", "--password=$Password",
              '--default-character-set=utf8mb4', '--table', $Db)
    $sql | & $mysql @args
    if ($LASTEXITCODE -ne 0) { throw "mysql 执行失败（exit=$LASTEXITCODE）" }
}

function Invoke-MySqlFile([string]$path, [switch]$Table) {
    $args = @("--host=$DbHost", "--port=$Port", "--user=$User", "--password=$Password",
              '--default-character-set=utf8mb4')
    if ($Table) { $args += '--table' }
    $args += $Db
    # 用标准输入喂脚本：--execute 会把整串按 ';' 拆开逐条发送，
    # 不支持 SOURCE 等客户端命令（踩过一次：静默什么都没做）
    Get-Content -LiteralPath $path -Raw -Encoding UTF8 | & $mysql @args
    if ($LASTEXITCODE -ne 0) { throw "mysql 执行脚本失败（exit=$LASTEXITCODE）" }
}

function Scalar([string]$sql) {
    $r = Invoke-MySql $sql
    return ($r | Where-Object { $_ -ne '' } | Select-Object -First 1)
}

# ---------------------------------------------------------------------
# 状态与守卫
# ---------------------------------------------------------------------
$alreadyNew = [int](Scalar "SELECT COUNT(*) FROM sys_department WHERE is_deleted = 0 AND dept_code LIKE '%-HQ';")
$deptTotal  = [int](Scalar "SELECT COUNT(*) FROM sys_department WHERE is_deleted = 0;")
$orgTotal   = [int](Scalar "SELECT COUNT(*) FROM sys_org WHERE is_deleted = 0;")
$userTotal  = [int](Scalar "SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0;")
$userWithDept = [int](Scalar "SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0 AND dept_id IS NOT NULL;")
$expectedDepts = $orgTotal * 11

Write-Host "当前状态：机构 $orgTotal / 部门 $deptTotal / 有效用户 $userTotal（其中挂部门的 $userWithDept）"

if ($alreadyNew -gt 0) {
    Write-Host "`n⚠ 检测到部门已是新结构（$alreadyNew 个 -HQ 部门），无需迁移。" -ForegroundColor Yellow
    Invoke-MySqlTable @"
SELECT d.dept_name AS 部门, d.dept_code AS 编码, IFNULL(p.dept_name,'(顶级)') AS 上级,
       (SELECT COUNT(*) FROM sys_user u WHERE u.dept_id=d.id AND u.is_deleted=0) AS 人数
FROM sys_department d LEFT JOIN sys_department p ON p.id=d.parent_id
WHERE d.is_deleted=0 AND d.org_id=1 ORDER BY d.sort_no;
"@
    return
}

if (-not $Apply) {
    Write-Host "`n=== 演练模式（不写库）===" -ForegroundColor Cyan
    Write-Host "阶段 1：建映射表 + 定点备份（不改业务数据）"
    Write-Host "        期望 _mig_dept = $expectedDepts 行、_mig_user_map = $userWithDept 行"
    Write-Host "阶段 2：置空 dept_id → 删除旧 $deptTotal 个部门 → 插入 $expectedDepts 个新部门 → 回写 $userWithDept 个用户"
    Write-Host "`n加 -Apply 真正执行（会先自动全库备份到 %TEMP%）。" -ForegroundColor Yellow
    return
}

# ---------------------------------------------------------------------
# 全库备份
# ---------------------------------------------------------------------
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $env:TEMP "guarantee_before_dept_tree_$stamp.sql"
Write-Host "`n[1/4] 备份到 $backup ..."
& $dump "--host=$DbHost" "--port=$Port" "--user=$User" "--password=$Password" `
    '--single-transaction' '--no-tablespaces' "--result-file=$backup" $Db 2>$null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $backup)) { throw "备份失败，已中止（未做任何修改）" }
Write-Host ("      完成：{0:N2} MB" -f ((Get-Item $backup).Length / 1MB))

# ---------------------------------------------------------------------
# 阶段 1：建映射（不动业务数据）
# ---------------------------------------------------------------------
Write-Host "`n[2/4] 阶段 1：建映射表与定点备份 ..."
Invoke-MySqlFile -path $prepare -Table

# ---------------------------------------------------------------------
# 校验：不通过就中止，此时业务数据仍是原样
# ---------------------------------------------------------------------
Write-Host "`n[3/4] 校验映射完整性 ..."
$mappedDepts = [int](Scalar "SELECT COUNT(*) FROM _mig_dept;")
$mappedUsers = [int](Scalar "SELECT COUNT(*) FROM _mig_user_map;")
$topLevel    = [int](Scalar "SELECT COUNT(*) FROM _mig_dept WHERE parent_name IS NULL;")

Write-Host "      _mig_dept=$mappedDepts（期望 $expectedDepts）"
Write-Host "      _mig_user_map=$mappedUsers（期望 $userWithDept）"
Write-Host "      映射表里的顶级部门=$topLevel（期望 $orgTotal）"

$problems = @()
if ($mappedDepts -ne $expectedDepts) { $problems += "部门映射数不符：$mappedDepts ≠ $expectedDepts" }
if ($mappedUsers -ne $userWithDept)   { $problems += "用户映射数不符：$mappedUsers ≠ $userWithDept" }
if ($topLevel -ne $orgTotal)          { $problems += "顶级部门数不符：$topLevel ≠ $orgTotal" }
if ($problems.Count -gt 0) {
    Invoke-MySql "DROP TABLE IF EXISTS _mig_dept; DROP TABLE IF EXISTS _mig_user_map;" | Out-Null
    throw "校验未通过，已中止（业务数据未被修改）：`n  - " + ($problems -join "`n  - ")
}
Write-Host "      校验通过" -ForegroundColor Green

# ---------------------------------------------------------------------
# 阶段 2：换树
# ---------------------------------------------------------------------
Write-Host "`n[4/4] 阶段 2：替换部门树并回写用户 ..."
Invoke-MySqlFile -path $applySql -Table

Write-Host "`n全库备份：$backup" -ForegroundColor Green
Write-Host "定点备份表：_mig_backup_dept / _mig_backup_user_dept（确认无误后可 DROP）"
Write-Host "回滚方式：Get-Content `"$backup`" -Raw | & `"$mysql`" --host=$DbHost --port=$Port --user=$User --password=*** $Db"
