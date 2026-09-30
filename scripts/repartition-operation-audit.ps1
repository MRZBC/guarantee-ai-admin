<#
.SYNOPSIS
  ai_operation_audit 分区名校准 / 未来分区追加（T6-03）。**默认 DRY-RUN**。

.DESCRIPTION
  背景（已查清，证据见 docs/IMPL-审计归档-分区命名.md）：
  ai_operation_audit 由 guarantee-web/src/main/resources/db/schema.sql 的
  `CREATE TABLE ... PARTITION BY RANGE (TO_DAYS(operated_at))` 创建（由
  application.yml 的 spring.sql.init 在首次启动时执行）。原来的 36 个边界值用的是
  **Python `date.toordinal()`** 那套序数（739648 = toordinal('2026-02-01')），
  而分片函数是 MySQL 的 `TO_DAYS()`，两者相差 **365 天**。后果：**既有库**里每个分区的
  真实覆盖比名字早一年 —— 名为 p202709 的分区实际覆盖 2026-09。

  schema.sql 已改为正确的 TO_DAYS 值，但那**只影响新建库**（CREATE TABLE IF NOT EXISTS
  对已存在的表是空操作）。本脚本用来处理**既有库**，且有两个独立动作：

    1) 默认动作：把"名字与真实覆盖不符"的分区**改名**到与真实覆盖一致（p202709 → p202609）。
       ⚠️ MySQL 8.0 **没有** `ALTER TABLE ... RENAME PARTITION`（2026-10-01 在探针表上实测报
          `ERROR 1064 ... near 'PARTITION p1 TO p1x'`，带反引号亦然）。改名只能用 **1:1 REORGANIZE**：
          `REORGANIZE PARTITION <旧名> INTO (PARTITION <新名> VALUES LESS THAN (<原边界数值>))`
          —— **保留原边界数值**（含那 11 个分区的闰日漂移），只换名字。
          代价：REORGANIZE **会重建该分区**（该分区的行数据复制一次），不再是纯元数据操作；
          本表只有当月分区有数据（~2.5k 行），代价可接受，但仍建议业务低峰执行。
          顺序：按目标名**升序**逐条处理（本场景是整体下移一年，升序可保证目标名此刻已腾空），
          每条执行前再校验"目标名不存在"，否则给可读错误而不是撞 `ERROR 1517 Duplicate partition name`。
    2) -AddMonths N：为 **N 个尚未覆盖的月份** 追加正确命名的分区。SQL 里的边界用
       `TO_DAYS('<下月 1 日>')` **表达式**，由 MySQL 求值 —— 从根上避免再次写错序数。
       若目标名字已被占用（说明还有分区没校准），脚本会拒绝而不是撞名。

  三条安全约束：
    · 默认 **DRY-RUN**：只打印将执行的 SQL，不加 -Execute 绝不改库；
    · **永不触碰 pmax / MAXVALUE**；
    · 只处理 `ai_operation_audit`，且只做 REORGANIZE（1:1 改名 / 追加），**不 DROP 任何分区**。

  风险与回滚（详见 docs/IMPL-审计归档-分区命名.md §4）：
    · 风险：1:1 REORGANIZE 会复制该分区数据（InnoDB 行锁 + 重建），执行期间该分区的写入会等待；
      跨任务共享库上执行前必须确认没有其它人正在写 ai_operation_audit；
    · 回滚：本脚本写日志（含每一步 SQL 与原名字）；改名可反向再跑一次
      （把每个分区的真名与旧名对调即可，见文档 §4 的 ROLLBACK 模板）。
      建议回滚前先 `mysqldump --no-data` 留一份分区定义。

.PARAMETER Execute
  显式开启真实执行。**不加即 DRY-RUN**。

.PARAMETER AddMonths
  追加未来分区数（默认 0 = 只做改名）。追加从"当前最大真实上界的下一个月"开始。

.PARAMETER MysqlExe
  mysql 客户端可执行文件；默认取 PATH 里的 mysql。

.EXAMPLE
  # 1) 演练：看清哪些分区名要改（不动库）
  pwsh -File scripts/repartition-operation-audit.ps1 -Password <pwd>
.EXAMPLE
  # 2) 真正改名（确认过 1 的输出后）
  pwsh -File scripts/repartition-operation-audit.ps1 -Password <pwd> -Execute
