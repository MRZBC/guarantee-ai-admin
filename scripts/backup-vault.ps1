<#
  backup-vault.ps1 —— 知识库（Obsidian Vault）离线/远端备份

  为什么需要它：Vault 是全项目知识的**唯一真源**（STATE / TASKS / PROJECT / LOG / Decisions / Wiki）。
  它当前只有本地目录 + 本地 git，没有远端 —— 磁盘损坏或被误删就是全损。

  它做什么：
    1) 校验身份：`.agent/vault.local.yaml` 的 vault 路径 + Vault 的 VAULT_ID.md 是否与本仓 `.agent/project.yaml` 一致（不一致则拒绝执行）
    2) 提交 Vault 里的未提交改动（可 `-NoCommit` 跳过）
    3) 生成**全量历史 bundle**（`git bundle --all`，可离线克隆/恢复）
    4) 生成**工作区快照 zip**（便于人工翻看）
    5) 轮转保留最近 N 份（`-Keep`）
    6) 写 MANIFEST.md（时间 / HEAD / 大小 / 文件数）
    7) 可选 `-Push`：Vault 若已配置远端，则推送

  用法：
    pwsh scripts/backup-vault.ps1                       # 默认目标 D:\Backups\guarantee-ai-admin-vault
    pwsh scripts/backup-vault.ps1 -Dest E:\vault-bak    # 指定目标（建议指向**另一块盘**或同步目录）
    pwsh scripts/backup-vault.ps1 -Push                 # 同时推送到 Vault 已配置的远端

  恢复方式（写进 MANIFEST.md）：
    git clone <bundle 文件> <目标目录>        # 从 bundle 恢复完整历史
    或 Expand-Archive <zip> <目标目录>        # 只要工作区快照
#>
[CmdletBinding()]
param(
  [string]$Dest = 'D:\Backups\guarantee-ai-admin-vault',
  [int]$Keep = 10,
  [switch]$NoCommit,
  [switch]$Push,
  [string]$RepoRoot
)

$ErrorActionPreference = 'Stop'

if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent $PSScriptRoot }
$localYaml  = Join-Path $RepoRoot '.agent/vault.local.yaml'
$projYaml   = Join-Path $RepoRoot '.agent/project.yaml'

function Fail($msg) { Write-Host "[备份失败] $msg" -ForegroundColor Red; exit 2 }
function Info($msg) { Write-Host "[备份] $msg" }

if (-not (Test-Path -LiteralPath $localYaml)) { Fail "缺少 $localYaml（本机 Vault 绑定未配置）" }

# ---- 1) 解析绑定并校验身份（不做磁盘扫描猜 Vault） ----
$vaultPath = (Select-String -LiteralPath $localYaml -Pattern '^\s*path:\s*"?([^"\r\n]+)"?' |
              Select-Object -First 1).Matches[0].Groups[1].Value.Trim()
if (-not $vaultPath) { Fail "从 $localYaml 解析不出 vault.path" }
$vaultPath = $vaultPath -replace '/', '\'

$projId = (Select-String -LiteralPath $projYaml -Pattern '^\s*id:\s*"?([^"\r\n]+)"?' |
           Select-Object -First 1).Matches[0].Groups[1].Value.Trim()
$vaultIdFile = Join-Path $vaultPath 'VAULT_ID.md'
if (-not (Test-Path -LiteralPath $vaultIdFile)) { Fail "Vault 身份文件不存在：$vaultIdFile" }
$vaultId = (Select-String -LiteralPath $vaultIdFile -Pattern 'project_id:\s*"?([^"\r\n]+)"?' |
            Select-Object -First 1).Matches[0].Groups[1].Value.Trim()
if ($vaultId -ne $projId) { Fail "身份不一致：仓库 project.id='$projId' vs Vault project_id='$vaultId' —— 拒绝写入" }
if (-not (Test-Path -LiteralPath (Join-Path $vaultPath '.git'))) { Fail "Vault 不是 git 仓库：$vaultPath" }

