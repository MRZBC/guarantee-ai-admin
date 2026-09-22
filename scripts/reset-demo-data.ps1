#!/usr/bin/env pwsh
<#
.SYNOPSIS
  重置本地演示数据库的业务数据（保留表结构），使后端启动时的 DataInitializer 重新生成。

.DESCRIPTION
  为什么需要它：
  - DataInitializer 只在 sys_user 为空时执行，因此"想重建演示数据"必须先清表；
  - 本需求（D-3 / SYS-P-15~17）修改了机构层级与部门层级的生成逻辑，
    旧数据的 parent_id/org_level 是平铺的，必须重建才能验证数据范围；
  - 其余演示数据（订单/企业/项目）同样由 DataInitializer 重新生成，
    区域权重未变，因此分布规律与总量保持不变（SYS-P-16a）。

  脚本只做 TRUNCATE，不 DROP 表 —— 表结构由 schema.sql（幂等）负责，
  避免脚本与 schema.sql 出现两份定义。

.EXAMPLE
  pwsh scripts/reset-demo-data.ps1
  pwsh scripts/reset-demo-data.ps1 -IncludeAi
#>
param(
    [string]$MySqlHost = "127.0.0.1",
    [int]$Port = 3307,
    [string]$Database = "guarantee_ai_admin",
    [string]$User = "guarantee",
    [string]$Password = "guarantee@2026",
    [string]$MySqlExe = "D:\environment\mysql-8.0.29-winx64\bin\mysql.exe",
    # 是否同时清空 AI 域（会话/工具调用/提案/审计）。默认清空，保证测试从干净状态开始。
    [bool]$IncludeAi = $true
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $MySqlExe)) {
    $found = Get-Command mysql -ErrorAction SilentlyContinue
    if ($found) { $MySqlExe = $found.Source } else { throw "找不到 mysql 客户端：$MySqlExe" }
}

$tables = [System.Collections.Generic.List[string]]::new()
# 顺序无关（TRUNCATE 不做外键级联检查），但按依赖关系列出便于阅读
@(
    "tender_order",
    "performance_order",
    "project",
    "enterprise",
    "insurance_type",
    "sys_user_role",
    "sys_role_permission",
    "sys_user",
    "sys_department",
    "sys_org",
    "sys_role",
    "sys_permission"
) | ForEach-Object { $tables.Add($_) }

if ($IncludeAi) {
    @(
        "ai_message",
        "ai_tool_call",
        "ai_audit_log",
        "ai_operation_proposal",
        "ai_operation_secret",
        "ai_operation_audit",
        "ai_conversation"
    ) | ForEach-Object { $tables.Add($_) }
}

$sql = ($tables | ForEach-Object { "TRUNCATE TABLE ``$_``;" }) -join "`n"
Write-Host "即将清空 $($tables.Count) 张表（数据库 $Database @ $MySqlHost`:$Port）..." -ForegroundColor Yellow

$sql | & $MySqlExe -h $MySqlHost -P $Port -u $User "-p$Password" $Database
if ($LASTEXITCODE -ne 0) { throw "清空失败（退出码 $LASTEXITCODE）" }

Write-Host "已清空：$($tables -join ', ')" -ForegroundColor Green
Write-Host "提示：下次启动后端时会由 DataInitializer 重新生成演示数据（固定种子 20260920）。" -ForegroundColor Green