.EXAMPLE
  # 3) 追加未来 12 个月分区（改名完成后）
  pwsh -File scripts/repartition-operation-audit.ps1 -Password <pwd> -AddMonths 12
#>
[CmdletBinding()]
param(
    [string]$MysqlHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }),
    [int]$Port = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 3307 }),
    [string]$User = $(if ($env:DB_USER) { $env:DB_USER } else { 'guarantee' }),
    [string]$Password = $env:DB_PASSWORD,
    [string]$Database = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'guarantee_ai_admin' }),
    [string]$MysqlExe = 'mysql',
    [string]$LogDir = (Join-Path $PSScriptRoot '..' '.agent' 'archive' 'operation-audit'),
    [int]$AddMonths = 0,
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'
$table = 'ai_operation_audit'
$tmpPrefix = '__repart_tmp_'

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$script:LogFile = Join-Path $LogDir 'repartition-operation-audit.log'
$dryRun = -not $Execute

function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $line = "{0}`t{1}`t{2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    Write-Host $line
    Add-Content -Path $script:LogFile -Value $line -Encoding UTF8
}

function Invoke-Mysql {
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

# ---------------------------------------------------------------------
# 0) 读现状：分区名 + 真实上界（**只认 FROM_DAYS 算出的真实边界**）
#
#    真实名字的算法：`DATE_SUB(FROM_DAYS(上界), INTERVAL 1 MONTH)` 的年月。
#    · 对"正确生成"的分区（上界 = 覆盖月的下月 1 日）→ 正好是它覆盖的那个月；
#    · 对历史遗留的错位分区（上界 = 下月 2 日，见下方的闰日漂移）→ 名字取**数据主要落在的那个月**
#      （例：p202811 实际覆盖 2027-11-02 ~ 2027-12-01，29/30 天是 11 月 → 目标名 p202711）。
#    为什么不用"最后一个被覆盖的那天所在月"：那会把上面这种分区叫成 12 月（12 月只有 1 天）。
#
#    ⚠️ 闰日漂移：历史值按"名字年的下月 1 日"减 365 天写成，凡跨过 2028-02-29 的月份
#    （p202802 ~ p202812 共 11 个）真实上界会落在**下月 2 日**，即这些分区比日历月多 1 天。
#    改名能让"名字"变真，但**改不掉这 1 天**（要改只能重建分区，见文档 §4，成本高、未执行）。
# ---------------------------------------------------------------------
$listSql = @"
SELECT PARTITION_NAME, PARTITION_DESCRIPTION,
       CASE WHEN PARTITION_DESCRIPTION = 'MAXVALUE' THEN NULL
            ELSE DATE_FORMAT(DATE_SUB(FROM_DAYS(PARTITION_DESCRIPTION), INTERVAL 1 MONTH), '%Y%m') END AS TRUE_NAME,
       CASE WHEN PARTITION_DESCRIPTION = 'MAXVALUE' THEN NULL
            ELSE DAY(FROM_DAYS(PARTITION_DESCRIPTION)) END AS BOUND_DAY
FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA = '$Database' AND TABLE_NAME = '$table'
ORDER BY PARTITION_ORDINAL_POSITION
"@

$rows = @(Invoke-Mysql -Sql $listSql | Where-Object { $_ -and $_.Trim() -ne '' } |
        ForEach-Object {
            $p = $_ -split "`t"
            [pscustomobject]@{
                Partition = $p[0]
                Description = $p[1]
                TrueName = if ($p.Count -gt 2 -and $p[2] -and $p[2] -ne 'NULL') { $p[2] } else { $null }
                BoundDay = if ($p.Count -gt 3 -and $p[3] -and $p[3] -ne 'NULL') { [int]$p[3] } else { $null }
            }
        })

if ($rows.Count -eq 0) {
    throw "找不到表 $table 的分区定义：确认库名/表名是否正确（本脚本只处理分区表）"
}

$drifted = @($rows | Where-Object { $_.BoundDay -and $_.BoundDay -ne 1 })

Write-Log ("开始：db={0} table={1} 分区 {2} 个 模式={3} 追加月份={4}" -f `
        $Database, $table, $rows.Count, ($(if ($dryRun) { 'DRY-RUN（不改库）' } else { 'EXECUTE' })), $AddMonths)
if ($drifted.Count -gt 0) {
    Write-Log ("其中 {0} 个分区的真实上界不是当月 1 日（历史值跨闰日漂移，比日历月多 1 天）：{1}" -f `
            $drifted.Count, (($drifted | ForEach-Object { $_.Partition + '→' + $_.Description }) -join ', ')) 'WARN'
    Write-Log "改名能让名字变真，但改不掉这 1 天；需要精确边界只能用重建分区（文档 §4，代价高、本脚本不做）" 'WARN'
}

