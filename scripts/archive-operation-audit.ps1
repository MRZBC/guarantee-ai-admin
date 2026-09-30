<#
.SYNOPSIS
  ai_operation_audit 到期分区归档执行体（REQ-CFG-12 / SYS-A-14）。

.DESCRIPTION
  策略：在线 24 个月 + 归档 36 个月，到期分区以 DROP PARTITION 清理（元数据操作、无长事务）。

  本脚本把"只有设计与分区、没有执行体"这个缺口补上，并遵守三条硬约束：

    1) **不得静默删数据**：默认是 DRY-RUN（只列出到期分区、只导出、不 DROP）。
       要真正删除必须显式加 -Execute；SOP 要求先看 dry-run 输出再执行。
    2) **先导出、再校验、最后才删**：导出文件行数必须与分区行数一致，任一环节失败
       立即终止（退出码 1）并写日志，绝不在校验失败时继续 DROP。
    3) **永不触碰 pmax / MAXVALUE**：兜底分区一旦被删，后续写入会直接失败。

  留痕：每一步（到期分区、行数、导出文件、DROP 结果）追加写入 ArchiveDir 下的
  archive-operation-audit.log；应用进程不持有 DDL 权限（本脚本用运维账号执行）。

.PARAMETER RetentionMonths
  在线窗口（月）。上界完全早于 (今天 - N 个月) 的分区才会被归档。默认 24。

.PARAMETER Execute
  显式开启真实 DROP PARTITION。**不加即 dry-run**。

.PARAMETER MysqlExe
  mysql 客户端可执行文件。默认从 PATH 取 mysql。

.EXAMPLE
  # 演练（不动数据）：
  ./scripts/archive-operation-audit.ps1 -RetentionMonths 6

.EXAMPLE
  # 真正执行（先跑一次上面那条确认输出）：
  ./scripts/archive-operation-audit.ps1 -Execute
#>
[CmdletBinding()]
param(
    [string]$MysqlHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }),
    [int]$Port = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 3307 }),
    [string]$User = $(if ($env:DB_USER) { $env:DB_USER } else { 'guarantee' }),
    [string]$Password = $env:DB_PASSWORD,
    [string]$Database = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'guarantee_ai_admin' }),
    [string]$MysqlExe = 'mysql',
    [string]$ArchiveDir = (Join-Path $PSScriptRoot '..' '.agent' 'archive' 'operation-audit'),
    [int]$RetentionMonths = 24,
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'
$table = 'ai_operation_audit'

function Get-MysqlCommand {
    param([string]$Sql)
    if (-not $Password) {
        throw "缺少数据库口令：请传 -Password 或设置环境变量 DB_PASSWORD（脚本不会把口令写进日志）"
    }
    # 口令走进程环境变量，避免出现在命令行与进程列表里
    $env:MYSQL_PWD = $Password
    $args = @('-h', $MysqlHost, '-P', "$Port", '-u', $User,
        '--default-character-set=utf8mb4', '-N', '-B', $Database, '-e', $Sql)
    & $MysqlExe @args
    if ($LASTEXITCODE -ne 0) {
        throw "mysql 执行失败（exit=$LASTEXITCODE）：$Sql"
    }
}

function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $line = "{0}`t{1}`t{2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    Write-Host $line
    Add-Content -Path $script:LogFile -Value $line -Encoding UTF8
}

New-Item -ItemType Directory -Path $ArchiveDir -Force | Out-Null
$script:LogFile = Join-Path $ArchiveDir 'archive-operation-audit.log'
$dryRun = -not $Execute
$cutoff = (Get-Date).Date.AddMonths(-$RetentionMonths).ToString('yyyy-MM-dd')