Info "Vault = $vaultPath（project_id=$vaultId 校验通过）"

# ---- 2) 提交未提交改动 ----
if (-not $NoCommit) {
  $dirty = & git -C $vaultPath status --porcelain
  if ($dirty) {
    & git -C $vaultPath add -A | Out-Null
    $msg = "chore(vault-backup): 备份前自动提交 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    & git -C $vaultPath -c core.hooksPath= commit -m $msg | Out-Null
    Info "已提交 Vault 未提交改动：$msg"
  } else { Info 'Vault 工作区干净，无需提交' }
} else { Info '-NoCommit：跳过提交' }

$head   = (& git -C $vaultPath rev-parse --short HEAD).Trim()
$stamp  = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Force -Path $Dest | Out-Null

# ---- 3) 全量历史 bundle ----
$bundle = Join-Path $Dest "vault-$stamp.bundle"
& git -C $vaultPath bundle create $bundle --all 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "git bundle 失败" }

# ---- 4) 工作区快照 zip（排除 .git，历史已由 bundle 覆盖） ----
$zip     = Join-Path $Dest "vault-$stamp.zip"
$staging = Join-Path ([IO.Path]::GetTempPath()) "vault-snap-$stamp"
New-Item -ItemType Directory -Force -Path $staging | Out-Null
Copy-Item -Path (Join-Path $vaultPath '*') -Destination $staging -Recurse -Force
Remove-Item -LiteralPath (Join-Path $staging '.git') -Recurse -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zip -Force
Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue

# ---- 5) 校验 bundle 可用 ----
$verify = & git bundle verify $bundle 2>&1 | Select-Object -Last 1
if ($LASTEXITCODE -ne 0) { Fail "bundle 校验失败：$verify" }

# ---- 6) 轮转 + MANIFEST ----
Get-ChildItem -LiteralPath $Dest -Filter 'vault-*.*' |
  Sort-Object LastWriteTime -Descending | Select-Object -Skip $Keep |
  ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force; Info "轮转删除旧备份：$($_.Name)" }

$fileCount = (Get-ChildItem -LiteralPath $vaultPath -Recurse -File |
              Where-Object { $_.FullName -notlike '*\.git\*' } | Measure-Object).Count
$manifest = Join-Path $Dest 'MANIFEST.md'
$line = "| $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') | ``$head`` | $([math]::Round((Get-Item $bundle).Length/1MB,2)) MB | $([math]::Round((Get-Item $zip).Length/1MB,2)) MB | $fileCount | ``$(Split-Path -Leaf $bundle)`` |"
if (-not (Test-Path -LiteralPath $manifest)) {
  @(
    '# Vault 备份清单', '',
    '> 由 `scripts/backup-vault.ps1` 生成。**恢复方式**：',
    '> - 完整历史：`git clone <vault-*.bundle> <目标目录>`',
    '> - 只要工作区快照：`Expand-Archive <vault-*.zip> <目标目录>`', '',
    '| 时间 | HEAD | bundle | zip | 文件数 | 文件 |', '|---|---|---|---|---|---|'
  ) | Set-Content -LiteralPath $manifest -Encoding UTF8
}
Add-Content -LiteralPath $manifest -Value $line -Encoding UTF8

# ---- 7) 可选推送 ----
if ($Push) {
  $remote = & git -C $vaultPath remote
  if ($remote) {
    & git -C $vaultPath push $remote (git -C $vaultPath rev-parse --abbrev-ref HEAD) 2>&1 | Select-Object -Last 2
    Info "已推送到远端：$remote"
  } else { Info '-Push 指定了，但 Vault 没有配置远端 —— 见 docs/VAULT-备份.md 的三步配置法' }
}

Info "完成：HEAD=$head，bundle=$bundle（$verify），zip=$zip"
Info "恢复说明见 $manifest"
exit 0
