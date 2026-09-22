#!/usr/bin/env pwsh
<#
.SYNOPSIS
  校验「阶段一：用户与部门都不再有机构字段」的库结构是否符合新领域模型。

.DESCRIPTION
  领域模型（依据 docs/PLAN-移除用户与部门的机构归属.md）：
    机构(sys_org)       = 外部出函机构，服务于**订单**
    部门(sys_department) = 内部组织单元，服务于**人**
    用户 → 部门（唯一归属）；用户与部门都不再挂机构

  阶段一由 guarantee-web/src/main/resources/db/migration/V4__drop_org_from_user_and_dept.sql
  落库：sys_user.org_id / sys_department.org_id 删除，sys_user.dept_id 收紧为 NOT NULL。

  本脚本**只读**，只做结构断言，不改任何数据。5 项断言全部通过才 exit 0，
  任一失败则打印失败项并 exit 1。

  它与历史脚本 scripts/verify-dept-tree.ps1 / verify-dept-tree-shape.mjs 的区别：
  那两个走 HTTP 接口、且断言以「部门归属某机构」为前提，阶段一后已失效（文件头已标注勿再执行）；
  本脚本直接查库结构，是新模型下的有效校验。

.EXAMPLE
  pwsh -File scripts/verify-no-org-on-user-dept.ps1
  pwsh -File scripts/verify-no-org-on-user-dept.ps1 -Port 3307 -Database guarantee_ai_admin
#>
param(
    [string]$DbHost = '127.0.0.1',
    [int]$Port = 3307,
    [string]$Database = 'guarantee_ai_admin',
    [string]$User = 'guarantee',
    [string]$Password = 'guarantee@2026',
    [string]$MysqlHome = 'D:\environment\mysql-8.0.29-winx64'
)

$ErrorActionPreference = 'Stop'

$mysql = Join-Path $MysqlHome 'bin\mysql.exe'
if (-not (Test-Path $mysql)) {
    $found = Get-Command mysql -ErrorAction SilentlyContinue
    if ($found) { $mysql = $found.Source } else { throw "找不到 mysql 客户端：$mysql" }
}

function Invoke-MySql([string]$sql) {
    $args = @("--host=$DbHost", "--port=$Port", "--user=$User", "--password=$Password",
              '--default-character-set=utf8mb4', '--skip-column-names')
    $args += @($Database, '--execute', $sql)
    $out = & $mysql @args 2>$null
    if ($LASTEXITCODE -ne 0) { throw "mysql 查询失败（exit=$LASTEXITCODE）：$sql" }
    return $out
}

# 只取第一个非空行：mysql 的警告走 stderr（已丢弃），这里再防一手空行
function Scalar([string]$sql) {
    return ((Invoke-MySql $sql) | Where-Object { $_ -ne '' } | Select-Object -First 1)
}

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    if ($ok) { $script:pass++; Write-Host "  ✔ $name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "  ✖ $name  $detail" -ForegroundColor Red }
}

$pass = 0
$fail = 0

Write-Host "`n=== 阶段一结构断言（$Database @ $DbHost`:$Port）===" -ForegroundColor Cyan
Write-Host "模型：机构→订单（外部出函机构）；部门→人（内部组织）；用户必须属于一个部门`n"

# ---------------------------------------------------------------------------
# 断言 1/2：sys_user / sys_department 都不再有 org_id 列
#   information_schema 查询显式带上 TABLE_SCHEMA = DATABASE()，
#   避免把同名库（或只传 -Database 之外的库）里的表算进来。
# ---------------------------------------------------------------------------
$orgColSql = @"
SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'org_id'
  AND TABLE_NAME IN ('sys_user', 'sys_department');
"@
$orgCols = @(Invoke-MySql $orgColSql | Where-Object { $_ -ne '' })

Check '断言1  sys_user 不存在 org_id 列' `
    (@($orgCols | Where-Object { $_ -like 'sys_user.*' }).Count -eq 0) `
    "仍存在：$(($orgCols | Where-Object { $_ -like 'sys_user.*' }) -join ',')"

Check '断言2  sys_department 不存在 org_id 列' `
    (@($orgCols | Where-Object { $_ -like 'sys_department.*' }).Count -eq 0) `
    "仍存在：$(($orgCols | Where-Object { $_ -like 'sys_department.*' }) -join ',')"

# ---------------------------------------------------------------------------
# 断言 3：sys_user.dept_id 为 NOT NULL（「用户必须属于一个部门」）
# ---------------------------------------------------------------------------
$deptIdMeta = Invoke-MySql @"
SELECT CONCAT(IFNULL(IS_NULLABLE, '(列不存在)'), '|', IFNULL(DATA_TYPE, '-'))
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'dept_id';
"@
$deptIdMeta = ($deptIdMeta | Where-Object { $_ -ne '' } | Select-Object -First 1)
$isNullable = if ($null -eq $deptIdMeta) { '(列不存在)' } else { ($deptIdMeta -split '\|')[0] }
$dataType   = if ($null -eq $deptIdMeta) { '-' } else { ($deptIdMeta -split '\|')[1] }

Check '断言3  sys_user.dept_id 为 NOT NULL' ($isNullable -eq 'NO') `
    "IS_NULLABLE=$isNullable DATA_TYPE=$dataType"

# ---------------------------------------------------------------------------
# 断言 4：无部门的未删除用户数 = 0
# ---------------------------------------------------------------------------
$nullDept = [int](Scalar "SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0 AND dept_id IS NULL;")
Check '断言4  无部门的未删除用户数 = 0' ($nullDept -eq 0) "实际=$nullDept"

# ---------------------------------------------------------------------------
# 断言 5：dept_id 指向不存在或已删除部门的未删除用户数 = 0
#   LEFT JOIN + (d.id IS NULL OR d.is_deleted = 1)：
#     d.id IS NULL     → 指向的部门 id 根本不存在
#     d.is_deleted = 1 → 指向的部门已被逻辑删除
# ---------------------------------------------------------------------------
$dangling = [int](Scalar @"
SELECT COUNT(*) FROM sys_user u
LEFT JOIN sys_department d ON d.id = u.dept_id
WHERE u.is_deleted = 0 AND (d.id IS NULL OR d.is_deleted = 1);
"@)
Check '断言5  dept_id 指向不存在或已删除部门的未删除用户数 = 0' ($dangling -eq 0) "实际=$dangling"

# ---------------------------------------------------------------------------
# 结果
# ---------------------------------------------------------------------------
Write-Host "`n=== 结果：PASS=$pass  FAIL=$fail ===" -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
if ($fail -gt 0) {
    Write-Host "结构不符合阶段一新模型，请先确认 V4 迁移是否已执行：" -ForegroundColor Red
    Write-Host "  guarantee-web/src/main/resources/db/migration/V4__drop_org_from_user_and_dept.sql" -ForegroundColor Red
    exit 1
}
exit 0