Write-Log ("开始归档：db={0} table={1} 在线窗口={2} 个月 截止日期={3} 模式={4}" -f `
        $Database, $table, $RetentionMonths, $cutoff, ($(if ($dryRun) { 'DRY-RUN（不删除）' } else { 'EXECUTE' })))

# ---------------------------------------------------------------------
# 1) 找出"上界完全早于截止日期"的分区
#    **按分区的真实上界判定，绝不按分区名**。
#    背景（T6-03 已查清并修复来源）：schema.sql 原本用 Python 的 `date.toordinal()`
#    序数写分区边界，而分区分片函数是 MySQL 的 `TO_DAYS()`，两者相差 365 天
#    （TO_DAYS('2025-02-01') = toordinal('2026-02-01') = 739648）。因此**旧 schema.sql
#    建出的既有库**里，每个分区的真实覆盖比名字早一年（例：p202709 真实上界是
#    2026-10-01，当前数据正落在它里面）。按名字归档会删错数据。
#    · schema.sql 已改为正确的 TO_DAYS 值（只影响**新建库**）；
#    · 既有库如需校准分区名，用 scripts/repartition-operation-audit.ps1（默认 DRY-RUN）；
#    · 本脚本对两种命名都安全：判据始终是 FROM_DAYS(PARTITION_DESCRIPTION)。
#    pmax / MAXVALUE 永远排除。
# ---------------------------------------------------------------------
$dueSql = @"
SELECT PARTITION_NAME, PARTITION_DESCRIPTION, FROM_DAYS(PARTITION_DESCRIPTION) AS UPPER_BOUND
FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA = '$Database' AND TABLE_NAME = '$table'
  AND PARTITION_NAME <> 'pmax'
  AND PARTITION_DESCRIPTION <> 'MAXVALUE'
  AND FROM_DAYS(PARTITION_DESCRIPTION) <= '$cutoff'
ORDER BY PARTITION_DESCRIPTION
"@

$dueRows = @(Get-MysqlCommand -Sql $dueSql | Where-Object { $_ -and $_.Trim() -ne '' })
if ($dueRows.Count -eq 0) {
    Write-Log "没有到期分区（在线窗口内），无需归档"
    exit 0
}
Write-Log ("到期分区 {0} 个：{1}" -f $dueRows.Count, (($dueRows | ForEach-Object { ($_ -split "`t")[0] }) -join ', '))

$totalRows = 0
foreach ($row in $dueRows) {
    $parts = $row -split "`t"
    $partition = $parts[0]
    $upperBound = $parts[2]

    if ($partition -eq 'pmax') {
        throw "安全检查失败：pmax 不得进入归档清单"
    }

    # 2) 行数（DROP 前的校验基准）
    $countText = (Get-MysqlCommand -Sql "SELECT COUNT(*) FROM $table PARTITION ($partition);" | Select-Object -First 1)
    $rows = [long]$countText
    $totalRows += $rows

    # 3) 导出（客户端侧导出，不需要 FILE 权限）。
    #    先自己写表头，再用 -N 导出数据：这样"导出行数 = 1 + 分区行数"的校验
    #    在**空分区**上也成立（mysql -B 在 0 行时不会输出表头）。
    $stamp = Get-Date -Format 'yyyyMMddHHmmss'
    $exportFile = Join-Path $ArchiveDir ("{0}-{1}-{2}.tsv" -f $table, $partition, $stamp)
    $header = Get-MysqlCommand -Sql @"
SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION SEPARATOR '\t')
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = '$Database' AND TABLE_NAME = '$table'
"@
    Set-Content -Path $exportFile -Value $header -Encoding UTF8

    $env:MYSQL_PWD = $Password
    $exportArgs = @('-h', $MysqlHost, '-P', "$Port", '-u', $User,
        '--default-character-set=utf8mb4', '-N', '-B', $Database,
        '-e', "SELECT * FROM $table PARTITION ($partition);")
    & $MysqlExe @exportArgs | Add-Content -Path $exportFile -Encoding UTF8
    if ($LASTEXITCODE -ne 0) {
        throw "导出失败：partition=$partition"
    }

    # 4) 校验行数：导出内容 = 1 行表头 + rows 行数据
    $exported = @(Get-Content -Path $exportFile -Encoding UTF8).Count
    if ($exported -ne ($rows + 1)) {
        throw ("行数校验失败，已终止（不删除）：partition={0} 分区行数={1} 导出行数={2}（含表头）文件={3}" -f `
                $partition, $rows, $exported, $exportFile)
    }
    Write-Log ("导出校验通过：{0}（上界 {1}）行数={2} 文件={3}" -f $partition, $upperBound, $rows, $exportFile)

    if ($dryRun) {
        Write-Log ("DRY-RUN：跳过 DROP PARTITION {0}（如确认无误请加 -Execute 重跑）" -f $partition)
        continue
    }

    # 5) 删除分区（元数据操作）
    Get-MysqlCommand -Sql "ALTER TABLE $table DROP PARTITION $partition;" | Out-Null
    Write-Log ("已删除分区：{0}（行数={1}，已归档到 {2}）" -f $partition, $rows, $exportFile)
}

if ($dryRun) {
    Write-Log ("DRY-RUN 结束：到期分区 {0} 个、合计 {1} 行；未删除任何数据" -f $dueRows.Count, $totalRows)
} else {
    Write-Log ("归档完成：删除 {0} 个分区、合计 {1} 行" -f $dueRows.Count, $totalRows)
}