# ---------------------------------------------------------------------
# 1) 计算改名计划（只挑"名字 ≠ 真实名"的；pmax 永不参与）
# ---------------------------------------------------------------------
$mismatched = @($rows | Where-Object { $_.Partition -ne 'pmax' -and $_.TrueName -and $_.Partition -ne ("p" + $_.TrueName) })
$existingNames = @($rows | ForEach-Object { $_.Partition })

if ($mismatched.Count -eq 0) {
    Write-Log "分区名与真实覆盖一致，无需改名" 'OK'
} else {
    Write-Log ("发现 {0} 个分区名与真实覆盖不符：" -f $mismatched.Count)
    foreach ($m in $mismatched) {
        Write-Log ("  {0}（上界 {1}）实际覆盖 {2} → 目标名 p{3}" -f `
                $m.Partition, $m.Description, $m.TrueName, $m.TrueName)
    }

    # 目标名冲突检查：目标名不能是"另一个不能被改名的分区"
    foreach ($m in $mismatched) {
        $target = 'p' + $m.TrueName
        $blocker = $existingNames | Where-Object { $_ -eq $target -and $_ -ne $m.Partition }
        if ($blocker) {
            $blockerIsMoving = @($mismatched | Where-Object { $_.Partition -eq $blocker }).Count -gt 0
            if (-not $blockerIsMoving) {
                throw "改名目标 $target 已被分区 $blocker 占用，且它不在改名清单里 —— 请人工核对后再执行"
            }
        }
    }

    # 改名语句：MySQL 8.0 没有 RENAME PARTITION（实测 1064），只能用 1:1 REORGANIZE。
    # 保留**原边界数值**（$m.Description，含闰日漂移）→ 覆盖范围一字不改，只换名字。
    # 升序处理 + 动态名额校验：每处理一条，就把它从"当前名字集合"里划掉、把目标名加进去，
    # 因此"目标名此刻是否被占"是按**执行顺序**判定的（而不是按执行前的快照）。
    $plan = @()
    $namesNow = [System.Collections.Generic.HashSet[string]]::new([string[]]$existingNames)
    foreach ($m in ($mismatched | Sort-Object TrueName)) {
        $target = 'p' + $m.TrueName
        if ($namesNow.Contains($target)) {
            throw "改名目标 $target 此刻仍被占用（升序处理下不该发生）：命名模式可能不是整体平移，已中止且未执行任何语句"
        }
        [void]$namesNow.Remove($m.Partition)
        [void]$namesNow.Add($target)
        $sql = "ALTER TABLE $table REORGANIZE PARTITION $($m.Partition) INTO (PARTITION $target VALUES LESS THAN ($($m.Description)));"
        $plan += [pscustomobject]@{
            Step = 'reorganize-rename'; Sql = $sql
            From = $m.Partition; To = $target; RealMonth = $m.TrueName
        }
    }

    foreach ($p in $plan) {
        Write-Log ("[{0}] {1}" -f $p.Step, $p.Sql)
    }

    if ($dryRun) {
        Write-Log ("DRY-RUN：以上 {0} 条 1:1 REORGANIZE 改名未执行（确认无误后加 -Execute）" -f $plan.Count) 'WARN'
    } else {
        foreach ($p in $plan) {
            Invoke-Mysql -Sql $p.Sql | Out-Null
            Write-Log ("已执行：{0}" -f $p.Sql) 'OK'
        }
    }
}

# ---------------------------------------------------------------------
# 2) 追加未来分区（-AddMonths）
#    起点 = 当前最大真实上界的下一个月；名字 = 该月；边界 = `TO_DAYS('<下月 1 日>')` 表达式。
# ---------------------------------------------------------------------
if ($AddMonths -gt 0) {
    $dated = @($rows | Where-Object { $_.TrueName })
    if ($dated.Count -eq 0) { throw "没有可用的分区上界，无法推导追加起点" }
    # 真实上界最大的那个分区：它的上界 = 第一个**尚未覆盖**的日子的 00:00，
    # 所以"下一个未覆盖的月"就是这个上界所在的那个月（不要再 +1 个月，否则会漏一个月）。
    $last = $dated | Sort-Object { [int]$_.Description } | Select-Object -Last 1
    $lastUpper = Invoke-Mysql -Sql ("SELECT FROM_DAYS({0});" -f $last.Description) | Select-Object -First 1
    $start = ([datetime]$lastUpper).Date
    Write-Log ("追加起点：{0}（由最大上界 {1} = {2} 推出；该月尚未被任何分区覆盖）" -f `
            $start.ToString('yyyy-MM'), $last.Description, $lastUpper)

    $blocked = @()
    for ($i = 0; $i -lt $AddMonths; $i++) {
        $month = $start.AddMonths($i)
        $name = 'p' + $month.ToString('yyyyMM')
        $nextMonthFirstDay = $month.AddMonths(1).ToString('yyyy-MM-dd')

        $sql = ("ALTER TABLE {0} REORGANIZE PARTITION pmax INTO (" + `
                "PARTITION {1} VALUES LESS THAN (TO_DAYS('{2}')), " + `
                "PARTITION pmax VALUES LESS THAN MAXVALUE);") -f $table, $name, $nextMonthFirstDay

        $occupant = $rows | Where-Object { $_.Partition -eq $name } | Select-Object -First 1
        if ($occupant) {
            $occupantTrue = if ($occupant.TrueName) { 'p' + $occupant.TrueName } else { 'MAXVALUE' }
            if ($occupantTrue -eq $name) {
                Write-Log ("[add] {0} 已存在且名字正确，跳过" -f $name) 'SKIP'
                continue
            }
            Write-Log ("[add] {0} 被既有分区 {0}（真实覆盖 {1}）占用 —— **BLOCKED**：请先跑一次不带 -AddMonths 的改名校准" -f $name, $occupantTrue) 'WARN'
            $blocked += $name
        }

        Write-Log ("[add] {0}（覆盖 {1}，上界表达式 TO_DAYS('{2}')）" -f $name, $month.ToString('yyyy-MM'), $nextMonthFirstDay)
        Write-Log ("       {0}" -f $sql)
    }

    if ($dryRun) {
        Write-Log ("DRY-RUN：以上追加计划未执行（确认无误后加 -Execute）" -f $AddMonths) 'WARN'
        if ($blocked.Count -gt 0) {
            Write-Log ("注意：{0} 个目标名与既有（未校准）分区冲突，直接执行会失败 —— 先做分区名校准" -f $blocked.Count) 'WARN'
        }
    } else {
        if ($blocked.Count -gt 0) {
            throw ("以下目标分区名与既有未校准分区冲突，已中止（未执行任何追加）：{0} —— 请先去 -AddMonths 跑一次改名校准" -f ($blocked -join ', '))
        }
        for ($i = 0; $i -lt $AddMonths; $i++) {
            $month = $start.AddMonths($i)
            $name = 'p' + $month.ToString('yyyyMM')
            $nextMonthFirstDay = $month.AddMonths(1).ToString('yyyy-MM-dd')
            $sql = ("ALTER TABLE {0} REORGANIZE PARTITION pmax INTO (" + `
                    "PARTITION {1} VALUES LESS THAN (TO_DAYS('{2}')), " + `
                    "PARTITION pmax VALUES LESS THAN MAXVALUE);") -f $table, $name, $nextMonthFirstDay
            Invoke-Mysql -Sql $sql | Out-Null
            Write-Log ("已执行：{0}" -f $sql) 'OK'
        }
    }
}

# ---------------------------------------------------------------------
# 3) 收尾核对（EXECUTE 时读回真实分区列表）
# ---------------------------------------------------------------------
if (-not $dryRun) {
    $after = @(Invoke-Mysql -Sql $listSql | Where-Object { $_ -and $_.Trim() -ne '' } |
            ForEach-Object {
                $p = $_ -split "`t"
                "{0}({1})" -f $p[0], ($(if ($p.Count -gt 2 -and $p[2] -and $p[2] -ne 'NULL') { $p[2] } else { 'MAXVALUE' }))
            })
    Write-Log ("执行后分区：{0}" -f ($after -join ', '))
} else {
    Write-Log "DRY-RUN 结束：未对数据库做任何修改"
}
